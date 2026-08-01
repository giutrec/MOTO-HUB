package io.motohub.android.androidauto

data class PreviewViewport(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val source: DisplayGeometry,
    val sourceLeft: Int = 0,
    val sourceTop: Int = 0,
    val sourceWidth: Int = source.width,
    val sourceHeight: Int = source.height
) {
    fun mapToSource(canvasX: Int, canvasY: Int): Pair<Int, Int>? {
        val relativeX = canvasX - x
        val relativeY = canvasY - y
        if (relativeX !in 0 until width || relativeY !in 0 until height) return null
        val sourceX = sourceLeft + (relativeX.toLong() * sourceWidth / width).toInt()
        val sourceY = sourceTop + (relativeY.toLong() * sourceHeight / height).toInt()
        return sourceX.coerceIn(0, source.width - 1) to
            sourceY.coerceIn(0, source.height - 1)
    }
}

internal fun calculatePreviewViewport(
    canvas: DisplayGeometry,
    source: DisplayGeometry
): PreviewViewport {
    val contentAspect = source.width.toDouble() / source.height
    val canvasAspect = canvas.width.toDouble() / canvas.height
    val width: Int
    val height: Int
    if (contentAspect < canvasAspect) {
        height = canvas.height
        width = (canvas.height * contentAspect).toInt().coerceAtLeast(1)
    } else {
        width = canvas.width
        height = (canvas.width / contentAspect).toInt().coerceAtLeast(1)
    }
    return PreviewViewport(
        x = (canvas.width - width) / 2,
        y = (canvas.height - height) / 2,
        width = width,
        height = height,
        source = source
    )
}

/**
 * Rewrites a viewport that was laid out against the content area so it SAMPLES that area out of
 * the coded frame, instead of stretching the whole frame - margins included - over it.
 *
 * The offset is half the margin on each axis because Android Auto centres its UI in what it was
 * given (see [AaAspectMargins]). Returns the viewport untouched when there are no margins, which
 * is every session that did not need aspect matching.
 */
internal fun PreviewViewport.sampleContentOf(
    coded: DisplayGeometry,
    margins: AaAspectMargins
): PreviewViewport {
    if (!margins.any) return this
    return copy(
        source = coded,
        sourceLeft = (margins.width / 2).coerceIn(0, coded.width - 1),
        sourceTop = (margins.height / 2).coerceIn(0, coded.height - 1),
        sourceWidth = (coded.width - margins.width).coerceIn(1, coded.width),
        sourceHeight = (coded.height - margins.height).coerceIn(1, coded.height)
    )
}

internal fun calculateMarginCropViewport(
    canvas: DisplayGeometry,
    source: DisplayGeometry,
    marginWidth: Int,
    marginHeight: Int,
    // Defaults preserve the old centered-crop behavior for any caller that doesn't know the real
    // split. Callers backed by TBoxScreenMargins (asymmetric left/top/right/bottom) MUST pass the
    // true offset - marginWidth/2 silently assumes the margin was split evenly between the two
    // edges, which is wrong whenever e.g. left=0, right=100.
    offsetX: Int = marginWidth / 2,
    offsetY: Int = marginHeight / 2
): PreviewViewport {
    val sourceLeft = offsetX.coerceIn(0, source.width - 1)
    val sourceTop = offsetY.coerceIn(0, source.height - 1)
    val sourceWidth = (source.width - marginWidth).coerceIn(1, source.width)
    val sourceHeight = (source.height - marginHeight).coerceIn(1, source.height)
    return PreviewViewport(
        x = 0,
        y = 0,
        width = canvas.width,
        height = canvas.height,
        source = source,
        sourceLeft = sourceLeft,
        sourceTop = sourceTop,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight
    )
}

internal fun calculateStretchViewport(
    canvas: DisplayGeometry,
    source: DisplayGeometry,
    marginWidth: Int = 0,
    marginHeight: Int = 0,
    offsetX: Int = marginWidth / 2,
    offsetY: Int = marginHeight / 2
): PreviewViewport =
    if (marginWidth > 0 || marginHeight > 0) {
        calculateMarginCropViewport(
            canvas = canvas,
            source = source,
            marginWidth = marginWidth,
            marginHeight = marginHeight,
            offsetX = offsetX,
            offsetY = offsetY
        )
    } else {
        PreviewViewport(
            x = 0,
            y = 0,
            width = canvas.width,
            height = canvas.height,
            source = source
        )
    }

internal fun calculateFillViewport(
    canvas: DisplayGeometry,
    source: DisplayGeometry
): PreviewViewport {
    val sourceAspect = source.width.toDouble() / source.height
    val canvasAspect = canvas.width.toDouble() / canvas.height
    val width: Int
    val height: Int
    if (sourceAspect < canvasAspect) {
        width = canvas.width
        height = (canvas.width / sourceAspect).toInt().coerceAtLeast(canvas.height)
    } else {
        height = canvas.height
        width = (canvas.height * sourceAspect).toInt().coerceAtLeast(canvas.width)
    }
    return PreviewViewport(
        x = (canvas.width - width) / 2,
        y = (canvas.height - height) / 2,
        width = width,
        height = height,
        source = source
    )
}
