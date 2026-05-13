package com.vdharmani.imagepicker

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single + multi image picker with:
 *  - Camera capture (`captureImage`)
 *  - System Photo Picker for gallery (`uploadImage`, `pickMultipleImages`)
 *  - Optional cropping via a pluggable [CropHandler] (see `imagepicker-ucrop`)
 *  - EXIF-aware rotation, downscale to a max edge, JPEG compression
 *  - All heavy work off the main thread
 *  - Survives process death while the camera is open (via SavedStateRegistry)
 *
 * Construct from an Activity or a Fragment. If you have multiple
 * `ImagePickerManager` instances in the same host (e.g. profile + cover photo),
 * pass a unique [stateKey] to each so their saved state doesn't collide.
 *
 * Consumers must declare a [FileProvider] in their AndroidManifest whose
 * `authorities` matches [authority], plus an `xml/file_paths.xml` exposing the
 * app's `cacheDir`. See the README.
 *
 * Must be instantiated **before** the host reaches the STARTED state — both
 * `registerForActivityResult` and `SavedStateRegistry.registerSavedStateProvider`
 * require that.
 */
class ImagePickerManager private constructor(
    private val caller: ActivityResultCaller,
    private val lifecycleOwner: LifecycleOwner,
    private val savedStateRegistry: SavedStateRegistry,
    private val contextProvider: () -> Context,
    private val authority: String,
    private val stateKey: String,
    private val config: Config,
    private val multiCallback: ((List<Uri>) -> Unit)?,
    private val callback: ((Uri) -> Unit)?,
) {

    /** Construct for an Activity. */
    constructor(
        activity: ComponentActivity,
        authority: String,
        config: Config = Config(),
        stateKey: String = DEFAULT_STATE_KEY,
        multiCallback: ((List<Uri>) -> Unit)? = null,
        callback: ((Uri) -> Unit)? = null,
    ) : this(
        caller = activity,
        lifecycleOwner = activity,
        savedStateRegistry = activity.savedStateRegistry,
        contextProvider = { activity },
        authority = authority,
        stateKey = stateKey,
        config = config,
        multiCallback = multiCallback,
        callback = callback,
    )

    /** Construct for a Fragment. */
    constructor(
        fragment: Fragment,
        authority: String,
        config: Config = Config(),
        stateKey: String = DEFAULT_STATE_KEY,
        multiCallback: ((List<Uri>) -> Unit)? = null,
        callback: ((Uri) -> Unit)? = null,
    ) : this(
        caller = fragment,
        lifecycleOwner = fragment,
        savedStateRegistry = (fragment as SavedStateRegistryOwner).savedStateRegistry,
        contextProvider = { fragment.requireContext() },
        authority = authority,
        stateKey = stateKey,
        config = config,
        multiCallback = multiCallback,
        callback = callback,
    )

    data class Config(
        /**
         * Provide a [CropHandler] (e.g. `UCropHandler` from `imagepicker-ucrop`)
         * to enable cropping after a single-image pick. `null` (default) = no crop.
         */
        val cropHandler: CropHandler? = null,
        /** Options passed to [CropHandler.buildCropIntent]. */
        val cropOptions: CropOptions = CropOptions(),
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
        /** Toast shown when the user denies the camera permission. */
        val cameraPermissionDeniedMessage: String = "Camera permission is required to capture images",
        /** Invoked with `true` before bulk compression begins, `false` after. */
        val onLoadingChanged: ((Boolean) -> Unit)? = null,
        /** Invoked when any picker/crop result is cancelled by the user. */
        val onCancelled: (() -> Unit)? = null,
        /** Invoked when an unexpected failure happens (decode error, IO, crop, etc.). */
        val onError: ((Throwable) -> Unit)? = null,
    )

    private val context: Context get() = contextProvider()
    // Use the application context so the processor doesn't pin the Activity/Fragment
    // host, and so it's safe to lazy-init even if the host has detached by then.
    private val processor: ImageProcessor by lazy { ImageProcessor(context.applicationContext) }

    private var tempCameraUri: Uri? = null
    private var isProcessing = false
    private var pendingMultiMax: Int = Int.MAX_VALUE
    private val multiContract = MutableMaxPickMultipleVisualMedia()

    init {
        // Register the provider eagerly — safe to call any time before STARTED.
        try {
            savedStateRegistry.registerSavedStateProvider(stateKey) {
                Bundle().apply {
                    tempCameraUri?.let { putString(KEY_TEMP_CAMERA_URI, it.toString()) }
                }
            }
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(
                "Another ImagePickerManager is already registered with stateKey=\"$stateKey\" " +
                    "in this host. If you have multiple pickers in the same Activity/Fragment, " +
                    "pass a unique stateKey to each, e.g. ImagePickerManager(..., stateKey = \"profile\").",
                e,
            )
        }

        // Restoration: `consumeRestoredStateForKey` only returns the saved bundle
        // AFTER `SavedStateRegistry.performRestore()` runs, which happens during
        // `super.onCreate(savedInstanceState)`. Consumers commonly construct the
        // manager as a field initializer (before super.onCreate) — without this
        // observer, the restored URI is silently lost. If we're already past
        // CREATED at construction time, restore immediately; otherwise wait for
        // the ON_CREATE event.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            consumeRestoredState()
        } else {
            lifecycleOwner.lifecycle.addObserver(object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    if (event == Lifecycle.Event.ON_CREATE) {
                        consumeRestoredState()
                        source.lifecycle.removeObserver(this)
                    }
                }
            })
        }
    }

    private fun consumeRestoredState() {
        val restored = savedStateRegistry.consumeRestoredStateForKey(stateKey)
        restored?.getString(KEY_TEMP_CAMERA_URI)?.let { tempCameraUri = it.toUri() }
    }

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
            val uri = tempCameraUri
            tempCameraUri = null
            if (result.resultCode == Activity.RESULT_OK && uri != null) {
                processImage(uri)
            } else {
                config.onCancelled?.invoke()
            }
        }

    private val pickSingleLauncher: ActivityResultLauncher<PickVisualMediaRequest> =
        caller.registerForActivityResult(PickVisualMedia()) { uri ->
            if (uri != null) processImage(uri) else config.onCancelled?.invoke()
        }

    private val pickMultiLauncher: ActivityResultLauncher<PickVisualMediaRequest> =
        caller.registerForActivityResult(multiContract) { uris ->
            if (uris.isNullOrEmpty()) {
                config.onCancelled?.invoke()
                return@registerForActivityResult
            }
            val capped = uris.take(pendingMultiMax)
            config.onLoadingChanged?.invoke(true)
            lifecycleOwner.lifecycleScope.launch {
                val processed = withContext(Dispatchers.IO) {
                    capped.mapNotNull { runCatching { processor.process(it, config) }.getOrNull() }
                }
                config.onLoadingChanged?.invoke(false)
                multiCallback?.invoke(processed)
            }
        }

    private val cropLauncher: ActivityResultLauncher<Intent> =
        caller.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val handler = config.cropHandler
            if (handler == null) {
                isProcessing = false
                return@registerForActivityResult
            }
            when (val r = handler.resolveResult(result.resultCode, result.data)) {
                is CropHandler.Result.Success -> compressAndReturnImage(r.uri)
                is CropHandler.Result.Cancelled -> {
                    isProcessing = false
                    config.onCancelled?.invoke()
                }
                is CropHandler.Result.Failure -> {
                    isProcessing = false
                    config.onError?.invoke(r.cause)
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
        safeLaunch { cameraPermissionLauncher.launch(perms.toTypedArray()) }
    }

    /** Launch the system Photo Picker for a single image. */
    fun uploadImage() {
        safeLaunch {
            pickSingleLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        }
    }

    /**
     * Launch the system Photo Picker for up to [maxItems] images. The picker
     * UI enforces the cap; the returned list is also trimmed defensively
     * before [multiCallback] runs.
     */
    fun pickMultipleImages(maxItems: Int) {
        if (maxItems <= 0) return
        pendingMultiMax = maxItems
        multiContract.maxItems = maxItems
        safeLaunch {
            pickMultiLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        }
    }

    // -- internals --------------------------------------------------------

    /**
     * Runs [block] guarding against `ActivityNotFoundException` (no app to
     * handle the intent — common on stripped emulators, Wear/TV builds, or
     * devices without a camera) and any other launch-time exceptions. Routes
     * failures through [Config.onError] instead of crashing the host.
     */
    private inline fun safeLaunch(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            config.onError?.invoke(t)
        }
    }

    private fun launchCameraIntent() {
        val ctx = context
        val file = File(ctx.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        val uri = try {
            FileProvider.getUriForFile(ctx, authority, file)
        } catch (e: IllegalArgumentException) {
            // Misconfigured FileProvider — authority mismatch, or file_paths.xml
            // doesn't expose the cache dir. Surface a useful error instead of crashing.
            config.onError?.invoke(
                IllegalStateException(
                    "FileProvider misconfigured for authority=\"$authority\". " +
                        "Make sure your manifest declares a <provider> with this authority " +
                        "and your file_paths.xml exposes <cache-path>.",
                    e,
                )
            )
            return
        }
        tempCameraUri = uri

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        safeLaunch { cameraLauncher.launch(intent) }
    }

    private fun processImage(uri: Uri) {
        if (isProcessing) return
        isProcessing = true
        val handler = config.cropHandler
        if (handler != null) {
            launchCrop(handler, uri)
        } else {
            compressAndReturnImage(uri)
        }
    }

    private fun launchCrop(handler: CropHandler, source: Uri) {
        val ctx = context
        val dest = Uri.fromFile(File(ctx.cacheDir, "cropping_${System.currentTimeMillis()}.jpg"))
        val intent = handler.buildCropIntent(ctx, source, dest, config.cropOptions)
        try {
            cropLauncher.launch(intent)
        } catch (t: Throwable) {
            isProcessing = false
            config.onError?.invoke(t)
        }
    }

    private fun compressAndReturnImage(uri: Uri) {
        lifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { processor.process(uri, config) }.getOrElse {
                    config.onError?.invoke(it)
                    uri
                }
            }
            callback?.invoke(result)
            isProcessing = false
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val DEFAULT_STATE_KEY: String = "ImagePickerManager"
        private const val KEY_TEMP_CAMERA_URI: String = "tempCameraUri"
    }
}
