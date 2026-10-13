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
