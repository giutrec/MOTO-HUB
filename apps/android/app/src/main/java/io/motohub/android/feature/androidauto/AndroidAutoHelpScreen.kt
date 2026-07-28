package io.motohub.android.feature.androidauto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.androidauto.AndroidAutoSelfModeHelp
import io.motohub.android.i18n.motoHubText
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubActionRow
import io.motohub.android.ui.components.MotoHubDetailScreen

/**
 * How to get Android Auto to project when it will not start on its own.
 *
 * Android Auto 17.4 removed the entry points an app could use to ask for projection, so on those
 * releases the rider has to start it from Android Auto's own developer menu. That is a sequence
 * of taps in another app, buried behind a hidden menu — exactly the kind of thing that belongs in
 * front of the rider rather than in a support thread.
 */
@Composable
fun AndroidAutoHelpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    MotoHubDetailScreen(
        title = motoHubText("Android Auto does not start"),
        backLabel = "‹ ${motoHubText("Back")}",
        onBack = onBack
    ) {
        Text(
            motoHubText(
                "Android Auto 17.4 removed the way an app can ask it to project. MOTO-HUB still " +
                    "tries, and on older versions it works — but when it does not, Android Auto " +
                    "can be started from its own developer menu instead. You do not need to " +
                    "install anything."
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()
        MonoLabel(motoHubText("ONE-TIME SETUP"))
        HelpStep(
            number = "1",
            text = motoHubText(
                "Open the Android Auto app, scroll to the bottom and tap \"Version\" ten times. " +
                    "Developer settings appear."
            )
        )
        HelpStep(
            number = "2",
            text = motoHubText(
                "In Developer settings, turn on \"Add new cars to Android Auto\" (older versions " +
                    "call it \"Unknown sources\")."
            )
        )

        HorizontalDivider()
        MonoLabel(motoHubText("EVERY TIME ANDROID AUTO WILL NOT START"))
        HelpStep(
            number = "3",
            text = motoHubText(
                "In Android Auto's Developer settings, open the ⋮ menu at the top right and " +
                    "choose \"Start head unit server\". It is in that menu, not in the list of " +
                    "settings below it."
            )
        )
        HelpStep(
            number = "4",
            text = motoHubText(
                "A notification confirms the server is running. Leave it running: it stays up " +
                    "until you stop it or restart the phone."
            )
        )
        HelpStep(
            number = "5",
            text = motoHubText(
                "Go back to MOTO-HUB and start Android Auto. It connects on its own within a " +
                    "couple of seconds — there is nothing else to press."
            )
        )

        Spacer(Modifier.height(4.dp))
        MotoHubActionRow(
            title = motoHubText("Open Android Auto settings"),
            description = motoHubText("Jumps straight to the app where the menu above lives"),
            onClick = { AndroidAutoSelfModeHelp.openAndroidAutoSettings(context) }
        )

        HorizontalDivider()
        MonoLabel(motoHubText("WHY"))
        Text(
            motoHubText(
                "Normally MOTO-HUB waits and asks Android Auto to connect to it. Version 17.4 " +
                    "closed that door for every app of this kind, not just this one. The head " +
                    "unit server reverses the direction — Android Auto waits and MOTO-HUB " +
                    "connects to it — which is a door Google left open for its own testing tools."
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HelpStep(number: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            number,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(20.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}
