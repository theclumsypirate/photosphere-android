# AGENTS.md — 360-photo-app

## NEVER build APKs locally

Do NOT run `./gradlew assembleDebug`, `assembleRelease`, `installDebug`, or
`adb install` from a locally built APK in this repo. Rationale:

- A debug build is ~43 MB (OpenCV native libs per ABI); local builds only
  waste disk and risk accidental commits of binaries (`*.apk` / `*.aab` are
  git-ignored).
- The local machine may not have the pinned SDK/NDK toolchain; CI is the
  source of truth.

Get test builds instead from the `build-debug` GitHub workflow
(`.github/workflows/android.yml`), which runs **only** on manual dispatch or
pull requests — never on push — and uploads `photosphere-debug-apks` as
artifacts. Download, unzip, `adb install` the APK matching the device ABI.

## CI scope

The `build-debug` workflow builds debug APKs only. `release-apk` attaches a
debug APK to an auto-versioned GitHub Release; `release-signed` attaches a
keystore-signed release APK the same way (keystore from CI secrets, never the
repo). Unit tests (`:app:testDebugUnitTest`)
and lint (`:app:lintDebug`) were deliberately removed from CI — do NOT
re-add them without asking first.
