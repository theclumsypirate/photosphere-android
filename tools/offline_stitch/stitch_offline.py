"""Offline panorama stitcher for photo sessions captured by the 360 photo app.

Version 2: the poses are refined from the image content, not guessed.

Pipeline, in the classical order the feature-stitching literature uses:

1. an initial pose per frame: the EXIF attitude comment when the session
   carried it (angles, plus the debug camera basis matrix), else an even
   horizon ring in capture order
2. SIFT (or AKAZE) features on every frame, matched with a Lowe ratio test
3. every consecutive pair plus the ring-closing pair gets a homography
   estimated by MagSAC (cv2.USAC_MAGSAC); its inliers are unprojected to
   camera-frame rays and a Horn rotation solve gives the exact relative
   rotation of the pair (the camera rotates about its pivot, so a pair's
   motion is a rotation by construction)
4. the horizontal field of view is scanned over a candidate range; the one
   with the smallest total rotation residual wins
5. poses chain forward through the measured relative rotations, and the
   loop-closure residual spreads back evenly over the chain, so drift does
   not pile up along the ring
6. the render is the winner-takes-all recipe that produced the reference
   stair panorama: one frame owns each canvas pixel, transitions get a
   blurred crossfade, and the 180 degree meridian gets its own crossfade

Usage:

    python3 stitch_offline.py DIR -o pano.jpg --run yeah [options]

Needs Python 3.8+ with numpy, opencv-python, pillow. The same pieces exist
on Android (OpenCV 4.7+ carries SIFT patent-free and the USAC/MagSAC
solvers), so this pipeline is the design the app itself can adopt.
"""

import argparse
import os
import re
import sys

import cv2
import numpy as np

FRAME_PATTERN = re.compile(r"^frame_(\d+)")
ATTITUDE_PATTERN = re.compile(
    r"index=-?\d+;yaw=(-?\d+(?:\.\d+)?);pitch=(-?\d+(?:\.\d+)?);"
    r"roll=(-?\d+(?:\.\d+)?)")
MATRIX_PATTERN = re.compile(
    r"matrix=(?P<matrix>-?\d+(?:\.\d+)?(?:,-?\d+(?:\.\d+)?){8})")
FEATHER_PX = 80
WRAP_SEAM_PX = 64
WINNER_BLUR_PX = 7
RUN_CONFIRM = "yeah"
DETECT_WIDTH = 640
FOV_SCAN_STEP = 2.5
FOV_SCAN_HALFSPAN = 15.0
FOV_FLOOR = 35.0
MIN_PAIR_INLIERS = 30


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
    """The debug camera basis matrix (columns: right, minus the up and the
    forward) or None."""
    match = MATRIX_PATTERN.search(read_exif_string(path))
    if not match:
        return None
    return np.array([float(v) for v in match.group("matrix").split(",")],
                    dtype=np.float64).reshape(3, 3)


def ring_pose(i, count, hfov):
    """Fallback pose: an even horizon ring, centred, in pick order."""
    step = hfov * 0.65
    return ((i - (count - 1) / 2) * step, 0.0, 0.0)


def camera_frame(yaw_d, pitch_d, roll_d):
    """Right, up and forward world unit vectors of an angle pose."""
    yaw, elev, roll = (np.radians(yaw_d), np.radians(-pitch_d),
                       np.radians(roll_d))
    sy, cy, ce = np.sin(yaw), np.cos(yaw), np.cos(elev)
    fwd = np.array([sy * ce, cy * ce, np.sin(elev)])
    r0x, r0y = cy, -sy
    u0x, u0y = r0y * fwd[2], -r0x * fwd[2]
    u0z = r0x * fwd[1] - r0y * fwd[0]
    cr, sr = np.cos(roll), np.sin(roll)
    right = np.array([r0x * cr - u0x * sr,
                      r0y * cr - u0y * sr,
                      -u0z * sr])
    up = np.array([u0x * cr + r0x * sr,
                   u0y * cr + r0y * sr,
                   u0z * cr])
    return right, up, fwd


def basis_frame(basis):
    """Right, up and forward straight from the EXIF matrix columns (the
    app stamps the basis as right, minus the up, forward)."""
    return basis[:, 0].copy(), -basis[:, 1].copy(), basis[:, 2].copy()


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
        print("picked %d of %d frames" % (len(names), len(entries)))
    return [os.path.join(directory, name) for name in names]


