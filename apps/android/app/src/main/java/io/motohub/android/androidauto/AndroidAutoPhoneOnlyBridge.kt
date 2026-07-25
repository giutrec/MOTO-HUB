package io.motohub.android.androidauto

/**
 * Flavor seam: lets Android Auto run without any T-Box/TFT connection, visible only through the
 * phone's own [io.motohub.android.feature.androidauto.AndroidAutoPreviewScreen]. Core builds and
 * runs the AA receiver itself (see `PhoneOnlyAndroidAutoBridge`); Advanced has no local Android
 * Auto code at all, so it deep-links into Core's own app instead (see
 * `ProAndroidAutoPhoneOnlyBridge`), mirroring how "Preview & touch" already works for a real
 * T-Box-backed session (see `AndroidAutoPreviewLaunchRequest`).
 */
interface AndroidAutoPhoneOnlyBridge {
    fun start(onFailure: (String) -> Unit)
    fun stop()
}
