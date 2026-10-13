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
    poses = []
    for path in files:
        pose = read_pose(path)
        if pose is None:
            print(f"SKIP (no pose): {os.path.basename(path)}", flush=True)
            continue
        poses.append((path, pose))
    print(f"{len(poses)} frames with poses", flush=True)

    def warp_one(path, pose):
        """Project one frame to canvas: warped image, feather weight, mask."""
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
        w = cv2.remap(feather, (px * s).astype(np.float32), (py * s).astype(np.float32),
                      cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
        w[~inside] = 0
        return sampled.astype(np.float64), w.astype(np.float64)

    def add(acc, cnt, img, w):
        acc += img * w[..., None]
        cnt += w

    # base canvas from the well-behaved frames; steep near-pole frames (|pitch|
    # over 60, where Euler rolls collapse) are content-refined below
    acc = np.zeros((H, W, 3), np.float64)
    cnt = np.zeros((H, W), np.float64)
    steep = []
    for path, pose in poses:
        if abs(pose[1]) > 60:
            steep.append((path, pose))
            continue
        r = warp_one(path, pose)
        if r is None:
            continue
        add(acc, cnt, *r)
        print(f"ok {os.path.basename(path)} yaw={pose[0]:.0f} pitch={pose[1]:.0f}", flush=True)

    with np.errstate(invalid="ignore", divide="ignore"):
        base = np.where(cnt[..., None] > 1e-6, acc / np.maximum(cnt, 1e-6)[..., None], 0)
    base_gray = cv2.cvtColor(np.clip(base, 0, 255).astype(np.uint8),
                             cv2.COLOR_BGR2GRAY).astype(np.float32)
    criteria = (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 60, 1e-4)
    for path, pose in steep:
        r = warp_one(path, pose)
        if r is None:
            continue
        img, w = r
        name = os.path.basename(path)
        try:
            moving = cv2.cvtColor(np.clip(img, 0, 255).astype(np.uint8),
                                  cv2.COLOR_BGR2GRAY).astype(np.float32)
            warp = np.eye(2, 3, dtype=np.float32)
            cc, warp = cv2.findTransformECC(
                base_gray, moving, warp, cv2.MOTION_EUCLIDEAN, criteria,
                (w > 0.05).astype(np.uint8), 5)
        except cv2.error as e:
            print(f"ecc {name}: failed ({str(e)[:60]}), keeping pose", flush=True)
            add(acc, cnt, img, w)
            continue
        if cc < 0.55:
            print(f"ecc {name}: rho={cc:.2f} too low, keeping pose", flush=True)
            add(acc, cnt, img, w)
            continue
        img2 = cv2.warpAffine(img, warp, (W, H),
                              flags=cv2.INTER_LINEAR + cv2.WARP_INVERSE_MAP)
        w2 = cv2.warpAffine(w, warp, (W, H),
                            flags=cv2.INTER_LINEAR + cv2.WARP_INVERSE_MAP)
        add(acc, cnt, img2, w2)
        print(f"ecc {name}: rho={cc:.2f} corrected", flush=True)

    pano = np.where(cnt[..., None] > 1e-6, acc / np.maximum(cnt, 1e-6)[..., None], 0)
    # wrap seam: canvas columns 0 and W-1 are adjacent in yaw but far apart in
    # memory, so feathering never crosses the closure line. Blend it by hand.
    S = min(WRAP_SEAM_PX, W // 4)
    left = pano[:, :S].astype(np.float64)
    right = pano[:, -S:].astype(np.float64)
    a = (np.arange(S, dtype=np.float64) / S)[None, :, None]
    pano[:, :S] = np.clip((1 - a) * left + a * right, 0, 255).astype(np.uint8)
    pano = np.clip(pano, 0, 255).astype(np.uint8)
    print(f"coverage {(cnt > 0).mean() * 100:.1f}%", flush=True)
    cv2.imwrite(out, pano, [cv2.IMWRITE_JPEG_QUALITY, 92])
    print(f"saved {out}", flush=True)
    return 0


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="all36_v1")
    ap.add_argument("--w", type=int, default=2048)
    a = ap.parse_args()
    os.makedirs(os.path.join(here, "out"), exist_ok=True)
    sys.exit(main(os.path.join(here, "ref", "all36"),
                  os.path.join(here, "out", a.out + ".jpg"), a.w))
