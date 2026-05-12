package com.vdharmani.imagepicker

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import androidx.annotation.ColorInt

/**
 * Plug-in interface for image cropping.
 *
 * `imagepicker-core` knows nothing about uCrop or any other cropper. To enable
 * cropping, add the `imagepicker-ucrop` dependency (or write your own
 * implementation) and pass an instance via [ImagePickerManager.Config.cropHandler].
 *
 * Lifecycle:
 *  1. [buildCropIntent] is called when the manager has a source [Uri] and needs
 *     to launch the crop UI. The returned Intent is launched via
 *     `ActivityResultContracts.StartActivityForResult`.
 *  2. On result, [resolveResult] is called with the result code + data. It
 *     returns one of the [Result] subtypes describing what happened.
 */
interface CropHandler {

    sealed interface Result {
        /** Cropping succeeded — [uri] points to the cropped image. */
        data class Success(val uri: Uri) : Result

        /** User cancelled the crop UI. */
        data object Cancelled : Result

        /** Crop UI reported an error. */
        data class Failure(val cause: Throwable) : Result
    }

    fun buildCropIntent(
        context: Context,
        source: Uri,
        destination: Uri,
        options: CropOptions,
    ): Intent

    fun resolveResult(resultCode: Int, data: Intent?): Result
}

/**
 * Visual options the cropper should honour where supported.
 *
 * Not every [CropHandler] supports every option — implementations should
 * gracefully ignore anything they can't apply.
 */
data class CropOptions(
    /** Crop aspect ratio (width, height). `null` = free crop. */
    val aspect: Pair<Float, Float>? = 1f to 1f,
    @ColorInt val toolbarColor: Int = Color.BLACK,
    @ColorInt val statusBarColor: Int = Color.BLACK,
    @ColorInt val activeControlsColor: Int = Color.WHITE,
    val toolbarTitle: String = "Crop Image",
)
