// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Helpers around the official CFMOTO app that can hold the EasyConn session. There is no
 * programmatic "close": since Android 14 (this app's minSdk), killBackgroundProcesses only
 * affects the caller's own packages, so the only real remedy is the user force-stopping the
 * official app from its App info screen ([openAppSettings]).
 */
internal object OfficialCfmotoClient {
    const val PACKAGE_NAME = "com.cfmoto.cfmotointernational"

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        true
    }.getOrDefault(false)

    fun openAppSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$PACKAGE_NAME")
            )
        )
        true
    }.getOrDefault(false)
}
