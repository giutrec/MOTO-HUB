package io.motohub.android.encoding

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import io.motohub.android.session.ProjectionEventLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
 * two buffers, the full frame compressed at JPEG quality 60, one frame on the wire every 100 ms.
 *
 * **Capture and send are deliberately decoupled**, which is the shape `GoogleImageReaderLiveThread`
 * uses and the shape this class had to be rewritten into. That thread compresses *every* image the
 * VirtualDisplay posts into a single latest-wins slot (`imgBuf`, guarded by `dataUseSem`) and lets
 * a 100 ms `Timer` release a semaphore that sends whatever is in the slot. Pacing the capture
 * instead - which is what this class did first - looks equivalent and is not: it caps production at
 * exactly the send rate, so any frame lost to a busy socket or a full send window is a frame that
 * is never made up, and the dash receives at an irregular rate rather than a slow one. Irregular is
 * what a rider sees as judder, and turn-by-turn guidance is where it is least tolerable.
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
    private var sender: ScheduledExecutorService? = null
    private val running = AtomicBoolean(false)
    private val frameId = AtomicInteger(0)

    /** The most recently compressed still, waiting for the next send tick. Latest wins. */
    private val latest = AtomicReference<ByteArray?>(null)

    // Reused across frames. Compressing a 1024x464 frame used to allocate three ARGB_8888 bitmaps
    // of ~1.9 MB each and copy the pixels through all of them; two of those copies existed only to
    // crop the reader's row padding and to apply a scale that is now the identity.
    private var padded: Bitmap? = null
    private var cropped: Bitmap? = null
    private var croppedCanvas: Canvas? = null
    private val jpegBuffer = ByteArrayOutputStream(DEFAULT_JPEG_BUFFER_BYTES)
    private val cropSource = Rect()
    private val cropDestination = Rect()

    // Counters for the periodic throughput line. Only ever touched from the capture thread and the
    // single sender thread respectively, except `bytesSent`, which the sender alone accumulates.
    private val captured = AtomicInteger(0)
    private var sent = 0
    private var refused = 0
    private var idle = 0
    private var repeated = 0
    private var bytesSent = 0L
    private var lastSent: ByteArray? = null
    private var statsAtMillis = 0L

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

        val periodMillis = (1000L / frameRate.coerceAtLeast(1)).coerceAtLeast(1L)
        statsAtMillis = SystemClock.elapsedRealtime()
        sender = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "motohub-jpeg-send")
        }.also {
            it.scheduleAtFixedRate({ tick() }, periodMillis, periodMillis, TimeUnit.MILLISECONDS)
        }

        ProjectionEventLog.record(
            "JPEG",
            "Capturing ${width}x$height as JPEG stills, quality $JPEG_QUALITY, " +
                "one frame on the wire every ${periodMillis}ms (${frameRate}fps)."
        )
    }

    /**
     * Compresses every frame the display posts, keeping only the newest.
     *
     * There is no rate limit here on purpose - see the note on the class. The single capture thread
     * limits itself: the next image cannot be picked up until this one has finished compressing, so
     * the loop settles at whatever rate the phone can actually sustain, and the send tick always
     * finds the freshest picture that rate allowed.
     */
    private fun onImageAvailable(reader: ImageReader) {
        if (!running.get()) {
            runCatching { reader.acquireLatestImage()?.close() }
            return
        }
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes.firstOrNull() ?: return
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            // The reader hands back rows padded to its own stride, so the backing bitmap has to be
            // wide enough to hold the padding and is cropped afterwards. Skipping this shears the
            // picture diagonally, which would look exactly like a codec fault.
            val paddedWidth = (if (pixelStride > 0) rowStride / pixelStride else width)
                .coerceAtLeast(width)

            val source = paddedBitmap(paddedWidth).also { it.copyPixelsFromBuffer(plane.buffer) }
            // Only pay for the crop when the reader actually padded the rows. At 1024 pixels of
            // RGBA the stride is usually already exact, and then the padded bitmap *is* the frame.
            val frame = if (paddedWidth == width) source else croppedBitmap().also { destination ->
                cropSource.set(0, 0, width, height)
                cropDestination.set(0, 0, width, height)
                croppedCanvas(destination).drawBitmap(source, cropSource, cropDestination, null)
            }

            jpegBuffer.reset()
            frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpegBuffer)
            latest.set(jpegBuffer.toByteArray())
            captured.incrementAndGet()
        } catch (failure: Throwable) {
            if (running.get()) onFailure(failure)
        } finally {
            runCatching { image.close() }
        }
    }

    /** Sends whatever the capture thread last produced, on a fixed cadence. */
    private fun tick() {
        if (!running.get()) return
        try {
            val jpeg = latest.get()
            if (jpeg == null) {
                idle++
            } else {
                if (jpeg === lastSent) repeated++
                lastSent = jpeg
                val id = frameId.getAndIncrement()
                if (onFrame(jpeg, id)) {
                    sent++
                    bytesSent += jpeg.size
                } else {
                    refused++
                }
            }
            reportThroughput()
        } catch (failure: Throwable) {
            if (running.get()) onFailure(failure)
        }
    }

    /**
     * One line every [STATS_INTERVAL_MS], because the field question this path keeps raising is
     * "why is it not smooth" and the answer is always one of three numbers: frames the phone could
     * compress, frames the transport would take, and bytes per second on the wire. Guessing between
     * them has already cost several test rides.
     */
    private fun reportThroughput() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - statsAtMillis
        if (elapsed < STATS_INTERVAL_MS) return
        val seconds = elapsed / 1000.0
        val averageKb = if (sent > 0) bytesSent / sent / 1024.0 else 0.0
        ProjectionEventLog.debug(
            "JPEG",
            "%.1f fps out of the phone, %.1f fps on the wire, %.0f KB average, %.0f KB/s"
                .format(captured.getAndSet(0) / seconds, sent / seconds, averageKb,
                    bytesSent / 1024.0 / seconds) +
                " ($refused refused, ${idle + repeated} ticks with nothing new)"
        )
        sent = 0
        refused = 0
        idle = 0
        repeated = 0
        bytesSent = 0
        statsAtMillis = now
    }

    private fun paddedBitmap(paddedWidth: Int): Bitmap {
        val existing = padded
        if (existing != null && !existing.isRecycled &&
            existing.width == paddedWidth && existing.height == height
        ) {
            return existing
        }
        existing?.recycle()
        return Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888).also { padded = it }
    }

    private fun croppedBitmap(): Bitmap {
        val existing = cropped
        if (existing != null && !existing.isRecycled) return existing
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            cropped = it
            croppedCanvas = null
        }
    }

    private fun croppedCanvas(destination: Bitmap): Canvas =
        croppedCanvas ?: Canvas(destination).also { croppedCanvas = it }

    /** Resets the frame counter, for a dash that just told us it re-entered its map view. */
    fun resetFrameId() {
        frameId.set(0)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { sender?.shutdownNow() }
        sender = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        surface = null
        runCatching { readerThread?.quitSafely() }
        readerThread = null
        latest.set(null)
        lastSent = null
        padded?.recycle()
        padded = null
        cropped?.recycle()
        cropped = null
        croppedCanvas = null
        ProjectionEventLog.record("JPEG", "JPEG capture stopped after ${frameId.get()} frames.")
    }

    private companion object {
        /** Ride MO's compression quality for the same path. */
        const val JPEG_QUALITY = 60

        /** Roughly one full-size map frame at [JPEG_QUALITY]; the stream grows itself if wrong. */
        const val DEFAULT_JPEG_BUFFER_BYTES = 128 * 1024

        const val STATS_INTERVAL_MS = 5_000L

    }
}
