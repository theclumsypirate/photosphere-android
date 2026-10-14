#!/usr/bin/env python3
"""restitch v4: own pairwise graph-cut seams (PyMaxflow) + full-res composite.

cv2's GraphCutSeamFinder is broken in this OpenCV build, and the DP
fallback draws content-blind diagonal cuts through laptops and cabinets
(v3: sharp and ghost-free, but seam placement 3/10). This version solves
the 2-label cut directly with Boykov-Kolmogorov maxflow, in the
photomontage formulation: cutting between two pixels costs the color
disagreement at both ends, so the seam routes around movers and parallax
instead of through them. Same warp, gains, ownership composite, hole
fill and 2px crossfade as v3.

Usage:
    python3 tools/restitch_other/restitch_v4.py
"""
import os
import sys
import time

import cv2
import maxflow
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
INF = 1e9


def gc_masks(mosaic_u8, mosaic_mask, frame_u8, frame_mask):
    """2-label graph cut between mosaic (0) and frame (1)."""
    both = (mosaic_mask > 0) & (frame_mask > 0)
    ys, xs = np.nonzero(both)
    if len(ys) == 0:
        return mosaic_mask.copy(), frame_mask.copy()
    y0, y1 = ys.min(), ys.max() + 1
    x0, x1 = xs.min(), xs.max() + 1
    # One pixel of context so the cut can reach the overlap border.
    y0, x0 = max(0, y0 - 1), max(0, x0 - 1)
    y1, x1 = min(both.shape[0], y1 + 1), min(both.shape[1], x1 + 1)
    A = mosaic_u8[y0:y1, x0:x1].astype(np.float32)
    B = frame_u8[y0:y1, x0:x1].astype(np.float32)
    ma = mosaic_mask[y0:y1, x0:x1] > 0
    mb = frame_mask[y0:y1, x0:x1] > 0
    both_c = ma & mb

    disagree = np.abs(A - B).sum(axis=2)
    h, w = both_c.shape
    w_h = np.zeros((h, w), np.float32)
    w_h[:, :-1] = (disagree[:, :-1] + disagree[:, 1:]) / 6.0
    w_v = np.zeros((h, w), np.float32)
    w_v[:-1, :] = (disagree[:-1, :] + disagree[1:, :]) / 6.0

    g = maxflow.Graph[float](h * w, h * w * 2)
    g.add_nodes(h * w)
    idx = np.arange(h * w, dtype=np.int32).reshape(h, w)
    only_a = ma & ~mb
    only_b = mb & ~ma
    g.add_grid_tedges(idx, np.where(only_b, INF, 0.0),
                      np.where(only_a, INF, 0.0))
    g.add_grid_edges(idx, w_h, structure=[[0, 0, 0], [0, 0, 1], [0, 0, 0]],
                     symmetric=True)
    g.add_grid_edges(idx, w_v, structure=[[0, 0, 0], [0, 0, 0], [0, 1, 0]],
                     symmetric=True)
    g.maxflow()
    lab = g.get_grid_segments(idx).astype(np.uint8) * 255

    m0 = mosaic_mask.copy()
    m1 = np.zeros_like(frame_mask)
    win = np.zeros_like(m0)
    win[y0:y1, x0:x1] = (lab == 0).astype(np.uint8) * 255
    m0[both] = win[both]
    m1[both] = 255 - win[both]
    m1[(mosaic_mask == 0) & (frame_mask > 0)] = 255
    return m0, m1


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
        take = small_cov[i] > 0
        if ((mosaic_mask > 0) & take).sum() < 50:
            mosaic[take] = frame[take]
        else:
            _m0, m1 = gc_masks(np.clip(mosaic, 0, 255).astype(np.uint8),
                               mosaic_mask, small[i], small_cov[i])
            take = m1 > 0
            mosaic[take] = frame[take]
        mosaic_mask[take] = 255
        owner[take] = i
        if i % 6 == 0:
            print(f"seam {i + 1}/{N}", flush=True)

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

    holes = weight < 1e-9
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

    pano_path = os.path.join(OUT, "restitch_v4.jpg")
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
    views_path = os.path.join(OUT, "views_restitch_v4.jpg")
    cv2.imwrite(views_path, sheet, [cv2.IMWRITE_JPEG_QUALITY, 90])
    print(f"saved {views_path}", flush=True)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    main()
