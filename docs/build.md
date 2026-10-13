# Build

## Requirements

| Tool | Version |
| --- | --- |
| Android Gradle Plugin | 9.3.1 |
| Gradle | 9.5.0 (via wrapper) |
| Kotlin | 2.2.10 |
| JDK | 17 |
| compileSdk / targetSdk | 37 |
| minSdk | 26 |

`minSdk` is 26 so the project can rely on adaptive launcher icons and modern
camera2 behaviour without legacy fallbacks. CameraX itself supports API 21+, so
lowering it is possible — you would need to add pre-API-26 launcher icon PNGs.

## Build

You need the Android SDK (compileSdk 37 + build-tools 37) and a JDK 17. The
simplest route is Android Studio, which supplies both: open the project folder,
let it sync, and it writes `local.properties` for you. From the command line:

```bash
# Point the build at your SDK (or let Android Studio create this file).
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :app:assembleDebug
```

`local.properties` is machine-specific and git-ignored — it is the one file you
must create yourself before a fresh clone will build.

The ABI split in `app/build.gradle.kts` produces one APK per ABI
(`arm64-v8a`, `armeabi-v7a`, `x86_64`) instead of a single universal one,
because the OpenCV AAR carries native libraries for every ABI. They land in
`app/build/outputs/apk/debug/`. Because the split makes several APKs out of one
variant, there is no per-ABI install task — install to a connected device with

```bash
./gradlew :app:installDebug          # picks the APK matching the device
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk   # or by hand
```

CI passes `-PonlyAbi=arm64-v8a`, so only that split is packaged there.

Debug builds are signed with the local debug keystore and carry the
`.debug` application id suffix, so they sideload and can sit alongside a release
install. `assembleRelease` produces an **unsigned** APK — add a `signingConfigs`
block with your own keystore before using it for anything installable.

### Without a local SDK

`.github/workflows/android.yml` runs on demand from the Actions tab and on
every pull request, assembles the debug APK, and uploads it as a workflow
artifact (`photosphere-debug-apk-arm64`). Download it from the run's summary
page, unzip, and `adb install` it — no local toolchain needed.

## Test builds

