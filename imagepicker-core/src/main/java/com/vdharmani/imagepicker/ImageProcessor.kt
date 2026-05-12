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

        val rotation = readExifRotation(uri)
        val scaled = scaleToMaxEdge(sampled, config.maxEdgePx)
        val oriented = applyRotation(scaled, rotation)

        val outFile = File(context.cacheDir, "compressed_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outFile).use { out ->
            oriented.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality.coerceIn(1, 100), out)
        }
        oriented.recycle()
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

    private fun readExifRotation(uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    } catch (_: Exception) {
        0
    }

    private fun applyRotation(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) src.recycle()
        return rotated
    }
}
