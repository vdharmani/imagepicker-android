package com.vdharmani.imagepicker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia

/**
 * Same as [PickMultipleVisualMedia] but with a runtime-mutable [maxItems].
 *
 * AndroidX's contract bakes `maxItems` into the constructor, but Activity Result
 * launchers must be registered before the host reaches STARTED — well before
 * the consumer knows what max to pass. This contract reads [maxItems] inside
 * [createIntent] (which runs at launch time), so callers update the field right
 * before each `.launch()` and the system Photo Picker UI itself enforces the
 * cap. Without this, the picker shows the platform-default max and the library
 * silently truncates the result, which surprises users.
 */
internal class MutableMaxPickMultipleVisualMedia :
    ActivityResultContract<PickVisualMediaRequest, List<Uri>>() {

    /** Set before each launch. Internally coerced to [[2, platformMax]]. */
    var maxItems: Int = Int.MAX_VALUE

    override fun createIntent(context: Context, input: PickVisualMediaRequest): Intent {
        // AndroidX requires maxItems >= 2 (init) and <= getPickImagesMaxLimit() on API 33+.
        val platformMax = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            MediaStore.getPickImagesMaxLimit()
        } else {
            Int.MAX_VALUE
        }
        val effective = maxItems.coerceIn(2, platformMax)
        return PickMultipleVisualMedia(effective).createIntent(context, input)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> =
        PickMultipleVisualMedia().parseResult(resultCode, intent)
}
