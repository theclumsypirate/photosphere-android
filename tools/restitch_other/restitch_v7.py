#!/usr/bin/env python3
"""restitch v7: feather-center ownership (other_v2 placement) + full-res composite.

v2 proved two things: single-source compositing kills all ghosting, but
(1) the mosaic loop ran at 512px and the 4x upscale blurred everything,
(2) DP seam masks leave 1px gaps that print as black dotted seam lines,
(3) cv2's GraphCutSeamFinder is broken in this OpenCV build (global and
pairwise, COLOR and COLOR_GRAD) so it is not attempted anymore.

v3 keeps seam decisions at 512px (DP pairwise, fast) but composites at
full 2048px: the low-res owner map is upscaled, each frame contributes
through a slightly blurred one-hot mask (2px crossfade hides the DP
staircase), and any pixel no owner claims is filled from the covering
frame closest to its own optical center.

Usage:
    python3 tools/restitch_other/restitch_v7.py
"""
import os
import sys
import time

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import load_inputs, solve_gains, warp_all

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "pano_proto"))
from view360 import VIEWS, render_view

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "pano_proto", "ref", "all36")
OUT = os.path.join(HERE, "out")
W = 2048
SEAM_W = 512
WRAP_PX = 64
CROSSFADE_PX = 2.0


def main():
    t0 = time.time()
    files, poses, geom = load_inputs(SRC, "*.jpg", hfov=65.0)
    warped, cover = warp_all(files, poses, geom, W, lever=0.35, scene=3.5)
    gains = solve_gains(warped, cover)
    N, H, W_ = len(warped), W // 2, W

    # Ownership like other_v2: every pixel comes from the covering frame
    # whose center is closest (feather-center winner-takes-all). No DP, no
    # graph cut: with this much parallax any computed seam slices content,
    # while center-ownership keeps each object whole inside one frame.
    s = SEAM_W / W_
    sh, sw = int(H * s), SEAM_W
    small_cov = [(cv2.resize(c, (sw, sh), interpolation=cv2.INTER_NEAREST) > 0)
                 for c in cover]
    yy, xx = np.mgrid[0:sh, 0:sw].astype(np.float32)
    score = np.full((N, sh, sw), -1.0, np.float32)
    for i, co in enumerate(small_cov):
        ys, xs = np.nonzero(co)
        if len(ys) == 0:
            continue
        cy, cx = ys.mean(), xs.mean()
        d2 = ((xx - cx) ** 2 + (yy - cy) ** 2)
        d2 /= max(d2.max(), 1e-9)
        score[i][co] = 1.0 - d2[co]
    owner = score.argmax(axis=0).astype(np.int16)
    print("ownership done", flush=True)

    owner_full = cv2.resize(owner.astype(np.float32), (W_, H),
                            interpolation=cv2.INTER_NEAREST).astype(np.int16)
    pano = np.zeros((H, W_, 3), np.float64)
    weight = np.zeros((H, W_), np.float64)
    for i in range(N):
        m = (owner_full == i).astype(np.float32)
        if m.sum() == 0:
            continue
        m = cv2.GaussianBlur(m, (0, 0), CROSSFADE_PX)
        frame = warped[i].astype(np.float64) * float(gains[i])
        pano += frame * m[..., None]
        weight += m
        if i % 6 == 0:
            print(f"composite {i + 1}/{N}", flush=True)

    # Hole fill: pixels nobody owns but somebody covers come from the
    # covering frame closest to its optical center (max feather weight).
    holes = (weight < 1e-9)
    if holes.any():
        fh, fw = warped[0].shape[:2]
        yy, xx = np.mgrid[0:fh, 0:fw].astype(np.float32)
        dist = np.sqrt(((xx - fw / 2) / (fw / 2)) ** 2
                       + ((yy - fh / 2) / (fh / 2)) ** 2)
        best, best_d = np.zeros((H, W_), np.int16), np.full((H, W_), 1e9)
        for i in range(N):
            cand = (cover[i] > 0) & holes & (dist < best_d)
            best[cand] = i
            best_d[cand] = dist[cand]
        filled = 0
        for i in range(N):
            take = holes & (best == i)
            if take.any():
                pano[take] = warped[i][take] * float(gains[i])
                weight[take] = 1.0
                filled += int(take.sum())
        print(f"hole-filled {filled} px", flush=True)

    pano /= np.maximum(weight, 1e-9)[..., None]
    pano = np.clip(pano, 0, 255).astype(np.uint8)
    a = (np.arange(WRAP_PX, dtype=np.float64) / WRAP_PX)[None, :, None]
    left = pano[:, :WRAP_PX].astype(np.float64)
    right = pano[:, -WRAP_PX:].astype(np.float64)
    pano[:, :WRAP_PX] = ((1 - a) * left + a * right).astype(np.uint8)

    pano_path = os.path.join(OUT, "restitch_v7.jpg")
    cv2.imwrite(pano_path, pano, [cv2.IMWRITE_JPEG_QUALITY, 92])
    print(f"saved {pano_path} ({time.time() - t0:.0f}s total)", flush=True)

    outs = []
    for yaw, pitch in VIEWS:
        view = render_view(pano, yaw, pitch)
        cv2.putText(view, f"yaw {yaw} pitch {pitch}", (12, 28),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 0), 2)
        outs.append(view)
    sheet = np.concatenate(
        [np.concatenate(outs[r * 3:(r + 1) * 3], axis=1) for r in range(3)], axis=0)
    views_path = os.path.join(OUT, "views_restitch_v7.jpg")
    cv2.imwrite(views_path, sheet, [cv2.IMWRITE_JPEG_QUALITY, 90])
    print(f"saved {views_path}", flush=True)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    main()
