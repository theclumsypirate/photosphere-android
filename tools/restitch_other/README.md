# restitch_other: killing ghosting on the 37-frame room capture

Source: `../pano_proto/ref/all36` (37 frames, EXIF sensor poses).
Baseline: `../pano_proto/out/other_v2.jpg` + `views_other_v2.jpg` (8.5/10 pano).

## What was tried

- **v1** — global `cv2.detail_GraphCutSeamFinder` + multiband. Dead: the
  finder throws `addEdges w>=0` on this OpenCV build (4.13.0), global and
  pairwise, COLOR and COLOR_GRAD.
- **v2** — incremental pairwise seams (DP fallback), mosaic at 512px.
  3/10: 4x upscale blur + black dotted seam gaps. Zero ghosting though.
- **v3** — low-res DP ownership, full-res composite, 2px crossfade.
  5.5/10: sharp, ghost-free, but DP cuts diagonally through objects.
- **v4** — own pairwise graph cut via PyMaxflow. 5.5/10: same as v3.
  Narrow overlaps force any pairwise seam through content.
- **v5** — global alpha-expansion, squared data costs. 2/10 confetti:
  data (up to 195k) crushed smoothness.
- **v6** — same with Kotlin-scale costs (L1 data, LAMBDA 10). 4.5/10:
  coherent regions but sliced/duplicated movers. Parallax too severe.
- **v7** — feather-center ownership (other_v2 placement) + full-res
  composite + gains + 2px crossfade. **8/10 views**: sharp, whole
  objects, no ghosts, seams hide in walls and furniture edges.

## Verdict

v7 beats the old views grid (6.5/10). Winner: `out/restitch_v7.jpg` and
`out/views_restitch_v7.jpg`. Remaining seams are parallax, not blending:
no 2D seam can align pixels that disagree everywhere. Next real gains
would come from per-overlap local alignment (ECC/flow) before seam
search, or capturing with less translation between frames.
