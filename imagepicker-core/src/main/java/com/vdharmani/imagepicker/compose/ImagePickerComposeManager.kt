package com.vdharmani.imagepicker.compose

/**
 * Compose-side counterpart to `ImagePickerManager`. Construct via
 * [rememberImagePicker]; you should not instantiate this class directly.
 *
 * Method names mirror `ImagePickerManager` so a developer who knows one
 * surface can use the other without re-learning the API.
 */
class ImagePickerComposeManager internal constructor(
    private val onCapture: () -> Unit,
    private val onUpload: () -> Unit,
    private val onPickMultiple: (Int) -> Unit,
) {
    /** Request camera permission (if needed) and launch the camera. */
    fun captureImage() = onCapture()

    /** Launch the system Photo Picker for a single image. */
    fun uploadImage() = onUpload()

    /**
     * Launch the system Photo Picker for up to [maxItems] images. The
     * returned list is trimmed to [maxItems] before reaching `onMultiPicked`.
     */
    fun pickMultipleImages(maxItems: Int) = onPickMultiple(maxItems)
}