def make_detector(prefer):
    """SIFT when the build offers it, AKAFE otherwise."""
    if prefer == "sift":
        try:
            detector = cv2.SIFT_create()
        except AttributeError:
            detector = None
        if detector is not None:
            return detector, "SIFT"
    return cv2.AKAZE_create(), "AKAZE"


def features(paths, detector):
    """Grayscale detectAndCompute per frame, at the detection width.

    Keypoint pixel coordinates come back in full-resolution pixels, so
    every later solve works in one coordinate system.
    """
    keys, descs = [], []
    for path in paths:
        gray = cv2.cvtColor(cv2.imread(path), cv2.COLOR_BGR2GRAY)
        scale = min(1.0, DETECT_WIDTH / gray.shape[1])
        small = cv2.resize(gray, None, fx=scale, fy=scale,
                           interpolation=cv2.INTER_AREA)
        found, desc = detector.detectAndCompute(small, None)
        descs.append(desc)
        coords = cv2.KeyPoint_convert(found) if found is not None \
            else np.zeros((0, 2), np.float32)
        keys.append(np.asarray(coords, dtype=np.float32).reshape(-1, 2)
                    / scale)
        print("features %s: %d" % (os.path.basename(path), len(coords)),
              flush=True)
    return keys, descs


def match_pair(keys_i, desc_i, keys_j, desc_j, kind):
    """Lowe-ratio matches of one pair, with MagSAC homography outliers
    killed. Returns the surviving pixel coordinates in both frames, or
    None when too few solid matches are left."""
    if desc_i is None or desc_j is None:
        return None
    norm = cv2.NORM_HAMMING if kind == "AKAZE" else cv2.NORM_L2
    raw = cv2.BFMatcher(norm).knnMatch(desc_i, desc_j, k=2)
    good = [
        (keys_i[m.queryIdx][:2].copy(), keys_j[m.trainIdx][:2].copy())
        for m in (pair[0] for pair in raw if len(pair) == 2)
        if m[0].distance < 0.75 * m[1].distance
    ]
    if len(good) < MIN_PAIR_INLIERS:
        return None
    src = np.float32([g[0] for g in good])
    dst = np.float32([g[1] for g in good])
    try:
        _, mask = cv2.findHomography(src, dst, cv2.USAC_MAGSAC, 3.0,
                                     maxIters=10000, confidence=0.9999)
    except cv2.error:
        return None
    if mask is None or mask[:, 0].sum() < MIN_PAIR_INLIERS:
        return None
    keep = mask[:, 0].astype(bool)
    return src[keep], dst[keep]


def rays_from_pixels(px, fx, fy, cx, cy):
    """Unproject pixel coordinates into unit camera-frame rays. Image y
    grows downward, so the up component of a ray is negative."""
    dirs = np.stack([
        (px[:, 0] - cx) / fx,
        -(px[:, 1] - cy) / fy,
        np.ones(len(px)),
    ], axis=1)
    dirs /= np.linalg.norm(dirs, axis=1, keepdims=True)
    return dirs


def horn_rotation(rays_src, rays_dst):
    """The Horn closed-form rotation carrying source rays onto
    destination rays, with the reflection case handled."""
    cov = rays_dst.T @ rays_src
    u, _, vt = np.linalg.svd(cov)
    rot = u @ vt
    if np.linalg.det(rot) < 0:
        flip = np.eye(3)
        flip[2, 2] = -1
        rot = u @ flip @ vt
    return rot


def rotation_residual(rot, rays_src, rays_dst):
    """Mean chord distance of the fit, on the unit-ray scale."""
    wrong = rays_dst - rays_src @ rot.T
    return float(np.sqrt(((wrong ** 2).sum(axis=1).mean())))


def pair_measured_rotation(src, dst, fx, fy, fw, fh):
    """Rotation fit of one matched pair at a given focal length, with the
    fit residual."""
    rays_i = rays_from_pixels(src, fx, fy, fw / 2.0, fh / 2.0)
    rays_j = rays_from_pixels(dst, fx, fy, fw / 2.0, fh / 2.0)
    rot = horn_rotation(rays_i, rays_j)
    return rot, rotation_residual(rot, rays_i, rays_j)


