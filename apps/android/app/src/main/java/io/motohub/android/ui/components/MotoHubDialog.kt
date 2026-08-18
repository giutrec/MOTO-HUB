package io.motohub.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Body slot for every MOTO-HUB [androidx.compose.material3.AlertDialog].
 *
 * Material 3 gives the text slot whatever height is left over after the title and the buttons, but
 * it never scrolls it: the overflow is simply clipped and unreachable. A narrow phone wraps the
 * same paragraphs onto more lines, and a large system font size makes each line taller, so a
 * dialog that fits on one device silently loses its last sentences on another. Riders were left
 * unable to read a safety warning to the end.
 *
 * Wrapping the body here keeps it scrollable, and fades the bottom edge while there is more to
 * read so the rider can see that scrolling is possible.
 */
@Composable
fun MotoHubDialogBody(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val scroll = rememberScrollState()
    val fadeColor = AlertDialogDefaults.containerColor
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content
        )
        if (scroll.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(FADE_HEIGHT)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, fadeColor)))
            )
        }
    }
}

private val FADE_HEIGHT = 28.dp
