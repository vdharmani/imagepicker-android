# imagepicker-android

A small, opinionated image picker for modern Android — camera, system Photo
Picker, single or multi-select, optional cropping, EXIF-correct rotation, and
automatic downscale + JPEG compression off the main thread.

Works in **Compose, Activities, and Fragments** with a single shared engine.

---

## Highlights

- 🟢 **Compose-first API** (`rememberImagePicker`) plus a matching View-side
  manager (`ImagePickerManager`) — same engine, same configuration.
- 📷 Camera + system Photo Picker. No `READ_MEDIA_IMAGES` permission needed.
- 🔁 Survives process death while the camera is open
  (`rememberSaveable` in Compose, `SavedStateRegistry` on the View side).
- 🧭 EXIF-correct rotation, configurable downscale, JPEG re-encode — all on
  `Dispatchers.IO`. No more sideways camera photos, no OOM on large captures.
- ✂️ **Optional cropping** via a `CropHandler` SPI. Pull in the `imagepicker-ucrop`
  artifact for a uCrop-backed implementation, or write your own. Apps that
  don't crop don't pay for uCrop.
- 🛡️ Launch failures (misconfigured FileProvider, no camera app, no Photo Picker
  backfill) route to `config.onError` instead of crashing the host.

---

## Table of contents

