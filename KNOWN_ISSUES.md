# Known issues

Things that look like bugs but are physics, hardware limits, or trade-offs.
**if you know a concrete way how to fix them** then file a bug report.

## Ghosting and sliced objects

Nearby or moving subjects (people walking through, hands, furniture close
to the lens) appear doubled, sliced, or smeared along seams. Cause: the
phone moves between shots, so the same ray shows different content per
frame (parallax). No 2D stitcher can align pixels that disagree
everywhere, including professional 360 cameras. Workarounds: restitch as
**Crisp** (one source per pixel, no translucency), keep people out of the
frame, or step back so subjects are farther away.

## Exposure patches

Bright and dark tiles on ceilings and walls. Cause: auto-exposure drifts
as you turn toward and away from light. The app locks exposure after the
first frames and compensates per-frame gains, but strong directional
light exceeds the correction range. Workaround: start facing the
brightest part of the room, avoid bare bulbs in frame.

## Blurry or dark stitches

Too few usable frames, heavy motion blur, or a near-dark room. The
stitcher blends what it gets; it cannot invent detail. Reshoot with
steadier holds and more light.

## Refinement finds nothing

The log says "matched 0 edges". Expected on plain walls and repetitive
texture: there is nothing distinctive to match, so sensor poses stand.
Not an error.

## Wrap seam behind you

The ±180° join can show a step. The scene behind the starting bearing
gets the least overlap. Start facing the least important direction.

## Compass accuracy "Unknown"

Some phones (notably MediaTek) never report calibration. Capture works
the same; only the accuracy label is missing.

## Old or odd lenses

Ultra-wide and heavily distorting lenses warp frame edges beyond the
radial model. Debug builds can re-stitch with distortion off to isolate
it; report the phone model if you see systematic bending.
