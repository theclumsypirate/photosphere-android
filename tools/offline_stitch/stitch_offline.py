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
FOV_PATTERN = re.compile(r";hfov=(-?\d+(?:\.\d+)?);vfov=(-?\d+(?:\.\d+)?)")
FEATHER_PX = 80
WRAP_SEAM_PX = 64
WINNER_BLUR_PX = 7
RUN_CONFIRM = "yeah"
DETECT_WIDTH = 640
FOV_SCAN_STEP = 2.5
FOV_SCAN_HALFSPAN = 25.0
FOV_FLOOR = 30.0
# The field of view the renders the user kept were made with. It is the
# horizontal span of an upright portrait frame at this lens; the vertical
# follows from the frame aspect, about 61 degrees tall.
DEFAULT_HFOV = 48.0
MIN_PAIR_INLIERS = 12
# How far a frame's refined pose may stray from its seed, degrees. The
# seeds carry the multi-row structure the content cannot recover on its
# own (texture-poor bands, pole frames), so the refinement only nudges.
# 3 degrees is the setting the render that framed the room cleanly used;
# a wider clamp lets thin sliver matches pull the rows apart.
REFINE_MAX_DEG = 3.0
# SuperPoint + SuperGlue backend, loaded lazily from a local clone of the
# Factral/image-stitching-supeglue style layout (models/matching.py with the
# MagicLeap weights). The SuperGlue sources carry a proprietary banner, so
# they are never vendored into this repo: the tool looks for the clone on
# disk, uses what is there, and says exactly what is missing otherwise.
SUPERGLUE_SOURCE_CANDIDATES = (
    "~/image-stitching-supeglue-main",
    "~/image-stitching-supeglue",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "third_party",
                 "image-stitching-supeglue"),
)
SUPERGLUE_CONFIG = {
    "superpoint": {
        "nms_radius": 5,
        "keypoint_threshold": 0.05,
        "max_keypoints": 2048,
    },
    "superglue": {
        "weights": "outdoor",
        "sinkhorn_iterations": 20,
        "match_threshold": 0.9,
    },
}
# SuperPoint feeds on a capped grayscale tensor, to keep one frame at a
# sane memory size on a laptop CPU.
SUPERGLUE_WIDTH = 1024

_BACKEND = {}


def find_superglue_source():
    """The local clone directory the SuperGlue loads out of, if any."""
    for candidate in SUPERGLUE_SOURCE_CANDIDATES:
        expanded = os.path.expanduser(candidate)
        if os.path.isfile(os.path.join(expanded, "models", "matching.py")):
            return expanded
    return None


def find_pipx_torch():
    """A pipx-installed torch's site-packages, probed on disk; the
    script runs on the system python, which does not see venvs."""
    import glob
    patterns = (
        os.path.expanduser("~/.local/share/pipx/venvs/torch/lib/"
                           "python*/site-packages"),
        os.path.expanduser("~/.local/pipx/venvs/torch/lib/"
                           "python*/site-packages"),
        os.path.expanduser("~/.local/share/pipx/venvs/torch/lib/"
                           "python*/site-packages"),
    )
    for pattern in patterns:
        for found in sorted(glob.glob(pattern)):
            if os.path.isdir(os.path.join(found, "torch")):
                return found
    return None


def superglue_backend():
    """Loads torch plus the clone's Matching module once; dies loudly when
    the pieces are missing so the run tells the user what to install."""
    if not _BACKEND:
        probe = find_pipx_torch()
        if probe and probe not in sys.path:
            sys.path.insert(0, probe)
        try:
            import torch
        except ImportError as error:
            raise SystemExit(
                "SuperGlue backend needs torch on the laptop: "
                "pip install torch (CPU wheel) and rerun") from error
        source = find_superglue_source()
        if source is None:
            raise SystemExit(
                "SuperGlue backend needs the models clone on disk; looked "
                "under: %s" % ", ".join(SUPERGLUE_SOURCE_CANDIDATES))
        torch.set_grad_enabled(False)
        if source not in sys.path:
            sys.path.insert(0, source)
        from models.matching import Matching
        device = "cuda" if torch.cuda.is_available() else "cpu"
        print("SuperGlue backend: %s on %s" % (source, device), flush=True)
        _BACKEND["matching"] = Matching(SUPERGLUE_CONFIG).eval().to(device)
        _BACKEND["torch"] = torch
        _BACKEND["device"] = device
    return _BACKEND


