# Offline stitcher

A laptop tool in the shape of the winner-takes-all recipe that produced the
reference stair panorama: EXIF poses, half-resolution remap, one winner frame
per pixel, a blurred crossfade across winner changes, and a wrap crossfade at
the 180 degree meridian.

## Which sessions it handles

- Sessions captured with a debug APK from 2026-10 on: every frame carries its
  attitude in the EXIF UserComment (`index=..;yaw=..;pitch=..;roll=..`), and
  debug builds append the camera basis matrix (`;matrix=b1,...,b9`). The basis
  is the exact orientation, so those sessions align by construction.
- Older sessions without poses, like those under `pano_proto/ref/all36/`:
  they fall back to an even horizon ring in capture order. Use `--every N`
  to keep the ring spacing sane: 9 frames at the default hfov 65 degrees
  space 38 degrees apart, comfortably inside each frame's 65 degree coverage.

## Running it

    cd tools/offline_stitch

Nine photos, one ring, default 2048 wide:

    python3 stitch_offline.py ../../pano_proto/ref/all36 -o ring9.jpg --every 9 --run yeah

Confirm the run with `--run yeah`; the dry-run form is there so a stray
enter key on the laptop does not start a minute of work.

Useful options:

- `--w 4096` for a taller output
- `--hfov 55` when the fixed FOV was calibrated to something else
- `--lever 0.35 --scene 3.5` for hand-held pivot referral, like
  `pano_proto/stitch_36_but_other_idea.py`

## Files

- `stitch_offline.py`: the whole tool, no imports from pano_proto.
