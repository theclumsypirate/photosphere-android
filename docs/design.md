# Design

Everything the user looks at sits on top of either a live viewfinder or a
finished photograph, and that one fact settles most of the design decisions.
The palette, type scale and shape ladder live in
[`ui/theme/`](../app/src/main/java/com/n30dyn4m1c/photosphere/ui/theme/) and are
defined once each — the two signal colours in particular were previously hex
literals repeated across three files, which is how a design drifts.

## Dark always, and Material You optional

`PhotoSphereTheme` takes no `darkTheme` parameter. A light scheme was removed
deliberately:

- **Dark in every configuration.** A light chrome around a viewfinder throws
  light back at the user in exactly the situation where they are judging
  exposure, and it tints the photograph it surrounds. The capture screen is
  pinned to black regardless, so following the system into a light scheme only
  ever produced a light result screen bolted onto a black capture screen.

Material You is the other half of the theme, and it is optional: off by
default, switched from the settings, and on Android 12 and later only, because
below that the platform has no wallpaper palette to derive. With it on, the
wallpaper supplies the hues and the app keeps its own neutrals (background,
surface, onSurface, outline), so the chrome around the picture stays the app's
dark and only the accents move. The capture markers read the accent and the
signal from the colour scheme, so they follow the wallpaper's hues with the
palette on and the app's own green and amber with it off.

Because the app is dark in every configuration, `MainActivity` also pins both
system bars to **light icons** via `SystemBarStyle.dark(...)`. Left to the
default they follow the system's day/night setting, and a phone in light mode
paints dark status-bar icons over a black viewfinder.

`res/values/colors.xml` holds `sphere_background`, the window background the
platform paints before Compose takes over. It must stay in step with
`SphereBackground` in `Color.kt` or every cold start flashes the wrong dark.

## The two signals

| Token | Meaning |
| --- | --- |
| `SphereAccent` (green) | aligned, locked, captured, complete — the "yes" |
| `SphereActive` (amber) | the live target the reticle is being sent to |

They are warm/cool opposites so the two never trade places at a glance, and
both are bright enough to hold against a blown-out sky or a dark room. They are
shared by the HUD and by `TargetOverlayColors`, so the marker on the overlay and
the progress bar in the pill are the same green by construction.

## Chrome over a moving image

The HUD is one translucent material (`GlassSurface` / `GlassSurfaceDim`) rather
than a handful of similar blacks, and gradient scrims run along **both** the top
and the bottom of the frame — the guidance line and the finish button sit over
live scene just as the progress pill does.

Motion is used only where it carries meaning: the finish button arrives on an
animation because the moment it appears is the moment the run stops being
all-or-nothing; the progress bar slides so a landing frame registers in
peripheral vision; the guidance line and the stitch stage crossfade so a change
is noticeable without being read at that instant.

## Accessibility

The progress pill is collapsed to a single spoken sentence
(`capture_progress_description`) — left to itself it handed a screen reader
"12", "/ 48", "frames", a bare progress bar and "1 of 3 bands" as five unrelated
announcements. Controls that are conditionally inert, such as the capture-scope
selector once the first frame has locked the plan in, pass `enabled` to the
component rather than checking it inside `onClick`, so a disabled control is not
announced as actionable.

Guided capture itself — aiming a reticle at a marker — remains unusable without
sight; see the [open issues](status.md).
