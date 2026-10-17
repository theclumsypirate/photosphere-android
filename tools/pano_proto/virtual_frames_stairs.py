#!/usr/bin/env python3
"""Cut virtual capture frames from stairs_360.jpg at known yaw/pitch.

The internet pano has no capture coords, so we define the grid ourselves
(yaw ring + pitch rows, like the orbit plan), render perspective frames
with the same ray-cast math as view360.py, and stamp each file's EXIF
UserComment with yaw/pitch/roll exactly like the app does. That makes the
virtual set a drop-in input for stitch_all36_v*.py --dir, which proves the
loop closes: known coords in, best stitch out.

Usage: python3 tools/pano_proto/virtual_frames_stairs.py [--n 36]
Only writes under out/virtual_stairs/, never touches Kotlin.
"""
import argparse
import json
import math
import os
import cv2
import numpy as np
import piexif

HERE = os.path.dirname(os.path.abspath(__file__))
PANO = os.path.join(HERE, "viewer", "stairs_360.jpg")

# Grid mirrors the app capture plan: ring yaws x pitch rows + caps.
PITCH_ROWS = [-45, -20, 0, 20, 45]
CAPS = [(0, 75), (60, 75), (120, 75), (180, 75), (-120, 75), (-60, 75),
        (0, -75), (60, -75), (120, -75), (180, -75), (-120, -75), (-60, -75)]
FOV = 60.0
FW, FH = 1224, 1632  # native warp size: warp_one resizes input to fw//2 x fh//2, so no resample here
# Must match stitch_all36.py exactly or the loop cannot close.
H_FOV, V_FOV = 49.0, 63.0
LEVER_M, SCENE_M = 0.35, 3.5


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


def render_view(img, yaw_deg, pitch_deg):
    """Forward render that mickmicks the stitcher's inverse model exactly:
    dirs -> adj (pivot referral) -> pick x,y = (adj@right, adj@up)/adj@fwd ->
    sample pano at lon/lat reconstructed from ray = C + t * ray_adj_hit.
    Reused bit-exact math with stitch_closed.py's warp_one."""
    H, W = img.shape[:2]
    fwd, right, up = camera_frame(yaw_deg, pitch_deg, 0.0)
    fx = (FW / 2.0) / math.tan(math.radians(H_FOV / 2.0))
    fy = (FH / 2.0) / math.tan(math.radians(V_FOV / 2.0))
    xs = (np.arange(FW, dtype=np.float64) - FW / 2.0) / fx
    ys = -((np.arange(FH, dtype=np.float64) - FH / 2.0) / fy)
    # frame-plane angles for each render pixel (this is the ray generator,
    # same direct x,y the stitcher inverts):
    xx, yy = np.meshgrid(xs, ys)
    C = LEVER_M * fwd
    # Rays leave the LENS at C (pivot referral, as the stitcher assumes).
    r = xx[..., None] * right + yy[..., None] * up + fwd
    r /= np.linalg.norm(r, axis=-1, keepdims=True)
    # scene sphere of radius SCENE_M centered at the PIVOT (origin): hit
    b = (r * C).sum(axis=-1)
    c = float(C @ C) - SCENE_M * SCENE_M
    t = -b + np.sqrt(np.maximum(b * b - c, 0))
    Q = C + t[..., None] * r
    lon = np.arctan2(Q[..., 0], Q[..., 1])
    lat = np.arcsin(np.clip(Q[..., 2] / SCENE_M, -1, 1))
    u = ((lon / (2 * math.pi) + 0.5) * W).astype(np.float32)
    v = ((0.5 - lat / math.pi) * H).astype(np.float32)
    return cv2.remap(img, u, v, cv2.INTER_LINEAR, borderMode=cv2.BORDER_WRAP)


def main(n_ring):
    pano = cv2.imread(PANO)
    assert pano is not None, f"missing {PANO}"
    out = os.path.join(HERE, "out", "virtual_stairs")
    os.makedirs(out, exist_ok=True)
    ring = n_ring // len(PITCH_ROWS)
    coords = []
    idx = 0
    for pitch in PITCH_ROWS:
        for k in range(ring):
            yaw = -180 + k * (360.0 / ring)
            idx += 1
            frame = render_view(pano, yaw, pitch)
            name = f"vframe_{idx:02d}.jpg"
            path = os.path.join(out, name)
            cv2.imwrite(path, frame, [cv2.IMWRITE_JPEG_QUALITY, 100])
            comment = f"yaw={yaw:.1f};pitch={pitch:.1f};roll=0.0"
            exif_d = {"Exif": {piexif.ExifIFD.UserComment: comment.encode()}}
            piexif.insert(piexif.dump(exif_d), path)
            coords.append({"file": name, "yaw": round(yaw, 1),
                           "pitch": float(pitch), "roll": 0.0, "fov": FOV})
    for yaw, pitch in CAPS:
        idx += 1
        frame = render_view(pano, yaw, pitch)
        name = f"vframe_{idx:02d}.jpg"
        path = os.path.join(out, name)
        cv2.imwrite(path, frame, [cv2.IMWRITE_JPEG_QUALITY, 100])
        comment = f"yaw={yaw:.1f};pitch={pitch:.1f};roll=0.0"
        exif_d = {"Exif": {piexif.ExifIFD.UserComment: comment.encode()}}
        piexif.insert(piexif.dump(exif_d), path)
        coords.append({"file": name, "yaw": float(yaw),
                       "pitch": float(pitch), "roll": 0.0, "fov": FOV})
    with open(os.path.join(out, "coords.json"), "w") as fh:
        json.dump(coords, fh, indent=1)
    print(f"{len(coords)} virtual frames + coords.json -> {out}", flush=True)
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=35)
    a = ap.parse_args()
    raise SystemExit(main(a.n - len(CAPS)))
