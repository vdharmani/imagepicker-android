package com.vdharmani.imagepicker.ucrop

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vdharmani.imagepicker.CropHandler
import com.vdharmani.imagepicker.CropOptions
import com.yalantis.ucrop.UCrop

/**
 * [CropHandler] implementation backed by [Yalantis/uCrop](https://github.com/Yalantis/uCrop).
 *
 * Pass an instance via `ImagePickerManager.Config.cropHandler` to enable cropping:
 *
 * ```kotlin
 * ImagePickerManager(
 *     activity = this,
 *     authority = "$packageName.provider",
 *     config = Config(cropHandler = UCropHandler()),
 * ) { uri -> /* ... */ }
 * ```
 */
class UCropHandler : CropHandler {

    override fun buildCropIntent(
        context: Context,
        source: Uri,
        destination: Uri,
        options: CropOptions,
    ): Intent {
        val ucropOptions = UCrop.Options().apply {
            setToolbarColor(options.toolbarColor)
            setStatusBarColor(options.statusBarColor)
            setActiveControlsWidgetColor(options.activeControlsColor)
            setToolbarTitle(options.toolbarTitle)
            setCompressionQuality(100)
        }
        val builder = UCrop.of(source, destination).withOptions(ucropOptions).let {
            val aspect = options.aspect
            if (aspect != null) it.withAspectRatio(aspect.first, aspect.second)
            else it.useSourceImageAspectRatio()
        }
        return builder.getIntent(context)
    }

    override fun resolveResult(resultCode: Int, data: Intent?): CropHandler.Result = when {
        resultCode == Activity.RESULT_OK && data != null -> {
            val uri = UCrop.getOutput(data)
            if (uri != null) CropHandler.Result.Success(uri)
            else CropHandler.Result.Failure(IllegalStateException("uCrop returned no output"))
        }
        resultCode == UCrop.RESULT_ERROR -> {
            val cause = data?.let { UCrop.getError(it) } ?: RuntimeException("uCrop error")
            CropHandler.Result.Failure(cause)
        }
        else -> CropHandler.Result.Cancelled
    }
}
