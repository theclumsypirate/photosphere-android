#!/usr/bin/env python3
"""restitch v5: global alpha-expansion graph cut (PyMaxflow) + full-res composite.

v4 proved pairwise seams plateau: each new frame cuts against an already
mixed mosaic, and a narrow overlap forces the seam through content no
matter the cost function (v3/v4 views: ~5.5/10). This is the Kotlin Carve
mode formulation, solved globally: every canvas pixel gets the label of
one covering frame, minimizing data cost (color distance to the overlap
mean + center preference) plus smoothness cost (cuts pay the color
disagreement they cross, so they route around movers and parallax).
Solved by alpha-expansion: one binary maxflow cut per frame, two passes.

Usage:
    python3 tools/restitch_other/restitch_v5.py
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
PASSES = 2
LAMBDA = 2.0
BORDER = 400.0
INF = 1e9


def center_penalty(cov):
    """Prefer pixels near the frame's own coverage centroid (0..BORDER)."""
    ys, xs = np.nonzero(cov)
    cy, cx = ys.mean(), xs.mean()
    yy, xx = np.mgrid[0:cov.shape[0], 0:cov.shape[1]].astype(np.float32)
    dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    if dist.max() > 0:
        dist /= dist.max()
    return (dist ** 2 * BORDER).astype(np.float32)


def binary_cut(imgs, covs, data, labels, mosaic, alpha):
    """One expansion move: reassign pixels to `alpha` where it wins."""
    h, w = labels.shape
    cov_a = covs[alpha]
    img_a = imgs[alpha]
    d_alpha = np.where(cov_a, data[alpha], INF).astype(np.float32)
    d_cur = np.zeros((h, w), np.float32)
    for i in range(len(imgs)):
        take = labels == i
        d_cur[take] = data[i][take]
    disag = np.abs(mosaic - img_a).sum(axis=2).astype(np.float32)
    w_h = np.zeros((h, w), np.float32)
    w_h[:, :-1] = LAMBDA * (disag[:, :-1] + disag[:, 1:])
    w_v = np.zeros((h, w), np.float32)
    w_v[:-1, :] = LAMBDA * (disag[:-1, :] + disag[1:, :])

    g = maxflow.Graph[float](h * w, h * w * 2)
    g.add_nodes(h * w)
    idx = np.arange(h * w, dtype=np.int32).reshape(h, w)
    g.add_grid_tedges(idx, d_cur, d_alpha)
    g.add_grid_edges(idx, w_h, structure=[[0, 0, 0], [0, 0, 1], [0, 0, 0]],
                     symmetric=True)
    g.add_grid_edges(idx, w_v, structure=[[0, 0, 0], [0, 0, 0], [0, 1, 0]],
                     symmetric=True)
    g.maxflow()
    take_alpha = ~g.get_grid_segments(idx)
    take_alpha &= cov_a
    return take_alpha


def main():
    t0 = time.time()
    files, poses, geom = load_inputs(SRC, "*.jpg", hfov=65.0)
    warped, cover = warp_all(files, poses, geom, W, lever=0.35, scene=3.5)
    gains = solve_gains(warped, cover)
    N, H, W_ = len(warped), W // 2, W

    s = SEAM_W / W_
    sh, sw = int(H * s), SEAM_W
    imgs = [(cv2.resize(w, (sw, sh), interpolation=cv2.INTER_AREA)
             .astype(np.float32) * np.float32(g)) for w, g in zip(warped, gains)]
    covs = [(cv2.resize(c, (sw, sh), interpolation=cv2.INTER_NEAREST) > 0)
            for c in cover]

    stacked = np.stack([im * co[..., None] for im, co in zip(imgs, covs)])
    counts = np.stack(covs).sum(axis=0)
    mean = stacked.sum(axis=0) / np.maximum(counts, 1)[..., None]
    data = [(((im - mean) ** 2).sum(axis=2) * co + center_penalty(co))
            for im, co in zip(imgs, covs)]

    # Greedy init: lowest data cost wins (closest to mean, near its center).
    dcost = np.stack([np.where(co, d, INF) for d, co in zip(data, covs)])
    labels = dcost.argmin(axis=0).astype(np.int16)
    mosaic = np.zeros((sh, sw, 3), np.float32)
    for i in range(N):
        take = labels == i
        mosaic[take] = imgs[i][take]

    order = sorted(range(N), key=lambda i: -covs[i].sum())
    for p in range(PASSES):
        for k, alpha in enumerate(order):
            take = binary_cut(imgs, covs, data, labels, mosaic, alpha)
            if take.any():
                labels[take] = alpha
                mosaic[take] = imgs[alpha][take]
            if k % 9 == 0:
                print(f"pass {p + 1} label {k + 1}/{N}", flush=True)
    print(f"expansion done ({time.time() - t0:.0f}s total)", flush=True)

    owner_full = cv2.resize(labels.astype(np.float32), (W_, H),
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

    pano_path = os.path.join(OUT, "restitch_v5.jpg")
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
    views_path = os.path.join(OUT, "views_restitch_v5.jpg")
    cv2.imwrite(views_path, sheet, [cv2.IMWRITE_JPEG_QUALITY, 90])
    print(f"saved {views_path}", flush=True)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    main()
