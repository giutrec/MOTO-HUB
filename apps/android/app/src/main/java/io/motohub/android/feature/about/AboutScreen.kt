package io.motohub.android.feature.about

import io.motohub.android.i18n.motoHubText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.BuildConfig
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubBackground
import io.motohub.android.ui.components.MotoHubHeader

const val MOTO_HUB_GITHUB_URL = "https://github.com/vincenzobpt/MOTO-HUB"
const val MOTO_HUB_DISCORD_URL = "https://discord.gg/Y8bnx9Zxgw"

@Composable
fun AboutScreen(
    onOpenGithub: () -> Unit,
    onOpenDiscord: () -> Unit,
    onCheckUpdates: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    MotoHubBackground(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            MotoHubHeader(
                modifier = Modifier.fillMaxWidth(),
                trailing = {
                    TextButton(onClick = onBack) {
                        Text(motoHubText("Close"))
                    }
                }
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MonoLabel(motoHubText("ABOUT THE PROJECT"))
                Text(
                    text = "Your phone.\nYour motorcycle display.",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "MOTO-HUB connects an Android 14+ phone to a motorcycle dashboard that " +
                        "pairs over EasyConn — the Carbit software several manufacturers ship, " +
                        "CFMOTO among them. It supports screen and app mirroring, Android Auto " +
                        "projection, saved motorcycle profiles, and on-device diagnostics.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    MonoLabel(motoHubText("COMMUNITY & SOURCE"))
                    Text(
                        text = "Follow development, join our community, report issues, and download releases.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onOpenGithub,
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                        ) {
                            Text(motoHubText("GitHub"))
                        }
                        Button(
                            onClick = onOpenDiscord,
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                        ) {
                            Text(motoHubText("Discord"))
                        }
                    }
                }
            }

            VersionCard()
            Button(
                onClick = onCheckUpdates,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(motoHubText("Check for updates"))
            }
            DisclaimerCard()

            Text(
                text = "MOTO-HUB is an independent project. It is not affiliated with, endorsed by, " +
                    "or sponsored by Carbit, CFMOTO, any other manufacturer whose dashboard uses " +
                    "EasyConn, Google, or Android Auto. All product names and marks belong to " +
                    "their respective owners.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun VersionCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                MonoLabel(motoHubText("VERSION"))
                Text(
                    text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                MonoLabel(motoHubText("PLATFORM"))
                Text(
                    text = "Android 14+",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun DisclaimerCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MonoLabel(motoHubText("EXPERIMENTAL SOFTWARE"))
            Text(
                text = "MOTO-HUB is an experimental proof-of-concept, not a production-grade product.",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Development and testing happen on a CFMOTO 700MT-ADV dashboard with " +
                    "OnePlus 13 / Galaxy Z Fold4 phones. Other motorcycles, brands, T-Box " +
                    "firmware versions and phones are untested here: expect different behaviour, " +
                    "retries, or no connection at all. If the dashboard shows a pairing QR code, " +
                    "it is worth trying.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Do not rely on it as your only source of critical navigation. Configure " +
                    "navigation while parked and use the software at your own risk.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
