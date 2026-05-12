# imagepicker-android

A small, opinionated image picker for Android: camera + system Photo Picker,
single or multi-select, optional cropping, EXIF-correct rotation, automatic
downscale + JPEG compression off the main thread.

- Works in **Activities, Fragments, and Compose** (registers against the right lifecycle for each).
- **Survives process death** while the camera is open (`SavedStateRegistry` on the View side; `rememberSaveable` on the Compose side).
- One class, no inheritance, no boilerplate.
- Returns ready-to-upload `Uri`s in your app's `cacheDir`.
- Uses the modern Photo Picker (no `READ_MEDIA_IMAGES` permission needed).
- **Pluggable cropping** — core has zero crop dependency; opt in to uCrop with one extra artifact.

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

**Step 2.** Add the modules you actually need:

```kotlin
dependencies {
    // Required: camera + gallery + compression + EXIF + downscale.
    implementation("com.github.vdharmani.imagepicker-android:imagepicker-core:1.0.1")

    // Optional: only if you want cropping (uses uCrop under the hood).
    implementation("com.github.vdharmani.imagepicker-android:imagepicker-ucrop:1.0.1")
}
```

Apps that don't crop **don't pay for uCrop** (~200KB).

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
            // Provide a CropHandler to enable cropping. Drop it to skip.
            config = ImagePickerManager.Config(cropHandler = UCropHandler()),
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
> `registerForActivityResult` and `SavedStateRegistry.registerSavedStateProvider`,
> both of which require that.

## Usage — Compose

```kotlin
@Composable
fun EditProfileScreen(onSaved: (Uri) -> Unit) {
    val context = LocalContext.current
    val picker = rememberImagePicker(
        authority = "${context.packageName}.provider",
        // Optional cropping — drop this line to skip crop.
        config = ImagePickerConfig(cropHandler = UCropHandler()),
        onPicked = { uri -> onSaved(uri) },
    )

    Row {
        Button(onClick = { picker.captureImage() }) { Text("Camera") }
        Button(onClick = { picker.uploadImage() }) { Text("Gallery") }
    }
}
```

Multi-pick in Compose:

```kotlin
var loading by remember { mutableStateOf(false) }
val picker = rememberImagePicker(
    authority = "${context.packageName}.provider",
    config = ImagePickerConfig(onLoadingChanged = { loading = it }),
    onMultiPicked = { uris -> viewModel.addImages(uris) },
)

Button(onClick = { picker.pickMultipleImages(maxItems = 5) }) {
    Text(if (loading) "Compressing…" else "Add up to 5")
}
```

Result launchers registered inside `rememberImagePicker` are scoped to the
current composition, so they're released automatically when the composable
leaves the tree. `tempCameraUri` is stored in `rememberSaveable`, so the
camera result survives configuration changes **and** process death — no manual
`stateKey` needed, and you can call `rememberImagePicker` multiple times in the
same screen without collision.

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
        adapter.addImages(uris)   // up to maxItems compressed Uris
    },
)

pickButton.setOnClickListener {
    imagePicker.pickMultipleImages(maxItems = 5)
}
```

## Multiple pickers in the same screen

If you have two `ImagePickerManager` instances in the same Activity/Fragment
(e.g. profile photo *and* business-card scan), give each a unique `stateKey`
so their saved camera-Uri state doesn't collide on process death:

```kotlin
val profilePicker = ImagePickerManager(activity = this, authority = ..., stateKey = "profile") { ... }
val cardPicker    = ImagePickerManager(activity = this, authority = ..., stateKey = "card")    { ... }
```

## Configuration

All optional, via `ImagePickerManager.Config`:

| Field | Default | Purpose |
|---|---|---|
| `cropHandler` | `null` | Provide a `CropHandler` (e.g. `UCropHandler()`) to enable cropping. `null` = no crop. |
| `cropOptions` | `CropOptions()` | Aspect ratio + uCrop colors/title. |
| `compress` | `true` | Apply EXIF rotation + downscale + JPEG re-encode. `false` returns the original `Uri`. |
| `maxEdgePx` | `1920` | Longest edge (px) the output is downscaled to. |
| `jpegQuality` | `75` | Output JPEG quality, 1–100. |
| `cameraPermissionDeniedMessage` | `"Camera permission is required to capture images"` | Toast when the user denies CAMERA. |
| `onLoadingChanged` | `null` | `(Boolean) -> Unit` — drives your own progress UI during bulk compression. |
| `onCancelled` | `null` | `() -> Unit` — user backed out of camera/gallery/crop. |
| `onError` | `null` | `(Throwable) -> Unit` — unexpected failure (decode/IO/crop). |

`CropOptions` fields:

| Field | Default | Purpose |
|---|---|---|
| `aspect` | `1f to 1f` | Crop aspect ratio. `null` = free crop. |
| `toolbarColor` | `Color.BLACK` | Crop UI toolbar background. |
| `statusBarColor` | `Color.BLACK` | Crop UI status bar tint. |
| `activeControlsColor` | `Color.WHITE` | Crop UI active controls tint. |
| `toolbarTitle` | `"Crop Image"` | Crop UI title. |

## Writing your own `CropHandler`

If uCrop doesn't fit (e.g. you want a Compose-based cropper), implement the
SPI directly:

```kotlin
class MyCropHandler : CropHandler {
    override fun buildCropIntent(context, source, destination, options): Intent { ... }
    override fun resolveResult(resultCode, data): CropHandler.Result { ... }
}
```

Then pass `MyCropHandler()` as `Config.cropHandler`.

## Output

Every returned `Uri` points to a file inside the consuming app's `cacheDir`
(JPEG, quality `Config.jpegQuality`). The library does **not** clean these
files up automatically — delete them in `onDestroy` if you care, or rely on
the OS to clear the cache when storage is low.

## License

MIT
