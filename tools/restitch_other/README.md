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
  composite + gains + 2px crossfade. 7/10 views: sharp, whole
  objects, no ghosts, but ceiling exposure tiles and dotted seams
  remain, plus 10-30px shelf offsets.
- **v8** — v7 ownership + `cv2.detail_MultiBandBlender` finish (5 bands,
  int16 feed). **8.5/10 views**: ceiling and walls blend into smooth
  gradients, dotted seams gone, detail stays single-source crisp.

## Verdict

v11 is the winner: `out/restitch_v11.jpg` and `out/views_restitch_v11.jpg`
(**9/10 views**, completeness 9/10). The stove is back, no ghosts, no
black areas, ceiling and walls blend smooth. What remains is honest
geometry: a ~15px cabinet step (parallax), and the walking person
appears twice (true motion, not ghosting).

- **v9** — wrap-aware circular centroid ownership. Same look as v8;
  did not bring the stove back (ownership was never the cause).
- **v10** — steep frames excluded from ownership (all36_v5 lesson) +
  two mask bugfixes (feed only own coverage; holes = covered but
  unfed). Full pano with ceiling and floor, but stove still missing.
- **v11** — global alpha-expansion (v6 costs) over horizon frames
  only, init from data cost, multiband finish. The content-aware cut
  routes around the stove instead of through it. **9/10**.

## What the subagent probes proved

- ECC translation-only refinement: wrong motion model. Consecutive
  frames differ by yaw rotation, not translation; ECC converged to
  huge inconsistent shifts (15-55px). Skipped, correctly.
- Multiband needs aligned inputs: on misaligned pairs it equals plain
  averaging. With v7 ownership (aligned by construction) it fixes the
  exposure tiles. Bindings that work: `prepare((x,y,w,h))` single
  tuple, `feed` int16 image + uint8 mask, `blend(None, None)`.
