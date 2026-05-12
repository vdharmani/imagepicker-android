package com.vdharmani.imagepicker

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.annotation.ColorInt
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Single + multi image picker with:
 *  - Camera capture (`captureImage`)
 *  - System Photo Picker for gallery (`uploadImage`, `pickMultipleImages`)
 *  - Optional uCrop cropping with configurable aspect ratio
 *  - EXIF-aware rotation, downscale to a max edge, JPEG compression
 *  - All heavy work off the main thread
 *
 * Construct from an `Activity` or a `Fragment`:
 *
 * ```kotlin
 * // in ComponentActivity / AppCompatActivity:
 * val picker = ImagePickerManager(activity = this, authority = "$packageName.provider") { uri -> ... }
 *
 * // in Fragment:
 * val picker = ImagePickerManager(fragment = this, authority = "${requireContext().packageName}.provider") { uri -> ... }
 * ```
 *
 * Consumers must declare a [FileProvider] in their AndroidManifest whose
 * `authorities` matches [authority], plus an `xml/file_paths.xml` exposing
 * the app's `cacheDir`. See the README.
 *
 * Instantiate **before** the host reaches the STARTED state — internally it
 * calls [ActivityResultCaller.registerForActivityResult].
 */
class ImagePickerManager private constructor(
    private val caller: ActivityResultCaller,
    private val lifecycleOwner: LifecycleOwner,
    private val contextProvider: () -> Context,
    private val authority: String,
    private val config: Config,
    private val multiCallback: ((List<Uri>) -> Unit)?,
    private val callback: ((Uri) -> Unit)?,
) {

    /** Construct for an Activity. */
    constructor(
        activity: ComponentActivity,
        authority: String,
        config: Config = Config(),
        multiCallback: ((List<Uri>) -> Unit)? = null,
        callback: ((Uri) -> Unit)? = null,
    ) : this(
        caller = activity,
        lifecycleOwner = activity,
        contextProvider = { activity },
        authority = authority,
        config = config,
        multiCallback = multiCallback,
        callback = callback,
    )

    /** Construct for a Fragment. */
    constructor(
        fragment: Fragment,
        authority: String,
        config: Config = Config(),
        multiCallback: ((List<Uri>) -> Unit)? = null,
        callback: ((Uri) -> Unit)? = null,
    ) : this(
        caller = fragment,
        lifecycleOwner = fragment,
        contextProvider = { fragment.requireContext() },
        authority = authority,
        config = config,
        multiCallback = multiCallback,
        callback = callback,
    )

    data class Config(
        /** Run the result through uCrop. Set [cropAspect] to control the ratio. */
        val crop: Boolean = false,
        /** Crop aspect ratio (width, height). `null` = free crop. Default 1:1. */
        val cropAspect: Pair<Float, Float>? = 1f to 1f,
        /**
         * If true (default), images are decoded with EXIF rotation applied,
         * downscaled to [maxEdgePx], and re-encoded to JPEG at [jpegQuality].
         * If false, the original [Uri] is returned untouched.
         */
        val compress: Boolean = true,
        /** Longest edge (px) the output should be downscaled to before encoding. */
        val maxEdgePx: Int = 1920,
        /** JPEG output quality, 1..100. Ignored when [compress] is false. */
        val jpegQuality: Int = 75,
        /** Toolbar background for uCrop. */
        @ColorInt val cropToolbarColor: Int = Color.BLACK,
        /** Status-bar tint for uCrop. */
        @ColorInt val cropStatusBarColor: Int = Color.BLACK,
        /** Active control (knob/handle) tint for uCrop. */
        @ColorInt val cropActiveControlsColor: Int = Color.WHITE,
        /** Title shown on the uCrop toolbar. */
        val cropToolbarTitle: String = "Crop Image",
        /** Toast shown when the user denies the camera permission. */
        val cameraPermissionDeniedMessage: String = "Camera permission is required to capture images",
        /** Invoked with `true` before bulk compression begins, `false` after it ends. */
        val onLoadingChanged: ((Boolean) -> Unit)? = null,
        /** Invoked when any picker/crop result is cancelled by the user. */
        val onCancelled: (() -> Unit)? = null,
        /** Invoked when an unexpected failure happens (decode error, IO, etc.). */
        val onError: ((Throwable) -> Unit)? = null,
    )

    private val context: Context get() = contextProvider()

    private var tempCameraUri: Uri? = null
    private var isProcessing = false
    private var pendingMultiMax: Int = Int.MAX_VALUE

    // -- launchers --------------------------------------------------------

    private val cameraPermissionLauncher: ActivityResultLauncher<Array<String>> =
        caller.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result.all { it.value }) {
                launchCameraIntent()
            } else {
                toast(config.cameraPermissionDeniedMessage)
                config.onCancelled?.invoke()
            }
        }

    private val cameraLauncher: ActivityResultLauncher<Intent> =
        caller.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && tempCameraUri != null) {
                processImage(tempCameraUri!!)
            } else {
                config.onCancelled?.invoke()
            }
        }

    private val pickSingleLauncher: ActivityResultLauncher<PickVisualMediaRequest> =
        caller.registerForActivityResult(PickVisualMedia()) { uri ->
            if (uri != null) processImage(uri) else config.onCancelled?.invoke()
        }

    private val pickMultiLauncher: ActivityResultLauncher<PickVisualMediaRequest> =
        caller.registerForActivityResult(PickMultipleVisualMedia()) { uris ->
            if (uris.isNullOrEmpty()) {
                config.onCancelled?.invoke()
                return@registerForActivityResult
            }
            val capped = uris.take(pendingMultiMax)
            config.onLoadingChanged?.invoke(true)
            lifecycleOwner.lifecycleScope.launch {
                val processed = withContext(Dispatchers.IO) {
                    capped.mapNotNull { runCatching { processSync(it) }.getOrNull() }
                }
                config.onLoadingChanged?.invoke(false)
                multiCallback?.invoke(processed)
            }
        }

    private val cropLauncher: ActivityResultLauncher<Intent> =
        caller.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            when {
                result.resultCode == Activity.RESULT_OK && result.data != null ->
                    UCrop.getOutput(result.data!!)
                        ?.let { compressAndReturnImage(it) }
                        ?: run {
                            isProcessing = false
                            config.onError?.invoke(IllegalStateException("uCrop returned no output"))
                        }
                result.resultCode == UCrop.RESULT_ERROR ->
                    result.data?.let {
                        isProcessing = false
                        config.onError?.invoke(UCrop.getError(it) ?: RuntimeException("uCrop error"))
                    }
                else -> {
                    isProcessing = false
                    config.onCancelled?.invoke()
                }
            }
        }

    // -- public API -------------------------------------------------------

    /** Request camera permission (if needed) and launch the camera. */
    fun captureImage() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        cameraPermissionLauncher.launch(perms.toTypedArray())
    }

    /** Launch the system Photo Picker for a single image. */
    fun uploadImage() {
        pickSingleLauncher.launch(
            PickVisualMediaRequest(PickVisualMedia.ImageOnly)
        )
    }

    /**
     * Launch the system Photo Picker for up to [maxItems] images.
     *
     * The picker registers with the platform's default max; we trim the
     * returned list to [maxItems] before invoking [multiCallback].
     */
    fun pickMultipleImages(maxItems: Int) {
        if (maxItems <= 0) return
        pendingMultiMax = maxItems
        pickMultiLauncher.launch(
            PickVisualMediaRequest(PickVisualMedia.ImageOnly)
        )
    }

    // -- internals --------------------------------------------------------

    private fun launchCameraIntent() {
        val ctx = context
        val file = File(ctx.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        tempCameraUri = FileProvider.getUriForFile(ctx, authority, file)

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, tempCameraUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        cameraLauncher.launch(intent)
    }

    private fun processImage(uri: Uri) {
        if (isProcessing) return
        isProcessing = true
        if (config.crop) cropImage(uri) else compressAndReturnImage(uri)
    }

    private fun cropImage(uri: Uri) {
        val ctx = context
        val file = File(ctx.cacheDir, "cropping_${System.currentTimeMillis()}.jpg")
        val destUri = Uri.fromFile(file)
        val options = UCrop.Options().apply {
            setToolbarColor(config.cropToolbarColor)
            setStatusBarColor(config.cropStatusBarColor)
            setActiveControlsWidgetColor(config.cropActiveControlsColor)
            setToolbarTitle(config.cropToolbarTitle)
            setCompressionQuality(100)
        }
        val uCrop = UCrop.of(uri, destUri).withOptions(options).let {
            val aspect = config.cropAspect
            if (aspect != null) it.withAspectRatio(aspect.first, aspect.second) else it.useSourceImageAspectRatio()
        }
        cropLauncher.launch(uCrop.getIntent(ctx))
    }

    private fun compressAndReturnImage(uri: Uri) {
        lifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { processSync(uri) }.getOrElse {
                    config.onError?.invoke(it)
                    uri
                }
            }
            callback?.invoke(result)
            isProcessing = false
        }
    }

    /**
     * Decode → EXIF-rotate → downscale → JPEG-encode into the app cache dir.
     * Called on a background dispatcher. Returns the original uri if
     * [Config.compress] is false.
     */
    private fun processSync(uri: Uri): Uri {
        if (!config.compress) return uri
        val ctx = context
        val resolver = ctx.contentResolver

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

        val outFile = File(ctx.cacheDir, "compressed_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outFile).use { out ->
            oriented.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality.coerceIn(1, 100), out)
        }
        oriented.recycle()
        return Uri.fromFile(outFile)
    }

    private fun calcInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
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

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
