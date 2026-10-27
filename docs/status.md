# Status

### Still trying to make them better
- **Focal-length refinement** — the pose refinement solves a per-frame focal
  correction from the matched bearings and the stitcher projects through it (see
  [Stitching](stitching.md)).
- **Multi-band blending** — overlaps resolve to a Laplacian pyramid blend that
  fades fine detail over a few pixels and broad illumination over the whole
  overlap.
- **Seam carving** — instead of blending every overlap, a graph-cut finds the
  assignment of each pixel to the frame that agrees best and cuts there, fading
  only a few pixels across it.


