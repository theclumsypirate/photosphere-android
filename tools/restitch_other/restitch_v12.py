#!/usr/bin/env python3
"""restitch v12: feather-center ownership (other_v2 placement) + full-res composite.

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
WRAP_PX = 128
PASSES = 2
LAMBDA = 10.0
BORDER = 1000.0
INF = 1e9


def center_penalty(cov):
    """Wrap-aware: prefers pixels near the frame's coverage center."""
    h, w = cov.shape
    ys, _xs = np.nonzero(cov)
    if len(ys) == 0:
        return np.zeros((h, w), np.float32)
    cy = ys.mean()
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    ang = xx / w * 2 * np.pi
    cx = np.arctan2(np.sin(ang[cov]).mean(), np.cos(ang[cov]).mean()) \
        / (2 * np.pi) * w % w
    dx = np.minimum(np.abs(xx - cx), w - np.abs(xx - cx))
    dist = np.sqrt(dx ** 2 + (yy - cy) ** 2)
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
    take_alpha = (~g.get_grid_segments(idx).astype(bool)) & cov_a
    return take_alpha


def main():
    t0 = time.time()
    files, poses, geom = load_inputs(SRC, "*.jpg", hfov=65.0)
    warped, cover = warp_all(files, poses, geom, W, lever=0.35, scene=3.5)
    gains = solve_gains(warped, cover)
    N, H, W_ = len(warped), W // 2, W

    # Seams: global alpha-expansion over horizon frames (steep floor and
    # ceiling frames only fill holes, per the v10 lesson). Data cost is
    # color distance to the overlap mean plus wrap-aware center
    # preference; cuts pay the disagreement they cross, so they route
    # around objects instead of through them (the v11 stove fix).
    s = SEAM_W / W_
    sh, sw = int(H * s), SEAM_W
    small_cov = [(cv2.resize(c, (sw, sh), interpolation=cv2.INTER_NEAREST) > 0)
                 for c in cover]
    # all36_v5 lesson: steep pitch frames (floor/ceiling rings) reproject
    # close-range pixels onto the horizon band at wrong positions (the
    # scene-depth assumption breaks under a meter). They must not compete
    # for ownership there: horizon frames own the band, steep frames only
    # fill what nobody owns (poles) through the hole fill below.
    steep = [abs(p[1]) > 20.0 for p in poses]
    print(f"steep frames (hole-fill only): {[i + 1 for i, s in enumerate(steep) if s]}",
          flush=True)
    # Gain-corrected seam-res images for the cut.
    imgs = [(cv2.resize(w, (sw, sh), interpolation=cv2.INTER_AREA)
             .astype(np.float32) * np.float32(g)) for w, g in zip(warped, gains)]
    covs = [np.zeros_like(co) if st else co for co, st in zip(small_cov, steep)]
    stacked = np.stack([im * co[..., None] for im, co in zip(imgs, covs)])
    counts = np.stack(covs).sum(axis=0)
    mean = stacked.sum(axis=0) / np.maximum(counts, 1)[..., None]
    data = [(np.abs(im - mean).sum(axis=2) / 3.0 * co + center_penalty(co))
            for im, co in zip(imgs, covs)]

    # Greedy init: lowest data cost wins.
    dcost = np.stack([np.where(co, d, INF) for d, co in zip(data, covs)])
    owner = dcost.argmin(axis=0).astype(np.int16)
    mosaic = np.zeros((sh, sw, 3), np.float32)
    for i in range(N):
        take = owner == i
        mosaic[take] = imgs[i][take]

    # Alpha-expansion over horizon frames only.
    order = sorted([i for i in range(N) if not steep[i]],
                   key=lambda i: -covs[i].sum())
    for p in range(PASSES):
        for k, alpha in enumerate(order):
            take = binary_cut(imgs, covs, data, owner, mosaic, alpha)
            if take.any():
                owner[take] = alpha
                mosaic[take] = imgs[alpha][take]
            if k % 4 == 0:
                print(f"pass {p + 1} label {k + 1}/{len(order)}", flush=True)
    print("expansion done", flush=True)

    # Perfection fixes (critic top 5): single source on the pole bands.
    # The zenith star and the nadir jigsaw come from ownership flipping
    # between frames where coverage is thin. Force each pole pixel to the
    # covering steep frame nearest its own center (up ring for zenith,
    # down ring for nadir); fall back to the expansion owner when none
    # of the ring covers.
    yy_s, xx_s = np.mgrid[0:sh, 0:sw].astype(np.float32)
    ang_s = xx_s / sw * 2 * np.pi
    up = [i for i in range(N) if poses[i][1] > 20.0]
    down = [i for i in range(N) if poses[i][1] < -20.0]

    def nearest(cands, rows):
        best = np.full((sh, sw), -1, np.int16)
        best_d = np.full((sh, sw), 1e18, np.float32)
        for i in cands:
            co = small_cov[i]
            ys = np.nonzero(co)[0]
            if len(ys) == 0:
                continue
            cy = ys.mean()
            cx = np.arctan2(np.sin(ang_s[co]).mean(),
                            np.cos(ang_s[co]).mean()) / (2 * np.pi) * sw % sw
            dx = np.minimum(np.abs(xx_s - cx), sw - np.abs(xx_s - cx))
            d2 = dx ** 2 + (yy_s - cy) ** 2
            take = co & (d2 < best_d)
            best[take] = i
            best_d[take] = d2[take]
        return best

    zen = np.zeros((sh, sw), bool)
    zen[:70] = True
    near_up = nearest(up, zen)
    keep = zen & (near_up >= 0)
    owner[keep] = near_up[keep]
    nad = np.zeros((sh, sw), bool)
    nad[175:] = True
    near_down = nearest(down, nad)
    keep = nad & (near_down >= 0)
    owner[keep] = near_down[keep]
    print("pole bands pinned to single sources", flush=True)

    owner_full = cv2.resize(owner.astype(np.float32), (W_, H),
                            interpolation=cv2.INTER_NEAREST).astype(np.int16)
    # Multiband finish (bindings validated by probe: int16 feed,
    # prepare(dst_roi), blend(None, None)): low frequencies blend wide so
    # the ceiling and wall exposure tiles disappear, high frequencies stay
    # single-source so detail never ghosts.
    blender = cv2.detail_MultiBandBlender()
    blender.setNumBands(6)
    blender.prepare((0, 0, W_, H))
    hole_owner = np.zeros((H, W_), np.int16)
    # argmax never yields "nobody": mask ownership by real coverage, or
    # uncovered pixels inherit frame 0's black borders.
    covered = np.stack(cover).any(axis=0)
    fed_any = np.zeros((H, W_), bool)
    for i in range(N):
        # Only pixels this frame actually covers: otherwise the blender
        # eats its black borders (the v10 pole bug).
        m = (((owner_full == i) & (cover[i] > 0)) * 255).astype(np.uint8)
        if m.sum() == 0:
            continue
        fed_any |= m > 0
        frame = np.clip(warped[i].astype(np.float32)
                        * np.float32(gains[i]), 0, 255).astype(np.int16)
        blender.feed(frame, m, (0, 0))
        hole_owner[m > 0] = i + 1
        if i % 6 == 0:
            print(f"feed {i + 1}/{N}", flush=True)
    pano16, _ = blender.blend(None, None)
    pano = np.clip(pano16, 0, 255).astype(np.uint8)

    # Soften high-frequency ownership steps: blend the pano with its own
    # 3px blur strictly along owner boundaries. Multiband already hides
    # low-frequency steps; this takes the hard 1px edge off the rest.
    bnd = np.zeros((H, W_), bool)
    bnd[1:] |= owner_full[1:] != owner_full[:-1]
    bnd[:, 1:] |= owner_full[:, 1:] != owner_full[:, :-1]
    bnd[:, 0] |= owner_full[:, 0] != owner_full[:, -1]
    bnd = cv2.dilate(bnd.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
    soft = cv2.GaussianBlur(pano, (0, 0), 1.0)
    pano[bnd] = ((pano[bnd].astype(np.float32)
                  + soft[bnd].astype(np.float32)) / 2).astype(np.uint8)

    # Hole fill for pixels the blender never saw (covered only by steep
    # frames, e.g. the poles): nearest-to-center covering frame.
    holes = covered & ~fed_any
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
            take = holes & (best == i) & (cover[i] > 0)
            if take.any():
                pano[take] = np.clip(warped[i][take] * float(gains[i]),
                                     0, 255).astype(np.uint8)
                filled += int(take.sum())
        print(f"hole-filled {filled} px", flush=True)
    a = (np.arange(WRAP_PX, dtype=np.float64) / WRAP_PX)[None, :, None]
    left = pano[:, :WRAP_PX].astype(np.float64)
    right = pano[:, -WRAP_PX:].astype(np.float64)
    pano[:, :WRAP_PX] = ((1 - a) * left + a * right).astype(np.uint8)

    pano_path = os.path.join(OUT, "restitch_v12.jpg")
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
    views_path = os.path.join(OUT, "views_restitch_v12.jpg")
    cv2.imwrite(views_path, sheet, [cv2.IMWRITE_JPEG_QUALITY, 90])
    print(f"saved {views_path}", flush=True)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    main()
