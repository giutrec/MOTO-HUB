package io.motohub.android.tbox

import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * The "build net" half of EC-BTP: how a dashboard that owns no Wi-Fi of its own gets onto one.
 *
 * [EcBtpProtocol] carries the framing and the two clock answers. This is the other conversation
 * that rides on it, and it is the only way some dashboards can be reached at all. A Zontes S350
 * (Brazil, JTZ) brings up no access point and no Wi-Fi Direct peer, ever: it prints an opaque
 * `CARBIT` + 12 hex QR, advertises `b360` over BLE, and waits to be *told* which network to join.
 * The rider cannot supply that - the dash prints no credentials - so without this exchange there
 * is nothing for [io.motohub.android.session.TBoxConnectionMode.PHONE_HOTSPOT] to work with.
 *
 * Decoded from Carbit Ride (`qg/b.java`, `qg/d.java`, `net/easyconn/carman/ble/model/PacketCommand`,
 * `net/easyconn/carman/ble_net/`). The exchange, once the GATT link is up:
 *
 * ```
 * phone -> 0x30 CLIENT_INFO        {"phoneType":0,"phoneID":"…"}
 * dash  -> 0x30 / 0x58 handshake   {"supportFunction":…, "modelid":…}   bit 1 set = speaks build-net
 * phone -> 0x50 REQUEST_BUILD_NET  (no payload)
 * dash  -> 0x50 build-net status   {"status":2}  = "host a network and tell me about it"
 * phone -> 0x52 NOTIFY_AP_INFO     {"ssid":…,"pwd":…,"auth":"WPA2","mac":"","ip":…}
 * dash  -> 0x53 NOTIFY_CAR_NET_INFO {"cnt":1,"data":[{"name":"wlan0","ip":…,"mask":…}]}
 * ```
 *
 * That last frame is worth more than it looks: it is the dash's own address on the network it just
 * joined, which turns discovery from a sweep of a whole /24 into one probe. Carbit does exactly
 * that - `ble_net/a.java` connects straight to `ip:10930`, the wake-probe port MOTO-HUB already
 * speaks - and retries five times at 500ms before giving up on it.
 *
 * Every payload here is JSON, and the payload length lives in a single byte, so nothing this
 * protocol sends may exceed 251 bytes. Credentials are far below that; a JSON blob that is not is
 * a dash doing something this code has never seen, and is dropped rather than truncated.
 */
internal object EcBtpNetProtocol {

    /** phone -> dash: who is calling. The dash answers with its own handshake frame. */
    const val CMD_CLIENT_INFO: Byte = 0x30

    /** phone -> dash, empty: "how should I get you onto a network?" */
    const val CMD_REQUEST_BUILD_NET: Byte = 0x50

    /** phone -> dash, empty: the phone is done setting the network up. */
    const val CMD_NOTIFY_BUILD_NET_FINISH: Byte = 0x51

    /** phone -> dash: the credentials of the network the phone is now hosting. */
    const val CMD_NOTIFY_AP_INFO: Byte = 0x52

    /** dash -> phone: the addresses the dash holds, once it has joined. */
    const val CMD_NOTIFY_CAR_NET_INFO: Byte = 0x53

    /** dash -> phone: the newer handshake shape, carrying the same `supportFunction`. */
    const val CMD_HANDSHAKE_RESPONSE: Byte = 0x58

    /** The dash cannot authorise this phone. Retrying is pointless; say so and stop. */
    const val STATUS_AUTH_FAILED = -2

    /** Authorisation is still in flight - ask again in a moment. */
    const val STATUS_AUTH_PENDING = -1

    /** The dash is already on a network; nothing to build. */
    const val STATUS_ALREADY_BUILT = 0

    /** The dash has a network of its own and the PHONE is the one that has to join it. */
    const val STATUS_PHONE_JOINS_DASH = 1

    /** The dash wants to join a network the phone hosts. This is the Zontes S350 case. */
    const val STATUS_USE_PHONE_AP = 2

    /** `supportFunction` bit 1: this dash speaks the build-net exchange (Carbit `& 2`). */
    const val SUPPORT_BUILD_NET = 0b10

