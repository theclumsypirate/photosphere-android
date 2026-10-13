# Pano prototype

Throwaway OpenCV experiments that validate stitching ideas before they earn a
place in the Kotlin pipeline. Run from the repo root:

```bash
python3 tools/pano_proto/stitch_v3.py --out v6
python3 tools/pano_proto/stitch_v3.py --out mine --images A.jpg B.jpg C.jpg
python3 tools/pano_proto/stitch_v3.py --out wide --images $(cat list.txt) --focal 830
```

Corpus (`ref/`, git-ignored — clone
[SSARCandy/panoramas-image-stitching](https://github.com/SSARCandy/panoramas-image-stitching)
or pull device frames with `tools/photosphere_lab.py frames`) and outputs
(`out/`, git-ignored).

## Scored loop (Xue-Mountain set, f=830)

- **v1** — SIFT + full-homography chaining, feather blend. DIVERGED
  (37k-px canvas): one bad link poisons a naive chain.
- **v2** — cylindrical projection, AffinePartial2D + per-pair sanity gates,
  middle anchor, overlap gain compensation. 6.5/10: real photo, but wide
  feather blending ghosts the sky into translucent vertical bands.
- **v3** — center seam + 24px crossfade per overlap. 8.5/10: bands gone.
- **v4** — middle-anchored chain (drift halved). ~9/10.
- **v6** — global horizon shear (end-to-end drift). 9/10: one continuous
  photo, only crop-edge wedges left.

## Scored loop (user's 37 saved frames, true EXIF poses)

- **all36_v1** — sensor poses + plain averaging. 5.5/10: full coverage, but
  ghosts everywhere, ceiling arcs, nadir smear, wrap strip.
- **all36_v2** — + pivot referral (0.35 m / 3.5 m room scale) + feather
  weights. 7.5/10: horizon band reads clean.
- **all36_v3** — + room-scale scene + wrap-seam blend. 8.5/10: no strip,
  tight ceiling; nadir streaks remain (close-range floor + wild pole rolls).
- **all36_v4** — ECC content-refinement of near-pole frames. 8.5/10: one
  frame genuinely corrected (rho 0.80), the rest refused — but a warped mask
  left a black wedge. Lesson: canvas-space warps need care.
- **all36_v5** — steep frames contribute only their sharp center (one owner
  per pole pixel). **9/10**: clean zenith, readable nadir, continuous horizon;
  only faint corner wedges left. Loop target met.
- **other_v1 (new file, Gemini's idea)** — winner-takes-all + pure rotation
  (lever 0). 8/10: zero transparency anywhere, but disagreement moved into
  seam smears (door, bed edge, lamp). Sharper where agreed, worse at seams.
- **other_v2 (same, lever 0.35/3.5)** — 8.5/10: door smear gone, so the lever
  earns its keep even under WTA; nadir streaks remain. Verdict: neither WTA
  nor feather dominates — content-aware seam placement (graph-cut, i.e. the
  app's SeamFinder) is the principled union of both.
- **Reference: stock `cv2.Stitcher`** (same engine xpano is built on):
  crashed outright on 7 texture-poor frames (FLANN assert); on the remaining
  30 it built a single-viewpoint mosaic, not a sphere — 2/10 *as a 360 photo*
  (locally clean, ~8/10 as a patch). Blind chaining without poses cannot do
  this job; the score to beat was set by our own v3 instead.

## What this proved for the app

Every validated trick already has a Kotlin counterpart: sanity-gated edges
(PoseRefiner), overlap gains (ExposureCompensation), seams (SeamFinder),
horizon-true geometry (sensor poses). The loop's verdict: don't replace the
pipeline — its bad stitches come from coverage, darkness and EXIF handling,
not the blender.

## Headless 360-viewer test (`view360.py`)

`tools/pano_proto/ref/html-360-viewer` (A-Frame `a-sky`) assumes a full 2:1
equirectangular sphere. `view360.py` reproduces exactly its mapping — same
ray-cast, same fov 80 — over person-like pans, no browser needed:

```bash
python3 tools/pano_proto/view360.py --pano out/v6.jpg --out views_v6naive
python3 tools/pano_proto/view360.py --pano out/v7.jpg --out views_v7
```

Scores from the contact sheets (`out/views_*.jpg`):

- **v6-as-sphere 3/10**: the cylindrical strip stretched over a sphere —
  black bars splitting views, squeezed content, black on any tilt. This is
  the "flat looks OK, 360 looks deformed" failure: the input was never a
  sphere, so no viewer can show it as one.
- **v7 true equirect 8.5/10**: the whole horizon ring explorable, black only
  where nothing was shot (poles). Honest output for partial coverage.

The ported fix: `PanoramaView` mapped *every* canvas as a full sphere, so
ring captures (narrow latitude bands) stretched vertically in-app — the same
deformation class. The renderer now derives the latitude span from the canvas
aspect (`uSpanLat` uniform + `equirectUvForView` span parameter, pinned by
`PanoramaUvTest`).
