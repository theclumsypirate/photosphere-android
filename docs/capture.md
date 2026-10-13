# Capture

## Permissions

Declared in
[`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml):

| Permission | Type | Why |
| --- | --- | --- |
| `CAMERA` | runtime | Frame capture |
| `HIGH_SAMPLING_RATE_SENSORS` | **normal** (install-time, API 31+) | >200 Hz gyro/accel sampling to track device attitude between frames |
| `VIBRATE` | **normal** (install-time) | Haptic tick confirming an automatic capture |
| `WRITE_EXTERNAL_STORAGE` | runtime, `maxSdkVersion="28"` | Saving on pre-scoped-storage devices |

No READ permission is declared: the app never reads the user's photo library —
captured frames and finished spheres live in the app's own cache until the user
exports them — so neither `READ_EXTERNAL_STORAGE` nor `READ_MEDIA_IMAGES` is
declared, which also keeps the Android 14+ selected-photos grant state from
ever arising.

`HIGH_SAMPLING_RATE_SENSORS` is a normal permission — it is granted at install
time, and requesting it at runtime always returns "denied". Declaring it is
sufficient.

Frames captured during a run are written to the app's own cache
(`cacheDir/sphere_sessions/<session>/`), which needs no permission at all —
they are stitcher input, not photos the user asked to keep. The finished sphere
lands beside them in `cacheDir/spheres/` and stays private until the user asks
for it. MediaStore (`Pictures/360Panoramas/`) is reserved for that moment; it
works on every API level, so from API 29 up no storage permission is requested
either.

### Runtime handling

`RequirePermissions` in
[`MainActivity.kt`](../app/src/main/java/com/n30dyn4m1c/photosphere/MainActivity.kt)
gates the capture UI. It:

- requests the set once on first composition,
- distinguishes a plain denial (shows a rationale + retry) from
  "don't ask again" (deep-links to the app's settings page) using
  `shouldShowRequestPermissionRationale`,
- re-checks the grant on resume, so returning from Settings recovers without a
  restart.

The grant is read back from the system rather than inferred from the launcher's
result map, and that is load-bearing. When the dialog is dismissed **without an
answer** — swiped away, interrupted by an incoming call, screen locked —
`RequestMultiplePermissions` delivers an *empty* map, and
`emptyMap().values.all { it }` is vacuously `true`: read straight from the map, a
cancellation counts as a grant and drops the user onto the capture screen with no
camera permission, where the bind fails and the viewfinder is simply black.

That same cancellation must not be routed to the "permanently denied" branch
either. `shouldShowRequestPermissionRationale` reads `false` after a cancel, so
trusting it there would send someone who never answered the dialog off to the
Settings app to fix a setting they had not touched. Only an explicit denial the
system will no longer surface a dialog for earns that trip.

## Device orientation

[`OrientationTracker`](../app/src/main/java/com/n30dyn4m1c/photosphere/sensor/OrientationTracker.kt)
listens to `TYPE_GAME_ROTATION_VECTOR` (gyroscope + accelerometer, falling back
to `TYPE_ROTATION_VECTOR` on a device without one) and publishes it as a
`StateFlow<OrientationData>` of yaw, pitch and roll in degrees. The game vector
leaves the magnetometer out — the same choice Street View and Google Camera's
Photo Sphere make. A sphere only needs the aim to be consistent *between
frames*, and the compass is the one input that can make a still phone's heading
jump by degrees indoors (a steel beam, a laptop, rebar in the floor); without it
yaw is relative to an arbitrary zero and drifts by a fraction of a degree a
minute instead, which a capture of a couple of minutes absorbs in its overlap.
Nothing asks the user for a figure-eight calibration any more.

The angles are read straight off the device→world rotation matrix, so they
describe **where the rear camera points**, not the screen:

1. **The camera basis.** `getRotationMatrixFromVector` gives the matrix mapping
   device axes to the world frame (Z up; with the game vector X and Y are a
   fixed but arbitrary horizontal pair rather than east and north). The lens
   looks along the device's -Z axis, so that is the camera's forward. Yaw is its
   bearing, elevation its height above the horizon (reported as a pitch that is
   **negative above the horizon**, matching the capture plan and the stitcher),
   and roll is the image's tilt about the forward axis.
2. **Display rotation.** Which chassis axis counts as the image's "up" depends
   on how the display is turned, so the tracker maps the display rotation onto
   the device axis that points at the top of the screen and measures the roll
   against it. Portrait and landscape then both report the same absolute
   attitude — turning a landscape phone "up" reads as pitch, never as roll.

This extraction is the *inverse* of the axis construction
[`SphereProjection`](../app/src/main/java/com/n30dyn4m1c/photosphere/camera/SphereProjection.kt)
and [`CameraBasis`](../app/src/main/java/com/n30dyn4m1c/photosphere/stitching/SphericalGeometry.kt)
build their camera bases with, so a yaw/pitch/roll handed to the stitcher places
a frame exactly where the sensor held the camera. (It used to go through a
`remapCoordinateSystem` + `getOrientation` shortcut that reported a level pan as
*roll* and pinned yaw near zero; every frame of a ring then landed on the same
longitude, each rotated differently — the "aligned but at odd angles" failure —
so it is pinned down by unit tests against `CameraBasis` now.)

The ranges follow the camera-basis construction: yaw and roll are `atan2`-derived
and span -180°..180°, pitch is `asin`-derived and spans -90°..90°. Pitch is
**negative when the camera is aimed above the horizon**.

One orientation per session. `MainActivity` is locked to portrait in the
manifest, so the display rotation cannot change mid-run. That matters beyond
convenience: the target plan, the sensor's angles and each frame's EXIF
orientation all describe the same display rotation, and a run that crossed a
rotation would carry frames in two incompatible conventions — nothing would
stitch them. The tracker still reads the display rotation properly (the angles
are correct for any rotation), but with the screen locked it is constant for
the whole session.

Events are delivered on a private `HandlerThread`, so a high sampling rate never
competes with the UI. The tracker holds an OS listener registration and must be
lifecycle-driven — `startListening()` when the screen is visible,
`stopListening()` when it is not, or the gyro stays powered in the background.
From Compose that is already wired up:

```kotlin
val orientation by rememberOrientationData()   // starts on STARTED, stops on STOP
Text("yaw ${orientation.yawDegrees}")
```

Debug builds carry an **Orientation debug** button in the top-right corner of
`MainActivity`, which swaps the capture UI for a live readout (artificial
horizon, the three angles, sensor accuracy, and a sample counter that freezes if
the listener is ever leaked or stopped early). The same screen has
`@Preview`s for Android Studio. `BuildConfig.DEBUG` gates the button, so R8
strips it from release builds.

## Guided capture

[`PhotoSphereCameraScreen`](../app/src/main/java/com/n30dyn4m1c/photosphere/camera/PhotoSphereCameraScreen.kt)
binds a CameraX `Preview` and `ImageCapture` to the activity lifecycle and runs
the loop that turns device attitude into frames. The user never presses a
shutter: they move the reticle onto the next marker and hold.

**The target list.** [`SphereTargetPlan`](../app/src/main/java/com/n30dyn4m1c/photosphere/camera/SphereTarget.kt)
lays its rings out to match the device's lens. The yaw gap on the equator is the
horizontal field of view minus a 35% overlap target, so a wide-angle phone takes
fewer, larger frames and a narrow one takes more, smaller ones — every frame
covers roughly the same slice of the sphere. Ring elevations step up and down
from the horizon by the same fraction of the *vertical* field of view, and the
outermost ring always reaches the poles, so no lens leaves an uncovered cap at
the top or bottom. Yaw spacing widens by `1 / cos(elevation)` so neighbouring
frames stay a constant *angular* distance apart instead of bunching up as the
rings shrink. The 35% overlap is what the feature-based pose refinement needs:
it leaves every frame well inside its neighbours' view, and is the margin that
absorbs a field-of-view estimate that runs high and a degree or two of sensor
drift (see [Stitching](stitching.md)). The plan's default path is the horizon,
then every ring up to the zenith, then every ring down to the nadir, each swept
opposite to the one before, so the user never swings between ceiling and floor
more than once. The whole plan is rotated to start at whatever bearing the user
is already facing, and the stitched photo is centred on that bearing too — every
360 viewer opens on the scene the run began with, and the wrap-around seam sits
directly behind it.

**Any order, Street View style.** The path is a default, not a rule
([`TargetSelection`](../app/src/main/java/com/n30dyn4m1c/photosphere/camera/TargetSelection.kt)).
Aim squarely (within 6°) at any other uncaptured dot and it becomes the active
target — fill a gap you can see, skip a frame someone is walking through and
come back to it, or shoot in whatever order is comfortable. After each shot the
active target hands over to the nearest uncaptured dot, with the plan's next one
given a 12° head start so an ordinary sweep keeps its direction.

The plan is laid out against 90% of the reported field of view, not all of it
(`FIELD_OF_VIEW_SAFETY_FACTOR`). The overlap is only ever as good as the field
of view it is measured against, and that number is read off the bound camera's
own intrinsic calibration rather than a guess; overestimating it spaces the
targets too far apart, and the overlap is the first thing to be eaten. Spending
a few extra frames is the cheaper mistake.

**One plan, inferred coverage.** A toggle chooses how much of the sphere the
plan walks: [`SphereCaptureScope.Ring`](../app/src/main/java/com/n30dyn4m1c/photosphere/camera/SphereTarget.kt)
lays out just the horizon — a regular pano that goes all the way around, like a
ring — while [`SphereCaptureScope.Sphere`](../app/src/main/java/com/n30dyn4m1c/photosphere/camera/SphereTarget.kt)
adds rings out to both poles. Either way coverage is inferred from the frames as
they land, and the run can be stitched at any point — three overlapping frames
are already a panorama. Stop early for a partial capture (black where it was
never shot), or walk the whole plan for a full one. The HUD counts bands so "the
horizon is closed" is a visible moment rather than a guess.

**Where a marker goes on screen.** [`SphereProjection`](../app/src/main/java/com/n30dyn4m1c/photosphere/camera/SphereProjection.kt)
rotates a target's direction out of the world frame into the camera's own frame
and divides through by depth — the same rectilinear projection the lens
performs, so a marker lands on the pixel its target will actually occupy. The
focal length comes from the camera's reported optics
([`CameraOptics`](../app/src/main/java/com/n30dyn4m1c/photosphere/camera/CameraOptics.kt)),
preferring the device's own intrinsic calibration and falling back to typical
phone values if the camera will not describe itself. Roll is included, so
tilting the phone turns the markers with the scene; it is deliberately excluded
from the *distance* measure, since turning the phone in its own plane does not
change where it is aimed.

**The overlay, Photo Sphere style.** [`TargetOverlay`](../app/src/main/java/com/n30dyn4m1c/photosphere/camera/TargetOverlay.kt)
draws the reticle the way Google Camera's Photo Sphere and Street View do: a
large ring fixed at the centre of the viewfinder, sized to the display rather
than a fine crosshair, that warms from white toward green as the aim closes on
the active target and fills a dwell arc around its rim while the aim is held
inside it. Targets are circles rather than footprints: a hollow one is "still to
cover", a filled green one is "done", and the live target pulses with a dashed
guide line back to the reticle — collapsing to a chevron on the border when it
is off-screen, so the user always knows which way to turn.

**Who decides.** The rule that turns attitude into a shutter lives in
[`SphereCaptureCoordinator`](../app/src/main/java/com/n30dyn4m1c/photosphere/camera/SphereCaptureCoordinator.kt),
not in the screen: it owns the plan, the index the run is on, the dwell and the
window of samples a frame's pose is averaged over, and it has no camera and no
Compose behind it, so the whole rule is driven directly by unit tests. What is
left in `PhotoSphereCameraScreen` is the half that needs an `ImageCapture`.

That split is also what makes the run's position safe to move. A capture,
an undo and a "start over" all move it, and they are not naturally exclusive —
a capture suspends for hundreds of milliseconds while its frame is written, and
an undo tapped inside that window used to read and write the active index in
between the capture reading it and writing it back. Whichever wrote last won,
and the reticle was left pointing at a target that had never been shot. All
three now go through one lock: an undo during a shutter is refused outright
(the button dims to say so), and a capture that reports back after the run has
been restarted or stepped back is discarded rather than applied to the run that
replaced it.

**The trigger.** [`AlignmentGate`](../app/src/main/java/com/n30dyn4m1c/photosphere/camera/AlignmentGate.kt)
fires once the aim has been within **2°** of the active target continuously for
**300 ms**. The dwell is what keeps a frame from being taken mid-swing: at a
normal pan rate the reticle crosses a 2° window in far less than 300 ms, so only
a deliberate stop trips it. Any sample outside the window clears the timer
outright. The shutter also refuses to fire while a compass-fused sensor reports
its readings as unreliable (the game vector has no such state), and the
attitude stamped onto each frame is the mean over the dwell rather than a single
sample — both take the jitter out of the pose the stitcher starts from.

**Bursts keep their own poses.** A burst of max-quality stills takes a couple of
seconds on a Samsung, and the dwell mean only describes the moment before the
first shot. Each shot therefore records the attitude at its own shutter
(`onCaptureStarted`); the kept shot is stamped with the dwell mean only if it
is within 0.4° of it, and with its own sample otherwise — so a hand that eases
off after the first flash cannot put a later shot on the sphere where the first
one was aimed. The sharpest shot is judged on the central half of the frame at
the stitch's own ~2000 px input size, where a few pixels of handshake smear
actually shows, rather than on a thumbnail where every candidate scores alike. On success the shutter sound and a haptic tick
fire together, the marker turns green, and focus animates onto the next target.

**Tap to focus, locked for the session.** Phone lenses have a fixed physical
aperture, so there is no f-stop to dial — focus is what changes, and it was
being parked at infinity, which reads as blur on any scene with something nearer
than the horizon. The session now runs tap-to-focus instead: tap anywhere on the
viewfinder to aim the lens at that part of the scene (a focus square appears and
turns green when it locks), and the lens holds that distance for the whole run —
exactly like a regular camera's tap-to-focus lock, and exactly what a sphere
needs, because no frame re-focuses mid-sweep. Until the first tap the lens holds
the centre of the first scene it saw. AE and AWB stay locked for consistency, so
the only thing a tap ever moves is focus.

**Frames** are written full-resolution to the session's cache directory with the
capture attitude stamped into EXIF `UserComment`, giving the stitcher a starting
guess at where each frame belongs. Stale sessions are cleared when a new run
starts. A frame that came out blurred, or one shot while something moved through
the scene, is dropped with the **undo** button in the top-left corner (the
Street View camera's control): the last frame is deleted and its target becomes
the active one again, so it can simply be re-shot.

**Device tuning.** [`SphereDeviceProfile`](../app/src/main/java/com/n30dyn4m1c/photosphere/camera/SphereDeviceProfile.kt)
is where a specific phone is tuned, decided once per device. On a Samsung
Galaxy S23 it is tuned for sharpness:

- **Capture with the 50 MP main camera.** The main lens is the sharpest on the
  phone — better glass, OIS, a far larger sensor than the ultrawide — so a
  sphere shot on it is noticeably crisper. It costs more frames (~33 instead of
  the ultrawide's ~11), which is the price of sharpness.
- **Cap the stills at 12 MP.** Even though the main sensor is 50 MP, the stitch
  reads ~2000 px per frame; capturing full-size is pure waste — slower writes,
  a slower per-target burst, more heat.
- **Stitch at 2000 → 6144.** Frames are decoded at a 2000 px long edge and the
  sphere is rendered up to 6144 wide (Google's Photo Sphere standard is 5376),
  so the main camera's detail reaches the finished photo instead of being
  thrown away at 4096. Resampling uses a Lanczos kernel and a gentle masked
  unsharp mask lifts the finished canvas.
- **Burst three shots per target**, so a sharp survivor is more likely.

On a phone without a wider lens the same code path falls back to the default
back camera, so the tuning is specific without being fragile.

**Which lens the numbers describe.** The field of view is re-read from the
camera CameraX actually bound, using its camera2 id and the resolution of the
stream it settled on, rather than from a guess at what *would* bind. That
matters on a phone with three rear lenses: the plan's spacing, the overlay's
markers and the angle each stitched frame is taken to cover are all scaled by
this one number, and describing the ultrawide while streaming the main lens
spaces every target roughly twice too far apart — frames that do not overlap at
all, and a run that fails at the end with nothing to show for it. Three things
guard against it, all in
[`CameraOptics`](../app/src/main/java/com/n30dyn4m1c/photosphere/camera/CameraOptics.kt):

- **Two independent estimates, reconciled.** One from `LENS_INTRINSIC_CALIBRATION`
  against the *pre-correction* active array, one from physical sensor size over
  focal length. The calibration is the more precise and wins when the geometry
  agrees with it; past a 25% disagreement it is discarded, because a focal
  length in millimetres over a sensor size in millimetres has no crop to be
  quoted against and therefore no way to be off by a factor.
- **The right focal length out of a logical multi-camera**, which lists one per
  physical lens. The main lens is picked by its diagonal field of view landing
  nearest ~78°; profiles that asked for the widest lens take the shortest focal
  length instead. With no sensor size to compare against, the *longest* focal
  length wins — guessing narrow costs frames, guessing wide costs the run.
- **The stream's aspect ratio, not the sensor array's.** Camera2 derives a
  differently shaped stream by cropping, so a 16:9 preview off a 4:3 sensor sees
  a quarter less across one axis. Preview and `ImageCapture` are both pinned to
  4:3 so one field of view describes the viewfinder and the stills alike —
  without that, the overlay draws the angles of a frame the viewfinder never
  shows.

The geometry, the target plan and the trigger rule are pure Kotlin, and are
covered by local unit tests in
[`app/src/test/java/com/n30dyn4m1c/photosphere/camera/`](../app/src/test/java/com/n30dyn4m1c/photosphere/camera).