def refine_chain(frames, measured, closure_rot):
    """Chains the poses forward through the measured relative rotations
    and spreads the loop-closure residual back over the chain.

    [frames] start from the initial guess, one right/up/forward triple per
    frame. [measured][i] carries frame i rays onto frame i+1 rays, and
    [closure_rot] carries the last frame's rays onto the first frame's.
    Returns the refined triples.
    """
    refined = [np.array(triple, dtype=np.float64) for triple in frames]
    count = len(refined)
    for i in range(count - 1):
        refined[i + 1] = refined[i] @ measured[i].T

    # The chain predicts B0^T B_last; the measured closure says what it
    # must be. Their mismatch, conjugated into the world frame and spread
    # with an exponent that anchors frame 0 and fully applies at the last
    # frame, closes the ring.
    chain_pred = refined[0].T @ refined[-1]
    corr = closure_rot @ chain_pred.T
    world = refined[0] @ corr @ refined[0].T
    gold, _ = cv2.Rodrigues(np.float32(world))
    axis = gold[:, 0].astype(np.float64)
    angle = float(np.linalg.norm(axis))
    if angle > 1e-9:
        axis /= angle
        for k in range(1, count):
            share = k / max(1, count - 1)
            rot, _ = cv2.Rodrigues(np.float32(axis * angle * share))
            refined[k] = rot.astype(np.float64) @ refined[k]
    return refined


