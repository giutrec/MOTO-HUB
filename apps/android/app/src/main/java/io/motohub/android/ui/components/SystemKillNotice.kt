package io.motohub.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.i18n.motoHubText
import io.motohub.android.session.BatteryOptimisationGate
import io.motohub.android.session.ProcessExitReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tells a rider whose previous session was ended by the phone that it was the phone, and what to
 * change so it is less likely to happen again.
 *
 * Shown only after it has actually happened, and only once per occurrence. A warning shown to
 * everyone before every long session is one riders learn to dismiss without reading, and most of
 * them are never killed; this one appears to the person who just watched their TFT go dark, in
 * the minute they have a reason to care.
 *
 * Deliberately not styled as an error. Nothing is broken and there is nothing to retry - the last
 * session ended, this explains why, and the rider decides what to do about it.
 */
@Composable
fun SystemKillNotice(modifier: Modifier = Modifier) {
    val kill = ProcessExitReport.unacknowledgedSystemKill ?: return
    val context = LocalContext.current
    var dismissed by remember(kill.at) { mutableStateOf(false) }
    if (dismissed) return

    val appName = remember {
        runCatching {
            context.applicationInfo.loadLabel(context.packageManager).toString()
        }.getOrDefault("MOTO-HUB")
    }
    // Read once per composition of this notice, not per recomposition: the rider may change the
    // setting and come back, and re-reading on every frame would make the text flicker between
    // the two pieces of advice while the settings screen animates away.
    val advice = remember(kill.at) { BatteryOptimisationGate.advice(context, appName) }
    val actionLabel = remember(kill.at) { BatteryOptimisationGate.actionLabel(context) }
    val time = remember(kill.at) { TIME_FORMAT.format(Date(kill.at)) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                motoHubText("STOPPED BY YOUR PHONE"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                motoHubText(
                    "Your last session ended at %1\$s because the phone closed %2\$s, not because " +
                        "of a fault in the app.",
                    time,
                    appName
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                motoHubText(advice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { BatteryOptimisationGate.openSettings(context) }) {
                    Text(motoHubText(actionLabel))
                }
                TextButton(
                    onClick = {
                        ProcessExitReport.acknowledgeSystemKill(context)
                        dismissed = true
                    }
                ) {
                    Text(motoHubText("Dismiss"))
                }
            }
        }
    }
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
