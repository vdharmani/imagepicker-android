package com.vdharmani.imagepicker

/**
 * Top-level alias for [ImagePickerManager.Config]. Lets Compose consumers
 * write `ImagePickerConfig(...)` instead of the nested-class form.
 *
 * Functionally identical to `ImagePickerManager.Config` — both names refer to
 * the same type, so callers can use whichever reads better at the call site.
 */
typealias ImagePickerConfig = ImagePickerManager.Config
