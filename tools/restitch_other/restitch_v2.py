#!/usr/bin/env python3
"""restitch v2: incremental pairwise seams + multiband finish.

v1 died inside cv2's global GraphCutSeamFinder (addEdges assert with 37
full-canvas images, both COST_COLOR_GRAD and COST_COLOR). This version
never builds the 37-label graph: frames join the mosaic one at a time in
capture order, and each join cuts a 2-label seam (pairwise graph cut, with
a DP seam as fallback). Same warp, same Brown-Lowe gains as v1.

Usage:
    python3 tools/restitch_other/restitch_v2.py
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


def pairwise_masks(mosaic_u8, mosaic_mask, frame_u8, frame_mask):
    """Cut a 2-label seam between mosaic and new frame. Returns (m0, m1)."""
    imgs = [mosaic_u8, frame_u8]
    masks = [mosaic_mask.copy(), frame_mask.copy()]
    try:
        finder = cv2.detail_GraphCutSeamFinder("COST_COLOR")
        finder.find(imgs, [(0, 0), (0, 0)], masks)
    except cv2.error as e:
        print(f"pairwise GC failed ({str(e)[:60]}), DP fallback", flush=True)
        dp = cv2.detail_DpSeamFinder("COLOR")
        masks = [mosaic_mask.copy(), frame_mask.copy()]
        dp.find(imgs, [(0, 0), (0, 0)], masks)
    return masks[0], masks[1]


def main():
    t0 = time.time()
    files, poses, geom = load_inputs(SRC, "*.jpg", hfov=65.0)
    warped, cover = warp_all(files, poses, geom, W, lever=0.35, scene=3.5)
    gains = solve_gains(warped, cover)

    N, H, W_ = len(warped), W // 2, W
    s = SEAM_W / W_
    sh, sw = int(H * s), SEAM_W
    small = [cv2.resize(w, (sw, sh), interpolation=cv2.INTER_AREA)
             for w in warped]
    small_cov = [(cv2.resize(c, (sw, sh), interpolation=cv2.INTER_NEAREST) > 0)
                 .astype(np.uint8) * 255 for c in cover]

    mosaic = small[0].astype(np.float32) * np.float32(gains[0])
    mosaic_mask = small_cov[0].copy()
    owner = np.full((sh, sw), -1, np.int16)
    owner[small_cov[0] > 0] = 0
    for i in range(1, N):
        frame = small[i].astype(np.float32) * np.float32(gains[i])
        ov = ((mosaic_mask > 0) & (small_cov[i] > 0)).sum()
        if ov < 50:
            take = small_cov[i] > 0
            mosaic[take] = frame[take]
            mosaic_mask[take] = 255
            owner[take] = i
            continue
        _m0, m1 = pairwise_masks(np.clip(mosaic, 0, 255).astype(np.uint8),
                                mosaic_mask, small[i], small_cov[i])
        take = m1 > 0
        mosaic[take] = frame[take]
        mosaic_mask[take] = 255
        owner[take] = i
        if i % 6 == 0:
            print(f"joined {i + 1}/{N}", flush=True)
    print(f"mosaic done ({time.time() - t0:.0f}s total)", flush=True)

    pano = cv2.resize(np.clip(mosaic, 0, 255).astype(np.uint8), (W_, H),
                      interpolation=cv2.INTER_LINEAR)
    a = (np.arange(WRAP_PX, dtype=np.float64) / WRAP_PX)[None, :, None]
    left = pano[:, :WRAP_PX].astype(np.float64)
    right = pano[:, -WRAP_PX:].astype(np.float64)
    pano[:, :WRAP_PX] = ((1 - a) * left + a * right).astype(np.uint8)

    pano_path = os.path.join(OUT, "restitch_v2.jpg")
    cv2.imwrite(pano_path, pano, [cv2.IMWRITE_JPEG_QUALITY, 92])
    print(f"saved {pano_path}", flush=True)

    outs = []
    for yaw, pitch in VIEWS:
        view = render_view(pano, yaw, pitch)
        cv2.putText(view, f"yaw {yaw} pitch {pitch}", (12, 28),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 0), 2)
        outs.append(view)
    sheet = np.concatenate(
        [np.concatenate(outs[r * 3:(r + 1) * 3], axis=1) for r in range(3)], axis=0)
    views_path = os.path.join(OUT, "views_restitch_v2.jpg")
    cv2.imwrite(views_path, sheet, [cv2.IMWRITE_JPEG_QUALITY, 90])
    print(f"saved {views_path}", flush=True)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    main()
