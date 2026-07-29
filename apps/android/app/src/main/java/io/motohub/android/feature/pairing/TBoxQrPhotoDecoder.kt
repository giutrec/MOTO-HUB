package io.motohub.android.feature.pairing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlin.math.min

/** Decodes EasyConn QR photos, including photos taken from a reflective TFT display. */
object TBoxQrPhotoDecoder {
    private const val PHOTO_SCAN_SIZE = 1400
    private const val LOW_PASS_SIZE = 320

    fun scan(
        context: Context,
        uri: Uri,
        onProgress: (attempt: Int, total: Int) -> Unit = { _, _ -> },
        onResult: (Result<TBoxQrPayload>) -> Unit
    ) {
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
        val original = runCatching { InputImage.fromFilePath(context, uri) }
            .getOrElse { failure ->
                scanner.close()
                onResult(Result.failure(failure))
                return
            }
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        }.getOrNull()
        val photoSpecs = bitmap?.let { buildPhotoScanSpecs(it.width, it.height) }.orEmpty()
        val totalAttempts = 1 + photoSpecs.size

        fun finish(result: Result<TBoxQrPayload>) {
            scanner.close()
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            onResult(result)
        }

        // A photo of a dash can also catch a poster, a sticker or a second screen. When a crop
        // yields credentials from an unfamiliar source we hold on to it and keep looking: a code
        // that corroborates itself in a later crop is the better answer, and the held candidate is
        // only returned once every crop has been tried.
        var unverified: TBoxQrPayload? = null

        fun attempt(index: Int, lastFailure: Throwable?) {
            if (index > photoSpecs.size) {
                onProgress(totalAttempts, totalAttempts)
                val held = unverified
                finish(
                    when {
                        held != null -> Result.success(held)
                        else -> Result.failure(
                            lastFailure ?: IllegalArgumentException(
                                "The selected photo does not contain a readable QR code."
                            )
                        )
                    }
                )
                return
            }
            onProgress(index + 1, totalAttempts)

            // The file-backed image is attempted first so normal QR photos stay fast. The
            // following attempts use overlapping square regions: a QR displayed on a TFT is
            // often only a few hundred pixels wide inside a much larger camera photo.
            val prepared = if (index == 0 || bitmap == null) {
                null
            } else {
                prepare(bitmap, photoSpecs[index - 1])
            }
            val image = prepared?.image ?: original

            // ML Kit is excellent for the live camera stream, but it can reject a still
            // photograph whose TFT subpixel pattern has already been normalised. ZXing is a
            // small pure-Java fallback for these prepared bitmaps; the same EasyConn parser is
            // still the authority for accepting the decoded payload.
            val zxingPayload = prepared?.bitmap?.let(::decodeWithZxing)
            if (zxingPayload != null) {
                val parsed = TBoxQrParser.parse(zxingPayload).getOrNull()
                if (parsed?.origin == TBoxQrOrigin.RECOGNISED) {
                    prepared.recycle()
                    finish(Result.success(parsed))
                    return
                }
                if (parsed != null && unverified == null) unverified = parsed
            }

            scanner.process(image)
                .addOnSuccessListener { codes ->
                    prepared?.recycle()
                    var parseFailure: Throwable? = null
                    for (rawValue in codes.mapNotNull { it.rawValue }) {
                        val parsed = TBoxQrParser.parse(rawValue)
                        val payload = parsed.getOrNull()
                        when {
                            payload?.origin == TBoxQrOrigin.RECOGNISED -> {
                                finish(Result.success(payload))
                                return@addOnSuccessListener
                            }
                            payload != null -> if (unverified == null) unverified = payload
                            else -> parseFailure = parsed.exceptionOrNull()
                        }
                    }
                    attempt(index + 1, parseFailure ?: lastFailure)
                }
                .addOnFailureListener { failure ->
                    prepared?.recycle()
                    attempt(index + 1, failure)
                }
        }

