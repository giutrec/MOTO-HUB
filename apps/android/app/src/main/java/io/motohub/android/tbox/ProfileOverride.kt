package io.motohub.android.tbox

/**
 * Manual T-Box profile override that the user can set from the Garage.
 * [AUTO] lets the app detect the profile from QR/modelId/CLIENT_INFO;
 * any other entry pins that profile regardless of detection.
 *
 * [GENERIC] is the one entry that pins *less* rather than more. Detection can only recognise
 * dashboards it has seen, so a rider whose dashboard scores against the wrong profile has no way
 * back to the neutral defaults without it — [AUTO] would just re-run the same mistaken match.
 */
enum class ProfileOverride(
    val key: String,
    val label: String,
    val description: String
) {
    AUTO("auto", "Auto", "Detect from the motorcycle (recommended)"),
    GENERIC("generic", "Generic dashboard", "Neutral defaults for a dashboard that is not recognised"),
    LEGACY_CFDL16("legacy_cfdl16", "CFDL16 / Legacy", "CFDL16 / 450SR-style non-touch"),
    CFMOTO_800NK("cfmoto_800nk", "CFMOTO 800NK", "CRCP / sdk 0.9.23.x non-touch"),
    CFMOTO_MTX800("cfmoto_mtx800", "CFMOTO MTX800", "Portrait Wi-Fi Direct dashboard, modelId 66660732"),
    CFDL26_LANDSCAPE("cfdl26_landscape", "800MT (CFDL26)", "CFDL26 MotoPlay landscape touch"),
    CFDL26_PORTRAIT("cfdl26_portrait", "1000 MT-X (CFDL26)", "CFDL26 MotoPlay portrait handlebar-primary"),
    CFDL26_NK_TOUCH("cfdl26_nk_touch", "800NK Advanced (CFDL26)", "Near-square touch panel, 720x712"),
    CFDL16_MOTOPLAY_LANDSCAPE("cfdl16_motoplay_landscape", "MotoPlay Landscape (CFDL16)", "modelId 66660742, Wi-Fi Direct, non-touch"),
    CL_C450("cl_c450", "CL-C450", "Near-square panel, 544x512"),
    ZONTES_368G_TEST("zontes_368g_test", "Zontes 368G (test)", "Experiment for JCDZ dashes stuck on the QR page: indexed framing + 1s GOP"),
    ZONTES_368G_TEST_B(
        "zontes_368g_test_b",
        "Zontes 368G (test B)",
        "Same experiment with plain framing instead: the dash's ext byte decides, plus a 1s GOP"
    ),
    KOVE_800X("kove_800x", "KOVE 800X (ThinkerRide)", "BLE-paired ThinkerRide dash, 600x1024 portrait"),
    MORINI_XCAPE_1200(
        "morini_xcape_1200",
        "X-Cape 1200 (Yunmo)",
        "Moto Morini X-Cape 1200 SoftAP dash on Yunmo :8200 (not the 649/700/Seiemmezzo)"
    ),
    MORINI_XCAPE_1200_B(
        "morini_xcape_1200_b",
        "X-Cape 1200 (test B)",
        "Same dash, but each video frame is tagged with its own type instead of a fixed one"
    ),
    MORINI_XCAPE_1200_C(
        "morini_xcape_1200_c",
        "X-Cape 1200 (test C)",
        "Test B plus the frame number and size written into every video frame header"
    ),
    MORINI_XCAPE_1200_D(
        "morini_xcape_1200_d",
        "X-Cape 1200 (test D)",
        "Only the frame number and size added; frame tagging left as the standard profile"
    ),
    MOTO_HUB_SIMULATOR("moto_hub_simulator", "MOTO-HUB Simulator", "Development simulator profile");

    fun resolve(): TBoxModelProfile? = when (this) {
        AUTO -> null
        GENERIC -> TBoxModelProfile.GENERIC
        LEGACY_CFDL16 -> TBoxModelProfile.LEGACY_CFDL16
        CFMOTO_800NK -> TBoxModelProfile.CFMOTO_800NK
        CFMOTO_MTX800 -> TBoxModelProfile.CFMOTO_MTX800
        CFDL26_LANDSCAPE -> TBoxModelProfile.CFDL26_LANDSCAPE
        CFDL26_PORTRAIT -> TBoxModelProfile.CFDL26_PORTRAIT
        CFDL26_NK_TOUCH -> TBoxModelProfile.CFDL26_NK_TOUCH
        CFDL16_MOTOPLAY_LANDSCAPE -> TBoxModelProfile.CFDL16_MOTOPLAY_LANDSCAPE
        CL_C450 -> TBoxModelProfile.CL_C450
        ZONTES_368G_TEST -> TBoxModelProfile.ZONTES_368G_TEST
        ZONTES_368G_TEST_B -> TBoxModelProfile.ZONTES_368G_TEST_B
        KOVE_800X -> TBoxModelProfile.KOVE_800X
        MORINI_XCAPE_1200 -> TBoxModelProfile.MORINI_XCAPE_1200
        MORINI_XCAPE_1200_B -> TBoxModelProfile.MORINI_XCAPE_1200_B
        MORINI_XCAPE_1200_C -> TBoxModelProfile.MORINI_XCAPE_1200_C
        MORINI_XCAPE_1200_D -> TBoxModelProfile.MORINI_XCAPE_1200_D
        MOTO_HUB_SIMULATOR -> TBoxModelProfile.MOTO_HUB_SIMULATOR
    }

    companion object {
        fun byKey(key: String?): ProfileOverride =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}
