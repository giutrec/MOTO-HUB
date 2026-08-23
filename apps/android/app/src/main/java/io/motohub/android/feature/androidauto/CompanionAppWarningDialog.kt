// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.androidauto

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.motohub.android.i18n.motoHubText
import io.motohub.android.ui.components.MotoHubDialogBody

/**
 * A pre-flight warning, not a claim that [companionAppName] is currently active.
 * Android does not expose a reliable cross-version API for determining whether another
 * application's background service owns the EasyConn connection, so we make the rider aware
 * of the known conflict before opening Google Android Auto.
 *
 * [companionAppName] is whichever companion app CompanionAppRegistry found on this phone, under
 * the label its own launcher icon carries. It used to be CFMOTO's, hard-coded, which read as
 * nonsense to every rider of another brand.
 */
@Composable
fun CompanionAppWarningDialog(
    companionAppName: String,
    doNotShowAgain: Boolean,
    onDoNotShowAgainChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onOpenCompanionAppSettings: () -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("%1\$s detected", companionAppName)) },
        text = {
            MotoHubDialogBody {
                Text(
                    motoHubText(
                        "MOTO-HUB starts Google Android Auto, not %1\$s.",
                        companionAppName
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    motoHubText(
                        "%1\$s can still take over the dashboard connection while it is in the " +
                            "background. For reliable projection, force-stop it in Android settings " +
                            "before continuing.",
                        companionAppName
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                // Three buttons never fit on one row of a narrow phone, and Material 3 wraps them
                // into a ragged stack, so the settings shortcut sits with the text that asks for it.
                OutlinedButton(
                    onClick = onOpenCompanionAppSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(motoHubText("Open %1\$s settings", companionAppName))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDoNotShowAgainChanged(!doNotShowAgain) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = doNotShowAgain,
                        onCheckedChange = onDoNotShowAgainChanged
                    )
                    Text(
                        motoHubText("Do not show this warning again"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onContinue) {
                Text(motoHubText("Continue with Android Auto"))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(motoHubText("Cancel"))
            }
        }
    )
}
