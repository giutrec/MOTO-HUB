package io.motohub.android.feature.androidauto

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.motohub.android.i18n.motoHubText

/**
 * A pre-flight warning, not a claim that the official app is currently active.
 * Android does not expose a reliable cross-version API for determining whether another
 * application's background service owns the EasyConn connection, so we make the rider aware
 * of the known conflict before opening Google Android Auto.
 */
@Composable
fun OfficialCfmotoWarningDialog(
    doNotShowAgain: Boolean,
    onDoNotShowAgainChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onOpenOfficialAppSettings: () -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("CFMOTO MotoPlay detected")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    motoHubText(
                        "MOTO-HUB starts Google Android Auto, not MotoPlay."
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    motoHubText(
                        "The official CFMOTO MotoPlay/EasyConn app can still take over the T-Box " +
                            "connection while it is in the background. For reliable projection, force-stop " +
                            "it in Android settings before continuing."
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenOfficialAppSettings) {
                    Text(motoHubText("Open MotoPlay settings"))
                }
                OutlinedButton(onClick = onDismiss) {
                    Text(motoHubText("Cancel"))
                }
            }
        }
    )
}
