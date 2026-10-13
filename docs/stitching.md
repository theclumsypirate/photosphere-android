# Stitching

**The buffer.** [`ImageBufferManager`](../app/src/main/java/com/n30dyn4m1c/photosphere/storage/ImageBufferManager.kt)
is what capture and stitching pass frames through. Each entry is a file in the
session's cache directory plus the yaw/pitch/roll the device held when it was
shot; the pixels stay on disk, because a sphere held as decoded bitmaps is
several hundred megabytes of heap. It owns the session id, so `clear()` (frames
consumed by a successful stitch) and `cancelSession()` (start over — the whole
session directory goes) are the only two ways frames leave.

**Why not OpenCV's `Stitcher`.** Because there isn't one. The stitching module
is absent from every prebuilt OpenCV for Android: `org.opencv:opencv` ships Java
bindings for core, imgproc, features2d, calib3d and the rest, but no
`org.opencv.stitching` package — and `libopencv_java4.so` contains none of the
pipeline's native symbols either, so a JNI shim would not link. Independently
built AARs are the same. `cv::Stitcher` is, in practice, a desktop API, and the
local OpenCV SDK ("Option B" in [build](build.md)) does not change that.

**What this does instead.** Guided capture already knows where the camera was
pointing for every frame — the shutter only fires when the device is held on a
known target — which turns the expensive half of stitching, solving for each
camera's rotation, into something already measured. What is left is a
reprojection, but the measured poses are not the last word:

- [`PoseRefiner`](../app/src/main/java/com/n30dyn4m1c/photosphere/stitching/PoseRefiner.kt)
  matches ORB features between every overlapping pair of frames, recovers the
  *content-observed* relative rotation of each pair with a pure-rotation RANSAC,
  and runs a Gauss–Seidel solve over the whole pose graph with the sensor
  rotations as the starting guess. The result is a small per-frame correction
  that sharpens the overlaps to what the pixels agree on. The sensor stays the
  anchor: every edge is sanity-checked against the sensor-relative rotation it
  should be near, and a frame whose content gives no reliable matches keeps its
  measured pose, so a featureless sky or a blank wall degrades gracefully to the
  orientation-driven stitch rather than a failed one.