1. [Install](#install)
2. [Manifest setup](#manifest-setup)
3. [Quick start — Compose](#quick-start--compose)
4. [Quick start — Activity](#quick-start--activity)
5. [Quick start — Fragment](#quick-start--fragment)
6. [Multi-image selection](#multi-image-selection)
7. [Cropping](#cropping)
8. [Configuration reference](#configuration-reference)
9. [Output & cleanup](#output--cleanup)
10. [License](#license)

---

## Install

**1.** Register JitPack in your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

**2.** Add the modules you actually need:

```kotlin
dependencies {
    // Required: camera + gallery + compression + EXIF + downscale.
    implementation("com.github.vdharmani.imagepicker-android:imagepicker-core:1.0.3")

    // Optional: only if you want cropping. Adds uCrop transitively.
    implementation("com.github.vdharmani.imagepicker-android:imagepicker-ucrop:1.0.3")
}
```

Compose runtime is a transitive dependency of `imagepicker-core`. View-only
projects pay this cost (~1 MB) once — almost every modern app ships Compose
anyway.

---

## Manifest setup

The library uses a `FileProvider` to hand the camera a writable URI in your
app's `cacheDir`. Declare it once, anywhere in your `<application>` block:

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

And add the camera permission:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

That's the entire setup — no other resources, themes, or services to wire up.

---

## Quick start — Compose

```kotlin
@Composable
fun EditProfileScreen(onSaved: (Uri) -> Unit) {
    val context = LocalContext.current
    val picker = rememberImagePicker(
        authority = "${context.packageName}.provider",
        // Drop the cropHandler line to skip cropping.
        config = ImagePickerConfig(cropHandler = UCropHandler()),
        onPicked = onSaved,
    )

    Row {
        Button(onClick = { picker.captureImage() }) { Text("Camera") }
        Button(onClick = { picker.uploadImage() }) { Text("Gallery") }
    }
}
```

Result launchers registered inside `rememberImagePicker` are scoped to the
current composition, so they're released automatically when the composable
leaves the tree. The temporary camera URI is stored in `rememberSaveable`, so
the result survives configuration changes **and** process death — no manual
`stateKey` needed. You can call `rememberImagePicker` multiple times in the
same screen without collisions.

---

## Quick start — Activity

```kotlin
class EditProfileActivity : AppCompatActivity() {

    private lateinit var imagePicker: ImagePickerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_profile)

        imagePicker = ImagePickerManager(
            activity = this,
            authority = "$packageName.provider",
            config = ImagePickerConfig(cropHandler = UCropHandler()),
        ) { uri ->
            profileImageView.setImageURI(uri)
        }

        cameraButton.setOnClickListener { imagePicker.captureImage() }
        galleryButton.setOnClickListener { imagePicker.uploadImage() }
    }
}
```

> Instantiate `ImagePickerManager` in `onCreate` **before** the activity
> reaches the `STARTED` state — it calls `registerForActivityResult` and
> `SavedStateRegistry.registerSavedStateProvider`, both of which require that.

If you create more than one `ImagePickerManager` in the same host (e.g. a
profile photo plus a business-card scan), pass a unique `stateKey` to each:

```kotlin
val profilePicker = ImagePickerManager(activity = this, authority = ..., stateKey = "profile") { ... }
val cardPicker    = ImagePickerManager(activity = this, authority = ..., stateKey = "card")    { ... }
```

---

## Quick start — Fragment

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

The Fragment constructor registers launchers against the Fragment's own
`ActivityResultRegistry`, so callbacks don't outlive the Fragment.

---

## Multi-image selection

**Compose:**

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

**Activity / Fragment:**

```kotlin
imagePicker = ImagePickerManager(
    activity = this,
    authority = "$packageName.provider",
    config = ImagePickerConfig(
        onLoadingChanged = { loading ->
            if (loading) showProgressDialog() else dismissProgressDialog()
        },
    ),
    multiCallback = { uris -> adapter.addImages(uris) },
)

pickButton.setOnClickListener { imagePicker.pickMultipleImages(maxItems = 5) }
```

`maxItems` caps the returned list — the platform picker enforces its own ceiling
and the library trims anything above your value before invoking the callback.

---

## Cropping

Cropping is **opt-in** and lives in the `imagepicker-ucrop` artifact. Add the
dependency, then hand a `UCropHandler` to your `Config`:

```kotlin
val config = ImagePickerConfig(
    cropHandler = UCropHandler(),
    cropOptions = CropOptions(
        aspect = 1f to 1f,                     // null = free crop
        toolbarTitle = "Crop profile picture",
    ),
)
```

### Writing your own cropper

The `CropHandler` SPI lives in `imagepicker-core`. To plug in a custom UI
(e.g. a Compose-based cropper), implement two methods:

```kotlin
class MyCropHandler : CropHandler {
    override fun buildCropIntent(
        context: Context,
        source: Uri,
        destination: Uri,
        options: CropOptions,
    ): Intent { /* ... */ }

    override fun resolveResult(resultCode: Int, data: Intent?): CropHandler.Result {
        return when (resultCode) {
            Activity.RESULT_OK     -> CropHandler.Result.Success(data!!.data!!)
            Activity.RESULT_CANCELED -> CropHandler.Result.Cancelled
            else                   -> CropHandler.Result.Failure(RuntimeException("..."))
        }
    }
}
```

Then pass `MyCropHandler()` as `Config.cropHandler`. The core library never
touches the cropper directly.

---

## Configuration reference

All optional, via `ImagePickerConfig` (alias of `ImagePickerManager.Config`):

| Field | Default | Purpose |
|---|---|---|
| `cropHandler` | `null` | Provide a `CropHandler` (e.g. `UCropHandler()`) to enable cropping. |
| `cropOptions` | `CropOptions()` | Aspect ratio + cropper colors/title. |
| `compress` | `true` | EXIF rotation + downscale + JPEG re-encode. `false` returns the original `Uri`. |
| `maxEdgePx` | `1920` | Longest edge (px) the output is downscaled to. |
| `jpegQuality` | `75` | Output JPEG quality, 1–100. |
| `cameraPermissionDeniedMessage` | `"Camera permission is required…"` | Toast when CAMERA is denied. |
| `onLoadingChanged` | `null` | `(Boolean) -> Unit` — fires before/after bulk compression. |
| `onCancelled` | `null` | `() -> Unit` — user backed out of camera / gallery / crop. |
| `onError` | `null` | `(Throwable) -> Unit` — unexpected failure (decode, IO, crop, launch). |

`CropOptions`:

| Field | Default | Purpose |
|---|---|---|
| `aspect` | `1f to 1f` | Crop aspect ratio. `null` = free crop. |
| `toolbarColor` | `Color.BLACK` | Cropper toolbar background. |
| `statusBarColor` | `Color.BLACK` | Cropper status-bar tint. |
| `activeControlsColor` | `Color.WHITE` | Cropper active-control tint. |
| `toolbarTitle` | `"Crop Image"` | Cropper title. |

---

## Output & cleanup

Every returned `Uri` points to a file inside the consuming app's `cacheDir`,
encoded as JPEG at `Config.jpegQuality`. The library does **not** sweep these
files itself — either delete them in `onDestroy` (View side) or in a
`DisposableEffect` (Compose), or let Android reclaim the cache when storage is
low.

---

## License

MIT — see [`LICENSE`](LICENSE).