Debug APKs are published as
[GitHub Release](https://github.com/theclumsypirate/photosphere-android/releases) assets
rather than committed to the repository — a debug build of this app is ~43 MB,
and committing one per rebuild grows the history by that much every time.

CI builds `arm64-v8a` only (see [Without a local SDK](#without-a-local-sdk));
take `app-arm64-v8a-debug.apk` for any modern phone. Automated agents must not
build APKs locally — see [AGENTS.md](../AGENTS.md).

## Test

```bash
./gradlew :app:testDebugUnitTest   # local JVM tests, no device
./gradlew :app:lintDebug           # Android lint
```

The unit tests cover the parts of the pipeline that are pure Kotlin: the sphere
target plan, the projection maths, the alignment gate, the capture
coordinator's rule and its serialisation of capture against undo and restart,
the equirectangular fit, the stitch status mapping and the GPano XMP splice.
Everything that needs real
hardware — the camera, the rotation vector sensor, OpenCV's native stitch — is
verified on a device. The **Orientation debug** button in the top-right of debug
builds exists for exactly that: it puts the live sensor readout on screen so a
leaked or stopped listener is visible immediately.

An emulator is enough to check that the app launches, the permission gate works
and the UI renders, but not to capture a sphere: the emulated camera and
synthetic sensors will not produce frames that stitch. Guided capture needs a
physical device with a gyroscope.

## Dependencies

Versions live in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml).

- **Jetpack Compose + Material 3** — `androidx.compose:compose-bom`,
  `material3`, `activity-compose`, `lifecycle-runtime-compose`
- **CameraX** — `camera-core`, `camera-camera2`, `camera-lifecycle`,
  `camera-view` (all pinned to one version; mixing versions breaks binding)
- **ExifInterface** — `androidx.exifinterface:exifinterface`
- **OpenCV** — `org.opencv:opencv` (see below)

### OpenCV

**Option A — Maven artifact (what this project uses).**

OpenCV has published its official Android SDK to Maven Central since 4.9.0, so
nothing needs to be downloaded by hand:

```kotlin
implementation(libs.opencv)   // org.opencv:opencv:4.12.0
```

Note the coordinates: the group is `org.opencv` and the artifact is `opencv`
(not `opencv-android`, which is an unofficial mirror). Note also what is *not*
in it: there is no `org.opencv.stitching` — see
[Stitching](stitching.md) for what this project does instead. The AAR is ~120 MB
because it bundles native libraries for all four ABIs — the ABI split above
keeps the installed APK closer to ~30 MB.

**Option B — local SDK module.**

Use this if you need a custom OpenCV build (extra contrib modules, a smaller
build with only `stitching` and `features2d`, or a version not on Maven).

1. Download the Android SDK from <https://opencv.org/releases/> and unpack it to
   `third_party/opencv-android-sdk/`.
2. Uncomment the two `include`/`projectDir` lines at the bottom of
   [`settings.gradle.kts`](../settings.gradle.kts).
3. In `app/build.gradle.kts`, replace `implementation(libs.opencv)` with
   `implementation(project(":opencv"))`.
4. The bundled SDK module often pins an old `compileSdk`/AGP combination. If the
   sync fails, edit `third_party/opencv-android-sdk/sdk/build.gradle` to match
   the values in `app/build.gradle.kts`.

Either way, the native library is loaded once in
[`PhotoSphereApplication`](../app/src/main/java/com/n30dyn4m1c/photosphere/PhotoSphereApplication.kt)
via `OpenCVLoader.initLocal()`. The old "OpenCV Manager" APK is not involved.
`proguard-rules.pro` keeps `org.opencv.**` so release builds survive the JNI
lookups.

## Project layout

```
app/src/main/java/com/n30dyn4m1c/photosphere/
├── MainActivity.kt              # entry point + runtime permission gate
├── PhotoSphereApplication.kt    # OpenCV native init
├── camera/
│   ├── PhotoSphereCameraScreen.kt # CameraX preview + the capture loop
│   ├── SphereCaptureCoordinator.kt # the capture rule and the run's position
│   ├── TargetOverlay.kt         # reticle, target markers, dwell arc
│   ├── SphereTarget.kt          # the sphere's target list
│   ├── TargetSelection.kt       # which target is active (any-order capture)
│   ├── SphereProjection.kt      # attitude + target -> screen position
│   ├── AlignmentGate.kt         # 2° / 300 ms shutter rule
│   ├── CameraOptics.kt          # field of view from the camera's optics
│   └── CaptureFeedback.kt       # shutter sound + haptic tick
├── sensor/
│   ├── OrientationTracker.kt    # game rotation vector -> yaw/pitch/roll StateFlow
│   ├── OrientationState.kt      # lifecycle-aware Compose bindings
│   └── OrientationDebugScreen.kt# live readout for on-device verification
├── stitching/
│   ├── PhotoSphereStitcher.kt   # the stitch: read, render, statuses, progress
│   ├── SphericalGeometry.kt     # camera basis, canvas mapping, frame footprints
│   ├── MultibandBlender.kt      # Laplacian-pyramid blend: levels, reconstruction
│   └── EquirectangularRenderer.kt # projecting frames onto the sphere, blending
├── metadata/
│   └── GPanoXmpInjector.kt      # GPano XMP into the JPEG header, no re-encode
├── result/
│   └── PanoramaResultScreen.kt  # preview, export to gallery, share, start over
├── storage/
│   ├── SphereImageStore.kt      # cache sessions, the finished sphere, EXIF
│   ├── MediaExporter.kt         # MediaStore write into Pictures/360Panoramas
│   └── ImageBufferManager.kt    # this run's frames + their capture attitude
└── ui/theme/
    ├── Color.kt                 # the one palette: signals, glass, surfaces
    ├── Theme.kt                 # the scheme — dark always, no dynamic colour
    ├── Type.kt                  # the type scale
    └── Shape.kt                 # the corner-radius ladder + PillShape
```
