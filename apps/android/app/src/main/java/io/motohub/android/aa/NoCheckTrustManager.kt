// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Ported from headunit-revived (AGPLv3): ssl/NoCheckTrustManager.kt
package io.motohub.android.aa

import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class NoCheckTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate>? = null
}
