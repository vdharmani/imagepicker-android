package com.vdharmani.imagepicker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorInt
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Single + multi image picker with optional uCrop cropping and JPEG compression.
 *
 * Consumers must declare a [FileProvider] in their AndroidManifest whose
 * `authorities` matches the [authority] passed here, and an `xml/file_paths.xml`
 * resource that exposes the app's `cacheDir` (a `<cache-path>` entry).
 *
 * Instantiate this from `Activity.onCreate` **before** the activity reaches the
 * STARTED state — internally it calls [ComponentActivity.registerForActivityResult].
 */
class ImagePickerManager(
    private val activity: ComponentActivity,
    private val authority: String,
    private val config: Config = Config(),
    private val multiCallback: ((List<Uri>) -> Unit)? = null,
    private val callback: ((Uri) -> Unit)? = null,
) {

    data class Config(
        /** Run the result through uCrop with a 1:1 aspect ratio. */
        val crop: Boolean = false,
        /** JPEG compression quality, 1..100. */
        val jpegQuality: Int = 75,
        /** Toolbar background for uCrop. */
        @ColorInt val cropToolbarColor: Int = Color.BLACK,
        /** Status-bar tint for uCrop. */
        @ColorInt val cropStatusBarColor: Int = Color.BLACK,
        /** Active control (knob/handle) tint for uCrop. */
        @ColorInt val cropActiveControlsColor: Int = Color.WHITE,
        /** Title shown on the uCrop toolbar. */
        val cropToolbarTitle: String = "Crop Image",
        /** Title for the chooser presented on single-image gallery picks. */
        val galleryChooserTitle: String = "Select Image",
        /** Title for the chooser presented on multi-image gallery picks. */
        val multiGalleryChooserTitle: String = "Select Images",
        /** Toast shown when the user denies the camera permission. */
        val cameraPermissionDeniedMessage: String = "Camera permission is required to capture images",
        /** Invoked with `true` before bulk compression begins, `false` after it ends. */
        val onLoadingChanged: ((Boolean) -> Unit)? = null,
    )

    private var tempCameraUri: Uri? = null
    private var isProcessing = false
    private var pendingMultiMax: Int = Int.MAX_VALUE

    private val cameraPermissionLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result.all { it.value }) {
                launchCameraIntent()
            } else {
                toast(config.cameraPermissionDeniedMessage)
            }
        }

    private val cameraLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && tempCameraUri != null) {
                processImage(tempCameraUri!!)
            }
        }

    private val galleryLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { processImage(it) }
            }
        }

    private val cropLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                UCrop.getOutput(result.data!!)?.let { compressAndReturnImage(it) }
            } else {
                isProcessing = false
            }
        }

    private val multiGalleryLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val uris = mutableListOf<Uri>()
            val clip = data.clipData
            if (clip != null) {
                val count = minOf(clip.itemCount, pendingMultiMax)
                for (i in 0 until count) uris.add(clip.getItemAt(i).uri)
            } else {
                data.data?.let { uris.add(it) }
            }
            if (uris.isEmpty()) return@registerForActivityResult
            config.onLoadingChanged?.invoke(true)
            activity.lifecycleScope.launch {
                val compressed = withContext(Dispatchers.IO) {
                    uris.mapNotNull { compressImage(it) }
                }
                config.onLoadingChanged?.invoke(false)
                multiCallback?.invoke(compressed)
            }
        }

    /** Request camera permission (if needed) and launch the camera. */
    fun captureImage() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        cameraPermissionLauncher.launch(perms.toTypedArray())
    }

    /** Launch the system gallery picker for a single image. */
    fun uploadImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        galleryLauncher.launch(Intent.createChooser(intent, config.galleryChooserTitle))
    }

    /**
     * Launch the system gallery picker for up to [maxItems] images.
     * The picker itself can't enforce the cap on every device, so we trim
     * the returned URIs to [maxItems] before invoking [multiCallback].
     */
    fun pickMultipleImages(maxItems: Int) {
        if (maxItems <= 0) return
        pendingMultiMax = maxItems
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        multiGalleryLauncher.launch(Intent.createChooser(intent, config.multiGalleryChooserTitle))
    }

    private fun launchCameraIntent() {
        val file = File(activity.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        tempCameraUri = FileProvider.getUriForFile(activity, authority, file)

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
        val file = File(activity.cacheDir, "cropping_${System.currentTimeMillis()}.jpg")
        val destUri = Uri.fromFile(file)
        val options = UCrop.Options().apply {
            setToolbarColor(config.cropToolbarColor)
            setStatusBarColor(config.cropStatusBarColor)
            setActiveControlsWidgetColor(config.cropActiveControlsColor)
            setToolbarTitle(config.cropToolbarTitle)
            setCompressionQuality(100)
        }
        val uCrop = UCrop.of(uri, destUri)
            .withAspectRatio(1f, 1f)
            .withOptions(options)
            .getIntent(activity)
        cropLauncher.launch(uCrop)
    }

    private fun compressAndReturnImage(uri: Uri) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { compressImage(uri) }.getOrNull() ?: uri
            }
            callback?.invoke(result)
            isProcessing = false
        }
    }

    private fun compressImage(uri: Uri): Uri? {
        val bitmap = activity.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: return null
        val outputFile = File(activity.cacheDir, "compressed_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outputFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality.coerceIn(1, 100), out)
        }
        bitmap.recycle()
        return Uri.fromFile(outputFile)
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
}
