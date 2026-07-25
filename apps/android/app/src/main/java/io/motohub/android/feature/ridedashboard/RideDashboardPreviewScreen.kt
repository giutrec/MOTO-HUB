package io.motohub.android.feature.ridedashboard

import io.motohub.android.i18n.motoHubText

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.motohub.android.ui.components.LivePill
import io.motohub.android.ui.components.MotoHubHeader

/**
 * Renders the exact same Ride Dashboard TFT output onto a phone-side
 * `SurfaceView`, using the phone's own GPS - no T-Box connection required.
 * Lets heading-up rotation, zoom, maneuver banners etc. be checked visually
 * without a motorcycle nearby, mirroring how [io.motohub.android.feature.androidauto.AndroidAutoPreviewScreen]
 * previews an Android Auto session, but standalone rather than mirroring a
 * live stream. Fullscreen mode mirrors that same screen too, for the same
 * reason: the header/status bar chrome leaves too little height in landscape
 * to judge how the dashboard will actually look.
 *
 * Always uses the OSM map panel, regardless of the rider's real Ride
 * Dashboard map source preference: there is no live Android Auto session to
 * embed here (no T-Box, no phone-mirrored AA feed), so honoring an AA
 * preference would just render an empty panel instead of previewing anything.
 *
 * Also serves as the entire experience for a phone-only session with no T-Box at all
 * (`publishRuntimeState = true`) - same renderer, just also publishing into
 * [RideDashboardRuntime] so the shared Home UI reflects it correctly.
 */
@Composable
fun RideDashboardPreviewScreen(
    onBack: () -> Unit,
    // false (default): a passive peek while a real T-Box session may already be running -
    // RideDashboardRuntime stays owned by RideDashboardSessionService, this screen renders its
    // own independent twin purely for the phone. true: this session IS the whole dashboard (no
    // T-Box at all) - publish into RideDashboardRuntime so the shared Home/ActiveSessionContent
    // UI reflects it correctly once the rider navigates back.
    publishRuntimeState: Boolean = false
) {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val session = remember(publishRuntimeState) {
        RideDashboardPhoneOnlySession(
            context = context,
            coroutineScope = coroutineScope,
            publishRuntimeState = publishRuntimeState,
            onFailure = { message -> errorMessage = message }
        )
    }

    val locationGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val window = (view.context as? Activity)?.window
    val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
    DisposableEffect(view, insetsController) {
        onDispose { insetsController?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(fullscreen, insetsController) {
        if (fullscreen) {
            insetsController?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler(enabled = fullscreen) { fullscreen = false }
    BackHandler(enabled = !fullscreen, onBack = onBack)

    fun stopPreview() {
        session.stop()
        isRunning = false
    }

    fun startPreview(surface: Surface) {
        if (!locationGranted) {
            errorMessage = "Location permission is required to preview the dashboard."
            return
        }
        isRunning = session.start(surface)
        if (isRunning) errorMessage = null
    }

    val preview = @Composable {
        Box(modifier = Modifier.fillMaxSize()) {
            if (locationGranted) {
                AndroidView(
                    factory = { viewContext ->
                        SurfaceView(viewContext).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    startPreview(holder.surface)
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) = Unit

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    stopPreview()
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            errorMessage?.let { message -> StatusOverlay(message, Modifier.align(Alignment.Center)) }
            if (!locationGranted) {
                StatusOverlay(
                    "Grant location access in Settings to preview the dashboard.",
                    Modifier.align(Alignment.Center)
                )
            }
        }
    }

    if (fullscreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            preview()
            OutlinedButton(
                onClick = { fullscreen = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xE6141B17),
                    contentColor = Color.White
                ),
                border = BorderStroke(1.dp, Color(0x33FFFFFF))
            ) { Text(motoHubText("Exit fullscreen")) }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MotoHubHeader(
                    modifier = Modifier.fillMaxWidth(),
                    trailing = { TextButton(onClick = onBack) { Text(motoHubText("Close")) } }
                )
                LivePill(if (isRunning) "PHONE PREVIEW LIVE" else "STARTING")
                Text(
                    motoHubText("Uses this phone's GPS - no T-Box needed. Same renderer and heading-up/zoom ") +
                        "logic that goes to the motorcycle TFT, always with the OSM map panel " +
                        "(there's no live Android Auto session to preview here).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                preview()
                OutlinedButton(
                    onClick = { fullscreen = true },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xE6141B17),
                        contentColor = Color.White
                    ),
                    border = BorderStroke(1.dp, Color(0x33FFFFFF))
                ) { Text(motoHubText("Fullscreen")) }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopPreview() }
    }
}

@Composable
private fun StatusOverlay(message: String, modifier: Modifier = Modifier) {
    MaterialSurface(
        modifier = modifier.padding(24.dp),
        color = Color.Black.copy(alpha = 0.78f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    ) {
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(18.dp)
        )
    }
}