    /**
     * `carNetDeviceInfo.mode` for a dash whose own network is a Wi-Fi Direct group, joined by MAC
     * rather than by SSID (Carbit `ble_net/a.java:r`). Only meaningful under
     * [STATUS_PHONE_JOINS_DASH].
     */
    const val CAR_NET_MODE_P2P = 8

    /**
     * What MOTO-HUB calls itself on this channel.
     *
     * Carbit sends the phone's IMEI here. Nothing in the exchange needs a device identifier - the
     * dash echoes it back at most - so a constant goes out instead. A hardware id that leaves the
     * phone over an unauthenticated radio link would be a real cost paid for no behaviour.
     */
    const val PHONE_ID = "MOTO-HUB"

    /** The widest payload a single length byte can describe: `LEN = payload + 4`, and LEN ≤ 255. */
    const val MAX_PAYLOAD_BYTES = 251

    fun clientInfo(phoneId: String = PHONE_ID): ByteArray {
        val json = JSONObject()
            .put("phoneType", 0)
            .put("phoneID", phoneId)
        return EcBtpProtocol.build(CMD_CLIENT_INFO, json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun requestBuildNet(): ByteArray = EcBtpProtocol.build(CMD_REQUEST_BUILD_NET, ByteArray(0))

    fun buildNetFinished(): ByteArray = EcBtpProtocol.build(CMD_NOTIFY_BUILD_NET_FINISH, ByteArray(0))

    /**
     * The credentials of the network the phone is hosting.
     *
     * [mac] is the phone AP's BSSID, which Android does not publish to the app that started the
     * hotspot; Carbit sends an empty string here too (`ble_net/a.java:N`), so this is the shape
     * the dash is used to receiving rather than a gap. [ip] is omitted when unknown, matching
     * Carbit's two-overload split.
     */
    fun phoneApInfo(
        ssid: String,
        password: String,
        auth: String,
        ip: String? = null,
        mac: String = ""
    ): ByteArray? {
        val json = JSONObject()
            .put("ssid", ssid)
            .put("pwd", password)
            .put("auth", auth)
            .put("mac", mac)
        if (!ip.isNullOrBlank()) json.put("ip", ip)
        val payload = json.toString().toByteArray(StandardCharsets.UTF_8)
        if (payload.size > MAX_PAYLOAD_BYTES) return null
        return EcBtpProtocol.build(CMD_NOTIFY_AP_INFO, payload)
    }

    /** What the dash says about itself when it answers [CMD_CLIENT_INFO]. */
    data class Handshake(val supportFunction: Int, val modelId: String?, val mtu: Int?) {
        val supportsBuildNet: Boolean get() = supportFunction and SUPPORT_BUILD_NET != 0
    }

    /** The dash's answer to [CMD_REQUEST_BUILD_NET]: what it wants the phone to do next. */
    data class BuildNet(
        val status: Int,
        val phoneApFrequencyMhz: Int,
        val dashSsid: String?,
        val dashPassword: String?,
        val dashAuth: String?,
        val dashMac: String?,
        val dashMode: Int,
        val dashName: String?
    )

    /** One interface the dash holds an address on, from [CMD_NOTIFY_CAR_NET_INFO]. */
    data class CarNetInterface(val name: String?, val ip: String, val mask: String?)

    fun parseHandshake(payload: ByteArray): Handshake? {
        val json = readJson(payload) ?: return null
        // A frame with none of these fields is some other 0x30/0x58 dialect, not a handshake this
        // code can act on. `supportFunction` absent is the one that matters: without it there is
        // no claim to check, and asking a dash to build a network it never said it could build is
        // how an unrelated peripheral gets written to.
        if (!json.has("supportFunction")) return null
        return Handshake(
            supportFunction = json.optInt("supportFunction", 0),
            modelId = json.optString("modelid").ifBlank { null },
            mtu = json.optInt("mtu", 0).takeIf { it > 0 }
        )
    }

    fun parseBuildNet(payload: ByteArray): BuildNet? {
        val json = readJson(payload) ?: return null
        if (!json.has("status")) return null
        val device = json.optJSONObject("carNetDeviceInfo")
        return BuildNet(
            status = json.optInt("status"),
            phoneApFrequencyMhz = json.optInt("phoneApFrequency", 0),
            dashSsid = device?.optString("ssid")?.ifBlank { null },
            dashPassword = device?.optString("pwd")?.ifBlank { null },
            dashAuth = device?.optString("auth")?.ifBlank { null },
            dashMac = device?.optString("mac")?.ifBlank { null },
            dashMode = device?.optInt("mode", 0) ?: 0,
            dashName = device?.optString("name")?.ifBlank { null }
        )
    }

    /**
     * The dash's addresses, in the order it listed them.
     *
     * Carbit checks `cnt` against the array length and drops the whole message when they disagree;
     * that check is not repeated here, because an address the dash actually answers on is worth
     * having whether or not it counted its own list correctly. Entries without an address are
     * dropped - there is nothing to probe.
     */
    fun parseCarNetInterfaces(payload: ByteArray): List<CarNetInterface> {
        val json = readJson(payload) ?: return emptyList()
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            val entry = data.optJSONObject(index) ?: return@mapNotNull null
            val ip = entry.optString("ip").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CarNetInterface(
                name = entry.optString("name").ifBlank { null },
                ip = ip,
                mask = entry.optString("mask").ifBlank { null }
            )
        }
    }

    private fun readJson(payload: ByteArray): JSONObject? {
        if (payload.isEmpty()) return null
        return runCatching { JSONObject(payload.toString(StandardCharsets.UTF_8)) }.getOrNull()
    }

    /**
     * Turns the notify stream back into whole EC-BTP frames.
     *
     * One notification is not one frame. At the default 23-byte MTU a JSON payload arrives in
     * 20-byte pieces, and a dash may also pack two frames into one notification. Parsing what
     * arrives, as it arrives, therefore both misses frames and mis-reads them.
     *
     * The length byte is what makes this reliable: a frame is `LEN + 1` bytes long, so once three
     * bytes are in hand the end of the frame is known exactly - no scanning for a terminator that
     * a JSON payload could contain by accident. A start byte that does not lead to a frame passing
     * [EcBtpProtocol.parse] is skipped by one byte rather than trusted, so a mid-stream resync
     * costs at most one frame.
     */
    class FrameAssembler {
        private var pending = ByteArray(0)

        fun accept(chunk: ByteArray): List<EcBtpProtocol.Frame> {
            if (chunk.isEmpty()) return emptyList()
            pending += chunk
            val frames = mutableListOf<EcBtpProtocol.Frame>()
            var index = 0
            // Where the last complete frame ended, and the earliest start byte that could still
            // become one. A candidate that cannot be completed yet is not the end of the scan: a
            // stray 0x24 announcing a 200-byte frame that will never arrive would otherwise hold
            // up every real frame behind it until the whole buffer is discarded.
            var consumedTo = 0
            var firstIncomplete = -1
            while (index < pending.size) {
                if (pending[index] != EcBtpProtocol.START) {
                    index++
                    continue
                }
                val complete = index + 3 <= pending.size &&
                    index + (pending[index + 2].toInt() and 0xFF) + 1 <= pending.size
                if (!complete) {
                    if (firstIncomplete < 0) firstIncomplete = index
                    index++
                    continue
                }
                val frameSize = (pending[index + 2].toInt() and 0xFF) + 1
                val frame = EcBtpProtocol.parse(pending.copyOfRange(index, index + frameSize))
                if (frame == null) {
                    index++
                    continue
                }
                frames += frame
                index += frameSize
                consumedTo = index
            }
            // Only a start byte that could still grow into a frame is worth keeping. Anything
            // before one that already parsed was noise, whatever it looked like, and a buffer
            // scanned end to end with no candidate left in it has nothing to keep at all.
            val retainFrom =
                if (firstIncomplete >= 0 && firstIncomplete >= consumedTo) firstIncomplete else pending.size
            pending = if (retainFrom >= pending.size) ByteArray(0) else pending.copyOfRange(retainFrom, pending.size)
            // A peer that goes quiet mid-frame, or one that never sends a valid frame at all, must
            // not grow this buffer for the whole session.
            if (pending.size > MAX_PENDING_BYTES) pending = ByteArray(0)
            return frames
        }

        private companion object {
            const val MAX_PENDING_BYTES = 4 * 1024
        }
    }
}
