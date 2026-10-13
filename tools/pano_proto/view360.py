#!/usr/bin/env python3
"""Headless 360-viewer test: render person-like perspective views.

Reproduces what html-360-viewer (a-sky: full-sphere equirect texture, fov 80,
drag-to-look) shows, without a browser: for each (yaw, pitch) a perspective
view is ray-cast from the sphere center and sampled from the panorama.

Usage:
    python3 tools/pano_proto/view360.py --pano out/v7.jpg --out views_v7
    python3 tools/pano_proto/view360.py --pano out/v6.jpg --out views_v6naive
"""
import argparse
import os
import cv2
import numpy as np

VIEWS = [(0, 0), (60, 0), (120, 0), (180, 0), (-60, 0), (-120, 0),
         (0, 25), (0, -25), (90, 10)]
FOV = 80.0
VW, VH = 640, 360


def render_view(img, yaw_deg, pitch_deg):
    H, W = img.shape[:2]
    tan_half = np.tan(np.radians(FOV / 2.0))
    aspect = VW / VH
    xs = (np.arange(VW, dtype=np.float64) / VW * 2 - 1) * tan_half * aspect
    ys = -((np.arange(VH, dtype=np.float64) / VH * 2 - 1) * tan_half)
    xx, yy = np.meshgrid(xs, ys)
    zz = np.ones_like(xx)
    yaw, pitch = np.radians(yaw_deg), np.radians(pitch_deg)
    sy, cy, sp, cp = np.sin(yaw), np.cos(yaw), np.sin(pitch), np.cos(pitch)
    fwd = np.array([sy * cp, cy * cp, sp])
    right = np.array([cy, -sy, 0.0])
    up = np.cross(right, fwd)
    dirs = (fwd[None, None, :] + xx[..., None] * right
            + yy[..., None] * up)
    dirs /= np.linalg.norm(dirs, axis=2, keepdims=True)
    u = (0.5 + np.arctan2(dirs[..., 0], dirs[..., 1]) / (2 * np.pi)) * W
    v = (0.5 - np.arcsin(np.clip(dirs[..., 2], -1, 1)) / np.pi) * H
    return cv2.remap(img, u.astype(np.float32), v.astype(np.float32),
                     cv2.INTER_LINEAR, borderMode=cv2.BORDER_WRAP)


def main(pano_path, out_stem):
    here = os.path.dirname(os.path.abspath(__file__))
    img = cv2.imread(pano_path)
    assert img is not None, pano_path
    outs = []
    for yaw, pitch in VIEWS:
        view = render_view(img, yaw, pitch)
        cv2.putText(view, f"yaw {yaw} pitch {pitch}", (12, 28),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 0), 2)
        outs.append(view)
    rows = []
    for r in range(3):
        rows.append(np.concatenate(outs[r * 3:(r + 1) * 3], axis=1))
    sheet = np.concatenate(rows, axis=0)
    out = os.path.join(here, "out", out_stem + ".jpg")
    cv2.imwrite(out, sheet, [cv2.IMWRITE_JPEG_QUALITY, 90])
    print(f"saved {out}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--pano", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    here = os.path.dirname(os.path.abspath(__file__))
    pano = args.pano if os.path.isabs(args.pano) else os.path.join(here, args.pano)
    main(pano, args.out)
