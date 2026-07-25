package io.motohub.android.feature.garage

import io.motohub.android.i18n.motoHubText

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.motohub.android.androidauto.AndroidAutoDisplayMode
import io.motohub.android.androidauto.TBoxScreenMargins
import io.motohub.android.feature.ridedashboard.widget.DashboardLayoutStore
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubActionRow
import io.motohub.android.ui.components.MotoHubBackground
import io.motohub.android.ui.components.MotoHubCardGroup
import io.motohub.android.ui.components.MotoHubHeader

private enum class DefaultDashboardDetail { ANDROID_AUTO_DISPLAY, TFT_MARGINS }

/**
 * A synthetic, never-persisted profile used only to key the per-motorcycle
 * [io.motohub.android.androidauto.AndroidAutoDisplayModeStore]/[io.motohub.android.androidauto.TBoxScreenMarginsStore]
 * with the same well-known key [DashboardLayoutStore.PHONE_ONLY_KEY] already used for widget
 * layout - it is never written to [io.motohub.android.data.MotorcycleProfileStore].
 */
val DEFAULT_DASHBOARD_SETTINGS_PROFILE = MotorcycleProfile(
    ssid = DashboardLayoutStore.PHONE_ONLY_KEY,
    password = "",
    id = DashboardLayoutStore.PHONE_ONLY_KEY
)

/**
 * Standalone panel for every dashboard/Android-Auto setting that would otherwise only be
 * reachable by first pairing a real motorcycle - widget layout, AA fit mode, TFT margins.
 * Applies to phone-only Android Auto and Ride Dashboard (no T-Box at all). Deliberately
 * excludes T-Box Profile Override and the Capability Inspector, both meaningless without a
 * real, connected T-Box.
 */
@Composable
fun DefaultDashboardSettingsScreen(
    displayMode: AndroidAutoDisplayMode,
    screenMargins: TBoxScreenMargins,
    onBack: () -> Unit,
    onCustomizeDashboard: () -> Unit,
    onDisplayModeChanged: (AndroidAutoDisplayMode) -> Unit,
    onScreenMarginsChanged: (TBoxScreenMargins) -> Unit
) {
    var detail by rememberSaveable { mutableStateOf<DefaultDashboardDetail?>(null) }

    BackHandler(enabled = detail != null) { detail = null }
    BackHandler(enabled = detail == null, onBack = onBack)

    MotoHubBackground(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = detail,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 3 } + fadeOut())
                }
            },
            label = "default-dashboard-settings"
        ) { current ->
            when (current) {
                null -> DefaultDashboardMainList(
                    displayMode = displayMode,
                    onBack = onBack,
                    onOpenDetail = { detail = it },
                    onCustomizeDashboard = onCustomizeDashboard
                )
                DefaultDashboardDetail.ANDROID_AUTO_DISPLAY -> AndroidAutoDisplayDetail(
                    displayMode = displayMode,
                    onDisplayModeChanged = onDisplayModeChanged,
                    onBack = { detail = null }
                )
                DefaultDashboardDetail.TFT_MARGINS -> TftMarginsDetail(
                    profile = DEFAULT_DASHBOARD_SETTINGS_PROFILE,
                    screenMargins = screenMargins,
                    onScreenMarginsChanged = onScreenMarginsChanged,
                    onBack = { detail = null }
                )
            }
        }
    }
}

@Composable
private fun DefaultDashboardMainList(
    displayMode: AndroidAutoDisplayMode,
    onBack: () -> Unit,
    onOpenDetail: (DefaultDashboardDetail) -> Unit,
    onCustomizeDashboard: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        MotoHubHeader(
            modifier = Modifier.fillMaxWidth(),
            trailing = { TextButton(onClick = onBack) { Text(motoHubText("Back")) } }
        )
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            MonoLabel(motoHubText("NO MOTORCYCLE NEEDED"))
            Text(motoHubText("Default settings"), style = MaterialTheme.typography.headlineMedium)
            Text(
                if (io.motohub.android.BuildConfig.IS_PRO) {
                    motoHubText("Used by Android Auto and Ride Dashboard whenever there is no T-Box connected.")
                } else {
                    motoHubText("Used by Android Auto whenever there is no T-Box connected.")
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        MotoHubCardGroup {
            // Ride Dashboard is Advanced-only - Core ships Mirror + Android Auto only.
            if (io.motohub.android.BuildConfig.IS_PRO) {
                MotoHubActionRow(
                    title = motoHubText("Customize Dashboard"),
                    description = motoHubText("Choose widgets for each side panel"),
                    onClick = onCustomizeDashboard
                )
            }
            MotoHubActionRow(
                title = motoHubText("Android Auto Display"),
                description = motoHubText("How the complete Android Auto image fits the screen"),
                value = displayMode.shortLabel,
                onClick = { onOpenDetail(DefaultDashboardDetail.ANDROID_AUTO_DISPLAY) }
            )
            MotoHubActionRow(
                title = motoHubText("TFT Safe Margins"),
                description = motoHubText("Exclude pixels occupied by the motorcycle UI"),
                onClick = { onOpenDetail(DefaultDashboardDetail.TFT_MARGINS) }
            )
        }
    }
}
