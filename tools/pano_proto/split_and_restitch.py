#!/usr/bin/env python3
"""Round-trip proof that the app stitching math works, without private photos.

Takes a public equirectangular panorama (CC BY-SA 4.0, see viewer/), cuts
standalone perspective frames out of it at known yaw/pitch poses, stamps each
frame's EXIF UserComment exactly like the app does
("index=N;yaw=..;pitch=..;roll=.."), then feeds the frames to stitch_all36.py
which reads those stamps and re-projects every pixel like
EquirectangularRenderer. If the math is right, the restitch matches the
original panorama.

Usage:
    python3 tools/pano_proto/split_and_restitch.py \
        --pano tools/pano_proto/viewer/ooty_360.jpg --out proof_ooty [--w 2048]

Outputs (under tools/pano_proto/out/, git-ignored):
    <out>_frames/frame_XX.jpg  standalone frames with stamped poses
    <out>.jpg                 restitched panorama
"""
import argparse
import glob
import os
import subprocess
import sys

import cv2
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

FW, FH = 1224, 1632
FOV_H, FOV_V = 49.0, 63.0

RINGS = [(0.0, 12), (30.0, 8), (-30.0, 8)]


def render_frame(pano, yaw_d, pitch_d, roll_d=0.0):
    H, W = pano.shape[:2]
    th = np.tan(np.radians(FOV_H / 2.0))
    tv = np.tan(np.radians(FOV_V / 2.0))
    xs = np.linspace(-th, th, FW)
    ys = np.linspace(tv, -tv, FH)
    xx, yy = np.meshgrid(xs, ys)
    zz = np.ones_like(xx)
    yaw, elev, roll = np.radians(yaw_d), np.radians(-pitch_d), np.radians(roll_d)
    sy, cy, ce = np.sin(yaw), np.cos(yaw), np.cos(elev)
    fwd = np.array([sy * ce, cy * ce, np.sin(elev)])
    r0x, r0y = cy, -sy
    u0x, u0y = r0y * fwd[2], -r0x * fwd[2]
    u0z = r0x * fwd[1] - r0y * fwd[0]
    cr, sr = np.cos(roll), np.sin(roll)
    right = np.array([r0x * cr - u0x * sr, r0y * cr - u0y * sr, -u0z * sr])
    up = np.array([u0x * cr + r0x * sr, u0y * cr + r0y * sr, u0z * cr])
    dirs = (fwd[None, None, :] + xx[..., None] * right + yy[..., None] * up)
    dirs /= np.linalg.norm(dirs, axis=2, keepdims=True)
    u = (0.5 + np.arctan2(dirs[..., 0], dirs[..., 1]) / (2 * np.pi)) * W
    v = (0.5 - np.arcsin(np.clip(dirs[..., 2], -1, 1)) / np.pi) * H
    return cv2.remap(pano, u.astype(np.float32), v.astype(np.float32),
                     cv2.INTER_LINEAR, borderMode=cv2.BORDER_WRAP)


def stamp(path, index, yaw, pitch, roll):
    with Image.open(path) as im:
        exif = im.getexif()
    sub = exif.get_ifd(0x8769)
    sub[0x9286] = f"index={index};yaw={yaw:.3f};pitch={pitch:.3f};roll={roll:.3f}"
    with Image.open(path) as im:
        im.save(path, exif=exif)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pano", required=True)
    ap.add_argument("--out", default="proof")
    ap.add_argument("--w", type=int, default=2048)
    args = ap.parse_args()
    pano = cv2.imread(args.pano)
    assert pano is not None, args.pano
    out_dir = os.path.join(HERE, "out")
    frames_dir = os.path.join(out_dir, args.out + "_frames")
    os.makedirs(frames_dir, exist_ok=True)
    poses = []
    for pitch, count in RINGS:
        for i in range(count):
            poses.append((i * 360.0 / count, pitch))
    index = 0
    for yaw, pitch in poses:
        index += 1
        frame = render_frame(pano, yaw, pitch)
        path = os.path.join(frames_dir, f"frame_{index:02d}.jpg")
        cv2.imwrite(path, frame, [cv2.IMWRITE_JPEG_QUALITY, 92])
        stamp(path, index, yaw, pitch, 0.0)
    print(f"{index} standalone frames in {frames_dir}", flush=True)
    stitch = os.path.join(HERE, "stitch_all36.py")
    subprocess.run(
        [sys.executable, stitch, "--dir", frames_dir,
         "--out", args.out, "--w", str(args.w)],
        check=True,
    )
    got = os.path.join(out_dir, args.out + ".jpg")
    print(f"restitched: {got}", flush=True)


if __name__ == "__main__":
    main()
