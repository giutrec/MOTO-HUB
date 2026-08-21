// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.pairing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import io.motohub.android.i18n.motoHubText

@Composable
fun TBoxQrPhotoProcessingDialog(
    completedAttempts: Int,
    totalAttempts: Int
) {
    val progress = if (totalAttempts > 0) {
        (completedAttempts.toFloat() / totalAttempts.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Dialog(onDismissRequest = {}) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                CircularProgressIndicator()
                Text(
                    text = motoHubText("Analyzing QR photo…"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 18.dp)
                )
                Text(
                    text = motoHubText("Removing display patterns and trying QR recognition"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp)
                )
                Text(
                    text = motoHubText("Attempt %1\$d of %2\$d", completedAttempts, totalAttempts),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
