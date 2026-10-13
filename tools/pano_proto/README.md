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
