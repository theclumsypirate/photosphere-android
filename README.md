<p align="center">
  <img src="docs/assets/icon.svg" width="128" alt="Photosphere app icon" />
</p>

<h1 align="center">Photosphere</h1>

<p align="center">
  <b>Android app for guided capture and stitching of 360° photo spheres.</b>
</p>

<p align="center">
  <a href="https://github.com/theclumsypirate/photosphere-android/releases/latest"><img src="https://img.shields.io/github/v/release/theclumsypirate/photosphere-android?label=release" alt="Latest release"></a>
  <img src="docs/assets/badge-android.svg" alt="Android 8.0 or later">
  <a href="LICENSE"><img src="docs/assets/badge-licence.svg" alt="Licence"></a>
</p>

## What it does

It has a built in camera that guides you frame by frame and takes each photo
for you when you hold still. It stitches the photos into one 360 photo that
you can pan around and view fullscreen. You can also import photos from your
gallery and stitch those. You can export the result to your gallery, share it,
or save every frame as its own photo. You can change brightness live and tap
to focus.

## Docs

- [Build](docs/build.md) — requirements, building, dependencies, project layout
- [Capture](docs/capture.md) — permissions, orientation, guided capture
- [Stitching](docs/stitching.md) — how frames become a sphere
- [Viewer](docs/viewer.md) — result screen, export, share
- [Design](docs/design.md) — dark UI, signals, accessibility
- [Status](docs/status.md) — what's done, what's left

Test builds come from the `build-debug` CI workflow — see
[Build](docs/build.md).

## Credits

| Project | Licence | How it is used |
| --- | --- | --- |
| [OpenCV](https://opencv.org) | Apache-2.0 | The stitching engine, fetched from Maven Central at build time and packaged in the APK. |
| [CameraX](https://developer.android.com/training/camerax) | Apache-2.0 | Guided capture: preview, stills, exposure control. |
| [Jetpack Compose + Material 3](https://developer.android.com/jetpack/compose) | Apache-2.0 | The whole UI, including the orbit walk and the result viewer. |
| [ExifInterface](https://developer.android.com/jetpack/androidx/releases/exifinterface) | Apache-2.0 | Poses stamped into frames, rotation tags read back at stitch time. |
| [n30dyn4m1c/360-photo-app](https://github.com/n30dyn4m1c/360-photo-app) | MIT | Original author. |

## License

[MIT](https://github.com/theclumsypirate/photosphere-android/blob/main/LICENSE)
