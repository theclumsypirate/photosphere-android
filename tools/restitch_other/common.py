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