def superglue_tensors(paths, torch, device):
    """One capped grayscale tensor per frame, cached for the whole run."""
    tensors = []
    for path in paths:
        gray = cv2.cvtColor(cv2.imread(path), cv2.COLOR_BGR2GRAY)
        scale = min(1.0, SUPERGLUE_WIDTH / max(gray.shape))
        small = cv2.resize(gray, None, fx=scale, fy=scale,
                           interpolation=cv2.INTER_AREA)
        image = small.astype("float32") / 255.0
        tensor = torch.from_numpy(image)[None, None].to(device)
        tensors.append(tensor)
    return tensors


def superglue_pair(images, index_i, index_j, scale_back):
    """One pair's matched pixels through SuperPoint + SuperGlue, with
    MagSAC outliers culled on top of the learned confidence. Returns
    (src, dst) full-res pixel arrays or None."""
    backend = superglue_backend()
    pred = backend["matching"]({
        "image0": images[index_i],
        "image1": images[index_j],
    })
    keys_i = pred["keypoints0"][0].cpu().numpy()
    keys_j = pred["keypoints1"][0].cpu().numpy()
    links = pred["matches0"][0].cpu().numpy().astype(int)
    keep = links > -1
    if keep.sum() == 0:
        return None
    src = keys_i[keep] / scale_back
    dst = keys_j[links[keep]] / scale_back
    try:
        _, mask = cv2.findHomography(src, dst, cv2.USAC_MAGSAC, 3.0,
                                     maxIters=10000, confidence=0.9999)
    except cv2.error:
        return None
    if mask is None or mask[:, 0].sum() < MIN_PAIR_INLIERS:
        return None
    usable = mask[:, 0].astype(bool)
    return src[usable], dst[usable]





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


def read_frame_fov(path):
    """The (horizontal, vertical) field of view a debug build baked into
    one frame's EXIF comment, or None for a release frame."""
    match = FOV_PATTERN.search(read_exif_string(path))
    if not match:
        return None
    return float(match.group(1)), float(match.group(2))


def read_capture_summary(directory):
    """The debug build's capture_debug.json beside the frames, when the
    session came off a debug capture: the exact field of view the capture
    ran with, plus the plan type. Release sessions have no such file and
    the tool falls back to the 48 degree default and the frame aspect."""
    import json
    path = os.path.join(directory, "capture_debug.json")
    try:
        with open(path, "r", encoding="utf-8") as handle:
            summary = json.load(handle)
    except (OSError, ValueError):
        return None
    if not isinstance(summary, dict):
        return None
    return summary


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
    good = []
    for pair in raw:
        if len(pair) != 2:
            continue
        best, second = pair
        if best.distance < 0.75 * second.distance:
            good.append((keys_i[best.queryIdx][:2].copy(),
                         keys_j[best.trainIdx][:2].copy()))
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


def pair_reprojection_error(rot, src, dst, fx, fy, fw, fh):
    """Mean pixel distance between the matched destination pixels and the
    rotation re-projected through this focal length. The scan criterion:
    unlike the ray-space residual, it has a true minimum at the correct
    field of view, because the pixel offsets grow with the distance from
    the optical axis."""
    rays = rays_from_pixels(src, fx, fy, fw / 2.0, fh / 2.0)
    mapped = rays @ rot.T
    xs = fw / 2.0 + mapped[:, 0] / mapped[:, 2] * fx
    ys = fh / 2.0 - mapped[:, 1] / mapped[:, 2] * fy
    error = np.hypot(xs - dst[:, 0], ys - dst[:, 1])
    return float(error.mean())


def pair_measured_rotation(src, dst, fx, fy, fw, fh):
    """Rotation fit of one matched pair at a given focal length, with the
    fit residual."""
    rays_i = rays_from_pixels(src, fx, fy, fw / 2.0, fh / 2.0)
    rays_j = rays_from_pixels(dst, fx, fy, fw / 2.0, fh / 2.0)
    rot = horn_rotation(rays_i, rays_j)
    return rot, rotation_residual(rot, rays_i, rays_j)


