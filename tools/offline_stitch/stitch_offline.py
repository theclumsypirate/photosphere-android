"""Offline panorama stitcher for photo sessions captured by the 360 photo app.

Lightweight laptop tool in the same shape as the pano_proto winner recipe
that produced the reference stair panorama:

1. every frame's attitude comes from its EXIF UserComment when present
   (angles, plus the camera basis matrix in debug builds from capture
   sessions that stamp them), else an even horizon ring in pick order
2. each frame is projected onto the equirectangular canvas with
   cv2.remap at half the frame size
3. winner-takes-all: each canvas pixel comes from the single frame whose
   feather weight is highest there, transitions get a narrow blurred
   crossfade, and the 180 degree wrap gets its own crossfade

Usage:

    python3 stitch_offline.py DIR -o pano.jpg            # all frames
    python3 stitch_offline.py DIR -o pano.jpg --every 9  # 9 spread picks

Needs Python 3.8+ with numpy, opencv-python, pillow. Run it yourself; it is
made to be a laptop tool, roughly a minute for 9 frames at the default
width of 2048.
"""

import argparse
import os
import re
import sys

import cv2
import numpy as np

FRAME_PATTERN = re.compile(r"^frame_(\d+)")
# The angles-only attitude comment the app has stamped on frames since the
# importer existed.
ATTITUDE_PATTERN = re.compile(
    r"index=-?\d+;yaw=(-?\d+(?:\.\d+)?);pitch=(-?\d+(?:\.\d+)?);"
    r"roll=(-?\d+(?:\.\d+)?)")
# The debug-only extension: the camera basis appended after the angles.
MATRIX_PATTERN = re.compile(
    r"matrix=(?P<matrix>-?\d+(?:\.\d+)?(?:,-?\d+(?:\.\d+)?){8})")
FEATHER_PX = 80
WRAP_SEAM_PX = 64
WINNER_BLUR_PX = 7
RUN_CONFIRM = "yeah"


def read_exif_string(path):
    """The EXIF UserComment of one JPEG as a plain string, bytes-safe."""
    try:
        from PIL import Image
        ex = Image.open(path).getexif()
        sub = ex.get_ifd(0x8769)
        value = sub.get(0x9286)
    except Exception as error:
        print("  no EXIF in %s: %s" % (os.path.basename(path), error),
              file=sys.stderr)
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", "replace")
    return str(value or "")


def read_pose(path):
    """(yaw, pitch, roll) or None, one frame's own stamped attitude."""
    match = ATTITUDE_PATTERN.search(read_exif_string(path))
    return (float(match.group(1)), float(match.group(2)),
            float(match.group(3))) if match else None


def read_basis(path):
    """The debug camera basis matrix (columns: right, -up, forward) or None."""
    match = MATRIX_PATTERN.search(read_exif_string(path))
    if not match:
        return None
    return np.array([float(v)
                     for v in match.group("matrix").split(",")],
                    dtype=np.float64).reshape(3, 3)


def ring_pose(i, count, hfov):
    """Fallback pose: an even horizon ring, centred, in pick order."""
    step = hfov * 0.65
    return ((i - (count - 1) / 2) * step, 0.0, 0.0)


def camera_frame(yaw_d, pitch_d, roll_d):
    """Forward, right and up world unit vectors of a pose, the same
    convention as the app's stitcher and pano_proto."""
    yaw, elev, roll = (np.radians(yaw_d), np.radians(-pitch_d),
                       np.radians(roll_d))
    sy, cy, ce = np.sin(yaw), np.cos(yaw), np.cos(elev)
    fx, fy, fz = sy * ce, cy * ce, np.sin(elev)
    r0x, r0y = cy, -sy
    u0x, u0y = r0y * fz, -r0x * fz
    u0z = r0x * fy - r0y * fx
    cr, sr = np.cos(roll), np.sin(roll)
    fwd = np.array([fx, fy, fz])
    right = np.array([r0x * cr - u0x * sr,
                      r0y * cr - u0y * sr,
                      -u0z * sr])
    up = np.array([u0x * cr + r0x * sr,
                   u0y * cr + r0y * sr,
                   u0z * cr])
    return fwd, right, up


