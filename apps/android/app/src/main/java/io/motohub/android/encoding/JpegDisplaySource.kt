package io.motohub.android.encoding

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import io.motohub.android.session.ProjectionEventLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Captures the projected display as JPEG stills instead of an H.264 stream.
 *
 * This exists for exactly one dash family. The Moto Morini X-Cape 1200's OEM app, Ride MO 1.0.23,
 * **never streams H.264**: `ParamSettings.deviceStreamType` is initialised to `Image`, its only
 * setter has zero call sites in the APK, and `createDisplayAndLiveAdapter` therefore always takes
 * the image branch. Its H.264 classes are complete and maintained but unreachable. Two independent
 * implementations - this one and the public reference - spent weeks tuning an H.264 stream against
 * those dead classes, and both ended at the same wall: the dash acknowledges every frame and paints
 * none of them. Acknowledgement is a transport-layer counter keyed on the frame index, so it says
 * nothing about whether the payload was understood.
 *
 * Every parameter here is read from that app rather than guessed: RGBA_8888 into an ImageReader of
 * two buffers, the bitmap halved before compression, JPEG quality 60, one frame every 100 ms.
 *
 * **Nothing else in the app uses this.** It is selected only by a profile that opts in, and the
 * H.264 path it sits beside is untouched - a dash that streams today cannot reach this code.
 */
class JpegDisplaySource(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    /**
     * Returning false means the transport refused the frame; the source drops it rather than
     * queueing, exactly as the encoder path does, because a still is only worth sending while it
     * is still current.
     */
    private val onFrame: (jpeg: ByteArray, frameId: Int) -> Boolean,
    private val onFailure: (Throwable) -> Unit
) {

    private var imageReader: ImageReader? = null
    private var readerThread: HandlerThread? = null
    private val running = AtomicBoolean(false)
    private val frameId = AtomicInteger(0)
    private var lastFrameAtMillis = 0L
    private var scaledBitmap: Bitmap? = null

    /** The surface a VirtualDisplay should render into. Null until [start]. */
    var surface: Surface? = null
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val thread = HandlerThread("motohub-jpeg-capture").also { it.start() }
        readerThread = thread
        // Two buffers, matching the OEM. One would stall the producer while a frame compresses;
        // more would only add latency, because a still older than the next one is worthless.
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ onImageAvailable(it) }, Handler(thread.looper))
        imageReader = reader
        surface = reader.surface
        ProjectionEventLog.record(
            "JPEG",
            "Capturing ${width}x$height as JPEG stills at ${frameRate}fps, " +
                "quality $JPEG_QUALITY, scaled to ${(SCALE * 100).toInt()}%."
        )
    }

    private fun onImageAvailable(reader: ImageReader) {
        if (!running.get()) {
            runCatching { reader.acquireLatestImage()?.close() }
            return
        }
        // Pace here rather than on the display: the VirtualDisplay posts whenever the content
        // changes, which on a moving map is far more often than the dash will take.
        val now = SystemClock.elapsedRealtime()
        val minimumGap = 1000L / frameRate.coerceAtLeast(1)
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (now - lastFrameAtMillis < minimumGap) return
            lastFrameAtMillis = now

            val plane = image.planes.firstOrNull() ?: return
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            // The reader hands back rows padded to its own stride, so the backing bitmap has to be
            // wide enough to hold the padding and is cropped afterwards. Skipping this shears the
            // picture diagonally, which would look exactly like a codec fault.
            val paddedWidth = if (pixelStride > 0) rowStride / pixelStride else width
            val full = Bitmap.createBitmap(paddedWidth.coerceAtLeast(width), height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)

            val targetWidth = (width * SCALE).toInt().coerceAtLeast(2) and 1.inv()
            val targetHeight = (height * SCALE).toInt().coerceAtLeast(2) and 1.inv()
            val cropped = Bitmap.createBitmap(full, 0, 0, width, height)
            val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)

            val out = ByteArrayOutputStream(scaled.byteCount / 8)
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            val jpeg = out.toByteArray()

            if (cropped !== full) full.recycle()
            if (scaled !== cropped) cropped.recycle()
            scaledBitmap?.takeIf { it !== scaled }?.recycle()
            scaledBitmap = scaled

            val id = frameId.getAndIncrement()
            if (!onFrame(jpeg, id)) {
                ProjectionEventLog.debug("JPEG", "Frame $id refused by the transport; dropped.")
            }
        } catch (failure: Throwable) {
            if (running.get()) onFailure(failure)
        } finally {
            runCatching { image.close() }
        }
    }

    /** Resets the frame counter, for a dash that just told us it re-entered its map view. */
    fun resetFrameId() {
        frameId.set(0)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        surface = null
        runCatching { readerThread?.quitSafely() }
        readerThread = null
        scaledBitmap?.recycle()
        scaledBitmap = null
        ProjectionEventLog.record("JPEG", "JPEG capture stopped after ${frameId.get()} frames.")
    }

    private companion object {
        /** Ride MO's `imageScale`. The dash is fed half the display and scales it back up. */
        const val SCALE = 0.5f

        /** Ride MO's compression quality for the same path. */
        const val JPEG_QUALITY = 60
    }
}