def connected_components(count, edges):
    """Every node grouped with the nodes its matched edges reach, biggest
    component first. Content-only runs keep just the first one, because a
    frame the matches never reach has no pose to render with."""
    parent = list(range(count))

    def find(node):
        while parent[node] != node:
            parent[node] = parent[parent[node]]
            node = parent[node]
        return node

    for first, second, _rotation in edges:
        root_a, root_b = find(first), find(second)
        if root_a != root_b:
            parent[root_a] = root_b
    groups = {}
    for node in range(count):
        groups.setdefault(find(node), []).append(node)
    return sorted(groups.values(), key=len, reverse=True)


def refine_components(frames, edges, spread_ring, max_deg=REFINE_MAX_DEG):
    """Chains every connected component of the matched-pair graph forward
    from its anchor node, and for the component that wraps the whole frame
    list with as many matched edges as nodes, spreads the closing residual
    back by graph distance.

    [frames] start from the initial guess, one right/up/forward triple per
    frame. [edges] are (i, j, rot) triples whose rot carries frame i's
    rays onto frame j's rays; frames in components without matches keep
    their initial guesses untouched. [spread_ring] is the loop-closure
    residual distributor, called with the refined frames.
    """
    count = len(frames)
    refined = [np.array(triple, dtype=np.float64) for triple in frames]
    adjacency = {i: [] for i in range(count)}
    for i, j, rot in edges:
        adjacency[i].append((j, rot, True))   # forward: rays i onto j
        adjacency[j].append((i, rot, False))  # backward: rays j onto i

    visited = set()
    for anchor in range(count):
        if anchor in visited:
            continue
        # Breadth-first chain: every basis follows its matched neighbour.
        component = {anchor}
        distances = {anchor: 0}
        queue = [anchor]
        visited.add(anchor)
        while queue:
            node = queue.pop(0)
            for neighbour, rot, forward in adjacency[node]:
                if neighbour in visited:
                    continue
                if forward:
                    refined[neighbour] = refined[node] @ rot.T
                else:
                    refined[neighbour] = refined[node] @ rot
                distances[neighbour] = distances[node] + 1
                visited.add(neighbour)
                component.add(neighbour)
                queue.append(neighbour)

        # The ring case: as many matched edges as nodes in the whole
        # component. The residual goes back by graph distance, anchored
        # on the node the component started from.
        inside = sum(1 for i, j, _ in edges
                     if i in component and j in component)
        if len(component) == count and inside == len(component):
            refined = spread_ring(refined, anchor, distances)

    # Clamp: every refined basis stays within REFINE_MAX_DEG of its seed.
    # Match pairs that live on thin overlap slivers or a distorted lens
    # can pull poses far away; the seeds carry the multi-row structure
    # the content alone cannot rebuild.
    limit = np.radians(max_deg)
    for node in range(count):
        initial = np.asarray(frames[node], dtype=np.float64)
        corr = refined[node] @ initial.T
        gold, _ = cv2.Rodrigues(np.float32(corr))
        axis = gold[:, 0].astype(np.float64)
        angle = float(np.linalg.norm(axis))
        if angle > limit:
            axis = axis / angle * limit
            capped, _ = cv2.Rodrigues(np.float32(axis))
            refined[node] = capped.astype(np.float64) @ initial
    return refined