- The same matches also sharpen the *field of view*. A focal-length error pulls
  every bearing radially about the optical axis, so the residual the rotations
  leave behind carries a measure of it: a per-frame focal correction is solved
  by least squares from the matched bearings (Brown & Lowe's step) and the
  stitcher projects through the corrected focal lengths. Frame 0 is anchored —
  the whole sphere can be uniformly scaled without changing any alignment, so
  the absolute scale is only defined relative to the frame held still — and
  corrections are clamped to ±15% and gated behind at least forty agreeing
  correspondences, so a featureless run keeps the device's reported field of
  view. The radial distortion is re-normalised against each corrected focal
  length, so the lens stays the same physical lens at its new focal.
- Which pairs count as overlapping is a *directional* test rather than a single
  distance threshold: the second frame's aim is projected into the first's
  camera frame and must land within one field of view on both axes. A portrait
  frame is far taller than it is wide, so two aims 55° apart can overlap
  vertically but not at all horizontally — a distance-only rule would waste a
  match on them.
- The lens's radial distortion is carried through the whole model. The camera's
  `LENS_DISTORTION` coefficients ([`RadialDistortion`](../app/src/main/java/com/n30dyn4m1c/photosphere/stitching/LensModel.kt))
  describe how the lens bends the pinhole projection, so the renderer samples
  the pixel the lens really recorded and the footprint walks the true (distorted)
  border — frame edges, where seams live, land where they should instead of
  bowing. The coefficients act on coordinates normalized by the *focal length*
  (`x_i = (x − c_x) / f_x`, the OpenCV convention), which is the one thing
  `LENS_DISTORTION` changed about the `LENS_RADIAL_DISTORTION` key it replaced —
  that older key normalized against the sensor array's farthest edge instead.
  Reading one through the other's convention overstates `k1` by `(f / halfEdge)²`
  and `k3` by the sixth power of the same, which is a correction several times
  larger than the distortion it is meant to remove.
- [`ExposureCompensation`](../app/src/main/java/com/n30dyn4m1c/photosphere/stitching/ExposureCompensation.kt)
  fits per-frame brightness gains against the overlap graph — a frame shot into
  the sun and the frame beside it recorded against it no longer meet at a
  brightness cliff inside the cross-fade.
- [`PivotModel`](../app/src/main/java/com/n30dyn4m1c/photosphere/stitching/PivotModel.kt)
  accounts for the fact that nobody turns the phone around its own lens. A
  panorama assumes every frame was shot from one point; holding a phone up and
  swivelling your *body* puts the camera on the end of a lever a third of a
  metre long, so between one frame and the one 90° later the lens has moved half
  a metre sideways. That is translation, not rotation, and it is what shows up
  as near objects refusing to line up while the far ones sit fine.

  The geometry is simple enough to correct for, because the camera is not just
  anywhere: it is at `leverArm × forward`, always ahead of the pivot along its
  own optical axis, which is what holding a phone out in front of you and
  turning does. Everything the pipeline projects is a direction from the pivot,
  so a scene point at a nominal distance is `sceneDistance × direction` and the
  ray the camera saw it along is that minus the lever arm. Only the *ratio* of
  the two lengths survives the subtraction, so neither has to be measured
  precisely — and because the lever points down the optical axis it cancels out
  of both lateral components, leaving the renderer's inner loop one subtraction
  heavier and nothing else. `PoseRefiner` applies the same referral to every
  feature bearing before matching, so the pure-rotation RANSAC is solving a
  problem that is actually a pure rotation.

  The default lever arm is 0.35 m against a nominal 10 m scene. The scene
  distance is deliberately long rather than typical: over-correcting bends
  frames apart just as surely as parallax bends them together, and this takes
  most of the error out of a close scene while adding under a degree to a
  distant one — comfortably inside what the refinement absorbs.

[`SphericalGeometry`](../app/src/main/java/com/n30dyn4m1c/photosphere/stitching/SphericalGeometry.kt)
holds the maths: a `CameraBasis` built from a frame's yaw/pitch/roll, the
mapping between canvas pixels and directions on the sphere, and the *footprint* —
the block of canvas a frame can reach. The footprint is found by walking the
frame's border rather than its four corners, since at a wide field of view the
middle of an edge reaches a higher latitude than either corner does, and it
handles the two cases that break a naive bounding box: a frame straddling the
±180° seam comes back as one unwrapped range, and a frame containing a pole opens
out to the full width, because every longitude passes underneath it.

[`EquirectangularRenderer`](../app/src/main/java/com/n30dyn4m1c/photosphere/stitching/EquirectangularRenderer.kt)
does the painting. Every output pixel is a direction; for each frame that
direction is rotated into the frame's own axes and divided through by depth,
which gives the pixel that looked at it (through the distortion model), and
`Imgproc.remap` samples it. The exposure gains are applied before the blend, so
they land inside it. Overlaps resolve to a multi-band (Laplacian pyramid)
blend — see [`MultibandBlender`](../app/src/main/java/com/n30dyn4m1c/photosphere/stitching/MultibandBlender.kt).
Each frame and its feather mask are split into bands, and each band is
cross-faded with a mask sized to the band: fine detail fades across a few
pixels, broad illumination across the whole overlap, so a seam is faded at
every scale by a transition narrower than the detail that scale carries. A
pixel only one frame reached still comes out at full strength, because its
mask divides back out.

**Seam carving** is the alternative to the wide cross-fade, toggled by the
debug **Seam** button next to Dist/Refine. [`SeamFinder`](../app/src/main/java/com/n30dyn4m1c/photosphere/stitching/SeamFinder.kt)
asks which single frame should paint each pixel, instead of letting every
overlapping frame contribute, and the renderer then paints near-hard selections
that fade only a few pixels across each cut — the sharpness a cross-fade
spends. The question is posed as an energy over the overlap:

- The **data term** for a pixel and a frame is how far the frame's sample sits
  from the mean of every frame covering that pixel — the frame closest to what
  the overlap actually shows wins the interior.
- The **seam cost** for cutting between two neighbouring pixels with different
  frames is how differently the two frames see those pixels, so a cut through a
  region where the frames agree is cheap and a cut through a ghost is not. The
  cost is a metric (a frame that does not cover a pixel is filled with that
  pixel's mean colour, which keeps the triangle inequality intact), so every
  expansion move is submodular and its exact min-cut can never raise the
  energy.

The energy is minimized with α-expansion — [`SeamSolver`](../app/src/main/java/com/n30dyn4m1c/photosphere/stitching/SeamSolver.kt)
— where each label in turn offers every pixel "keep your frame or switch to
this one", and each binary subproblem is an s-t min-cut over the pixel grid
(Dinic's max-flow). The whole solver is pure Kotlin, exercised on the JVM
against an exhaustive search on tiny grids. The seam is decided at a reduced
resolution (~512 columns across the canvas) because a cut only needs locating
to within a few output pixels, and the graph-cut's cost scales with that grid
rather than with the full canvas; [`SeamFeather`](../app/src/main/java/com/n30dyn4m1c/photosphere/stitching/SeamWeights.kt)
then turns the label map into the narrow cross-fade the renderer looks up — the
winner holds weight 1 everywhere, and the loser's contribution ramps in from
half on the boundary to nothing a few pixels away. The multi-band machinery is
left to handle that narrow transition, so coarse illumination still meets
softly where the seam runs, while fine detail keeps both copies — the point of
carving instead of blending.

The result is equirectangular *by construction* rather than by cropping
something else into shape: a pixel's row is its latitude, so an incomplete sphere
is black exactly where it was not shot, at the right elevation. The accuracy
ceiling is how much of the residual pose error the content can resolve — the
fused attitude sensor is steady but not perfect, and the feature
refinement pulls the overlaps back into agreement instead of leaving soft
doubling.

**Memory.** A 4096-wide canvas needs 100 MB of float accumulator if it is held
at once, which is exactly the allocation that ends a stitch on a mid-range
phone. Every level of the blend is built in horizontal bands instead, so only
the band being accumulated exists in float, and the coarse levels that carry
the wide cross-fade live in mats a small fraction of the canvas — the peak is
tens of megabytes rather than a hundred, the same order as the unsharp mask
that runs after it. The canvas is also never rendered wider than the frames
justify — a 1024 px frame across 66° carries about 15.5 px per degree, so a
full turn is worth ~5600 px, and rendering beyond that would only interpolate.

**The field of view is self-checked.** The angles handed to the stitcher describe
the upright frame as captured on the portrait-locked display, and `stitchPhotos`
verifies them against the first decoded frame: a lens has square pixels, so if
the horizontal and vertical angles imply focal lengths that disagree by more
than a real lens could, the pair is swapped and a warning is logged. It is a
safety net for the one mis-description that would silently bend every frame out
of shape — there is no error message that would rescue a run whose frames were
all projected through the wrong axis.

Failures come back as a failed `Result` carrying a `StitchException` with a
`StitchStatus`: too little of the sphere covered, a frame that would not decode,
the native library missing, out of memory.

**In the UI.** A **Finish & stitch** button appears in
`PhotoSphereCameraScreen` once three frames are buffered, and a modal with a
progress indicator covers the screen while the work runs. Both long stages report
real progress — one frame at a time while decoding, one band at a time while
rendering. Cancelling takes effect at the next band boundary. On success the
sphere is written to the cache as a GPano-tagged JPEG, the buffered frames are
deleted, and the result screen takes over; on failure the frames are kept,
because the usual fix is to capture a few more and try again.

Three is the floor, not the target. Frames are placed from their measured pose
rather than by searching for a chain of matches, so the pipeline has no
minimum-frames requirement of its own — what more frames buy is coverage, and
how much coverage is enough is the user's call to make on the result screen. A
partial capture comes back as an equirectangular image that is black where the
sphere was never shot, at the right elevation.
