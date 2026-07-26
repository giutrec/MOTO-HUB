package io.motohub.android.feature.ridedashboard

import android.content.Context
import io.motohub.android.androidauto.AndroidAutoCapabilityProfile
import io.motohub.android.androidauto.AndroidAutoDisplayMode

/** CORE has no Ride Dashboard implementation; embedded AA frames are consumed by PRO via IPC. */
fun createEmbeddedAndroidAutoSource(
    context: Context,
    capabilityProfile: AndroidAutoCapabilityProfile,
    displayMode: AndroidAutoDisplayMode
): EmbeddedAndroidAutoVideoSource? = null
