# Offline stitcher (version 2: content-refined poses)

A laptop tool. The first version only stamped EXIF poses over the
winner-takes-all render, and on sessions without EXIF poses the guesses
showed: objects shifted between frames, overlaps landed apart, content
vanishing at the seams. Version 2 refines every pose from the matched
image content before any rendering happens.

## The pipeline

1. Pose seeds: EXIF attitude comment when the session carries it
   (angles, plus the debug camera basis matrix), else an even horizon
   ring.
2. SIFT (or AKAZE with `--detector akaze`) features per frame; pairs
   matched with the Lowe ratio test, outliers killed by MagSAC
   (`cv2.USAC_MAGSAC`) on the pair homography.
3. Each pair's inlier matches unproject to camera-frame rays; a Horn
   rotation solve gives the exact relative rotation (the camera rotates
   about its pivot, so a pair's motion is a rotation by construction).
4. The horizontal field of view is scanned over candidate values, and the
   one with the smallest total rotation residual wins - the FOV no longer
   has to be right on the first try.
5. Poses chain forward through the measured relative rotations, and the
   ring-closure residual spreads back evenly, so drift does not pile up.
6. Render: winner-takes-all per canvas pixel, blurred crossfade across
   winner changes, wrap crossfade at the 180 degree meridian.

Verified on a synthetic ring: measured pair rotations land within
0.0001 degree of truth, and the chained-and-corrected poses agree with
each other inside 0.02 degrees pairwise (synthetic noise aside), with
the global orientation anchored where the seeds put frame 0 - matching
only sees relative geometry, absolute orientation stays the seed's.

## Running it

    cd tools/offline_stitch

Nine photos from the pano_proto ring:

    python3 stitch_offline.py ../pano_proto/ref/all36 -o ring9.jpg --every 9 --run yeah

Or the whole directory, no directory argument defaults to it:

    python3 stitch_offline.py --run yeah

Useful options:

- `--w 4096` for a taller output
- `--hfov 55` starts the FOV scan closer when you know the calibration
- `--no-refine` renders on the raw poses (the old behaviour, for A/B)
- `--detector akaze` when SIFT descriptors are too slow
- `--lever 0.35 --scene 3.5` for hand-held pivot referral

Confirm every run with `--run yeah`; the dry-run form is there so a
stray enter key on the laptop does not start work.

## What is phone-portable

Every piece the refinement needs exists on Android OpenCV 4.7+:
SIFT is patent-free there, and the USAC divider (MagSAC) ships in
`findHomography`. The Horn solve is three numpy lines. So this script is
also the design for the app's own stitcher refinement, or for an offline
NPU run such as the SuperPoint+LightGlue pairing on the AXERA boards.

## Files

- `stitch_offline.py`: the whole tool, no imports from pano_proto.
- `pano_proto/out/stairs_perfect.jpg` stays the quality reference it
  was built against.
