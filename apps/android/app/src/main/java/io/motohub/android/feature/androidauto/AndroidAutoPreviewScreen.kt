package io.motohub.android.feature.androidauto

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.motohub.android.androidauto.AndroidAutoRuntime
import io.motohub.android.androidauto.AndroidAutoRuntimeState
import io.motohub.android.androidauto.AndroidAutoPreviewView
import io.motohub.android.ui.components.LivePill
import io.motohub.android.ui.components.MotoHubHeader

@Composable
fun AndroidAutoPreviewScreen(onBack: () -> Unit, startFullscreen: Boolean = false) {
    val view = LocalView.current
    val runtimeState by AndroidAutoRuntime.state.collectAsStateWithLifecycle()
    var fullscreen by rememberSaveable(startFullscreen) { mutableStateOf(startFullscreen) }
    val window = (view.context as? ComponentActivity)?.window
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

    val streaming = runtimeState is AndroidAutoRuntimeState.Streaming
    val sessionActive = runtimeState is AndroidAutoRuntimeState.Preparing ||
        runtimeState is AndroidAutoRuntimeState.ReceiverReady || streaming
    val startupDetail by AndroidAutoRuntime.startupDetail.collectAsStateWithLifecycle()
    val status = when (val state = runtimeState) {
        AndroidAutoRuntimeState.Idle -> "Android Auto is not running. Start a session from Home."
        AndroidAutoRuntimeState.Preparing -> "Preparing Android Auto…"
        // Not "connected": at this point MOTO-HUB is only listening, and is still asking Google
        // Android Auto to project here — which can take several seconds and several attempts.
        AndroidAutoRuntimeState.ReceiverReady ->
            startupDetail ?: "Waiting for Android Auto to start projecting…"
        AndroidAutoRuntimeState.Streaming -> "Live preview · touch enabled"
        is AndroidAutoRuntimeState.Stopped -> state.reason
        is AndroidAutoRuntimeState.Failed -> state.message
    }

    val preview: @Composable () -> Unit = {
        AndroidView(
            factory = ::AndroidAutoPreviewView,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        )
    }

    if (fullscreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            preview()
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PreviewStatusPill(streaming = streaming)
                PreviewActionButton("Exit fullscreen") { fullscreen = false }
            }
            if (!sessionActive) {
                PreviewStatusOverlay(status, Modifier.align(Alignment.Center))
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
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
                    trailing = { TextButton(onClick = onBack) { Text("Close") } }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PreviewStatusPill(streaming = streaming)
                    Text(
                        text = if (streaming) "Touch the preview to control Android Auto" else status,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                preview()
                if (!sessionActive) {
                    PreviewStatusOverlay(status, Modifier.align(Alignment.Center))
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(14.dp),
                    color = Color.Black.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Android Auto",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge
                        )
                        PreviewActionButton("Fullscreen") { fullscreen = true }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewStatusPill(streaming: Boolean) {
    LivePill(if (streaming) "ANDROID AUTO LIVE" else "ANDROID AUTO STANDBY")
}

@Composable
private fun PreviewActionButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(36.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0xE6141B17),
            contentColor = Color.White
        ),
        border = BorderStroke(1.dp, Color(0x55FFFFFF))
    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun PreviewStatusOverlay(status: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(24.dp),
        color = Color.Black.copy(alpha = 0.82f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = status,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )
    }
}
