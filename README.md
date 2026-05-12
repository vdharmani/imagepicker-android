# imagepicker-android

A small, opinionated image picker for Android: camera + system Photo Picker,
single or multi-select, optional [uCrop](https://github.com/Yalantis/uCrop)
cropping, EXIF-correct rotation, automatic downscale + JPEG compression off
the main thread.

- Works in **Activities and Fragments** (registers against the right lifecycle for each).
- One class, no inheritance, no boilerplate.
- Returns ready-to-upload `Uri`s in your app's `cacheDir`.
- Uses the modern Photo Picker (no `READ_MEDIA_IMAGES` permission needed).

## Install

**Step 1.** Add JitPack to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

**Step 2.** Add the dependency to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.vdharmani:imagepicker-android:1.2.0")
}
```

## FileProvider setup (one-time, in the consuming app)

In `AndroidManifest.xml`:

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.provider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

In `res/xml/file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <cache-path name="cache_images" path="." />
</paths>
```

In `AndroidManifest.xml` (add the camera permission):

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

## Usage — single image (Activity)

```kotlin
class EditProfileActivity : AppCompatActivity() {

    private lateinit var imagePicker: ImagePickerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_profile)

        imagePicker = ImagePickerManager(
            activity = this,
            authority = "$packageName.provider",
            config = ImagePickerManager.Config(crop = true),
        ) { uri ->
            // single-image callback: this is your compressed (optionally cropped) Uri
            profileImageView.setImageURI(uri)
        }

        cameraButton.setOnClickListener { imagePicker.captureImage() }
        galleryButton.setOnClickListener { imagePicker.uploadImage() }
    }
}
```

## Usage — single image (Fragment)

```kotlin
class ProfileFragment : Fragment(R.layout.fragment_profile) {

    private lateinit var imagePicker: ImagePickerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        imagePicker = ImagePickerManager(
            fragment = this,
            authority = "${requireContext().packageName}.provider",
            config = ImagePickerManager.Config(crop = true),
        ) { uri ->
            profileImageView.setImageURI(uri)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraButton.setOnClickListener { imagePicker.captureImage() }
        galleryButton.setOnClickListener { imagePicker.uploadImage() }
    }
}
```

> **Important:** instantiate `ImagePickerManager` in `onCreate` *before* the
> host reaches the STARTED state. Internally it calls
> `registerForActivityResult`, which must happen during `INITIALIZED` or
> `CREATED`.

## Usage — multiple images

```kotlin
imagePicker = ImagePickerManager(
    activity = this,
    authority = "$packageName.provider",
    config = ImagePickerManager.Config(
        onLoadingChanged = { loading ->
            if (loading) showProgressDialog() else dismissProgressDialog()
        }
    ),
    multiCallback = { uris ->
        // up to maxItems compressed Uris
        adapter.addImages(uris)
    },
)

pickButton.setOnClickListener {
    imagePicker.pickMultipleImages(maxItems = 5)
}
```

## Configuration

All optional, via `ImagePickerManager.Config`:

| Field | Default | Purpose |
|---|---|---|
| `crop` | `false` | Run the picked image through uCrop. |
| `cropAspect` | `1f to 1f` | Crop aspect ratio. `null` = free crop. |
| `compress` | `true` | Apply EXIF rotation + downscale + JPEG re-encode. `false` returns the original `Uri`. |
| `maxEdgePx` | `1920` | Longest edge (px) the output is downscaled to before encoding. |
| `jpegQuality` | `75` | Output JPEG quality, 1–100. |
| `cropToolbarColor` | `Color.BLACK` | uCrop toolbar background. |
| `cropStatusBarColor` | `Color.BLACK` | uCrop status bar tint. |
| `cropActiveControlsColor` | `Color.WHITE` | uCrop active controls tint. |
| `cropToolbarTitle` | `"Crop Image"` | uCrop title. |
| `cameraPermissionDeniedMessage` | `"Camera permission is required to capture images"` | Toast when the user denies CAMERA. |
| `onLoadingChanged` | `null` | `(Boolean) -> Unit` — fired before/after bulk compression so you can drive your own progress UI. |
| `onCancelled` | `null` | `() -> Unit` — fired when the user backs out of the camera/gallery/crop. |
| `onError` | `null` | `(Throwable) -> Unit` — fired for unexpected failures (decode/IO/uCrop). |

## Output

Every returned `Uri` points to a file inside the consuming app's `cacheDir`
(JPEG, quality `Config.jpegQuality`). The library does **not** clean these
files up automatically — delete them in `onDestroy` if you care, or rely on
the OS to clear the cache when storage is low.

## License

MIT
