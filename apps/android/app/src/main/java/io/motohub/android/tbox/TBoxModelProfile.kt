package io.motohub.android.tbox

import io.motohub.android.androidauto.AndroidAutoDisplayMode
import io.motohub.android.androidauto.AndroidAutoVideoPreset
import io.motohub.android.androidauto.TBoxScreenMargins

/** Tunings that can be applied after the transport has decoded a touch frame. */
data class TBoxTouchPolicy(
    val ghostMergePx: Int = 48,
    val stitchMillis: Long = 80,
    val stitchDistancePx: Int = 150,
    val staleContactMillis: Long = 300,
    val maxPointers: Int = 2
)

/**
 * Known T-Box behavior hints, matching the profiles from the OpenCfMoto reference.
 * Wire-level quirks remain inside the native transport.
 */
enum class TBoxModelProfile(
    val key: String,
    val displayName: String,
    private val modelIds: Set<String>,
    val mapTilesRequireCellular: Boolean,
    val touchPolicy: TBoxTouchPolicy = TBoxTouchPolicy(),
    val defaultScreenMargins: TBoxScreenMargins = TBoxScreenMargins.NONE,
    /** Default only; riders may still select FIT, STRETCH, or CROP per motorcycle. */
    val defaultAndroidAutoDisplayMode: AndroidAutoDisplayMode = AndroidAutoDisplayMode.LETTERBOX,
    val supportsScreenTouch: Boolean = true,
    val defaultAndroidAutoPreset: AndroidAutoVideoPreset =
        AndroidAutoVideoPreset.LANDSCAPE_800X480,
    /**
     * Compatibility geometry used only when the T-Box omits its live VideoArea. This is kept
     * separate from the Android Auto source preset because the AA source and physical T-Box
     * projection canvas are not interchangeable.
     */
    val fallbackTBoxVideoArea: TBoxEvent.VideoArea? = null,
    /** Matches OpenCfMoto's requiresSockServerAuth / enableSockServerAuth flag. */
    val requiresSockAuth: Boolean = false,
    /** Capability bitmask returned by the MediaCtrlScreenConf response. */
    val advertisedSupportFunction: Int = 0,
    /**
     * Whether the dash's own `supportExtendProtocol` byte may pick the video frame format
     * (RideDaemonTransport's `plainVideoFramingAllowed`): ext=0 drops the 4-byte frame index,
     * ext=1 keeps it. Every recognised unit streams fine on the indexed framing it already
     * displays, so this stays off for all of them; only [GENERIC] — which by definition claims
     * nothing about the dash — and a framing experiment may hand the choice to the firmware.
     */
    val allowsPlainVideoFraming: Boolean = false,
    /**
     * Firmware closes EasyConn unless both reverse PXC channel sockets receive traffic. Defaults
     * to false only so that a profile written for a dash known not to need it can stay silent;
     * anything unidentified goes through [GENERIC], which turns it on.
     */
    val requiresProactivePxcHeartbeat: Boolean = false,
    /**
     * GOP length in seconds for the TFT AVC encoder; 0 keeps the all-intra stream every dash has
     * always received. Only compatibility-experiment profiles set this, so it can never change
     * the wire format of a dash that streams fine today.
     */
    val encoderKeyframeIntervalSeconds: Int = 0,
    /**
     * Capture frame rate for this dash, or null to keep the negotiated one. Only set it for a dash
     * whose OEM app is known to run slower — a lower rate is a real quality loss everywhere else.
     */
    val encoderFrameRate: Int? = null,
    /**
     * Base bitrate for this dash before the rider's quality setting scales it, or null to keep the
     * negotiated one.
     */
    val encoderBitRate: Int? = null,
    /**
     * Density for the captured virtual display, or null to use the phone's own. The phone's density
     * is right whenever the dash shows a mirror of the phone, and wrong whenever the dash drives a
     * layout of its own at a fixed size: a 1024x464 panel rendered at a modern phone's ~420dpi gets
     * UI sized for a screen three times its width.
     */
    val virtualDisplayDpi: Int? = null,
    /** Which wire protocol the dash speaks; routes the session to the matching transport. */
    val transportFamily: TBoxTransportFamily = TBoxTransportFamily.EASYCONN,
    /**
     * Yunmo only: use the OEM map-navigation display path (A0 cmd=6, with each keyframe split into
     * standalone SPS / PPS / coded-picture frames) instead of a plain mirror. This is the newest,
     * still-unconfirmed compatibility experiment for the X-Cape 1200, so it stays off unless a
     * profile opts in — a plain mirror is the safe default for any Yunmo dash.
     */
    val yunmoMapNavExperiment: Boolean = false,
    /**
     * Yunmo only: tag each video frame's header with the media type derived from its NAL content
     * (codec config / IDR / P) instead of the fixed `2` every build so far has sent.
     *
     * Both settings this flag chooses between were inherited from a reference implementation that
     * has never been seen to paint a dash, and neither has ever been observed on one that does.
     * The reference defines all four media types and derives them, then hardcodes the fixed value
     * at its only call site — so "fixed" is a guess, not a measurement. A field session on
     * 2026-08-11 had the dash in the right mode, drawing its own overlay, acknowledging every
     * frame and painting none of them, which is what a decoder does with frames it will not decode.
     */
    val yunmoTypedMediaHeader: Boolean = false,
    /**
     * Yunmo only: fill the header's frame id, width and height instead of leaving them zero.
     *
     * Same standing as [yunmoTypedMediaHeader]: the reference omits them, and the note that an
     * older build filling them produced a black TFT came from an era when that build also asked
     * for a canvas twice the panel's size, so the black screen has a simpler explanation and this
     * field was probably never the cause.
     */
    val yunmoFrameMetadata: Boolean = false
) {
    MOTO_HUB_SIMULATOR(
        key = "moto_hub_simulator",
        displayName = "MOTO-HUB T-Box Simulator",
        modelIds = setOf("MOTO-HUB-SIMULATOR"),
        mapTilesRequireCellular = false,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(800, 480),
        advertisedSupportFunction = 128
    ),
    /** CFDL16 / 450SR-style non-touch legacy dash. */
    LEGACY_CFDL16(
        key = "legacy_cfdl16",
        displayName = "CFDL16 / Legacy (BIKE A)",
        modelIds = setOf("37416"),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        requiresSockAuth = false,
        advertisedSupportFunction = 0
    ),
    /** 800NK CRCP / sdk 0.9.23.x non-touch. */
    CFMOTO_800NK(
        key = "cfmoto_800nk",
        displayName = "CFMOTO 800NK",
        modelIds = setOf("66660703", "66660721"),
        mapTilesRequireCellular = true,
        defaultScreenMargins = TBoxScreenMargins(top = 22),
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        requiresSockAuth = false,
        advertisedSupportFunction = 128,
        requiresProactivePxcHeartbeat = true
    ),
    /**
     * MTX800 portrait Wi-Fi Direct dashboard. Model id 66660732 was previously
     * grouped with 800NK, which gave the motorcycle the wrong product identity.
     * Its measured projection area is 460x750 and the tester hardware exhibits
     * the same dual-channel PXC timeout unless proactive heartbeats are enabled.
     * It has no confirmed motorcycle-owned top strip inside that projection area.
     */
    CFMOTO_MTX800(
        key = "cfmoto_mtx800",
        displayName = "CFMOTO MTX800",
        modelIds = setOf("66660732"),
        mapTilesRequireCellular = true,
        defaultAndroidAutoDisplayMode = AndroidAutoDisplayMode.FILL,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.PORTRAIT_720X1280,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(460, 750),
        requiresSockAuth = false,
        advertisedSupportFunction = 128,
        requiresProactivePxcHeartbeat = true
    ),
    /** CFDL26 MotoPlay Landscape (800MT) — touchscreen dash. */
    CFDL26_LANDSCAPE(
        key = "cfdl26_landscape",
        displayName = "CFDL26 / MotoPlay Landscape (800MT)",
        modelIds = setOf("37426"),
        mapTilesRequireCellular = true,
        supportsScreenTouch = true,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        requiresSockAuth = true,
        advertisedSupportFunction = 128
    ),
    /** CFDL26 MotoPlay Portrait (1000 MT‑X) — handlebar-primary, non-touch. */
    CFDL26_PORTRAIT(
        key = "cfdl26_portrait",
        displayName = "CFDL26 / MotoPlay Portrait (1000 MT‑X)",
        modelIds = setOf("37426"),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.PORTRAIT_720X1280,
        requiresSockAuth = true,
        advertisedSupportFunction = 128
    ),
    /**
     * CFDL26 800NK Advanced — near-square touch panel measured 720x712 by the OpenCfMoto
     * zanderp reference, which requests it at 160dpi. MOTO-HUB's [AndroidAutoVideoPreset]
     * bundles one fixed density per resolution and [AndroidAutoVideoPreset.PORTRAIT_720X1280]
     * is already 240dpi for [CFDL26_PORTRAIT] above; adding a second 720x1280 preset just for
     * this profile's dpi would ripple through the whole AA capability-profile/settings system
     * for a UI-scaling difference, not a resolution difference, so this deliberately reuses the
     * existing 240dpi preset. Shares modelId "37426" with [CFDL26_LANDSCAPE]/[CFDL26_PORTRAIT];
     * disambiguated from both via [resolve]'s CLIENT_INFO scoring, not by modelId alone.
     */
    CFDL26_NK_TOUCH(
        key = "cfdl26_nk_touch",
        displayName = "CFDL26 / 800NK Advanced (touch, 720x712)",
        modelIds = setOf("37426"),
        mapTilesRequireCellular = true,
        supportsScreenTouch = true,
        defaultScreenMargins = TBoxScreenMargins(top = 22),
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.PORTRAIT_720X1280,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(720, 712),
        requiresSockAuth = true,
        advertisedSupportFunction = 128
    ),
    /** CFDL16-class MotoPlay Landscape, modelId 66660742 (Wi-Fi Direct, non-touch). */
    CFDL16_MOTOPLAY_LANDSCAPE(
        key = "cfdl16_motoplay_landscape",
        displayName = "CFDL16 / MotoPlay Landscape (66660742)",
        modelIds = setOf("66660742"),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        requiresSockAuth = false,
        advertisedSupportFunction = 128
    ),
    /** Near-square CL-C450 panel measured 544x512; requested with an HD 1280x720 AA source. */
    CL_C450(
        key = "cl_c450",
        displayName = "CL-C450 (544x512)",
        modelIds = setOf("66660736", "CLC450"),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_1280X720,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(544, 512),
        requiresSockAuth = false,
        advertisedSupportFunction = 0
    ),
    /**
     * Compatibility experiment for the Zontes 368G (2025, PKE firmware 1.25.x, HUName JCDZ34-*):
     * the dash completes the whole EasyConn handshake, requests 1280x535, sends STREAM_START and
     * pulls frames, yet its UI never leaves the pairing-QR page (field log 2026-08-03). Manual
     * selection only - [score] never claims it, so no dash ever lands here by detection. Two
     * deltas versus [GENERIC], both suspected requirements of that firmware's decoder:
     * indexed video framing (any non-GENERIC profile keeps the frame index, see
     * RideDaemonTransport's plainVideoFramingAllowed) and a 1s GOP instead of all-intra.
     */
    ZONTES_368G_TEST(
        key = "zontes_368g_test",
        displayName = "Zontes 368G (test)",
        modelIds = emptySet(),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_1280X720,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(1280, 535),
        requiresSockAuth = false,
        advertisedSupportFunction = 0,
        requiresProactivePxcHeartbeat = true,
        encoderKeyframeIntervalSeconds = 1
    ),
    /**
     * Second half of the same experiment, after the tester ran [ZONTES_368G_TEST] on the bike
     * (field log 2026-08-11, app 1.1.58). That profile moved two things at once and the log says
     * one of them is wrong: with the frame index kept, the dash — which reports
     * `supportExtendProtocol=0` — closed the video socket itself after 6s (94 frames) and 17s
     * (321 frames), where plain framing had held the full 30s of the dash's own no-video
     * timeout. So the index is not what this firmware wants, and the 1s GOP is the only delta
     * left untested. This profile is [GENERIC]'s wire format — the ext byte decides the framing —
     * with the 1s GOP on top, so the two experiments can be compared one variable apart.
     * Manual selection only, like its sibling: [score] never claims it.
     */
    ZONTES_368G_TEST_B(
        key = "zontes_368g_test_b",
        displayName = "Zontes 368G (test B)",
        modelIds = emptySet(),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_1280X720,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(1280, 535),
        requiresSockAuth = false,
        advertisedSupportFunction = 0,
        allowsPlainVideoFraming = true,
        requiresProactivePxcHeartbeat = true,
        encoderKeyframeIntervalSeconds = 1
    ),
    /**
     * KOVE 800X (and, until they earn their own profiles, other ThinkerRide-family dashes): a
     * 600x1024 portrait TFT paired over BLE, reached through [TBoxTransportFamily.THINKERRIDE].
     * The ThinkerRide protocol never reports a panel size — the phone declares the stream
     * geometry and the dash scales — so [fallbackTBoxVideoArea] IS the negotiated area here,
     * and a future KOVE model with a different panel is a new profile with a different area
     * (pinned via [ProfileOverride] until its QR can be told apart). The modelId is the
     * pseudo-id the ThinkerRide QR dialect records, since the QR itself carries no model
     * information. GOP mirrors the reference implementation (streaming ~1.8 Mbps with a short
     * GOP, never all-intra) and detection scoring never claims this profile: CLIENT_INFO is an
     * EasyConn concept and does not exist on this wire.
     */
    KOVE_800X(
        key = "kove_800x",
        displayName = "KOVE 800X (ThinkerRide)",
        modelIds = setOf(ThinkerRideProtocol.PROVISIONING_MODEL_ID),
        mapTilesRequireCellular = true,
        defaultAndroidAutoDisplayMode = AndroidAutoDisplayMode.FILL,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.PORTRAIT_720X1280,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(
            ThinkerRideProtocol.DEFAULT_VIDEO_WIDTH,
            ThinkerRideProtocol.DEFAULT_VIDEO_HEIGHT
        ),
        requiresSockAuth = false,
        advertisedSupportFunction = 0,
        encoderKeyframeIntervalSeconds = 1,
        transportFamily = TBoxTransportFamily.THINKERRIDE
    ),
    /**
     * Where every dashboard no other profile claims ends up, whatever badge is on the tank. The
     * geometry here is a starting point, not a measurement: once the dash reports a live video
     * area it is learned per SSID and drives the next session (see TBoxDisplayGeometryStore).
     */
    GENERIC(
        key = "generic",
        displayName = "Generic EasyConn dashboard",
        modelIds = emptySet(),
        mapTilesRequireCellular = true,
        // Existing generic compatibility profile; never treated as live geometry.
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(800, 480),
        // On by default here because GENERIC is what every dashboard we cannot identify lands
        // on - every non-CFMOTO brand included - and that is exactly the population whose
        // firmware behaviour is unknown. A Zontes dash (package tayo.com.ZontesIntelligence,
        // modelId 21334, field log 2026-07-30) sent its opening PXC burst, requested the
        // capture area, sent STREAM_START and one heartbeat, then went silent and closed the
        // socket 96s later while the app streamed 1857 frames into it and told the rider that
        // projection was live. The reference implementation heartbeats unconditionally and
        // documents why: some firmware never initiates heartbeats and then drops the idle PXC
        // socket. Sending a keepalive to a dash that does not need one is harmless; not
        // sending one to a dash that does costs the whole session.
        requiresProactivePxcHeartbeat = true,
        // A dash no profile claims is the only one whose supportExtendProtocol byte we trust
        // over our own default; see [allowsPlainVideoFraming].
        allowsPlainVideoFraming = true
    ),
    /**
     * Moto Morini X-Cape 1200 — the one dash known to speak Yunmo over its `ML*` SoftAP at
     * 192.168.4.1:8200 instead of EasyConn. Reached through [TBoxTransportFamily.YUNMO].
     *
     * [modelIds] is deliberately empty: the pairing QR's `ProductID=00297` is shared with the
     * X-Cape 649 / 700 and the Seiemmezzo, which speak EasyConn, so keying this profile off that
     * id would break those bikes. It is reachable only by a manual [ProfileOverride] pin until an
     * owner's CLIENT_INFO (or a distinguishing SSID) can tell the 1200 apart. Geometry is a
     * fallback only — the Yunmo dim-query reports the panel size directly (an X-Cape answers
     * 1024x464); 800x480 backstops a dash that never answers.
     *
     * Map-nav is ON here, unlike any other Yunmo dash would default to. An owner's ADB capture of
     * the OEM app (Ride MO 1.0.23, 2026-08-07) showed it never mirrors this dash: the TFT menu
     * offers two *navigation* presentations (compact arrows / full-screen map) and the OEM always
     * drives the navigation path, latching the TFT's chosen mode at navigation startup. A plain
     * mirror is therefore unlikely to be a mode this firmware has at all.
     *
     * The frame rate, bitrate and dpi are what that same capture measured the OEM doing. The dpi is
     * not cosmetic — it is the density of the OEM `NaviVirtualDisplay`, and rendering this canvas
     * at a modern phone's own density instead sizes the UI for a screen three times as wide.
     *
     * The OEM's 2-second GOP is deliberately NOT copied. [YunmoTransport.offerAccessUnit] drops an
     * access unit when one is already queued behind a slow socket, and a dropped P-frame corrupts
     * the picture until the next keyframe — the smearing an earlier GOP experiment produced on a
     * bike. All-intra costs bitrate and nothing else, which at 10 fps this dash can afford.
     */
    MORINI_XCAPE_1200(
        key = "morini_xcape_1200",
        displayName = "Moto Morini X-Cape 1200 (Yunmo)",
        modelIds = emptySet(),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(800, 480),
        requiresSockAuth = false,
        advertisedSupportFunction = 0,
        encoderFrameRate = 10,
        encoderBitRate = 2_000_000,
        virtualDisplayDpi = 187,
        transportFamily = TBoxTransportFamily.YUNMO,
        yunmoMapNavExperiment = true
    ),
    /**
     * X-Cape 1200, variant B: the video header carries the media type derived from each frame's
     * NAL content instead of the fixed value [MORINI_XCAPE_1200] sends.
     *
     * B, C and D exist because the two header fields nobody has ever validated form a 2x2, and a
     * dash that acknowledges every frame without painting cannot tell us which corner is right.
     * Selecting between them from the Garage answers in one ride what a byte-level capture of the
     * OEM app would answer in one line - whichever arrives first.
     */
    MORINI_XCAPE_1200_B(
        key = "morini_xcape_1200_b",
        displayName = "Moto Morini X-Cape 1200 (test B)",
        modelIds = emptySet(),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(800, 480),
        requiresSockAuth = false,
        advertisedSupportFunction = 0,
        encoderFrameRate = 10,
        encoderBitRate = 2_000_000,
        virtualDisplayDpi = 187,
        transportFamily = TBoxTransportFamily.YUNMO,
        yunmoMapNavExperiment = true,
        yunmoTypedMediaHeader = true
    ),
    /** X-Cape 1200, variant C: typed media header *and* frame id / width / height filled in. */
    MORINI_XCAPE_1200_C(
        key = "morini_xcape_1200_c",
        displayName = "Moto Morini X-Cape 1200 (test C)",
        modelIds = emptySet(),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(800, 480),
        requiresSockAuth = false,
        advertisedSupportFunction = 0,
        encoderFrameRate = 10,
        encoderBitRate = 2_000_000,
        virtualDisplayDpi = 187,
        transportFamily = TBoxTransportFamily.YUNMO,
        yunmoMapNavExperiment = true,
        yunmoTypedMediaHeader = true,
        yunmoFrameMetadata = true
    ),
    /** X-Cape 1200, variant D: the fixed media type kept, but frame id / width / height filled in. */
    MORINI_XCAPE_1200_D(
        key = "morini_xcape_1200_d",
        displayName = "Moto Morini X-Cape 1200 (test D)",
        modelIds = emptySet(),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(800, 480),
        requiresSockAuth = false,
        advertisedSupportFunction = 0,
        encoderFrameRate = 10,
        encoderBitRate = 2_000_000,
        virtualDisplayDpi = 187,
        transportFamily = TBoxTransportFamily.YUNMO,
        yunmoMapNavExperiment = true,
        yunmoFrameMetadata = true
    );

    companion object {
        private fun candidatesForModelId(modelId: String?): List<TBoxModelProfile> {
            val normalized = modelId?.trim().orEmpty()
            if (normalized.isEmpty()) return emptyList()
            return entries.filter { normalized in it.modelIds }
        }

        fun fromModelId(modelId: String?): TBoxModelProfile =
            // If multiple profiles match the same modelId (e.g. "37426" for CFDL26_LANDSCAPE,
            // CFDL26_PORTRAIT and CFDL26_NK_TOUCH), resolve via CLIENT_INFO scoring in resolve().
            candidatesForModelId(modelId).singleOrNull() ?: GENERIC

        /** Prefer authoritative CLIENT_INFO strings when the QR/model id is ambiguous. */
        fun resolve(modelId: String?, capabilities: TBoxCapabilities?): TBoxModelProfile {
            return resolve(modelId, capabilities, null)
        }

        /** Resolve with optional manual override. When [profileOverride] is not AUTO, it pins the profile. */
        fun resolve(
            modelId: String?,
            capabilities: TBoxCapabilities?,
            profileOverride: ProfileOverride?
        ): TBoxModelProfile {
            profileOverride?.resolve()?.let { return it }
            val byId = fromModelId(modelId)
            if (byId != GENERIC) return byId
            if (capabilities == null) return GENERIC
            // Restrict scoring to profiles that share the (ambiguous) modelId when one was
            // provided at all - e.g. only the three CFDL26 variants compete for "37426", never
            // a profile the modelId itself doesn't claim. Only opens up to every profile when
            // there was no modelId lead to begin with.
            val candidates = candidatesForModelId(modelId).ifEmpty { entries.filterNot { it == GENERIC } }
            return candidates
                .map { it to score(it, capabilities) }
                .filter { (_, points) -> points > 0 }
                .maxByOrNull { (_, points) -> points }
                ?.first
                ?: GENERIC
        }

        /**
         * Every profile's CLIENT_INFO score, not just the one [resolve] picks - diagnostic
         * only (gated behind Settings > Diagnostics > Verbose T-Box logging), so a rider's
         * shared log can show *why* a given profile won instead of only the final answer.
         */
        internal fun scoreBreakdown(capabilities: TBoxCapabilities): String =
            entries.filterNot { it == GENERIC }
                .joinToString(", ") { "${it.displayName}=${score(it, capabilities)}" }

        /**
         * Weighted match against CLIENT_INFO signals, used to disambiguate profiles sharing a
         * modelId or to identify a device when no modelId is known at all. The signal set
         * mirrors what the OpenCfMoto zanderp fork's `BikeProfile.score()` checks per profile
         * (version_name/package_name/sdkVersion/supportFunction/HUName/capability-flag
         * matches), reimplemented against [TBoxCapabilities]. Highest positive score wins; a
         * score of 0 means "no claim" and is never selected over [GENERIC].
         */
        private fun score(profile: TBoxModelProfile, capabilities: TBoxCapabilities): Int {
            // Combined lowercase fallback for the same free-text keyword matching resolve()
            // used before this scoring existed (carModel included) - kept alongside the more
            // precise per-field signals below so devices only identifiable by a loose keyword
            // match (e.g. only `carModel` populated) still resolve the same way they did.
            val identity = listOf(
                capabilities.carModel,
                capabilities.huName,
                capabilities.packageName,
                capabilities.versionName,
                capabilities.sdkVersion
            ).filterNotNull().joinToString(" ").lowercase()
            val versionName = capabilities.versionName.orEmpty()
            val packageName = capabilities.packageName.orEmpty()
            val sdkVersion = capabilities.sdkVersion.orEmpty()
            val supportFunction = capabilities.supportFunction ?: 0
            val sockAuth = capabilities.socketServerAuth ?: false
            val mirrorOverlayTouch = capabilities.mirrorOverlayTouch ?: false
            val screenTouch = capabilities.screenTouch ?: false
            val landscapeAdaptive = capabilities.landscapeAdaptive ?: false

            fun cfdl26BaseScore(): Int {
                // Identity signals: things only a CFDL26-family CFMOTO dash reports. A modern
                // sdkVersion (1.x) and supportFunction=128 are true of other brands' EasyConn
                // firmware too (a generic Zontes dash reports sdkVersion=1.1.3.2 + 128 and was
                // misclassified as 800NK Advanced by exactly those two), so they only ever
                // corroborate an identity match, never establish one.
                var points = 0
                if (versionName.startsWith("CFDL26")) points += 4
                if (packageName == "com.cfmoto.easyconnect") points += 3
                if (sockAuth) points += 2
                if (identity.contains("cfdl26") || identity.contains("motoplay")) points += 2
                if (points == 0) return 0
                if (sdkVersion.isNotEmpty() && !sdkVersion.startsWith("0.")) points += 2
                if (supportFunction == 128) points += 1
                return points
            }

            return when (profile) {
                CFDL26_LANDSCAPE -> cfdl26BaseScore()
                CFDL26_PORTRAIT -> {
                    val base = cfdl26BaseScore()
                    if (base == 0) {
                        0
                    } else {
                        // Deliberately does NOT award points for screenTouch/mirrorOverlayTouch
                        // being merely absent (unset defaults to false the same as "measured
                        // false") - unlike the reference, which does. Landscape is the safer
                        // tie-break default when CLIENT_INFO is too sparse to say anything
                        // positive about orientation; a wrong landscape/portrait guess is a
                        // worse outcome than a wrong dpi guess.
                        val portraitHint = identity.contains("portrait") ||
                            identity.contains("mt_x") || identity.contains("mt-x")
                        base + (if (portraitHint) 2 else 0)
                    }
                }
                CFDL26_NK_TOUCH -> {
                    val base = cfdl26BaseScore()
                    if (base == 0) {
                        0
                    } else {
                        base + (if (mirrorOverlayTouch) 1 else 0) + (if (screenTouch) 1 else 0)
                    }
                }
                CFMOTO_800NK -> {
                    var points = 0
                    if (identity.contains("800nk") || identity.contains("800 nk")) points += 4
                    if (sdkVersion.startsWith("0.9.23") && identity.contains("linux_no_package")) points += 3
                    if (identity.contains("crcp")) points += 2
                    points
                }
                CFMOTO_MTX800 -> {
                    var points = 0
                    if (
                        identity.contains("mtx800") ||
                        identity.contains("mtx 800") ||
                        identity.contains("800mt-x") ||
                        identity.contains("800 mt-x")
                    ) {
                        points += 4
                    }
                    points
                }
                LEGACY_CFDL16 -> {
                    var points = 0
                    if (identity.contains("cfdl16") || identity.contains("bike a")) points += 3
                    points
                }
                CFDL16_MOTOPLAY_LANDSCAPE -> {
                    // Primary identification is the QR modelId (66660742). For CLIENT_INFO
                    // scoring the same identity-vs-corroboration split as cfdl26BaseScore()
                    // applies: supportLandscapeAdaptive is a generic EasyConn capability flag
                    // (other brands report it true) and must never claim this profile alone.
                    var points = 0
                    if (identity.contains("cfdl16") || identity.contains("motoplay")) points += 2
                    if (points > 0 && landscapeAdaptive) points += 1
                    points
                }
                CL_C450 -> {
                    var points = 0
                    if (identity.contains("48fb4c")) points += 4
                    if (sdkVersion.startsWith("0.9.23")) points += 1
                    points
                }
                MOTO_HUB_SIMULATOR -> {
                    var points = 0
                    if (identity.contains("moto-hub") || identity.contains("moto hub")) points += 4
                    points
                }
                // Manual-selection experiments: detection must never claim them, or the riders
                // whose Zontes dashes already stream fine would silently change wire format.
                ZONTES_368G_TEST -> 0
                ZONTES_368G_TEST_B -> 0
                // ThinkerRide dashes never produce CLIENT_INFO (an EasyConn concept), so scoring
                // has nothing to say; they resolve by the QR's pseudo modelId or a manual pin.
                KOVE_800X -> 0
                // Yunmo dashes never produce CLIENT_INFO either, and the X-Cape 1200 shares its
                // QR ProductID with EasyConn Morinis, so detection must never claim it: it is a
                // manual pin only.
                MORINI_XCAPE_1200, MORINI_XCAPE_1200_B, MORINI_XCAPE_1200_C, MORINI_XCAPE_1200_D -> 0
                GENERIC -> 0
            }
        }

        fun defaultAndroidAutoPreset(
            modelId: String?,
            capabilities: TBoxCapabilities?,
            profileOverride: ProfileOverride? = null
        ): AndroidAutoVideoPreset =
            resolve(modelId, capabilities, profileOverride).defaultAndroidAutoPreset

        /**
         * True when [defaultAndroidAutoPreset] comes from a recognized dash rather than
         * [GENERIC]. Only a recognized profile's orientation is evidence about the hardware;
         * see AndroidAutoCapabilityProfiles.usableSavedGeometryForAuto.
         *
         * A rider who pinned [ProfileOverride.GENERIC] is stating the opposite — that nothing here
         * is known-good — so the override has to reach this answer too, otherwise the pin would
         * silently keep the veto of whichever profile detection had guessed.
         */
        fun hasValidatedAndroidAutoPreset(
            modelId: String?,
            capabilities: TBoxCapabilities?,
            profileOverride: ProfileOverride? = null
        ): Boolean = resolve(modelId, capabilities, profileOverride) != GENERIC

        fun fallbackVideoArea(
            modelId: String?,
            capabilities: TBoxCapabilities?,
            profileOverride: ProfileOverride? = null
        ): TBoxEvent.VideoArea =
            resolve(modelId, capabilities, profileOverride).fallbackTBoxVideoArea
                ?: GENERIC.fallbackTBoxVideoArea
                ?: error("Generic T-Box fallback geometry is not configured.")
    }
}
