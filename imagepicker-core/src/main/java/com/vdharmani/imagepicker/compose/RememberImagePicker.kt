package com.vdharmani.imagepicker.compose

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.vdharmani.imagepicker.CropHandler
import com.vdharmani.imagepicker.ImagePickerConfig
import com.vdharmani.imagepicker.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Compose-native image picker.
 *
 * Camera + system Photo Picker (single + multi) + optional crop via the
 * existing [CropHandler] SPI. All result launchers are scoped to the current
 * composition, so they're released automatically when this composable leaves
 * the tree.
 *
 * `tempCameraUri` is persisted with [rememberSaveable], so the camera result
 * survives configuration changes **and** process death — no manual key
 * management, and no multi-instance footgun: you can call `rememberImagePicker`
 * twice in the same screen and the framework auto-disambiguates.
 *
 * @param authority FileProvider authority declared in the consumer's manifest.
 *   Typically `"${context.packageName}.provider"`.
 * @param config Picker configuration (crop handler, compression knobs, callbacks).
 * @param onPicked Called with the processed [Uri] after a single-image flow.
 * @param onMultiPicked Called with up to `maxItems` processed Uris after multi-pick.
 */
@Composable
fun rememberImagePicker(
    authority: String,
    config: ImagePickerConfig = ImagePickerConfig(),
    onPicked: (Uri) -> Unit = {},
    onMultiPicked: (List<Uri>) -> Unit = {},
): ImagePickerComposeManager {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val processor = remember(context) { ImageProcessor(context) }

    // Persisted across config changes and process death.
    var tempCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingMultiMax = remember { mutableIntStateOf(Int.MAX_VALUE) }
    // Guards against concurrent re-entry while a result is being processed.
    val isProcessing = remember { mutableStateOf(false) }

    // -- crop launcher (declared first so the others can reference it) --
    val cropLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val handler = config.cropHandler
        if (handler == null) {
            isProcessing.value = false
            return@rememberLauncherForActivityResult
        }
        when (val r = handler.resolveResult(result.resultCode, result.data)) {
            is CropHandler.Result.Success -> {
                scope.launch {
                    val out = withContext(Dispatchers.IO) {
                        runCatching { processor.process(r.uri, config) }.getOrElse {
                            config.onError?.invoke(it); r.uri
                        }
                    }
                    onPicked(out)
                    isProcessing.value = false
                }
            }
            is CropHandler.Result.Cancelled -> {
                isProcessing.value = false
                config.onCancelled?.invoke()
            }
            is CropHandler.Result.Failure -> {
                isProcessing.value = false
                config.onError?.invoke(r.cause)
            }
        }
    }

    fun processSingle(uri: Uri) {
        if (isProcessing.value) return
        isProcessing.value = true
        val handler = config.cropHandler
        if (handler != null) {
            val destFile = File(context.cacheDir, "cropping_${System.currentTimeMillis()}.jpg")
            val intent = handler.buildCropIntent(context, uri, Uri.fromFile(destFile), config.cropOptions)
            cropLauncher.launch(intent)
        } else {
            scope.launch {
                val out = withContext(Dispatchers.IO) {
                    runCatching { processor.process(uri, config) }.getOrElse {
                        config.onError?.invoke(it); uri
                    }
                }
                onPicked(out)
                isProcessing.value = false
            }
        }
    }

    // -- single gallery --
    val singleLauncher = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) processSingle(uri) else config.onCancelled?.invoke()
    }

    // -- multi gallery --
    val multiLauncher = rememberLauncherForActivityResult(PickMultipleVisualMedia()) { uris ->
        if (uris.isNullOrEmpty()) {
            config.onCancelled?.invoke()
            return@rememberLauncherForActivityResult
        }
        val capped = uris.take(pendingMultiMax.intValue)
        config.onLoadingChanged?.invoke(true)
        scope.launch {
            val processed = withContext(Dispatchers.IO) {
                capped.mapNotNull { runCatching { processor.process(it, config) }.getOrNull() }
            }
            config.onLoadingChanged?.invoke(false)
            onMultiPicked(processed)
        }
    }

    // -- camera --
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uriStr = tempCameraUri
        tempCameraUri = null
        if (result.resultCode == Activity.RESULT_OK && uriStr != null) {
            processSingle(uriStr.toUri())
        } else {
            config.onCancelled?.invoke()
        }
    }

    fun launchCameraIntent() {
        val file = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, authority, file)
        tempCameraUri = uri.toString()
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        cameraLauncher.launch(intent)
    }

    // -- camera permission --
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.all { it.value }) {
            launchCameraIntent()
        } else {
            Toast.makeText(context, config.cameraPermissionDeniedMessage, Toast.LENGTH_SHORT).show()
            config.onCancelled?.invoke()
        }
    }

    // If we restored a tempCameraUri but the camera result never came back
    // (host process was killed mid-capture and the camera Activity finished
    // before we restored), processing is no longer in flight — reset the flag.
    LaunchedEffect(Unit) {
        if (tempCameraUri != null) isProcessing.value = false
    }

    return remember {
        ImagePickerComposeManager(
            onCapture = {
                val perms = mutableListOf(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                cameraPermissionLauncher.launch(perms.toTypedArray())
            },
            onUpload = {
                singleLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
            },
            onPickMultiple = { max ->
                if (max > 0) {
                    pendingMultiMax.intValue = max
                    multiLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                }
            },
        )
    }
}
