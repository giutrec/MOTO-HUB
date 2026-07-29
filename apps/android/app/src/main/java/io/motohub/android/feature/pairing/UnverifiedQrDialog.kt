package io.motohub.android.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.motohub.android.i18n.motoHubText

/**
 * Asks before saving credentials that decoded cleanly but did not come from a Carbit provisioning
 * address. Two very different codes land here: the pairing QR of a dash whose manufacturer serves
 * it from their own domain, and any unrelated QR that happens to carry a network name. Only the
 * rider can tell those apart, so the SSID is shown and the decision is theirs.
 */
@Composable
fun UnverifiedQrDialog(
    payload: TBoxQrPayload,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("Unfamiliar pairing code")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    motoHubText(
                        "This code carries Wi-Fi details for %1\$s, but it was not issued by a " +
                            "Carbit address like the dashboards MOTO-HUB knows.",
                        payload.ssid
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    motoHubText(
                        "Several manufacturers ship the same dashboard software under their own " +
                            "branding, so this may well be your motorcycle. Continue only if you " +
                            "scanned it from your own dashboard."
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(motoHubText("Use these details"))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(motoHubText("Cancel"))
            }
        }
    )
}
