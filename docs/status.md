# Status

The pipeline features that were once listed here as outstanding have all
landed:

- **Focal-length refinement** — the pose refinement solves a per-frame focal
  correction from the matched bearings and the stitcher projects through it (see
  [Stitching](stitching.md)).
- **Multi-band blending** — overlaps resolve to a Laplacian pyramid blend that
  fades fine detail over a few pixels and broad illumination over the whole
  overlap.
- **Seam carving** — instead of blending every overlap, a graph-cut finds the
  assignment of each pixel to the frame that agrees best and cuts there, fading
  only a few pixels across it.

What is still outstanding is tracked in
[the issue tracker](https://github.com/theclumsypirate/360-camera/issues). The
larger items at the time of writing: guided capture cannot be driven without
sight, the result screen previews the sphere flat rather than as a pannable
view, there are no instrumented UI tests, the strings are English-only, and the
activity's hard portrait lock is ignored from Android 16 onward.
