package io.motohub.android.session

import io.motohub.android.i18n.motoHubText
import java.util.UUID

/** Explicit transport selected for the motorcycle T-Box Wi-Fi network. */
enum class TBoxConnectionMode {
    /** Infer P2P from a DIRECT- SSID; otherwise request the dashboard access point. */
    AUTO,
    /** Always use Android's regular Wi-Fi access-point request. */
    ACCESS_POINT,
    /** Always join through Wi-Fi Direct (P2P), including dashes acting as Group Owners. */
    WIFI_DIRECT,

    /**
     * The dash is the Wi-Fi *client*: it joins a hotspot the phone hosts, under an SSID and
     * password the dash prints on its own pairing screen ("Please open Android hotspot and set the
     * following parameters" - confirmed on a tester's dash 2026-08-02, which advertises no access
     * point of its own at all).
     *
     * There is nothing for MOTO-HUB to join here, and Android does not let a third-party app
     * create a hotspot with dictated credentials, so the rider turns tethering on by hand. This
     * mode only tells the transport to stop looking for a dash AP and start looking for a dash
     * *on the phone's own tethering subnet*.
     */
    PHONE_HOTSPOT,

    /**
     * ThinkerRide dashboards (KOVE family): the phone joins the dash's ordinary access point,
     * but everything after that is inverted — pairing runs over a BLE GATT handshake and the
     * dash then connects to TCP servers the phone opens. Selected automatically when the
     * ThinkerRide pairing QR (`g.thinkerride.com`) is scanned; also selectable by hand for a
     * rebadged unit whose QR points at some OEM host.
     */
    THINKERRIDE
}

enum class SessionPhase {
    SETUP_REQUIRED,
    CONNECTING_NETWORK,
    DISCOVERING_TBOX,
    READY,
    NETWORK_SETUP_REQUIRED,
    REQUESTING_PROJECTION,
    CAPTURING,
    ERROR
}

data class MotorcycleProfile(
    val ssid: String,
    val password: String,
    val id: String = UUID.randomUUID().toString(),
    val modelId: String? = null,
    val displayName: String? = null,
    val photoPath: String? = null,
    val fuelTankRangeKm: Double? = null,
    val profileOverrideKey: String? = null,
    val connectionMode: TBoxConnectionMode = TBoxConnectionMode.AUTO
)

data class HubSessionState(
    val phase: SessionPhase = SessionPhase.SETUP_REQUIRED,
    val motorcycle: MotorcycleProfile? = null,
    val message: String = motoHubText("Set up the motorcycle network to get started."),
    /**
     * The last failure was Android never joining the dash's access point, on a profile that was
     * not already [TBoxConnectionMode.PHONE_HOTSPOT]. That is the exact shape a Wi-Fi-client dash
     * produces - there is no access point to join, so the join can only time out - and whether a
     * given dash is one of those cannot be inferred from its brand, its SSID, or its modelId
     * (the modelId only arrives after a link is up, which never happens here). So the app cannot
     * decide for the rider; it can only make trying the other mode cost one tap instead of a
     * hunt through the menus.
     *
     * Carried as state rather than matched out of [message] because that text is assembled from
     * the underlying exception and is translated.
     */
    val offerPhoneHotspotRetry: Boolean = false
)

fun HubSessionState.withMotorcycle(profile: MotorcycleProfile): HubSessionState = copy(
    phase = SessionPhase.NETWORK_SETUP_REQUIRED,
    motorcycle = profile,
    message = motoHubText("Profile saved. Connect to the T-Box network and start discovery.")
)
