// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics.report

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.i18n.motoHubText
import io.motohub.android.session.InstallationId
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.ui.components.MotoHubActionRow
import io.motohub.android.ui.components.MotoHubCardGroup
import io.motohub.android.ui.components.ToggleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The top of Diagnostics: the Support ID a rider reads out when asking for help, the switch for
 * automatic reports, and the button that sends one right now.
 */
@Composable
fun SupportIdSection() {
    val context = LocalContext.current
    var supportId by remember { mutableStateOf<String?>(null) }
    var autoUpload by remember { mutableStateOf(DiagnosticReportSettings.autoUploadEnabled(context)) }
    var reviewingNotice by remember { mutableStateOf(false) }
    var readingPrivacyNotice by remember { mutableStateOf(false) }
    val status by DiagnosticReportScheduler.status.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        DiagnosticReportScheduler.refreshStatus(context)
        supportId = withContext(Dispatchers.IO) {
            val activeId = runCatching { MotorcycleProfileStore(context).load()?.id }.getOrNull()
            InstallationId.supportId(context, activeId)
        }
    }

    val shortId = supportId?.let(InstallationId::shortForm) ?: "…"
    MotoHubCardGroup {
        MotoHubActionRow(
            title = motoHubText("Support ID"),
            description = motoHubText("Tap to copy. Quote it when asking for help; it identifies this phone and motorcycle."),
            value = shortId,
            onClick = {
                val id = supportId ?: return@MotoHubActionRow
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText(motoHubText("MOTO-HUB Support ID"), id)
                )
                Toast.makeText(context, motoHubText("Support ID copied"), Toast.LENGTH_SHORT).show()
            }
        )
        MotoHubActionRow(
            title = motoHubText("What gets sent"),
            description = motoHubText("Read what a report contains, then choose whether to send them"),
            onClick = { reviewingNotice = true }
        )
        // The long form behind the summary above, and the only place a rider can find out how to
        // have their reports deleted - which is why it sits here rather than behind the notice.
        MotoHubActionRow(
            title = motoHubText("How your data is handled"),
            description = motoHubText("Who holds your reports, how long they are kept, and how to have them deleted"),
            onClick = { readingPrivacyNotice = true }
        )
        MotoHubActionRow(
            title = motoHubText("Send diagnostics now"),
            description = if (DiagnosticReportUploader.configured) status.describe()
            else motoHubText("This build has no collector configured"),
            onClick = {
                if (!DiagnosticReportUploader.configured || status.inProgress) return@MotoHubActionRow
                ProjectionEventLog.record("SUPPORT", "Diagnostics report requested by the rider.")
                DiagnosticReportScheduler.sendNow(context)
            }
        )
    }
    // Answering here is a real answer: a rider who reads what would leave the phone and decides
    // either way must be able to say so at that moment, not be sent looking for a separate
    // switch. Closing it without answering leaves the setting exactly as it was.
    if (reviewingNotice) {
        DiagnosticReportNoticeDialog(
            onAccept = {
                reviewingNotice = false
                autoUpload = true
                DiagnosticReportSettings.setAutoUploadEnabled(context, true)
                ProjectionEventLog.record("SETTINGS", "Automatic diagnostics upload confirmed from the notice.")
            },
            onDecline = {
                reviewingNotice = false
                autoUpload = false
                DiagnosticReportSettings.setAutoUploadEnabled(context, false)
                DiagnosticReportSettings.setPending(context, false)
                ProjectionEventLog.record("SETTINGS", "Automatic diagnostics upload declined from the notice.")
            },
            onDismiss = { reviewingNotice = false }
        )
    }
    if (readingPrivacyNotice) {
        PrivacyNoticeDialog(onDismiss = { readingPrivacyNotice = false })
    }
    Spacer(Modifier.height(8.dp))
    ToggleRow(
        title = motoHubText("Send diagnostics automatically"),
        description = motoHubText(
            "Sends the report above to the developer at most once a day, after an update, or after a " +
                "crash - only over a connection with Internet access. Dashboard and phone models, app " +
                "versions and the application log; never passwords, positions or hardware addresses."
        ),
        checked = autoUpload,
        onCheckedChange = {
            autoUpload = it
            DiagnosticReportSettings.setAutoUploadEnabled(context, it)
            if (!it) DiagnosticReportSettings.setPending(context, false)
            ProjectionEventLog.record("SETTINGS", "Automatic diagnostics upload changed to enabled=$it.")
        }
    )
}