        attempt(index = 0, lastFailure = null)
    }

    private enum class Transform {
        ORIGINAL,
        CONTRAST,
        THRESHOLD_120,
        THRESHOLD_160,
        THRESHOLD_190,
        LOW_PASS_THRESHOLD
    }

    private data class PhotoScanSpec(
        val region: Rect?,
        val transform: Transform
    )

    private data class PreparedImage(
        val image: InputImage,
        val bitmap: Bitmap,
        private val ownedBitmaps: List<Bitmap>
    ) {
        fun recycle() {
            ownedBitmaps.forEach { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun buildPhotoScanSpecs(width: Int, height: Int): List<PhotoScanSpec> {
        val side = min(width, height)
        // Try the centre first: TFT QR codes are normally framed near the centre of the
        // photograph, and this avoids spending the first attempts on mostly empty edges.
        val xPositions = listOf((width - side) / 2, 0, width - side).distinct()
        val yPositions = listOf((height - side) / 2, 0, height - side).distinct()
        val regions = buildList {
            yPositions.forEach { top ->
                xPositions.forEach { left ->
                    add(Rect(left, top, left + side, top + side))
                }
            }
        }

        return buildList {
            // Keep the previous whole-photo preprocessing as a fallback for QR images that
            // already occupy a substantial part of the selected photo.
            add(PhotoScanSpec(region = null, transform = Transform.CONTRAST))
            add(PhotoScanSpec(region = null, transform = Transform.THRESHOLD_160))
            add(PhotoScanSpec(region = null, transform = Transform.LOW_PASS_THRESHOLD))
            regions.forEach { region ->
                add(PhotoScanSpec(region, Transform.ORIGINAL))
                add(PhotoScanSpec(region, Transform.LOW_PASS_THRESHOLD))
                add(PhotoScanSpec(region, Transform.CONTRAST))
                add(PhotoScanSpec(region, Transform.THRESHOLD_120))
                add(PhotoScanSpec(region, Transform.THRESHOLD_160))
                add(PhotoScanSpec(region, Transform.THRESHOLD_190))
            }
        }
    }

    private fun prepare(source: Bitmap, spec: PhotoScanSpec): PreparedImage {
        val cropped = spec.region?.let { cropAndScale(source, it) } ?: source
        val intermediateBitmaps = mutableListOf<Bitmap>()
        val transformed = when (spec.transform) {
            Transform.ORIGINAL -> cropped
            Transform.CONTRAST -> enhanceContrast(cropped)
            Transform.THRESHOLD_120 -> threshold(cropped, 120)
            Transform.THRESHOLD_160 -> threshold(cropped, 160)
            Transform.THRESHOLD_190 -> threshold(cropped, 190)
            Transform.LOW_PASS_THRESHOLD -> {
                val softened = lowPass(cropped)
                intermediateBitmaps += softened
                val contrasted = enhanceContrast(softened)
                intermediateBitmaps += contrasted
                threshold(contrasted, 160)
            }
        }
        val owned = buildList {
            if (cropped !== source) add(cropped)
            addAll(intermediateBitmaps)
            if (transformed !== cropped) add(transformed)
        }
        return PreparedImage(InputImage.fromBitmap(transformed, 0), transformed, owned)
    }

    private fun decodeWithZxing(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true
        )
        return runCatching {
            MultiFormatReader().decode(
                BinaryBitmap(HybridBinarizer(source)),
                hints
            ).text
        }.getOrNull()
    }

    private fun cropAndScale(source: Bitmap, region: Rect): Bitmap {
        val crop = Bitmap.createBitmap(
            source,
            region.left,
            region.top,
            region.width(),
            region.height()
        )
        if (crop.width == PHOTO_SCAN_SIZE && crop.height == PHOTO_SCAN_SIZE) return crop
        return Bitmap.createScaledBitmap(crop, PHOTO_SCAN_SIZE, PHOTO_SCAN_SIZE, true).also {
            crop.recycle()
        }
    }

    /** Removes the TFT subpixel/grid pattern while retaining the much larger QR modules. */
    private fun lowPass(source: Bitmap): Bitmap {
        val reduced = Bitmap.createScaledBitmap(source, LOW_PASS_SIZE, LOW_PASS_SIZE, true)
        return Bitmap.createScaledBitmap(reduced, source.width, source.height, true).also {
            reduced.recycle()
        }
    }

    private fun enhanceContrast(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val matrix = ColorMatrix(
            floatArrayOf(
                2.2f, 0f, 0f, 0f, -150f,
                0f, 2.2f, 0f, 0f, -150f,
                0f, 0f, 2.2f, 0f, -150f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        Canvas(result).drawBitmap(
            source,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            }
        )
        return result
    }

    private fun threshold(source: Bitmap, cutoff: Int): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val luminance =
                (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            pixels[index] = if (luminance < cutoff) Color.BLACK else Color.WHITE
        }
        result.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return result
    }
}
