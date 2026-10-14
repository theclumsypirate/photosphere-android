#!/usr/bin/env python3
"""restitch v1: gain compensation + graph-cut seams + multiband blend.

Same warp as other_v2 (lever 0.35 / scene 3.5, EXIF poses). Differences:

1. Brown-Lowe scalar gains per frame (Kotlin ExposureCompensation clone),
   applied before seam search so fake exposure edges do not attract the cut.
2. cv2.detail GraphCutSeamFinder (COST_COLOR_GRAD) at 512px seam resolution
   instead of winner-takes-all by feather weight: the cut routes around
   disagreement (movers, parallax) instead of slicing through it.
3. cv2.detail MultiBandBlender (5 bands) at full res instead of Gaussian
   mask blur: detail stays crisp, low frequencies blend wide.

Usage:
    python3 tools/restitch_other/restitch_v1.py
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
BANDS = 5
WRAP_PX = 64


def main():
    t0 = time.time()
    files, poses, geom = load_inputs(SRC, "*.jpg", hfov=65.0)
    warped, cover = warp_all(files, poses, geom, W, lever=0.35, scene=3.5)
    gains = solve_gains(warped, cover)

    N, H, W_ = len(warped), W // 2, W
    s = SEAM_W / W_
    sh, sw = int(H * s), SEAM_W
    small = np.stack([cv2.resize(w, (sw, sh),
                                interpolation=cv2.INTER_AREA).astype(np.float32)
                      * g for w, g in zip(warped, gains)])
    small_masks = np.stack([cv2.resize(cov, (sw, sh),
                                       interpolation=cv2.INTER_NEAREST)
                            for cov in cover])
    small_masks[small_masks > 0] = 255

    # COST_COLOR, not COST_COLOR_GRAD: the gradient cost overflows the graph
    # edge weights on full-canvas images (addEdges w>=0 assert). Color-only
    # costs stay non-negative and still route around movers and parallax.
    finder = cv2.detail_GraphCutSeamFinder("COST_COLOR")
    corners = [(0, 0)] * N
    # uint8: the finder does its own gradient filtering and does not
    # accept float32 buffers (getLinearColumnFilter error).
    seam_src = [np.clip(sm, 0, 255).astype(np.uint8) for sm in small]
    seam_masks = [m for m in small_masks]
    t1 = time.time()
    finder.find(seam_src, corners, seam_masks)
    print(f"seam find took {time.time() - t1:.1f}s", flush=True)
    seam_small = np.stack(seam_masks)

    seam_full = np.stack([cv2.resize(m, (W_, H),
                                     interpolation=cv2.INTER_NEAREST)
                          for m in seam_small])
    seam_full[seam_full > 0] = 255
    seam_full = seam_full.astype(np.uint8)

    blender = cv2.detail_MultiBandBlender()
    blender.setNumBands(BANDS)
    blender.prepare([(0, 0, W_, H)])
    t1 = time.time()
    for i in range(N):
        # One frame at a time as float32: all 37 as float64 would be ~2 GB.
        img32 = (warped[i].astype(np.float32)
                 * np.float32(gains[i])).clip(0, 255)
        blender.feed(img32, seam_full[i], (0, 0))
    pano, _ = blender.blend(np.zeros((H, W_, 3), np.uint8),
                             np.zeros((H, W_), np.uint8))
    print(f"blend took {time.time() - t1:.1f}s", flush=True)

    a = (np.arange(WRAP_PX, dtype=np.float64) / WRAP_PX)[None, :, None]
    left = pano[:, :WRAP_PX].astype(np.float64)
    right = pano[:, -WRAP_PX:].astype(np.float64)
    pano[:, :WRAP_PX] = ((1 - a) * left + a * right).astype(np.uint8)

    pano_path = os.path.join(OUT, "restitch_v1.jpg")
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
    views_path = os.path.join(OUT, "views_restitch_v1.jpg")
    cv2.imwrite(views_path, sheet, [cv2.IMWRITE_JPEG_QUALITY, 90])
    print(f"saved {views_path}", flush=True)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    main()
