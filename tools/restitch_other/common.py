"""Shared warp for the restitch_other loop: sensor-pose equirect warp.

Same geometry as tools/pano_proto/stitch_all36.py (pivot-referred lever),
factored so each experiment only differs in gain/seam/blend.
"""
import glob
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "pano_proto"))
from stitch_all36 import camera_frame, read_pose

# Refinement gates (Kotlin PoseRefiner values): ORB features, Lowe ratio,
# rotation RANSAC threshold, iterations, minimum inliers, and the maximum
# correction trusted (bigger means the match is probably wrong, not the
# sensor).
ORB_FEATURES = 1500
LOWE_RATIO = 0.8
RANSAC_DEG = 2.0
RANSAC_ITERS = 300
MIN_INLIERS = 12
MAX_CORRECTION_DEG = 6.0
MIN_OVERLAP_PX = 2000


def load_inputs(src_dir, pattern, hfov):
    files = sorted(glob.glob(os.path.join(src_dir, pattern)))
    assert files, f"no {pattern} in {src_dir}"
    probe = cv2.imread(files[0])
    fh, fw = probe.shape[:2]
    aspect = fw / fh
    vfov = float(np.degrees(2 * np.arctan(np.tan(np.radians(hfov / 2)) / aspect)))
    stamped = [read_pose(p) for p in files]
    if all(p is not None for p in stamped):
        print("poses: stamped EXIF", flush=True)
        poses = stamped
    else:
        step = hfov * 0.65
        poses = [((i - (len(files) - 1) / 2) * step, 0.0, 0.0)
                 for i in range(len(files))]
        print(f"poses: even ring ({len(files)} x {step:.1f} deg)", flush=True)
    fx = (fw / 2) / np.tan(np.radians(hfov / 2))
    fy = (fh / 2) / np.tan(np.radians(vfov / 2))
    return files, poses, (fw, fh, fx, fy, cx_cy(fw, fh))


def cx_cy(fw, fh):
    return fw / 2.0, fh / 2.0


def warp_all(files, poses, geom, W, lever, scene, scale=0.5):
    """Inverse-warp every frame onto a W x W/2 equirect canvas.

    Returns (warped, cover): a list of uint8 HxWx3 frames and a stacked
    uint8 HxW coverage array (0/1). uint8 on purpose: 37 float64 frames
    would eat ~2 GB of RAM for no reason; float is only needed at blend.
    """
    fw, fh, fx, fy, (cx, cy) = geom
    H = W // 2
    us = (np.arange(W, dtype=np.float64) / W - 0.5) * 2 * np.pi
    vs = (0.5 - np.arange(H, dtype=np.float64) / H) * np.pi
    uu, vv = np.meshgrid(us, vs)
    dirs = np.stack([np.sin(uu) * np.cos(vv), np.cos(uu) * np.cos(vv),
                     np.sin(vv)], axis=-1)
    sw, sh = int(fw * scale), int(fh * scale)
    warped, cover = [], []
    for path, pose in zip(files, poses):
        fwd, right, up = camera_frame(*pose)
        adj = scene * dirs - lever * fwd[None, None, :]
        adj /= np.linalg.norm(adj, axis=2, keepdims=True)
        z = adj @ fwd
        front = z > 0.02
        x = adj @ right / np.maximum(z, 1e-9)
        y = adj @ up / np.maximum(z, 1e-9)
        px = (x * fx * scale + cx * scale).astype(np.float32)
        py = (cy * scale - y * fy * scale).astype(np.float32)
        inside = front & (px >= 0) & (px < sw) & (py >= 0) & (py < sh)
        if not inside.any():
            continue
        im = cv2.resize(cv2.imread(path), (sw, sh), interpolation=cv2.INTER_AREA)
        sampled = cv2.remap(im, px, py, cv2.INTER_LINEAR,
                            borderMode=cv2.BORDER_CONSTANT)
        warped.append(sampled)
        cover.append(inside.astype(np.uint8))
    print(f"warped {len(warped)}/{len(files)} frames", flush=True)
    return warped, np.stack(cover)


def solve_gains(warped, cover, iters=12, gw=256):
    """Brown-Lowe scalar gains in log space over the overlap graph.

    Everything runs at `gw` px wide: gains only need mean brightness per
    overlap, and full-res overlap tests (37x37 x 2 Mpx x 12 iters) are what
    made the old version hang. Overlap pairs are found once, then reused.
    """
    gh = gw // 2
    gray = np.stack([cv2.resize(cv2.cvtColor(w, cv2.COLOR_BGR2GRAY),
                                (gw, gh), interpolation=cv2.INTER_AREA)
                     .astype(np.float64) for w in warped])
    cov = np.stack([cv2.resize(c.astype(np.float32), (gw, gh),
                               interpolation=cv2.INTER_NEAREST) > 0.5
                    for c in cover])
    N = len(warped)
    pairs = []
    for i in range(N):
        for j in range(i + 1, N):
            ov = cov[i] & cov[j]
            n = int(ov.sum())
            if n < 50:
                continue
            mi = gray[i][ov].mean()
            mj = gray[j][ov].mean()
            pairs.append((i, j, np.log(max(mj, 1e-6)) - np.log(max(mi, 1e-6))))
    print(f"gain graph: {len(pairs)} overlap pairs", flush=True)
    log_gain = np.zeros(N)
    adj = [[] for _ in range(N)]
    for i, j, d in pairs:
        adj[i].append((j, d))
        adj[j].append((i, -d))
    for _ in range(iters):
        for i in range(N):
            if adj[i]:
                log_gain[i] = sum(log_gain[j] + d for j, d in adj[i]) / len(adj[i])
    log_gain -= log_gain.mean()
    gains = np.clip(np.exp(log_gain), 1 / 1.6, 1.6)
    print("gains: " + " ".join(f"{g:.3f}" for g in gains), flush=True)
    return gains


