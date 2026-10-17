#!/usr/bin/env python3
"""Closed-loop stitch for virtual frames: NO gain compensation.

All virtual frames are ray-cast from one pano, so they share one exposure
by construction. Any per-frame gain only overfits noise and prints tone
bands (the v14 defect). This script does pure geometry: pivot-model warp,
winner-takes-all ownership by feather weight, agreement-gated crossfade on
borders only. If poses and projection match, output must equal the source.

Usage: python3 tools/pano_proto/stitch_closed.py --dir out/virtual_stairs --out stairs_perfect [--w 4096]
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
H_FOV, V_FOV = 49.0, 63.0
LEVER_M, SCENE_M = 0.35, 3.5
FEATHER_PX = 80


def read_pose(path):
    """Attitude for one frame.

    Prefers a sidecar written beside the frame (`<name>.pose`, the debug
    capture screen's output), so a frame whose JPEG is never rewritten still
    carries its pose. Falls back to the EXIF UserComment the normal app
    stamps. Both use the same `yaw=..;pitch=..;roll=..` form.
    """
    sidecar = os.path.splitext(path)[0] + ".pose"
    if os.path.exists(sidecar):
        with open(sidecar) as fh:
            m = ATT_RE.search(fh.read())
        if m:
            return tuple(map(float, m.groups()))
    ex = Image.open(path).getexif()
    sub = ex.get_ifd(0x8769)
    for k, v in sub.items():
        if TAGS.get(k, k) == "UserComment":
            s = bytes(v).decode("utf-8", "replace") if isinstance(v, bytes) else str(v)
            m = ATT_RE.search(s)
            if m:
                return tuple(map(float, m.groups()))
    raw = str(sub.get(0x9286, ""))
    m = ATT_RE.search(raw)
    return tuple(map(float, m.groups())) if m else None


def read_upright(path):
    """cv2 image with any EXIF orientation applied, so a portrait capture
    whose pixels are stored landscape is not stitched sideways."""
    img = cv2.imread(path)
    if img is None:
        return None
    try:
        ex = Image.open(path).getexif()
        orient = ex.get(274, 1)
    except Exception:
        orient = 1
    if orient == 3:
        img = cv2.rotate(img, cv2.ROTATE_180)
    elif orient == 6:
        img = cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE)
    elif orient == 8:
        img = cv2.rotate(img, cv2.ROTATE_90_COUNTERCLOCKWISE)
    return img


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


def main(d, out, W, fw=2448, fh=3264, h_fov=H_FOV, v_fov=V_FOV):
    H = W // 2
    files = sorted(glob.glob(os.path.join(d, "*.jpg")))
    fx = (fw / 2) / np.tan(np.radians(h_fov / 2))
    fy = (fh / 2) / np.tan(np.radians(v_fov / 2))
    cx, cy = fw / 2.0, fh / 2.0

    us = (np.arange(W, dtype=np.float64) / W - 0.5) * 2 * np.pi
    vs = (0.5 - np.arange(H, dtype=np.float64) / H) * np.pi
    uu, vv = np.meshgrid(us, vs)
    dirs = np.stack([np.sin(uu) * np.cos(vv), np.cos(uu) * np.cos(vv),
                     np.sin(vv)], axis=-1)

    feather = np.ones((fh // 2, fw // 2), np.float32)
    r = min(FEATHER_PX, fw // 8)
    feather[:, :r] *= np.linspace(0, 1, r, dtype=np.float32)
    feather[:, -r:] *= np.linspace(1, 0, r, dtype=np.float32)
    feather[:r, :] *= np.linspace(0, 1, r, dtype=np.float32)[:, None]
    feather[-r:, :] *= np.linspace(1, 0, r, dtype=np.float32)[:, None]

    def warp_one(path, pose):
        fwd, right, up = camera_frame(*pose)
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
        im = read_upright(path)
        if im is None:
            return None
        im = cv2.resize(im, (fw // 2, fh // 2), interpolation=cv2.INTER_AREA)
        s = 0.5
        sampled = cv2.remap(im, (px * s).astype(np.float32), (py * s).astype(np.float32),
                            cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
        w = cv2.remap(feather, (px * s).astype(np.float32), (py * s).astype(np.float32),
                      cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
        w[~inside] = 0
        return sampled.astype(np.float64), w.astype(np.float64)

    poses = []
    for path in files:
        pose = read_pose(path)
        if pose is not None:
            poses.append((path, pose))
    print(f"{len(poses)} frames with poses", flush=True)

    warped = []
    for path, pose in poses:
        r_ = warp_one(path, pose)
        if r_ is not None:
            warped.append(r_)
    print(f"{len(warped)} warped", flush=True)

    best_w = np.full((H, W), -1.0, np.float64)
    owner = np.full((H, W), -1, np.int16)
    pano = np.zeros((H, W, 3), np.float64)
    for idx, (img, w) in enumerate(warped):
        take = w > best_w
        pano[take] = img[take]  # NO gain: one exposure by construction
        best_w = np.maximum(best_w, w)
        owner[take] = idx
    cnt = (best_w > 0).astype(np.float64)

    # ownership borders: 50/50 blend only where owners agree (diff < 12),
    # hard edge otherwise. No translucency by construction.
    own32 = owner.astype(np.int32)
    border = np.zeros((H, W), bool)
    border[1:, :] |= (own32[1:, :] != own32[:-1, :])
    border[:, 1:] |= (own32[:, 1:] != own32[:, :-1])
    border &= (own32 >= 0)
    if border.any():
        k = np.ones((9, 9), np.float32)
        for idx in sorted(set(owner[border].tolist())):
            if idx < 0:
                continue
            zone = (cv2.dilate((owner == idx).astype(np.uint8), k) > 0) & border
            if not zone.any():
                continue
            alt = np.full((H, W), -1.0, np.float64)
            alt_img = np.zeros((H, W, 3), np.float64)
            for jdx, (img2, w2) in enumerate(warped):
                if jdx == idx:
                    continue
                cand = zone & (w2 > 0.05)
                if not cand.any():
                    continue
                take2 = cand & (w2 > alt)
                alt_img[take2] = img2[take2]
                alt[take2] = w2[take2]
            agree = zone & (alt > 0)
            d = np.abs(warped[idx][0][agree] - alt_img[agree]).mean(axis=1)
            ok = np.zeros((H, W), bool)
            ok[agree] = d < 12
            pano[ok] = 0.5 * pano[ok] + 0.5 * alt_img[ok]

    hole = (cnt == 0)
    print(f"coverage {(~hole).mean() * 100:.2f}%, holes {int(hole.sum())}", flush=True)
    if hole.any():
        mask = (hole & (cv2.dilate((cnt > 0).astype(np.uint8),
                                   np.ones((41, 41), np.uint8)) > 0))
        filled = cv2.inpaint(np.clip(pano, 0, 255).astype(np.uint8),
                             mask.astype(np.uint8) * 255, 21,
                             cv2.INPAINT_TELEA).astype(np.float64)
        pano[mask] = filled[mask]

    pano = np.clip(pano, 0, 255).astype(np.uint8)
    cv2.imwrite(out, pano, [cv2.IMWRITE_JPEG_QUALITY, 95])
    print(f"saved {out}", flush=True)
    return 0


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=None)
    ap.add_argument("--out", default="stairs_perfect")
    ap.add_argument("--w", type=int, default=2048)
    # Frame optics. The defaults match the virtual set cut from stairs_360.jpg
    # (3:4 portrait, 49 deg horizontal, 63 deg vertical). A real phone capture
    # has its own lens, so override these when stitching real frames.
    ap.add_argument("--fw", type=int, default=2448)
    ap.add_argument("--fh", type=int, default=3264)
    ap.add_argument("--hfov", type=float, default=H_FOV)
    ap.add_argument("--vfov", type=float, default=V_FOV)
    a = ap.parse_args()
    os.makedirs(os.path.join(here, "out"), exist_ok=True)
    sys.exit(main(a.dir or os.path.join(here, "ref", "all36"),
                  os.path.join(here, "out", a.out + ".jpg"), a.w,
                  a.fw, a.fh, a.hfov, a.vfov))
