#!/usr/bin/env python3
"""Gemini's idea, isolated: winner-takes-all + pure rotation (LEVER_M=0).

No averaging anywhere: every canvas pixel comes from the single frame whose
feather weight is highest there (closest to a frame center, where parallax
and distortion are minimal). Transitions get a narrow crossfade by blurring
the winner masks, so seams don't turn jagged.

Usage:
    python3 tools/pano_proto/stitch_36_but_other_idea.py --out other_v1
    python3 tools/pano_proto/stitch_36_but_other_idea.py --out other_v2 --lever 0.35 --scene 3.5
"""
import argparse
import glob
import os
import sys
import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from stitch_all36 import (read_pose, camera_frame, H_FOV, V_FOV, FEATHER_PX,  # noqa
                          WRAP_SEAM_PX)

BLUR_PX = 7


def main(d, out, W, lever, scene):
    H = W // 2
    files = sorted(glob.glob(os.path.join(d, "*.jpg")))
    fw, fh = 2448, 3264
    fx = (fw / 2) / np.tan(np.radians(H_FOV / 2))
    fy = (fh / 2) / np.tan(np.radians(V_FOV / 2))
    cx, cy = fw / 2.0, fh / 2.0

    us = (np.arange(W, dtype=np.float64) / W - 0.5) * 2 * np.pi
    vs = (0.5 - np.arange(H, dtype=np.float64) / H) * np.pi
    uu, vv = np.meshgrid(us, vs)
    dirs = np.stack([np.sin(uu) * np.cos(vv), np.cos(uu) * np.cos(vv),
                     np.sin(vv)], axis=-1)

    my, mx = np.mgrid[0:fh // 2, 0:fw // 2].astype(np.float32)
    feather = np.ones((fh // 2, fw // 2), np.float32)
    r = min(FEATHER_PX, fw // 8)
    feather[:, :r] *= np.linspace(0, 1, r, dtype=np.float32)
    feather[:, -r:] *= np.linspace(1, 0, r, dtype=np.float32)
    feather[:r, :] *= np.linspace(0, 1, r, dtype=np.float32)[:, None]
    feather[-r:, :] *= np.linspace(1, 0, r, dtype=np.float32)[:, None]

    warped, weights = [], []
    for path in files:
        pose = read_pose(path)
        if pose is None:
            continue
        fwd, right, up = camera_frame(*pose)
        adj = scene * dirs - lever * fwd[None, None, :]
        adj /= np.linalg.norm(adj, axis=2, keepdims=True)
        z = adj @ fwd
        front = z > 0.02
        x = adj @ right / np.maximum(z, 1e-9)
        y = adj @ up / np.maximum(z, 1e-9)
        px = (x * fx + cx).astype(np.float32)
        py = (cy - y * fy).astype(np.float32)
        inside = front & (px >= 0) & (px < fw) & (py >= 0) & (py < fh)
        if not inside.any():
            continue
        im = cv2.imread(path)
        im = cv2.resize(im, (fw // 2, fh // 2), interpolation=cv2.INTER_AREA)
        s = 0.5
        sampled = cv2.remap(im, (px * s).astype(np.float32), (py * s).astype(np.float32),
                            cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
        w = cv2.remap(feather, (px * s).astype(np.float32), (py * s).astype(np.float32),
                      cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
        w[~inside] = 0
        warped.append(sampled.astype(np.float64))
        weights.append(w.astype(np.float64))
    print(f"warped {len(warped)} frames (lever={lever} scene={scene})", flush=True)

    W_ = np.stack(weights)          # N x H x W
    winner = W_.argmax(axis=0)      # H x W labels
    # narrow crossfade: blur one-hot masks, renormalize
    masks = np.zeros_like(W_)
    for i in range(len(warped)):
        m = (winner == i).astype(np.float64)
        masks[i] = cv2.GaussianBlur(m, (0, 0), BLUR_PX)
    masks /= np.maximum(masks.sum(axis=0, keepdims=True), 1e-9)
    pano = sum(img * m[..., None] for img, m in zip(warped, masks))
    pano = np.clip(pano, 0, 255)

    S = min(WRAP_SEAM_PX, W // 4)
    left = pano[:, :S].copy()
    right = pano[:, -S:].copy()
    a = (np.arange(S, dtype=np.float64) / S)[None, :, None]
    pano[:, :S] = (1 - a) * left + a * right
    cv2.imwrite(out, pano.astype(np.uint8), [cv2.IMWRITE_JPEG_QUALITY, 92])
    print(f"saved {out}", flush=True)
    return 0


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="other_v1")
    ap.add_argument("--w", type=int, default=2048)
    ap.add_argument("--lever", type=float, default=0.0)
    ap.add_argument("--scene", type=float, default=3.5)
    a = ap.parse_args()
    os.makedirs(os.path.join(here, "out"), exist_ok=True)
    sys.exit(main(os.path.join(here, "ref", "all36"),
                  os.path.join(here, "out", a.out + ".jpg"), a.w, a.lever, a.scene))
