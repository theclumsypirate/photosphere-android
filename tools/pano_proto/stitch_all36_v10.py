#!/usr/bin/env python3
"""Stitch the user's 37 saved frames with their TRUE stamped poses.

Reads index/yaw/pitch/roll from each file's EXIF UserComment (the same stamp
the app writes), reconstructs the camera basis exactly like
SphereProjection.cameraFrame, and renders an equirectangular canvas
2:1 by back-projecting every canvas pixel (like EquirectangularRenderer).

Usage:
    python3 tools/pano_proto/stitch_all36.py --out all36_v1 [--w 2048]

Poses are trusted; v1 blends overlaps by averaging. Iterate on the blend.
"""
import argparse
import glob
import os
import re
import sys
import cv2
import numpy as np
from PIL import Image
from PIL.ExifTags import TAGS

ATT_RE = re.compile(r"yaw=(-?[\d.]+);pitch=(-?[\d.]+);roll=(-?[\d.]+)")
H_FOV, V_FOV = 49.0, 63.0  # portrait frames, Moto G24 screen FOV
LEVER_M, SCENE_M = 0.35, 3.5  # handheld body-swivel pivot; room-scale scene
FEATHER_PX = 80
WRAP_SEAM_PX = 64


def read_pose(path):
    ex = Image.open(path).getexif()
    sub = ex.get_ifd(0x8769)
    for k, v in sub.items():
        if TAGS.get(k, k) == "UserComment":
            s = bytes(v).decode("utf-8", "replace") if isinstance(v, bytes) else str(v)
            m = ATT_RE.search(s)
            if m:
                return tuple(map(float, m.groups()))
    # EXIF UserComment may decode with undefined-prefix; try raw string form
    raw = str(sub.get(0x9286, ""))
    m = ATT_RE.search(raw)
    return tuple(map(float, m.groups())) if m else None


def camera_frame(yaw_d, pitch_d, roll_d):
    yaw, elev, roll = np.radians(yaw_d), np.radians(-pitch_d), np.radians(roll_d)
    sy, cy, ce = np.sin(yaw), np.cos(yaw), np.cos(elev)
    fx, fy, fz = sy * ce, cy * ce, np.sin(elev)
    r0x, r0y = cy, -sy
    u0x, u0y = r0y * fz, -r0x * fz
    u0z = r0x * fy - r0y * fx
    cr, sr = np.cos(roll), np.sin(roll)
    fwd = np.array([fx, fy, fz])
    right = np.array([r0x * cr - u0x * sr, r0y * cr - u0y * sr, -u0z * sr])
    up = np.array([u0x * cr + r0x * sr, u0y * cr + r0y * sr, u0z * cr])
    return fwd, right, up


