package io.motohub.android.feature.home

import io.motohub.android.i18n.motoHubText

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.SessionPhase
import io.motohub.android.feature.garage.MotorcyclePhoto
import io.motohub.android.feature.ridedashboard.RideDashboardMapSource
import io.motohub.android.feature.ridedashboard.nav.NavigationRuntime
import io.motohub.android.feature.ridedashboard.nav.SavedRide
import io.motohub.android.feature.ridedashboard.nav.SavedRidesStore
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.feature.trips.TripRecordingService
import io.motohub.android.feature.trips.TripRecordingSource
import io.motohub.android.feature.trips.TripDetails
import io.motohub.android.feature.trips.TripStore
import io.motohub.android.feature.trips.formatTripDistance
import io.motohub.android.feature.trips.formatTripDuration
import io.motohub.android.feature.ridedashboard.RideDashboardTrackOverlayRuntime
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.ui.components.ConnectionRail
import io.motohub.android.ui.components.ConnectionState
import io.motohub.android.ui.components.HubAppBar
import io.motohub.android.ui.components.HubBottomNavigation
import io.motohub.android.ui.components.HeroPrimaryAction
import io.motohub.android.ui.components.HeroTile
import io.motohub.android.ui.components.HubTab
import io.motohub.android.ui.components.LivePill
import io.motohub.android.ui.components.ModeIcon
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubBackground
import io.motohub.android.ui.components.MotoHubRadioRow
import io.motohub.android.ui.theme.MotoHubAndroidAuto
import io.motohub.android.ui.theme.MotoHubDashboard
import io.motohub.android.ui.theme.MotoHubImport
import io.motohub.android.ui.theme.MotoHubManual
import io.motohub.android.ui.theme.MotoHubMirror
import io.motohub.android.tbox.TBoxConflictDiagnostics
import io.motohub.android.tbox.WifiGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HubHomeScreen(
    state: HubUiState,
    selectedTab: HubTab,
    onTabSelected: (HubTab) -> Unit,
    onScanQr: () -> Unit,
    onImportQrPhoto: () -> Unit,
    onManualPairing: () -> Unit,
    onConnectAndDiscover: () -> Unit,
    officialCfmotoAppInstalled: Boolean,
    onCloseOfficialCfmotoAndRetry: () -> Unit,
    onOpenOfficialCfmotoSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onCancelConnection: () -> Unit,
    onDisconnect: () -> Unit,
    onStartProjection: () -> Unit,
    rideDashboardActive: Boolean,
    rideDashboardStreaming: Boolean,
    onStartRideDashboard: () -> Unit,
    onCustomizeRideDashboard: () -> Unit,
    rideDashboardMapSource: RideDashboardMapSource,
    onRideDashboardMapSourceChanged: (RideDashboardMapSource) -> Unit,
    onStopRideDashboard: () -> Unit,
    showRecordedTrackOnDashboard: Boolean,
    onShowRecordedTrackOnDashboardChanged: (Boolean) -> Unit,
    androidAutoActive: Boolean,
    androidAutoStreaming: Boolean,
    rideDashboardAndroidAutoActive: Boolean,
    rideDashboardAndroidAutoStreaming: Boolean,
    onStartAndroidAuto: () -> Unit,
    onStopAndroidAuto: () -> Unit,
    onOpenAndroidAutoPreview: () -> Unit,
    onOpenAndroidAutoFullscreenControls: () -> Unit,
    onOpenRideDashboardPreview: () -> Unit,
    onOpenControls: () -> Unit,
    dimDisplayEnabled: Boolean,
    onDimDisplayChanged: (Boolean) -> Unit,
    onStopProjection: () -> Unit,
    navContent: @Composable () -> Unit,
    tripsContent: @Composable () -> Unit,
    garageContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
    // ── External display (USB AOA) ──
    aoaAccessoryConnected: Boolean = false,
    externalDisplayActive: Boolean = false,
    externalDisplayStreaming: Boolean = false,
    onStartExternalDisplay: () -> Unit = {},
    onStopExternalDisplay: () -> Unit = {},
    // ── Phone-only mode (no T-Box) ──
    onStartPhoneOnlyAndroidAuto: () -> Unit = {},
    onStartPhoneOnlyRideDashboard: () -> Unit = {}
) {
    val session = state.session
    val destination = resolveHubDestination(session, androidAutoActive, rideDashboardActive, externalDisplayActive)
    val connectionState = when {
        session.phase == SessionPhase.CONNECTING_NETWORK ||
            session.phase == SessionPhase.DISCOVERING_TBOX -> ConnectionState.CONNECTING
        session.phase == SessionPhase.READY ||
            session.phase == SessionPhase.REQUESTING_PROJECTION ||
            session.phase == SessionPhase.CAPTURING -> ConnectionState.CONNECTED
        else -> ConnectionState.DISCONNECTED
    }

    MotoHubBackground(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding()
        ) {
            ConnectionRail(connectionState)
            HubAppBar(
                motorcycleName = session.motorcycle?.displayName?.takeIf(String::isNotBlank)
                    ?: session.motorcycle?.ssid,
                isConnected = connectionState == ConnectionState.CONNECTED,
                onMotorcycleTap = { onTabSelected(HubTab.GARAGE) }
            )
            CoreMissingBanner()

            Box(Modifier.weight(1f)) {
                Crossfade(targetState = selectedTab, label = "tab") { tab ->
                    when (tab) {
                        HubTab.RIDE -> RideTabContent(
                            state = state,
                            destination = destination,
                            onScanQr = onScanQr,
                            onImportQrPhoto = onImportQrPhoto,
                            onManualPairing = onManualPairing,
                            onConnectAndDiscover = onConnectAndDiscover,
                            officialCfmotoAppInstalled = officialCfmotoAppInstalled,
                            onCloseOfficialCfmotoAndRetry = onCloseOfficialCfmotoAndRetry,
                            onOpenOfficialCfmotoSettings = onOpenOfficialCfmotoSettings,
                            onOpenWifiSettings = onOpenWifiSettings,
                            onCancelConnection = onCancelConnection,
                            onDisconnect = onDisconnect,
                            onStartProjection = onStartProjection,
                            rideDashboardActive = rideDashboardActive,
                            rideDashboardStreaming = rideDashboardStreaming,
                            onStartRideDashboard = onStartRideDashboard,
                            onCustomizeRideDashboard = onCustomizeRideDashboard,
                            rideDashboardMapSource = rideDashboardMapSource,
                            onRideDashboardMapSourceChanged = onRideDashboardMapSourceChanged,
                            onStopRideDashboard = onStopRideDashboard,
                            showRecordedTrackOnDashboard = showRecordedTrackOnDashboard,
                            onShowRecordedTrackOnDashboardChanged = onShowRecordedTrackOnDashboardChanged,
                            androidAutoActive = androidAutoActive,
                            androidAutoStreaming = androidAutoStreaming,
                            rideDashboardAndroidAutoActive = rideDashboardAndroidAutoActive,
                            rideDashboardAndroidAutoStreaming = rideDashboardAndroidAutoStreaming,
                            onStartAndroidAuto = onStartAndroidAuto,
                            onStopAndroidAuto = onStopAndroidAuto,
                            onOpenAndroidAutoPreview = onOpenAndroidAutoPreview,
                            onOpenAndroidAutoFullscreenControls = onOpenAndroidAutoFullscreenControls,
                            onOpenRideDashboardPreview = onOpenRideDashboardPreview,
                            onOpenControls = onOpenControls,
                            dimDisplayEnabled = dimDisplayEnabled,
                            onDimDisplayChanged = onDimDisplayChanged,
                            onStopProjection = onStopProjection,
                            aoaAccessoryConnected = aoaAccessoryConnected,
                            externalDisplayActive = externalDisplayActive,
                            externalDisplayStreaming = externalDisplayStreaming,
                            onStartExternalDisplay = onStartExternalDisplay,
                            onStopExternalDisplay = onStopExternalDisplay,
                            onStartPhoneOnlyAndroidAuto = onStartPhoneOnlyAndroidAuto,
                            onStartPhoneOnlyRideDashboard = onStartPhoneOnlyRideDashboard
                        )
                        HubTab.NAV -> navContent()
                        HubTab.TRIPS -> tripsContent()
                        HubTab.GARAGE -> garageContent()
                        HubTab.SETTINGS -> settingsContent()
                    }
                }
            }

            HubBottomNavigation(
                selected = selectedTab,
                onSelect = onTabSelected,
                modifier = Modifier.navigationBarsPadding()
            )
        }
    }
}
@Composable
private fun RideTabContent(
    state: HubUiState,
    destination: HubDestination,
    onScanQr: () -> Unit,
    onImportQrPhoto: () -> Unit,
    onManualPairing: () -> Unit,
    onConnectAndDiscover: () -> Unit,
    officialCfmotoAppInstalled: Boolean,
    onCloseOfficialCfmotoAndRetry: () -> Unit,
    onOpenOfficialCfmotoSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onCancelConnection: () -> Unit,
    onDisconnect: () -> Unit,
    onStartProjection: () -> Unit,
    rideDashboardActive: Boolean,
    rideDashboardStreaming: Boolean,
    onStartRideDashboard: () -> Unit,
    onCustomizeRideDashboard: () -> Unit,
    rideDashboardMapSource: RideDashboardMapSource,
    onRideDashboardMapSourceChanged: (RideDashboardMapSource) -> Unit,
    onStopRideDashboard: () -> Unit,
    showRecordedTrackOnDashboard: Boolean,
    onShowRecordedTrackOnDashboardChanged: (Boolean) -> Unit,
    androidAutoActive: Boolean,
    androidAutoStreaming: Boolean,
    rideDashboardAndroidAutoActive: Boolean,
    rideDashboardAndroidAutoStreaming: Boolean,
    onStartAndroidAuto: () -> Unit,
    onStopAndroidAuto: () -> Unit,
    onOpenAndroidAutoPreview: () -> Unit,
    onOpenAndroidAutoFullscreenControls: () -> Unit,
    onOpenRideDashboardPreview: () -> Unit,
    onOpenControls: () -> Unit,
    dimDisplayEnabled: Boolean,
    onDimDisplayChanged: (Boolean) -> Unit,
    onStopProjection: () -> Unit,
    // ── External display (USB AOA) ──
    aoaAccessoryConnected: Boolean = false,
    externalDisplayActive: Boolean = false,
    externalDisplayStreaming: Boolean = false,
    onStartExternalDisplay: () -> Unit = {},
    onStopExternalDisplay: () -> Unit = {},
    // ── Phone-only mode (no T-Box) ──
    onStartPhoneOnlyAndroidAuto: () -> Unit = {},
    onStartPhoneOnlyRideDashboard: () -> Unit = {}
) {
    val session = state.session
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // The motorcycle identity (photo, name, SSID) stays on screen across every
        // destination once a profile is chosen, instead of each destination re-deriving
        // its own header - this is what keeps the screen feeling like one continuous
        // place rather than a new layout every time the connection state changes.
        session.motorcycle?.let { motorcycle ->
            MotorcycleHero(
                motorcycle = motorcycle,
                compact = destination == HubDestination.CONNECTING ||
                    destination == HubDestination.ACTIVE_SESSION
            )
        }

        when (destination) {
            HubDestination.PAIRING -> PairingContent(
                errorMessage = session.message.takeIf { session.phase == SessionPhase.ERROR },
                onScanQr = onScanQr,
                onImportQrPhoto = onImportQrPhoto,
                onManualPairing = onManualPairing,
                onStartPhoneOnlyAndroidAuto = onStartPhoneOnlyAndroidAuto,
                onStartPhoneOnlyRideDashboard = onStartPhoneOnlyRideDashboard
            )
            HubDestination.CONNECTING -> ConnectingContent(
                phase = session.phase,
                ssid = checkNotNull(session.motorcycle).ssid,
                onCancel = onCancelConnection
            )
            HubDestination.ACTIVE_SESSION -> ActiveSessionContent(
                androidAutoActive = androidAutoActive,
                androidAutoStreaming = androidAutoStreaming,
                rideDashboardAndroidAutoActive = rideDashboardAndroidAutoActive,
                rideDashboardAndroidAutoStreaming = rideDashboardAndroidAutoStreaming,
                rideDashboardActive = rideDashboardActive,
                rideDashboardStreaming = rideDashboardStreaming,
                mirrorStreaming = session.phase == SessionPhase.CAPTURING,
                dimDisplayEnabled = dimDisplayEnabled,
                onDimDisplayChanged = onDimDisplayChanged,
                showRecordedTrackOnDashboard = showRecordedTrackOnDashboard,
                onShowRecordedTrackOnDashboardChanged = onShowRecordedTrackOnDashboardChanged,
                rideDashboardMapSource = rideDashboardMapSource,
                onOpenAndroidAutoPreview = onOpenAndroidAutoPreview,
                onOpenAndroidAutoFullscreenControls = onOpenAndroidAutoFullscreenControls,
                onOpenRideDashboardPreview = onOpenRideDashboardPreview,
                onOpenControls = onOpenControls,
                onCustomizeRideDashboard = onCustomizeRideDashboard,
                externalDisplayActive = externalDisplayActive,
                externalDisplayStreaming = externalDisplayStreaming,
                onStopExternalDisplay = onStopExternalDisplay,
                onStop = when {
                    androidAutoActive -> onStopAndroidAuto
                    rideDashboardActive -> onStopRideDashboard
                    else -> onStopProjection
                }
            )
            HubDestination.MODE_SELECTION -> ModeSelectionContent(
                onStartProjection = onStartProjection,
                onStartRideDashboard = onStartRideDashboard,
                rideDashboardMapSource = rideDashboardMapSource,
                onRideDashboardMapSourceChanged = onRideDashboardMapSourceChanged,
                onStartAndroidAuto = onStartAndroidAuto,
                onDisconnect = onDisconnect,
                aoaAccessoryConnected = aoaAccessoryConnected,
                onStartExternalDisplay = onStartExternalDisplay
            )
            HubDestination.CONNECTION -> ConnectionContent(
                errorMessage = session.message.takeIf { session.phase == SessionPhase.ERROR },
                onConnect = onConnectAndDiscover,
                officialCfmotoAppInstalled = officialCfmotoAppInstalled,
                onCloseOfficialCfmotoAndRetry = onCloseOfficialCfmotoAndRetry,
                onOpenOfficialCfmotoSettings = onOpenOfficialCfmotoSettings,
                onOpenWifiSettings = onOpenWifiSettings,
                onScanQr = onScanQr,
                onImportQrPhoto = onImportQrPhoto,
                onManualPairing = onManualPairing,
                onStartPhoneOnlyAndroidAuto = onStartPhoneOnlyAndroidAuto,
                onStartPhoneOnlyRideDashboard = onStartPhoneOnlyRideDashboard
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * Persistent identity block: photo, display name, SSID, and a large monogram watermark
 * peeking out behind the name. [compact] drops the watermark and shrinks everything into
 * a single row once the screen's attention should be on connection/streaming status instead.
 */
@Composable
private fun MotorcycleHero(motorcycle: MotorcycleProfile, compact: Boolean) {
    val displayName = motorcycle.displayName?.takeIf { it.isNotBlank() } ?: "My motorcycle"
    if (compact) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MotorcyclePhoto(
                path = motorcycle.photoPath,
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp)
            )
            Text(
                displayName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        val monogram = displayName.trim().take(1).uppercase().ifBlank { "M" }
        val hasPhoto = !motorcycle.photoPath.isNullOrBlank()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(228.dp)
                .clip(RoundedCornerShape(22.dp))
        ) {
            if (hasPhoto) {
                MotorcyclePhoto(
                    path = motorcycle.photoPath,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(22.dp)
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                )
            }
            // Ghost monogram stamped over the art (not behind it - a user photo has no
            // transparency to peek through, unlike a studio product render), corner-anchored
            // so it reads as a stylistic accent instead of competing with the photo.
            Text(
                text = monogram,
                fontSize = 150.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (hasPhoto) 0.10f else 0.16f),
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 26.dp, y = (-34).dp)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.9f)
                            )
                        )
                    )
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Column {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.displaySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        motorcycle.ssid,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingContent(
    errorMessage: String? = null,
    onScanQr: () -> Unit,
    onImportQrPhoto: () -> Unit,
    onManualPairing: () -> Unit,
    onStartPhoneOnlyAndroidAuto: () -> Unit = {},
    onStartPhoneOnlyRideDashboard: () -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Without a saved motorcycle yet, resolveHubDestination always stays on this screen
        // (session.motorcycle == null takes priority over SessionPhase.ERROR) - so this is the
        // ONLY place a failed "Import QR" (unreadable photo, or a photo with no valid EasyConn
        // QR code in it) can ever be shown; it was previously silently dropped here.
        errorMessage?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        motoHubText("IMPORT FAILED"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoLabel(motoHubText("FIRST-TIME SETUP"))
            Text(motoHubText("Connect your motorcycle."), style = MaterialTheme.typography.displaySmall)
            Text(
                motoHubText("Scan the T-Box QR code to save the network credentials automatically."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HeroPrimaryAction(
            title = "Scan motorcycle QR code",
            subtitle = "Point your camera at the T-Box sticker",
            icon = "QrScan",
            color = MaterialTheme.colorScheme.primary,
            onClick = onScanQr
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HeroTile(
                title = "Import QR",
                subtitle = "Pick a saved photo",
                icon = "Import",
                color = MotoHubImport,
                modifier = Modifier.weight(1f),
                onClick = onImportQrPhoto
            )
            HeroTile(
                title = "Manual setup",
                subtitle = "Type the network in",
                icon = "Manual",
                color = MotoHubManual,
                modifier = Modifier.weight(1f),
                onClick = onManualPairing
            )
        }
        // No motorcycle needs to be paired at all to use either of these - both run entirely on
        // the phone. Ride Dashboard is Advanced-only (see ModeGrid); Android Auto works in both.
        MonoLabel(
            motoHubText("OR RIGHT NOW, NO MOTORCYCLE NEEDED"),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            textAlign = TextAlign.Center
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HeroTile(
                title = "Android Auto",
                subtitle = "Straight to your phone",
                icon = "Auto",
                color = MotoHubAndroidAuto,
                modifier = Modifier.weight(1f),
                onClick = onStartPhoneOnlyAndroidAuto
            )
            if (io.motohub.android.BuildConfig.IS_PRO) {
                HeroTile(
                    title = "Ride Dashboard",
                    subtitle = "Live on your phone",
                    icon = "Dashboard",
                    color = MotoHubDashboard,
                    modifier = Modifier.weight(1f),
                    onClick = onStartPhoneOnlyRideDashboard
                )
            }
        }
    }
}

@Composable
private fun ConnectionContent(
    errorMessage: String?,
    onConnect: () -> Unit,
    officialCfmotoAppInstalled: Boolean,
    onCloseOfficialCfmotoAndRetry: () -> Unit,
    onOpenOfficialCfmotoSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onScanQr: () -> Unit,
    onImportQrPhoto: () -> Unit,
    onManualPairing: () -> Unit,
    onStartPhoneOnlyAndroidAuto: () -> Unit = {},
    onStartPhoneOnlyRideDashboard: () -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        errorMessage?.let { message ->
            ErrorBanner(
                message = message,
                showPortConflictHelp = TBoxConflictDiagnostics.isPortConflict(message),
                officialCfmotoAppInstalled = officialCfmotoAppInstalled,
                onCloseOfficialCfmotoAndRetry = onCloseOfficialCfmotoAndRetry,
                onOpenOfficialCfmotoSettings = onOpenOfficialCfmotoSettings,
                showWifiSettingsAction = message == WifiGate.WIFI_OFF_MESSAGE,
                onOpenWifiSettings = onOpenWifiSettings
            )
        }
        HeroPrimaryAction(
            title = "Connect",
            subtitle = "Join the T-Box network and start",
            icon = "External",
            color = MaterialTheme.colorScheme.primary,
            onClick = onConnect
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HeroTile(
                title = "Scan new QR",
                subtitle = "A different motorcycle",
                icon = "QrScan",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                onClick = onScanQr
            )
            HeroTile(
                title = "Import QR",
                subtitle = "Pick a saved photo",
                icon = "Import",
                color = MotoHubImport,
                modifier = Modifier.weight(1f),
                onClick = onImportQrPhoto
            )
        }
        HeroTile(
            title = "No QR? Connect manually",
            subtitle = "Type the network in",
            icon = "Manual",
            color = MotoHubManual,
            modifier = Modifier.fillMaxWidth(),
            onClick = onManualPairing
        )
        // No motorcycle needs to be connected at all to use either of these - both run entirely
        // on the phone. Ride Dashboard is Advanced-only (see ModeGrid); Android Auto works in both.
        MonoLabel(
            motoHubText("OR RIGHT NOW, WITHOUT THE T-BOX"),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            textAlign = TextAlign.Center
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HeroTile(
                title = "Android Auto",
                subtitle = "Straight to your phone",
                icon = "Auto",
                color = MotoHubAndroidAuto,
                modifier = Modifier.weight(1f),
                onClick = onStartPhoneOnlyAndroidAuto
            )
            // Ride Dashboard is a PRO-only feature. CORE ships Mirror + Auto only (see ModeGrid).
            if (io.motohub.android.BuildConfig.IS_PRO) {
                HeroTile(
                    title = "Ride Dashboard",
                    subtitle = "Live on your phone",
                    icon = "Dashboard",
                    color = MotoHubDashboard,
                    modifier = Modifier.weight(1f),
                    onClick = onStartPhoneOnlyRideDashboard
                )
            }
        }
    }
}

/**
 * Collapsed by default to just the failure headline and the actual error message - both
 * always fully readable, never truncated. Only the secondary, situational help (port-conflict
 * explanation, retry actions) is behind the expand tap, so the banner stays compact without
 * ever hiding what went wrong.
 */
@Composable
private fun ErrorBanner(
    message: String,
    showPortConflictHelp: Boolean,
    officialCfmotoAppInstalled: Boolean,
    onCloseOfficialCfmotoAndRetry: () -> Unit,
    onOpenOfficialCfmotoSettings: () -> Unit,
    showWifiSettingsAction: Boolean,
    onOpenWifiSettings: () -> Unit
) {
    var expanded by rememberSaveable(message) { mutableStateOf(false) }
    val hasExtra = showPortConflictHelp || showWifiSettingsAction
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (hasExtra) it.clickable { expanded = !expanded } else it },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    motoHubText("CONNECTION FAILED"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (hasExtra) {
                    Text(
                        if (expanded) "▲" else "▼ Details",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (expanded) {
                if (showPortConflictHelp) {
                    Text(
                        motoHubText("Another EasyConn app can keep the T-Box link occupied even after it leaves the foreground."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (officialCfmotoAppInstalled) {
                        SecondaryAction("Close official app and retry", onCloseOfficialCfmotoAndRetry)
                        SecondaryAction("Open official app settings", onOpenOfficialCfmotoSettings)
                    }
                }
                if (showWifiSettingsAction) {
                    SecondaryAction("Open Wi-Fi settings", onOpenWifiSettings)
                }
            }
        }
    }
}

@Composable
private fun ConnectingContent(
    phase: SessionPhase,
    ssid: String,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp
        )
        Spacer(Modifier.height(18.dp))
        Text(
            motoHubText("Connecting"),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Text(
            motoHubText("Setting up network and T-Box"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(22.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                ConnectionStep("Profile loaded", done = true)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                ConnectionStep(
                    "Connecting to $ssid",
                    done = phase == SessionPhase.DISCOVERING_TBOX,
                    current = phase == SessionPhase.CONNECTING_NETWORK
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                ConnectionStep(
                    "Finding T-Box service",
                    current = phase == SessionPhase.DISCOVERING_TBOX
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        SecondaryAction("Cancel", onCancel, Modifier.fillMaxWidth(0.5f))
    }
}

@Composable
private fun ModeSelectionContent(
    onStartProjection: () -> Unit,
    onStartRideDashboard: () -> Unit,
    rideDashboardMapSource: RideDashboardMapSource,
    onRideDashboardMapSourceChanged: (RideDashboardMapSource) -> Unit,
    onStartAndroidAuto: () -> Unit,
    onDisconnect: () -> Unit,
    aoaAccessoryConnected: Boolean = false,
    onStartExternalDisplay: () -> Unit = {}
) {
    var showDashboardPicker by rememberSaveable { mutableStateOf(false) }

    if (showDashboardPicker) {
        DashboardSourcePicker(
            selected = rideDashboardMapSource,
            onSelectedChanged = onRideDashboardMapSourceChanged,
            onBack = { showDashboardPicker = false },
            onStart = {
                showDashboardPicker = false
                onStartRideDashboard()
            }
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LivePill(motoHubText("T-Box connected"))
                Text(motoHubText("What to show?"), style = MaterialTheme.typography.displaySmall)
            }
            ModeGrid(
                onMirror = onStartProjection,
                onDashboard = { showDashboardPicker = true },
                onAndroidAuto = onStartAndroidAuto,
                onExternal = if (aoaAccessoryConnected) onStartExternalDisplay else null
            )
            // The only way back once connect succeeds - without it, the rider had no path from
            // "what to show?" to a different motorcycle or a plain Wi-Fi release except
            // force-stopping the app.
            Text(
                motoHubText("Disconnect"),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDisconnect)
                    .padding(vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private data class ModeChoice(
    val title: String,
    val subtitle: String,
    val icon: String,
    val color: Color,
    val onClick: () -> Unit
)

@Composable
private fun ModeGrid(
    onMirror: () -> Unit,
    onDashboard: () -> Unit,
    onAndroidAuto: () -> Unit,
    onExternal: (() -> Unit)? = null
) {
    val modes = buildList {
        add(ModeChoice("Mirror", "Mirror your phone screen", "Mirror", MotoHubMirror, onMirror))
        // Ride Dashboard is a PRO-only feature. CORE ships Mirror + Auto only.
        if (io.motohub.android.BuildConfig.IS_PRO) {
            add(ModeChoice("Dashboard", "Gauges, map, and widgets", "Dashboard", MotoHubDashboard, onDashboard))
        }
        add(ModeChoice("Android Auto", "The full Google Android Auto", "Auto", MotoHubAndroidAuto, onAndroidAuto))
        if (onExternal != null) {
            add(ModeChoice("External", "Show on a connected USB display", "External", MotoHubImport, onExternal))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        modes.chunked(2).forEach { rowModes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowModes.forEach { mode ->
                    HeroTile(
                        title = mode.title,
                        subtitle = mode.subtitle,
                        icon = mode.icon,
                        color = mode.color,
                        modifier = Modifier.weight(1f),
                        onClick = mode.onClick
                    )
                }
                // Keeps a lone last item at half width instead of stretching it full-width,
                // so the grid reads as consistent 2-up tiles regardless of how many modes
                // are available (2, 3, or 4 depending on flavor/AOA accessory state).
                if (rowModes.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DashboardSourcePicker(
    selected: RideDashboardMapSource,
    onSelectedChanged: (RideDashboardMapSource) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            motoHubText("‹ Back"),
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(motoHubText("Dashboard map"), style = MaterialTheme.typography.displaySmall)
            Text(
                motoHubText("Choose what the Ride Dashboard shows for navigation."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RideDashboardMapSource.entries.forEach { source ->
                val active = source == selected
                val accent = when (source) {
                    RideDashboardMapSource.OPEN_STREET_MAP -> MotoHubDashboard
                    RideDashboardMapSource.MAPLIBRE -> Color(0xFF55D6FF)
                    RideDashboardMapSource.ANDROID_AUTO -> MotoHubAndroidAuto
                }
                val icon = when (source) {
                    RideDashboardMapSource.OPEN_STREET_MAP -> "OSM"
                    RideDashboardMapSource.MAPLIBRE -> "MapLibre"
                    RideDashboardMapSource.ANDROID_AUTO -> "Auto"
                }
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp)
                        .clickable { onSelectedChanged(source) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (active) accent.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(
                        width = if (active) 1.5.dp else 1.dp,
                        color = if (active) accent else MaterialTheme.colorScheme.outline
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            ModeIcon(
                                icon,
                                if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                iconSize = 21.dp
                            )
                            Text(
                                motoHubText(source.label),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) accent else MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        Text(
            motoHubText(
                when (selected) {
                    RideDashboardMapSource.OPEN_STREET_MAP -> "Native turn-by-turn navigation rendered on the TFT"
                    RideDashboardMapSource.MAPLIBRE -> "Customizable vector map rendered on the TFT"
                    RideDashboardMapSource.ANDROID_AUTO -> "Mirrors Android Auto's own map"
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        PrimaryAction("Start dashboard", onStart)
    }
}

@Composable
private fun ActiveSessionContent(
    androidAutoActive: Boolean,
    androidAutoStreaming: Boolean,
    rideDashboardAndroidAutoActive: Boolean,
    rideDashboardAndroidAutoStreaming: Boolean,
    rideDashboardActive: Boolean,
    rideDashboardStreaming: Boolean,
    mirrorStreaming: Boolean,
    dimDisplayEnabled: Boolean,
    onDimDisplayChanged: (Boolean) -> Unit,
    showRecordedTrackOnDashboard: Boolean,
    onShowRecordedTrackOnDashboardChanged: (Boolean) -> Unit,
    rideDashboardMapSource: RideDashboardMapSource,
    onOpenAndroidAutoPreview: () -> Unit,
    onOpenAndroidAutoFullscreenControls: () -> Unit,
    onOpenRideDashboardPreview: () -> Unit,
    onOpenControls: () -> Unit,
    onCustomizeRideDashboard: () -> Unit,
    externalDisplayActive: Boolean = false,
    externalDisplayStreaming: Boolean = false,
    onStopExternalDisplay: () -> Unit = {},
    onStop: () -> Unit
) {
    val context = LocalContext.current
    var showSavedRoutePicker by rememberSaveable { mutableStateOf(false) }
    var showSavedTripPicker by rememberSaveable { mutableStateOf(false) }
    val ready = when {
        androidAutoActive -> androidAutoStreaming
        rideDashboardActive -> rideDashboardStreaming
        externalDisplayActive -> externalDisplayStreaming
        else -> mirrorStreaming
    }
    val modeName = when {
        androidAutoActive -> "Android Auto"
        rideDashboardActive -> "Ride Dashboard"
        externalDisplayActive -> "External Display"
        else -> "Mirroring"
    }
    val modeColor = when {
        androidAutoActive -> MotoHubAndroidAuto
        rideDashboardActive -> MotoHubDashboard
        externalDisplayActive -> MotoHubDashboard
        else -> MotoHubMirror
    }
    val statusText = when {
        androidAutoActive && ready -> "Navigation active on TFT"
        rideDashboardActive && rideDashboardAndroidAutoStreaming ->
            "Dashboard and Android Auto active · phone can lock"
        rideDashboardActive && ready -> "GPS and map active · phone can lock"
        externalDisplayActive && ready -> "Streaming to external display via USB"
        ready -> "TFT is receiving your screen"
        else -> "Session is being prepared"
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ActiveSessionHero(ready, modeName, statusText, modeColor)
        MonoLabel(motoHubText("SESSION ACTIONS"))

        when {
            androidAutoActive || rideDashboardAndroidAutoActive -> {
                ActiveSessionAction(
                    title = motoHubText("Preview & touch"),
                    description = motoHubText("View Android Auto and interact from your phone."),
                    accentColor = MotoHubAndroidAuto,
                    icon = "Auto",
                    onClick = onOpenAndroidAutoPreview
                )
                if (androidAutoActive && androidAutoStreaming) {
                    ActiveSessionAction(
                        title = motoHubText("Controls"),
                        description = motoHubText("Open the on-screen Android Auto controls."),
                        accentColor = MotoHubAndroidAuto,
                        icon = "Auto",
                        onClick = onOpenControls
                    )
                }
            }
            !rideDashboardActive -> ToggleCard(
                title = motoHubText("Dim phone display"),
                description = motoHubText("Keep the TFT active while reducing phone distraction."),
                checked = dimDisplayEnabled,
                onCheckedChange = onDimDisplayChanged
            )
        }

        if (rideDashboardActive) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ActiveSessionQuickAction(
                    title = motoHubText("Preview"),
                    accentColor = MotoHubDashboard,
                    icon = "Preview",
                    onClick = onOpenRideDashboardPreview,
                    modifier = Modifier.weight(1f)
                )
                if (rideDashboardStreaming) {
                    ActiveSessionQuickAction(
                        title = motoHubText("Controls"),
                        accentColor = MotoHubDashboard,
                        icon = "Controls",
                        onClick = onOpenControls,
                        modifier = Modifier.weight(1f)
                    )
                }
                ActiveSessionQuickAction(
                    title = motoHubText("Customize"),
                    accentColor = MotoHubDashboard,
                    icon = "Customize",
                    onClick = onCustomizeRideDashboard,
                    modifier = Modifier.weight(1f)
                )
            }
            if (rideDashboardMapSource != RideDashboardMapSource.ANDROID_AUTO) {
                MonoLabel(motoHubText("LOAD TRACK"))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ActiveSessionQuickAction(
                        title = motoHubText("Saved route"),
                        accentColor = MotoHubDashboard,
                        icon = "Gps",
                        onClick = { showSavedRoutePicker = true },
                        modifier = Modifier.weight(1f)
                    )
                    ActiveSessionQuickAction(
                        title = motoHubText("Recorded trip"),
                        accentColor = MotoHubDashboard,
                        icon = "Route",
                        onClick = { showSavedTripPicker = true },
                        modifier = Modifier.weight(1f)
                    )
                }
                val loadedTrip by RideDashboardTrackOverlayRuntime.loadedTrip.collectAsState()
                val followLiveGps by RideDashboardTrackOverlayRuntime.followLiveGps.collectAsState()
                if (loadedTrip != null) {
                    MonoLabel(motoHubText("LOADED TRIP"))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ActiveSessionQuickAction(
                            title = motoHubText(if (followLiveGps) "GPS following" else "Restore GPS"),
                            accentColor = MotoHubDashboard,
                            icon = "Gps",
                            onClick = { RideDashboardTrackOverlayRuntime.restoreLiveGps() },
                            enabled = !followLiveGps,
                            modifier = Modifier.weight(1f)
                        )
                        ActiveSessionQuickAction(
                            title = motoHubText("Clear map"),
                            accentColor = MotoHubDashboard,
                            icon = "Clear",
                            onClick = { RideDashboardTrackOverlayRuntime.clearLoadedTrip() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            // Gated on the selected map source (same signal as the trip/route pickers above),
            // not just on whether embedded AA is currently streaming — with Android Auto as the
            // map source there is no track overlay possible at all, so the toggle must not
            // appear the moment that source is selected, not only once AA starts streaming.
            if (rideDashboardMapSource != RideDashboardMapSource.ANDROID_AUTO) {
                ToggleCard(
                    title = motoHubText("Show GPS track"),
                    description = motoHubText("Draw recorded points on the dashboard map."),
                    checked = showRecordedTrackOnDashboard,
                    onCheckedChange = onShowRecordedTrackOnDashboardChanged
                )
            }
        }

        StopAction(
            text = if (rideDashboardActive) "Stop dashboard" else "Stop streaming",
            onClick = onStop
        )
    }
    if (showSavedRoutePicker) {
        SavedRoutePickerDialog(
            onDismiss = { showSavedRoutePicker = false },
            onSelected = { ride ->
                NavigationRuntime.publish(ride.route, ride.destination)
                if (MotoHubSettings.autoRecordTrips(context)) {
                    TripRecordingService.startAuto(
                        context,
                        MotorcycleProfileStore(context).load()?.id,
                        TripRecordingSource.NAVIGATION
                    )
                }
                ProjectionEventLog.record(
                    "RIDE_NAV",
                    "Saved route loaded into the active Ride Dashboard: ${ride.destination.label}."
                )
                showSavedRoutePicker = false
            }
        )
    }
    if (showSavedTripPicker) {
        SavedTripPickerDialog(
            onDismiss = { showSavedTripPicker = false },
            onSelected = { details ->
                RideDashboardTrackOverlayRuntime.loadTrip(details)
                ProjectionEventLog.record(
                    "RIDE_DASHBOARD",
                    "Saved trip loaded into the active OSM Ride Dashboard: ${details.summary.id}."
                )
                showSavedTripPicker = false
            }
        )
    }
}

@Composable
private fun SavedRoutePickerDialog(
    onDismiss: () -> Unit,
    onSelected: (SavedRide) -> Unit
) {
    val context = LocalContext.current
    val savedRoutes = remember { SavedRidesStore(context).all() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("Load saved route")) },
        text = {
            if (savedRoutes.isEmpty()) {
                Text(motoHubText("No saved routes yet."))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    savedRoutes.forEach { ride ->
                        TextButton(
                            onClick = { onSelected(ride) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    ride.label ?: ride.destination.label,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    ride.destination.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(motoHubText("CLOSE")) }
        }
    )
}

@Composable
private fun SavedTripPickerDialog(
    onDismiss: () -> Unit,
    onSelected: (TripDetails) -> Unit
) {
    val context = LocalContext.current
    val savedTrips = remember { TripStore(context).listTrips() }
    val scope = rememberCoroutineScope()
    var loadingTripId by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("Load recorded trip")) },
        text = {
            if (savedTrips.isEmpty()) {
                Text(motoHubText("No recorded trips yet."))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    savedTrips.forEach { trip ->
                        TextButton(
                            onClick = {
                                if (loadingTripId == null) {
                                    loadingTripId = trip.id
                                    scope.launch {
                                        val details = withContext(Dispatchers.IO) {
                                            TripStore(context).getTrip(trip.id)
                                        }
                                        loadingTripId = null
                                        details?.let(onSelected)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = loadingTripId == null
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    trip.name ?: motoHubText(trip.source.label),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    motoHubText(
                                        "%1\$s · %2\$s · %3\$s",
                                        formatTripDistance(trip.distanceMeters, MotoHubSettings.distanceUnits(LocalContext.current)),
                                        formatTripDuration(trip.elapsedTimeMillis),
                                        motoHubText("%1\$d points", trip.pointCount)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (loadingTripId == trip.id) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(motoHubText("CLOSE")) }
        }
    )
}

@Composable
private fun ActiveSessionHero(ready: Boolean, modeName: String, statusText: String, accentColor: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                ModeIcon(
                    mode = when (modeName) {
                        "Ride Dashboard" -> "Dashboard"
                        "Android Auto" -> "Auto"
                        else -> "Mirror"
                    },
                    color = accentColor,
                    iconSize = 32.dp
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LivePill(if (ready) "LIVE ON TFT" else "PREPARING")
                Text(modeName, style = MaterialTheme.typography.titleLarge)
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ActiveSessionAction(
    title: String,
    description: String,
    accentColor: Color,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(132.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                ModeIcon(icon, accentColor, iconSize = 23.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Compact three-across action used while the dashboard is live, so the stop control
 * remains visible on short phone displays without hiding any active-session actions. */
@Composable
private fun ActiveSessionQuickAction(
    title: String,
    accentColor: Color,
    icon: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(84.dp)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 10.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        accentColor.copy(alpha = if (enabled) 0.12f else 0.05f),
                        RoundedCornerShape(11.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                ModeIcon(
                    icon,
                    accentColor.copy(alpha = if (enabled) 1f else 0.45f),
                    iconSize = 19.dp
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun ConnectionStep(text: String, done: Boolean = false, current: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    when {
                        done -> MaterialTheme.colorScheme.tertiary
                        current -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    },
                    CircleShape
                )
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done || current) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SecondaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Text(text)
    }
}

@Composable
private fun StopAction(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}