def basis_frame(basis):
    """Forward, right and up straight from the EXIF matrix: its columns are
    the image right, minus the image up and the image forward."""
    return basis[:, 2], basis[:, 0], -basis[:, 1]


def list_frames(directory, count):
    """JPEGs named frame_*.jpg, sorted by capture index, optionally picked
    evenly down to [count] frames."""
    entries = []
    for name in os.listdir(directory):
        stem, ext = os.path.splitext(name)
        match = FRAME_PATTERN.match(stem)
        if not match or ext.lower() not in (".jpg", ".jpeg"):
            continue
        entries.append((int(match.group(1)), name))
    entries.sort()
    names = [name for _, name in entries]
    if count and count < len(names):
        pick = np.linspace(0, len(names) - 1, count).round().astype(int)
        pick = sorted(dict.fromkeys(pick.tolist()))
        names = [names[i] for i in pick]
        print("picked %d of %d frames" % (count, len(entries)))
    return [os.path.join(directory, name) for name in names]


def main():
    ap = argparse.ArgumentParser(
        description="Stitch session frames from their EXIF poses.")
    ap.add_argument("directory", nargs="?", default=None)
    ap.add_argument("-o", "--output", default="offline_pano.jpg")
    ap.add_argument("--w", type=int, default=2048)
    ap.add_argument("--every", type=int, default=0,
                    help="pick only this many frames, spread evenly")
    ap.add_argument("--hfov", type=float, default=65.0,
                    help="frame field of view, horizontal, degrees")
    ap.add_argument("--lever", type=float, default=0.0,
                    help="pivot lever arm in scene units")
    ap.add_argument("--scene", type=float, default=3.5,
                    help="scene radius for the pivot referral")
    ap.add_argument("--run", default="",
                    help='type "%s" to confirm the run on this laptop'
                         % RUN_CONFIRM)
    args = ap.parse_args()
    if args.run != RUN_CONFIRM:
        ap.error('dry run: rerun with --run "%s" to actually stitch'
                 % RUN_CONFIRM)
    args.directory = args.directory or os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..", "pano_proto", "ref", "all36")

    H = args.w // 2
    files = list_frames(args.directory, args.every)
    if not files:
        ap.error("no frame_*.jpg in %s" % args.directory)

    poses, bases = [], []
    for path in files:
        basis = read_basis(path)
        bases.append(basis)
        poses.append(read_pose(path))
    if all(b is not None for b in bases):
        print("poses: EXIF camera basis (%d frames)" % len(bases))
    elif all(p is not None for p in poses):
        print("poses: EXIF angles (%d frames)" % len(poses))
    else:
        poses = [ring_pose(i, len(files), args.hfov)
                 for i in range(len(files))]
        print("poses: even ring %d x %.1f deg (hfov=%g)" % (
            len(files), args.hfov * 0.65, args.hfov))

    probe = cv2.imread(files[0])
    fh, fw = probe.shape[:2]
    aspect = fw / fh
    vfov = float(np.degrees(
        2 * np.arctan(np.tan(np.radians(args.hfov / 2)) / aspect)))
    fx = (fw / 2) / np.tan(np.radians(args.hfov / 2))
    fy = (fh / 2) / np.tan(np.radians(vfov / 2))
    cx, cy = fw / 2.0, fh / 2.0

    us = (np.arange(args.w, dtype=np.float64) / args.w - 0.5) * 2 * np.pi
    vs = (0.5 - np.arange(H, dtype=np.float64) / H) * np.pi
    uu, vv = np.meshgrid(us, vs)
    dirs = np.stack([np.sin(uu) * np.cos(vv), np.cos(uu) * np.cos(vv),
                     np.sin(vv)], axis=-1).astype(np.float32)

    # Half-resolution feather and remap: the canvas and the sampling are
    # downscaled by 2, so the laptop work fits in a quarter of the pixels.
    half_w, half_h = fw // 2, fh // 2
    my, mx = np.mgrid[0:half_h, 0:half_w].astype(np.float32)
    feather = np.ones((half_h, half_w), np.float32)
    r = min(FEATHER_PX, half_w // 8)
    feather[:, :r] *= np.linspace(0, 1, r, dtype=np.float32)
    feather[:, -r:] *= np.linspace(1, 0, r, dtype=np.float32)
    feather[:r, :] *= np.linspace(0, 1, r, dtype=np.float32)[:, None]
    feather[-r:, :] *= np.linspace(1, 0, r, dtype=np.float32)[:, None]

    warped, weights = [], []
    for index, (path, pose, basis) in enumerate(zip(files, poses, bases)):
        if basis is not None:
            fwd, right, up = basis_frame(basis)
        else:
            fwd, right, up = camera_frame(*pose)
        # Pivot referral: the lens sits ahead of the pivot, so the ray the
        # camera saw at a scene point is the point's direction minus the
        # lever arm, in the same units as --scene.
        adj = args.scene * dirs - args.lever * fwd[None, None, :]
        adj /= np.linalg.norm(adj, axis=2, keepdims=True)
        z = adj @ fwd
        front = z > 0.02
        x = adj @ right / np.maximum(z, 1e-9)
        y = adj @ up / np.maximum(z, 1e-9)
        px = (x * fx + cx).astype(np.float32) * 0.5
        py = (cy - y * fy).astype(np.float32) * 0.5
        inside = front & (px >= 0) & (px < half_w) & (py >= 0) & (py < half_h)
        if not inside.any():
            continue
        image = cv2.imread(path)
        image = cv2.resize(image, (half_w, half_h), interpolation=cv2.INTER_AREA)
        sampled = cv2.remap(image, px, py, cv2.INTER_LINEAR,
                            borderMode=cv2.BORDER_CONSTANT)
        weight = cv2.remap(feather, px, py, cv2.INTER_LINEAR,
                           borderMode=cv2.BORDER_CONSTANT)
        weight[~inside] = 0
        warped.append(sampled.astype(np.float32))
        weights.append(weight.astype(np.float32))
        print("warped %d/%d: %s" % (index + 1, len(files),
                                    os.path.basename(path)), flush=True)
    if not warped:
        ap.error("no frame projected inside the canvas; check --hfov")

    stack = np.stack(weights)
    winner = stack.argmax(axis=0)
    masks = np.empty_like(stack)
    for label in range(stack.shape[0]):
        one = (winner == label).astype(np.float32)
        masks[label] = cv2.GaussianBlur(one, (0, 0), WINNER_BLUR_PX)
    masks /= np.maximum(masks.sum(axis=0, keepdims=True), 1e-9)
    pano = sum(image * mask[..., None]
               for image, mask in zip(warped, masks))
    pano = np.clip(pano, 0, 255).astype(np.uint8)

    # Longitude wrap: the canvas is a cylinder, so the first and last
    # columns are neighbours and get one crossfade, never a hard seam.
    span = min(WRAP_SEAM_PX, args.w // 4)
    left = pano[:, :span].astype(np.float32)
    right = pano[:, -span:].astype(np.float32)
    ramp = (np.arange(span, dtype=np.float32) / span)[None, :, None]
    pano[:, :span] = np.clip(
        (1 - ramp) * left + ramp * right, 0, 255).astype(np.uint8)
    cv2.imwrite(args.output, pano, [cv2.IMWRITE_JPEG_QUALITY, 92])
    coverage = float((stack.max(axis=0) > 0).mean())
    print("saved %s, coverage %.1f%%" % (args.output, coverage * 100))


if __name__ == "__main__":
    main()