def main(d, out, W):
    H = W // 2
    files = sorted(glob.glob(os.path.join(d, "*.jpg")))
    print(f"{len(files)} frames", flush=True)
    fw, fh = 2448, 3264
    fx = (fw / 2) / np.tan(np.radians(H_FOV / 2))
    fy = (fh / 2) / np.tan(np.radians(V_FOV / 2))
    cx, cy = fw / 2.0, fh / 2.0

    us = (np.arange(W, dtype=np.float64) / W - 0.5) * 2 * np.pi
    vs = (0.5 - np.arange(H, dtype=np.float64) / H) * np.pi
    uu, vv = np.meshgrid(us, vs)
    dirs = np.stack([np.sin(uu) * np.cos(vv), np.cos(uu) * np.cos(vv),
                     np.sin(vv)], axis=-1)

    acc = np.zeros((H, W, 3), np.float64)
    cnt = np.zeros((H, W), np.float64)
    my, mx = np.mgrid[0:fh // 2, 0:fw // 2].astype(np.float32)
    feather = np.ones((fh // 2, fw // 2), np.float32)
    r = min(FEATHER_PX, fw // 8)
    feather[:, :r] *= np.linspace(0, 1, r, dtype=np.float32)
    feather[:, -r:] *= np.linspace(1, 0, r, dtype=np.float32)
    feather[:r, :] *= np.linspace(0, 1, r, dtype=np.float32)[:, None]
    feather[-r:, :] *= np.linspace(1, 0, r, dtype=np.float32)[:, None]
    # core window: steep near-pole frames contribute only their sharp center,
    # so one frame owns each pole pixel instead of three smeared copies
    core = np.zeros_like(feather)
    ch0, ch1 = int(fh // 2 * 0.20), int(fh // 2 * 0.80)
    cw0, cw1 = int(fw // 2 * 0.20), int(fw // 2 * 0.80)
    core[ch0:ch1, cw0:cw1] = 1.0
    core = cv2.GaussianBlur(core, (0, 0), 25)
    poses = []
    for path in files:
        pose = read_pose(path)
        if pose is None:
            print(f"SKIP (no pose): {os.path.basename(path)}", flush=True)
            continue
        poses.append((path, pose))
    print(f"{len(poses)} frames with poses", flush=True)

    def warp_one(path, pose, mask):
        """Project one frame to canvas: warped image, warped weight, mask."""
        fwd, right, up = camera_frame(*pose)
        # pivot referral: the lens sits ahead of the pivot, so the ray the
        # camera saw is the scene point minus the lever arm
        adj = SCENE_M * dirs - LEVER_M * fwd[None, None, :]
        adj /= np.linalg.norm(adj, axis=2, keepdims=True)
        z = adj @ fwd
        front = z > 0.02
        x = adj @ right / np.maximum(z, 1e-9)
        y = adj @ up / np.maximum(z, 1e-9)
        px = (x * fx + cx).astype(np.float32)
        py = (cy - y * fy).astype(np.float32)
        inside = front & (px >= 0) & (px < fw) & (py >= 0) & (py < fh)
        if not inside.any():
            return None
        im = cv2.imread(path)
        im = cv2.resize(im, (fw // 2, fh // 2), interpolation=cv2.INTER_AREA)
        s = 0.5
        sampled = cv2.remap(im, (px * s).astype(np.float32), (py * s).astype(np.float32),
                            cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
        w = cv2.remap(mask, (px * s).astype(np.float32), (py * s).astype(np.float32),
                      cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
        w[~inside] = 0
        return sampled.astype(np.float64), w.astype(np.float64)

    def add(acc, cnt, img, w):
        acc += img * w[..., None]
        cnt += w

    # --- v6: per-frame gain pass. Each frame's median luma over its valid
    # canvas pixels is pulled toward the global median, so WTA ownership
    # changes do not print as exposure steps.
    def frame_gain(path, pose, mask):
        r = warp_one(path, pose, mask)
        if r is None:
            return None, None
        img, w = r
        valid = w > 0.01
        if not valid.any():
            return None, None
        luma = img[..., 0] * 0.114 + img[..., 1] * 0.587 + img[..., 2] * 0.299
        return float(np.median(luma[valid])), r

    # base canvas from the well-behaved frames; steep near-pole frames (|pitch|
    # over 60, where Euler rolls collapse) contribute only their sharp center
    # v6: winner-takes-all ownership (no averaging, so no translucency) with
    # per-frame gain compensation. Each canvas pixel comes from exactly one
    # frame: the one with the highest feather weight there.
    jobs = []
    for path, pose in poses:
        mask = core if abs(pose[1]) > 60 else feather
        med, r = frame_gain(path, pose, mask)
        if r is None:
            continue
        jobs.append((path, pose, med, r))
    global_med = float(np.median([m for _, _, m, _ in jobs]))
    best_w = np.full((H, W), -1.0, np.float64)
    pano = np.zeros((H, W, 3), np.float64)
    for path, pose, med, (img, w) in jobs:
        g = global_med / max(med, 1e-6)
        g = min(max(g, 0.5), 2.0)
        take = w > best_w
        pano[take] = np.clip(img[take] * g, 0, 255)
        best_w = np.maximum(best_w, w)
        kind = "core" if abs(pose[1]) > 60 else "ok"
        print(f"{kind} {os.path.basename(path)} yaw={pose[0]:.0f} pitch={pose[1]:.0f} gain={g:.2f}", flush=True)
    cnt = (best_w > 0).astype(np.float64)
    # wrap seam: canvas columns 0 and W-1 are adjacent in yaw but far apart in
    # memory, so feathering never crosses the closure line. Blend it by hand.
    # wrap seam under WTA: each row of the strip is owned wholly by the left
    # or the right edge, whichever had the stronger frame there. No blending,
    # so no translucency at the closure line either.
    S = min(WRAP_SEAM_PX, W // 4)
    own_left = best_w[:, :S] >= best_w[:, -S:]
    pano[:, :S][~own_left] = pano[:, -S:][~own_left]
    pano = np.clip(pano, 0, 255).astype(np.uint8)
    print(f"coverage {(cnt > 0).mean() * 100:.1f}%", flush=True)
    cv2.imwrite(out, pano, [cv2.IMWRITE_JPEG_QUALITY, 92])
    print(f"saved {out}", flush=True)
    return 0


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=None)
    ap.add_argument("--out", default="all36_v1")
    ap.add_argument("--w", type=int, default=2048)
    a = ap.parse_args()
    os.makedirs(os.path.join(here, "out"), exist_ok=True)
    sys.exit(main(a.dir or os.path.join(here, "ref", "all36"),
                  os.path.join(here, "out", a.out + ".jpg"), a.w))
