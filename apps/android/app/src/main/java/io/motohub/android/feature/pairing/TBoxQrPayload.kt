package io.motohub.android.feature.pairing

import java.net.URI
import java.net.URLDecoder
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
        val uri = URI(rawValue)
        val parameters = uri.rawQuery.orEmpty()
            .split('&')
            .filter(String::isNotBlank)
            .associate { item ->
                val keyAndValue = item.split('=', limit = 2)
                decode(keyAndValue[0]) to decode(keyAndValue.getOrElse(1) { "" })
            }
        val ssid = parameters["ssid"].orEmpty().trim()
        check(ssid.isNotEmpty()) { "The QR code does not carry a T-Box network name." }

        val host = uri.host?.lowercase()
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

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun isCarbitHost(host: String): Boolean =
        host == "carbit.com" || host.endsWith(".carbit.com") ||
            host == "carbit.com.cn" || host.endsWith(".carbit.com.cn")
}