def main():
    ap = argparse.ArgumentParser(
        description="Stitch session frames with content-refined poses.")
    ap.add_argument("directory", nargs="?", default=None)
    ap.add_argument("-o", "--output", default="offline_pano.jpg")
    ap.add_argument("--w", type=int, default=2048)
    ap.add_argument("--every", type=int, default=0,
                    help="pick only this many frames, spread evenly")
    ap.add_argument("--hfov", type=float, default=65.0,
                    help="initial field of view guess, horizontal, degrees")
    ap.add_argument("--no-refine", action="store_true",
                    help="skip the feature alignment, render on raw poses")
    ap.add_argument("--detector", choices=["sift", "akaze"], default="sift")
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

    files = list_frames(args.directory, args.every)
    if not files:
        ap.error("no frame_*.jpg in %s" % args.directory)

    bases, poses = [], []
    for path in files:
        bases.append(read_basis(path))
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

    def fov_to_focal(hfov_deg):
        """Per-axis focal length for one horizontal field of view."""
        vfov = float(np.degrees(
            2 * np.arctan(np.tan(np.radians(hfov_deg) / 2)) / aspect))
        return ((fw / 2) / np.tan(np.radians(hfov_deg / 2)),
                (fh / 2) / np.tan(np.radians(vfov / 2)))

    # Basis per frame: the EXIF matrix wins over the angles, and the ring
    # fallback is last resort.
    frames = []
    for basis, pose in zip(bases, poses):
        frames.append(basis_frame(basis) if basis is not None
                      else camera_frame(*pose))

    chosen_fov = args.hfov
    refined = False
    if not args.no_refine:
        detector, kind = make_detector(args.detector)
        print("detector: %s" % kind)
        keys, descs = features(files, detector)

        # One inlier pixel set per consecutive pair, plus the ring closure,
        # matched once at the initial field of view.
        pairs = {i: match_pair(keys[i], descs[i], keys[i + 1], descs[i + 1],
                               kind)
                 for i in range(len(files) - 1)}
        closure = match_pair(keys[-1], descs[-1], keys[0], descs[0], kind)
        missing = [str(i) for i, p in pairs.items() if p is None]
        if missing or closure is None:
            print("WARNING: pairs without matches: %s%s" % (
                ", ".join(missing), " and ring closure"
                if closure is None else ""), file=sys.stderr)
        else:
            # Field of view scan: the winner minimises the mean rotation
            # residual over every pair. The matched pixels are fixed
            # already, so a whole scan is only cheap ray algebra.
            low = max(FOV_FLOOR, args.hfov - FOV_SCAN_HALFSPAN)
            scores = {}
            for candidate in np.arange(low, args.hfov + FOV_SCAN_HALFSPAN +
                                       1e-9, FOV_SCAN_STEP):
                fx, fy = fov_to_focal(float(candidate))
                total = 0.0
                for i, edge in pairs.items():
                    total += pair_measured_rotation(*edge, fx, fy, fw, fh)[1]
                closure_resid = pair_measured_rotation(
                    *closure, fx, fy, fw, fh)[1]
                scores[float(candidate)] = \
                    (total + closure_resid) / len(pairs)
            best = min(scores, key=scores.get)
            print("fov scan: chosen %.1f (residual %.3e; initial %.1f "
                  "scored %.3e)" % (best, scores[best], args.hfov,
                                    scores.get(float(args.hfov),
                                               float("inf"))))
            chosen_fov = best
            fx, fy = fov_to_focal(best)

            measured = {
                i: pair_measured_rotation(*edge, fx, fy, fw, fh)[0]
                for i, edge in pairs.items()
            }
            closure_rot = pair_measured_rotation(*closure, fx, fy, fw, fh)[0]
            frames = refine_chain(frames, measured, closure_rot)
            refined = True
            print("poses refined through %d pairs + ring closure" % len(files))
        if not refined:
            print("keeping the initial poses (refinement failed)",
                  file=sys.stderr)

    fx, fy = fov_to_focal(chosen_fov)
    right = [np.float64(f[0]) for f in frames]
    ups = [np.float64(f[1]) for f in frames]
    fwds = [np.float64(f[2]) for f in frames]

    canvas_h = args.w // 2
    us = (np.arange(args.w, dtype=np.float64) / args.w - 0.5) * 2 * np.pi
    vs = (0.5 - np.arange(canvas_h, dtype=np.float64) / canvas_h) * np.pi
    uu, vv = np.meshgrid(us, vs)
    dirs = np.stack([np.sin(uu) * np.cos(vv), np.cos(uu) * np.cos(vv),
                     np.sin(vv)], axis=-1).astype(np.float32)

    half_w, half_h = fw // 2, fh // 2
    feather = np.ones((half_h, half_w), np.float32)
    r = min(FEATHER_PX, half_w // 8)
    feather[:, :r] *= np.linspace(0, 1, r, dtype=np.float32)
    feather[:, -r:] *= np.linspace(1, 0, r, dtype=np.float32)
    feather[:r, :] *= np.linspace(0, 1, r, dtype=np.float32)[:, None]
    feather[-r:, :] *= np.linspace(1, 0, r, dtype=np.float32)[:, None]

    warped, weights = [], []
    for index, (path, rr, upV, ff) in enumerate(
            zip(files, right, ups, fwds)):
        # Pivot referral: the lens sits ahead of the pivot, so the ray the
        # camera saw at a scene point is the point's direction minus the
        # lever arm, in the same units as --scene.
        adj = args.scene * dirs - args.lever * ff[None, None, :]
        adj /= np.linalg.norm(adj, axis=2, keepdims=True)
        z = adj @ ff
        front = z > 0.02
        x = adj @ rr / np.maximum(z, 1e-9)
        y = adj @ upV / np.maximum(z, 1e-9)
        px = (x * fx + fw / 2.0).astype(np.float32) * 0.5
        py = (fh / 2.0 - y * fy).astype(np.float32) * 0.5
        inside = front & (px >= 0) & (px < half_w) & (py >= 0) & (py < half_h)
        if not inside.any():
            continue
        image = cv2.imread(path)
        image = cv2.resize(image, (half_w, half_h),
                           interpolation=cv2.INTER_AREA)
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
    pano = sum(image * mask[..., None] for image, mask in zip(warped, masks))
    pano = np.clip(pano, 0, 255).astype(np.uint8)

    span = min(WRAP_SEAM_PX, args.w // 4)
    left = pano[:, :span].astype(np.float32)
    right_side = pano[:, -span:].astype(np.float32)
    ramp = (np.arange(span, dtype=np.float32) / span)[None, :, None]
    pano[:, :span] = np.clip(
        (1 - ramp) * left + ramp * right_side, 0, 255).astype(np.uint8)
    cv2.imwrite(args.output, pano, [cv2.IMWRITE_JPEG_QUALITY, 92])
    coverage = float((stack.max(axis=0) > 0).mean())
    print("saved %s, coverage %.1f%%" % (args.output, coverage * 100))


if __name__ == "__main__":
    main()