def main():
    ap = argparse.ArgumentParser(
        description="Stitch session frames with content-refined poses.")
    ap.add_argument("directory", nargs="?", default=None)
    ap.add_argument("-o", "--output", default="offline_pano.jpg")
    ap.add_argument("--w", type=int, default=2048)
    ap.add_argument("--every", type=int, default=0,
                    help="pick only this many frames, spread evenly")
    ap.add_argument("--hfov", type=float, default=None,
                    help="field of view override, horizontal, degrees; "
                         "defaults to 48 with the frame aspect, which is "
                         "what the lens frames this room at")
    ap.add_argument("--fov-from-exif", action="store_true",
                    help="render with the field of view the capture "
                         "stamped into the frames (or the session "
                         "sidecar) instead of the default")
    ap.add_argument("--auto-fov", action="store_true",
                    help="scan the field of view instead of using the "
                         "default or the --hfov value; the scan prefers "
                         "thin overlaps, so this is off by default")
    ap.add_argument("--no-refine", action="store_true",
                    help="skip the feature alignment, render on raw poses")
    ap.add_argument("--detector", choices=["sift", "akaze"], default="sift",
                    help="legacy name for --matcher's SIFT/AKAZE half")
    ap.add_argument("--matcher", choices=["sift", "akaze", "superglue"],
                    default="sift",
                    help="superglue needs torch plus the local clone of "
                         "the SuperPoint/SuperGlue models on disk")
    ap.add_argument("--content-only", action="store_true",
                    help="ignore every pose the frames carry and rebuild "
                         "the sphere from matched pixels alone")
    ap.add_argument("--refine-max", type=float, default=REFINE_MAX_DEG,
                    help="how far one refined pose may stray from its "
                         "seed, degrees")
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
    if args.content_only and args.no_refine:
        ap.error("--content-only needs the refinement pass it builds the "
                 "poses in")
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
    if args.content_only:
        print("poses: none, %d frames rebuilt from matched pixels"
              % len(files))
    elif all(b is not None for b in bases):
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

    def focal_for(hfov_deg, vfov_deg):
        """Per-axis focal length for one field of view pair."""
        return ((fw / 2) / np.tan(np.radians(hfov_deg / 2)),
                (fh / 2) / np.tan(np.radians(vfov_deg / 2)))

    def aspect_vfov(hfov_deg):
        """Vertical field of view implied by the frame aspect ratio."""
        return float(np.degrees(
            2 * np.arctan(np.tan(np.radians(hfov_deg) / 2) / aspect)))

    def fov_to_focal(hfov_deg):
        """Per-axis focal length derived from a horizontal field of view
        and the frame aspect, for the scan and for release sessions."""
        return focal_for(hfov_deg, aspect_vfov(hfov_deg))

    # Field of view, best source first: an explicit --hfov is a manual
    # override; otherwise the default this lens renders correctly at; and
    # --fov-from-exif opts into the capture's own calibration instead. The
    # debug build stamps that calibration into every frame and into the
    # session sidecar, but on this device it reports about 39.7 degrees
    # where the content frames the room at 48, so it is not the default.
    stamped = [fov for fov in (read_frame_fov(path) for path in files)
               if fov is not None]
    summary = read_capture_summary(args.directory)
    hint = None
    if stamped:
        hint = (float(np.median([fov[0] for fov in stamped])),
                float(np.median([fov[1] for fov in stamped])))
    elif summary and summary.get("horizontalFovDegrees") \
            and summary.get("verticalFovDegrees"):
        hint = (float(summary["horizontalFovDegrees"]),
                float(summary["verticalFovDegrees"]))

    if args.hfov is not None:
        hfov = args.hfov
        vfov = aspect_vfov(hfov)
        fov_source = "--hfov %.1f (explicit override)" % hfov
    elif args.fov_from_exif and hint is not None:
        hfov, vfov = hint
        fov_source = (
            "the capture's own calibration (%d/%d frames)"
            % (len(stamped), len(files))
            if stamped else "capture_debug.json")
    else:
        hfov = DEFAULT_HFOV
        vfov = aspect_vfov(hfov)
        fov_source = "the default %.1f with the frame aspect" % hfov
        if hint is not None:
            fov_source += "; the frames report %.2f x %.2f, pass " \
                          "--fov-from-exif to use that" % hint
    print("field of view: %.2f x %.2f deg from %s" % (hfov, vfov, fov_source))

    # Basis per frame: the EXIF matrix wins over the angles, and the ring
    # fallback is last resort.
    frames = []
    if args.content_only:
        # No seed to clamp to: every pose comes out of the matched graph.
        frames = [np.eye(3) for _ in files]
    else:
        for basis, pose in zip(bases, poses):
            frames.append(basis_frame(basis) if basis is not None
                          else camera_frame(*pose))

    chosen_fov = hfov
    refined = False
    superglue_scale = min(1.0, SUPERGLUE_WIDTH / max(fw, fh))
    if not args.no_refine:
        if args.matcher == "superglue":
            backend = superglue_backend()
            images = superglue_tensors(files, backend["torch"],
                                       backend["device"])
            print("matcher: SuperPoint+SuperGlue on %d tensors"
                  % len(images), flush=True)
        else:
            detector, kind = make_detector(
                "sift" if args.matcher == "sift" else "akaze")
            print("backend: %s features" % kind)
            keys, descs = features(files, detector)

        def match(index_i, index_j):
            """One pair's inlier pixels, through the chosen backend."""
            if args.matcher == "superglue":
                return superglue_pair(images, index_i, index_j,
                                      superglue_scale)
            return match_pair(keys[index_i], descs[index_i],
                              keys[index_j], descs[index_j], kind)

        # Candidate pairs come from the seeded poses, not the capture
        # order: a sphere session is captured on several rows and the
        # file sequence jumps between rows. Any two frames whose forward
        # directions sit closer than 80 percent of the horizontal field
        # of view are worth matching.
        fwds = [np.asarray(f, dtype=np.float64)[:, 2]
                for f in frames]
        for fwd in fwds:
            fwd /= np.linalg.norm(fwd)
        candidates = []
        if args.content_only:
            # No poses, so no neighbourhood to narrow the pairs down with.
            # Every pair is tried; the inlier gate and the component
            # filter throw away the rest.
            candidates = [(i, j) for i in range(len(files))
                          for j in range(i + 1, len(files))]
        else:
            for i in range(len(files)):
                for j in range(i + 1, len(files)):
                    arc = float(np.degrees(np.arccos(np.clip(
                        float(fwds[i] @ fwds[j]), -1, 1))))
                    if arc < hfov * 0.8:
                        candidates.append((i, j))
        print("candidate pairs from poses: %d" % len(candidates), flush=True)

        pairs = {}
        for i, j in candidates:
            found = match(i, j)
            if found is not None:
                pairs[(i, j)] = found
        print("matched pairs: %d of %d candidates" % (
            len(pairs), len(candidates)), flush=True)
        if not pairs:
            print("WARNING: no pair matched; keeping the initial poses",
                  file=sys.stderr)
        elif args.auto_fov:
            # Field of view scan: the winner minimises the median pixel
            # reprojection error over the matched pairs. The median keeps
            # one noisy pair from dragging the choice, and the pixel
            # metric, unlike a ray-space residual, has its true minimum
            # at the correct field of view. Ran over the fixed matched
            # pixels, so a scan is only cheap ray algebra.
            low = max(FOV_FLOOR, hfov - FOV_SCAN_HALFSPAN)
            scores = {}
            for candidate in np.arange(low, hfov + FOV_SCAN_HALFSPAN +
                                       1e-9, FOV_SCAN_STEP):
                fx, fy = fov_to_focal(float(candidate))
                errs = []
                for edge in pairs.values():
                    rot = pair_measured_rotation(*edge, fx, fy, fw, fh)[0]
                    errs.append(pair_reprojection_error(
                        rot, edge[0], edge[1], fx, fy, fw, fh))
                scores[float(candidate)] = float(np.median(errs))
            best = min(scores, key=scores.get)
            print("fov scan: chosen %.1f (median px err %.2f; initial "
                  "%.1f scored %.2f)" % (best, scores[best], hfov,
                                         scores.get(float(hfov),
                                                    float("inf"))))
            chosen_fov = best
            vfov = aspect_vfov(best)
        # Chain the seed bases through every matched edge, with the
        # clamp keeping each pose near its seed.
        fx, fy = focal_for(chosen_fov, vfov)
        edges = [
            (i, j, pair_measured_rotation(*edge, fx, fy, fw, fh)[0])
            for (i, j), edge in pairs.items()
        ]
        clamp = 180.0 if args.content_only else args.refine_max
        if args.content_only:
            components = connected_components(len(frames), edges)
            keep = components[0] if components else list(range(len(frames)))
            if len(keep) < len(frames):
                dropped = sorted(set(range(len(frames))) - set(keep))
                remap = {old: new for new, old in enumerate(keep)}
                frames = [frames[i] for i in keep]
                files = [files[i] for i in keep]
                edges = [(remap[i], remap[j], rotation)
                         for (i, j, rotation) in edges
                         if i in remap and j in remap]
                print("content only: %d frames chained, dropped %s "
                      "(no matched overlap to the rest)" % (len(keep), dropped))
            if not edges:
                ap.error("no pair matched; these frames need more overlap")
        frames = refine_components(frames, edges, lambda ref, anchor,
                                   distances: ref, clamp)
        refined = True
        print("poses refined on %d matched edges" % len(edges))
        if not refined:
            print("keeping the initial poses (refinement failed)",
                  file=sys.stderr)

    # Render focal: the calibration pair exactly, never a re-derived
    # vertical from the aspect ratio, when the source carried one.
    fx, fy = focal_for(chosen_fov, vfov)
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
