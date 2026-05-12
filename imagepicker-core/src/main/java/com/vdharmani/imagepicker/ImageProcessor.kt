package com.vdharmani.imagepicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Pure image-processing helper shared by [ImagePickerManager] and the
 * Compose-side picker.
 *
 * Reads the image at [Uri], applies EXIF rotation, downscales to the requested
 * max edge, and writes the result as JPEG into the consumer's `cacheDir`.
 * Everything in here is synchronous and CPU/IO-bound — callers should invoke
 * it from a background dispatcher.
 */
class ImageProcessor(private val context: Context) {

    /**
     * Process [uri] using the rules in [config] and return a new [Uri] pointing
     * at the compressed file. If [ImagePickerManager.Config.compress] is
     * `false`, returns [uri] unchanged.
     */
    fun process(uri: Uri, config: ImagePickerManager.Config): Uri {
        if (!config.compress) return uri
        val resolver = context.contentResolver

        // First pass — get raw dimensions without allocating pixels.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            error("Could not decode image bounds for $uri")
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, config.maxEdgePx)
        }
        val sampled = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: error("Could not decode image for $uri")

        val transform = readExifTransform(uri)
        val scaled = scaleToMaxEdge(sampled, config.maxEdgePx)
        val oriented = applyTransform(scaled, transform)

        val outFile = File(context.cacheDir, "compressed_${System.currentTimeMillis()}.jpg")
        val ok = FileOutputStream(outFile).use { out ->
            oriented.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality.coerceIn(1, 100), out)
        }
        oriented.recycle()
        if (!ok) {
            // Compress returned false — codec failure or recycled bitmap. The output
            // file is almost certainly empty/corrupt; clean it up rather than handing
            // back a bad Uri.
            outFile.delete()
            error("Bitmap.compress returned false for $uri (possibly a codec failure)")
        }
        return Uri.fromFile(outFile)
    }

    internal fun calcInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (maxEdge <= 0) return 1
        var sample = 1
        val halfW = width / 2
        val halfH = height / 2
        while (halfW / sample >= maxEdge && halfH / sample >= maxEdge) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        if (maxEdge <= 0) return src
        val longEdge = max(src.width, src.height)
        if (longEdge <= maxEdge) return src
        val scale = maxEdge.toFloat() / longEdge
        val newW = (src.width * scale).toInt().coerceAtLeast(1)
        val newH = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, newW, newH, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    /**
     * Reads `TAG_ORIENTATION` and returns a [Matrix] that undoes it, or `null`
     * if the file has no EXIF, the orientation is `ORIENTATION_NORMAL`, or
     * EXIF parsing fails. Covers all eight EXIF orientations including the
     * four flip/transpose cases that pure-rotation handlers miss.
     */
    private fun readExifTransform(uri: Uri): Matrix? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                    Matrix().apply { postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_180 ->
                    Matrix().apply { postRotate(180f) }
                ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                    Matrix().apply { postScale(1f, -1f) }
                ExifInterface.ORIENTATION_TRANSPOSE ->
                    Matrix().apply { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 ->
                    Matrix().apply { postRotate(90f) }
                ExifInterface.ORIENTATION_TRANSVERSE ->
                    Matrix().apply { postRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 ->
                    Matrix().apply { postRotate(270f) }
                else -> null
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun applyTransform(src: Bitmap, matrix: Matrix?): Bitmap {
        if (matrix == null) return src
        val transformed = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (transformed !== src) src.recycle()
        return transformed
    }
}
