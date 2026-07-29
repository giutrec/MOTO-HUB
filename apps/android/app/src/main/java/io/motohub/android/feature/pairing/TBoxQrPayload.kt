package io.motohub.android.feature.pairing

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * How far the decoded QR corroborates itself.
 *
 * The pairing QR is a Carbit artefact, and Carbit licenses the same dash stack to manufacturers
 * well beyond CFMOTO. A rebadged unit can serve the identical query string from its own OEM host,
 * so treating the host as an entry requirement would turn a cosmetic difference into a hard
 * rejection. The host is therefore corroboration, not a gate: an unfamiliar source still produces
 * a payload, marked so the caller can put the decision in front of the rider instead of guessing.
 */
enum class TBoxQrOrigin {
    /** Served by a Carbit provisioning host — both the shape and the source check out. */
    CARBIT,

    /** Usable credentials from a source MOTO-HUB cannot vouch for. Confirm before saving. */
    UNVERIFIED
}

data class TBoxQrPayload(
    val ssid: String,
    val password: String,
    val encryption: String?,
    // Opaque T-Box provisioning identifier. It is never interpreted as a motorcycle model.
    val modelId: String?,
    val displayName: String?,
    val origin: TBoxQrOrigin
)

object TBoxQrParser {
    private const val WIFI_SCHEME = "WIFI:"

    /**
     * Decodes either a Carbit-style provisioning URL or a plain `WIFI:` network code. Failure is
     * reserved for content that carries no network name at all — anything with usable credentials
     * comes back with an [TBoxQrOrigin] describing how much it can be trusted.
     */
    fun parse(rawValue: String): Result<TBoxQrPayload> = runCatching {
        val trimmed = rawValue.trim()
        parseWifiNetworkCode(trimmed) ?: parseProvisioningUrl(trimmed)
    }

    private fun parseProvisioningUrl(rawValue: String): TBoxQrPayload {
        // URI rejects the whole string over one unescaped character - a `%` in a passphrase is
        // enough - so a dash whose QR is slightly off spec would be unpairable. Fall back to
        // reading the query and host by hand; content that carries no SSID is still rejected
        // below, which is the only rejection this parser owes the caller.
        val uri = runCatching { URI(rawValue) }.getOrNull()
        val parameters = (uri?.rawQuery ?: rawValue.substringAfter('?', "").substringBefore('#'))
            .split('&')
            .filter(String::isNotBlank)
            .associate { item ->
                val keyAndValue = item.split('=', limit = 2)
                decode(keyAndValue[0]) to decode(keyAndValue.getOrElse(1) { "" })
            }
        val ssid = parameters["ssid"].orEmpty().trim()
        check(ssid.isNotEmpty()) { "The QR code does not carry a T-Box network name." }

        val host = (uri?.host ?: hostOf(rawValue))?.lowercase()
        return TBoxQrPayload(
            ssid = ssid,
            password = parameters["pwd"].orEmpty(),
            encryption = parameters["auth"],
            modelId = parameters["modelid"],
            displayName = parameters["name"],
            origin = if (host != null && isCarbitHost(host)) {
                TBoxQrOrigin.CARBIT
            } else {
                TBoxQrOrigin.UNVERIFIED
            }
        )
    }

    /**
     * The standard `WIFI:S:name;T:WPA;P:secret;;` code some dashes print instead of a provisioning
     * URL. It carries no model id, so the dash is identified from CLIENT_INFO on first contact —
     * the same route an unrecognised provisioning URL takes.
     */
    private fun parseWifiNetworkCode(rawValue: String): TBoxQrPayload? {
        if (!rawValue.startsWith(WIFI_SCHEME, ignoreCase = true)) return null
        val fields = splitWifiFields(rawValue.substring(WIFI_SCHEME.length))
        val ssid = fields["S"].orEmpty()
        check(ssid.isNotEmpty()) { "The Wi-Fi QR code does not carry a network name." }

        return TBoxQrPayload(
            ssid = ssid,
            password = fields["P"].orEmpty(),
            encryption = fields["T"],
            modelId = null,
            displayName = null,
            origin = TBoxQrOrigin.UNVERIFIED
        )
    }

    /**
     * Splits the `key:value;` pairs of a Wi-Fi network code. The format escapes its own delimiters
     * with a backslash, so the key/value split has to happen while scanning: an SSID containing an
     * escaped colon would otherwise be cut in half by a later search.
     */
    private fun splitWifiFields(body: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        val buffer = StringBuilder()
        var key: String? = null
        var escaped = false

        fun commit() {
            key?.takeIf(String::isNotEmpty)?.let { name ->
                fields.putIfAbsent(name.uppercase(), buffer.toString())
            }
            key = null
            buffer.setLength(0)
        }

        for (character in body) {
            when {
                escaped -> {
                    buffer.append(character)
                    escaped = false
                }
                character == '\\' -> escaped = true
                character == ':' && key == null -> {
                    key = buffer.toString()
                    buffer.setLength(0)
                }
                character == ';' -> commit()
                else -> buffer.append(character)
            }
        }
        commit()
        return fields
    }

    /**
     * Percent-decodes one query component, leaving `+` and a stray `%` exactly as they were.
     *
     * `URLDecoder` implements `application/x-www-form-urlencoded`, where `+` stands for a space.
     * A Carbit provisioning QR is a plain query string, not a submitted form: a passphrase
     * containing a literal `+` was saved with a space in its place, and every join then failed
     * association with nothing in the log to say why. An unescaped `%` made `URLDecoder` throw,
     * which rejected the whole QR - passing the byte through beats refusing to pair at all.
     * Percent-escapes are still decoded, so `%2B` remains a `+` and `%20` remains a space.
     */
    private fun decode(value: String): String {
        if (!value.contains('%')) return value
        val decoded = StringBuilder(value.length)
        val escaped = ByteArrayOutputStream()

        // Consecutive escapes are one UTF-8 sequence: they have to be decoded together, so the
        // bytes are only turned into text once a literal character (or the end) interrupts them.
        fun flushEscaped() {
            if (escaped.size() == 0) return
            decoded.append(String(escaped.toByteArray(), StandardCharsets.UTF_8))
            escaped.reset()
        }

        var index = 0
        while (index < value.length) {
            val byte = if (value[index] == '%') hexByteAt(value, index + 1) else null
            if (byte == null) {
                flushEscaped()
                decoded.append(value[index])
                index++
            } else {
                escaped.write(byte)
                index += 3
            }
        }
        flushEscaped()
        return decoded.toString()
    }

    /** The byte spelled by the two hex digits at [start], or null if they are not two hex digits. */
    private fun hexByteAt(value: String, start: Int): Int? {
        if (start + 1 >= value.length) return null
        val high = Character.digit(value[start], 16)
        val low = Character.digit(value[start + 1], 16)
        if (high < 0 || low < 0) return null
        return (high shl 4) or low
    }

    /** Authority host of a URL [URI] refused to parse, so an off-spec QR can still be vouched for. */
    private fun hostOf(rawValue: String): String? {
        val authority = rawValue.substringAfter("://", missingDelimiterValue = "")
            .takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.substringAfterLast('@').substringBefore(':').takeIf(String::isNotEmpty)
    }

    private fun isCarbitHost(host: String): Boolean =
        host == "carbit.com" || host.endsWith(".carbit.com") ||
            host == "carbit.com.cn" || host.endsWith(".carbit.com.cn")
}