def canvas_bearings(xs, ys, w, h):
    """Canvas pixels to world unit vectors (sphere-center model)."""
    u = (np.asarray(xs, dtype=np.float64) / w - 0.5) * 2 * np.pi
    v = (0.5 - np.asarray(ys, dtype=np.float64) / h) * np.pi
    cu, cv = np.cos(u), np.cos(v)
    return np.stack([np.sin(u) * cv, cu * cv, np.sin(v)], axis=1)


def kabsch_rotation(a, b):
    """Rotation R minimizing |A - R B| for paired unit vectors."""
    h = b.T @ a
    u, _s, vt = np.linalg.svd(h)
    r = vt.T @ u.T
    if np.linalg.det(r) < 0:
        vt[-1] *= -1
        r = vt.T @ u.T
    return r


def ransac_rotation(a, b, thresh_rad, iters):
    """Rotation-only RANSAC over bearing correspondences."""
    n = len(a)
    best, best_in = None, np.zeros(n, bool)
    rng = np.random.default_rng(0)
    for _ in range(iters):
        j = rng.choice(n, 2, replace=False)
        try:
            r = kabsch_rotation(a[j], b[j])
        except np.linalg.LinAlgError:
            continue
        err = np.arccos(np.clip((r @ b.T).T * a, -1, 1).sum(axis=1))
        inl = err < thresh_rad
        if inl.sum() > best_in.sum():
            best, best_in = r, inl
            if inl.sum() > 0.9 * n:
                break
    if best is None or best_in.sum() < 3:
        return None, best_in
    return kabsch_rotation(a[best_in], b[best_in]), best_in


def rotation_angle_deg(r):
    return float(np.degrees(np.arccos(np.clip((np.trace(r) - 1) / 2, -1, 1))))


def roundtrip_pose(fwd, right, up):
    """(fwd, right, up) triplet back to (yaw, pitch, roll) degrees."""
    fwd = np.asarray(fwd, float) / np.linalg.norm(fwd)
    yaw = float(np.degrees(np.arctan2(fwd[0], fwd[1])))
    pitch = float(np.degrees(-np.arcsin(np.clip(fwd[2], -1, 1))))
    yaw_r = np.radians(yaw)
    r0 = np.array([np.cos(yaw_r), -np.sin(yaw_r), 0.0])
    se = np.sin(np.radians(-pitch))
    u0 = np.array([-r0[1] * se, r0[0] * se,
                   r0[0] * fwd[1] - r0[1] * fwd[0]])
    u0 /= max(np.linalg.norm(u0), 1e-9)
    right = np.asarray(right, float)
    roll = float(np.degrees(np.arctan2(right @ u0, right @ r0)))
    return yaw, pitch, roll


def refine_poses(imgs, covs, poses, w, h, passes=1):
    """Correct small sensor rotation errors, frame vs running mosaic.

    Matches ORB features between each frame and the mosaic built so far,
    solves the rotation-only correction with RANSAC on bearing vectors,
    and keeps it only when 12+ inliers agree within 2 degrees and the
    total correction is under 6 degrees. Returns corrected pose list.
    """
    n = len(imgs)
    orb = cv2.ORB_create(ORB_FEATURES)
    bf = cv2.BFMatcher(cv2.NORM_HAMMING)
    poses = [list(p) for p in poses]
    gray = [cv2.cvtColor(im, cv2.COLOR_BGR2GRAY) for im in imgs]
    for _ in range(passes):
        mosaic = np.zeros_like(imgs[0])
        mcover = np.zeros((h, w), bool)
        for i in range(n):
            if i > 0:
                ov = mcover & covs[i].astype(bool)
                if ov.sum() >= MIN_OVERLAP_PX:
                    kp1, d1 = orb.detectAndCompute(gray[i], None)
                    mg = mosaic.astype(np.uint8)
                    kp2, d2 = orb.detectAndCompute(mg, None)
                    if d1 is not None and d2 is not None and len(d1) >= 2 \
                            and len(d2) >= 2:
                        pairs = bf.knnMatch(d1, d2, k=2)
                        good = [m for m_n in pairs if len(m_n) == 2
                                for m in [m_n[0]]
                                if m.distance < LOWE_RATIO * m_n[1].distance]
                        if len(good) >= MIN_INLIERS:
                            pa = np.float32([kp1[m.queryIdx].pt for m in good])
                            pb = np.float32([kp2[m.trainIdx].pt for m in good])
                            ba = canvas_bearings(pa[:, 0], pa[:, 1], w, h)
                            bb = canvas_bearings(pb[:, 0], pb[:, 1], w, h)
                            r, inl = ransac_rotation(
                                bb, ba, np.radians(RANSAC_DEG), RANSAC_ITERS)
                            if r is not None and inl.sum() >= MIN_INLIERS:
                                deg = rotation_angle_deg(r)
                                if deg <= MAX_CORRECTION_DEG:
                                    f, rr, u = camera_frame(*poses[i])
                                    r_old = np.column_stack([rr, u, f])
                                    r_new = r @ r_old
                                    poses[i] = list(roundtrip_pose(
                                        r_new[:, 2], r_new[:, 0], r_new[:, 1]))
                                    print(f"frame {i + 1}: "
                                          f"corrected {deg:.2f} deg "
                                          f"({int(inl.sum())} inliers)",
                                          flush=True)
            take = covs[i].astype(bool) & ~mcover
            mosaic[take] = imgs[i][take]
            mcover |= covs[i].astype(bool)
    return [tuple(p) for p in poses]
