#!/usr/bin/env python3
"""Translation mosaic for forward-motion sets (the data_set case).

Consecutive-frame SIFT matches filtered by motion-direction consistency
(repetitive texture lies about *which* frames overlap, but true motion keeps
one direction), then chained translations composited with feathering.
Result is a top-down-style strip MAP, not a 360 sphere.

Usage: python3 tools/pano_proto/translation_mosaic.py --dir data_set --pattern '*.png' --out mosaic
"""
import argparse
import glob
import math
import os
import sys
import cv2
import numpy as np

LOWE = 0.75
RANSAC = 4.0
MIN_INLIERS = 60
MAX_ROT = 2.0
DIR_TOL_DEG = 25.0


def pair_motion(a, b):
    sift = cv2.SIFT_create()
    k1, d1 = sift.detectAndCompute(a, None)
    k2, d2 = sift.detectAndCompute(b, None)
    if d1 is None or d2 is None:
        return None
    m = cv2.BFMatcher().knnMatch(d1, d2, k=2)
    good = [x for x, y in m if x.distance < LOWE * y.distance]
    if len(good) < MIN_INLIERS:
        return None
    p1 = np.float32([k1[g.queryIdx].pt for g in good])
    p2 = np.float32([k2[g.trainIdx].pt for g in good])
    A, mask = cv2.estimateAffinePartial2D(p1, p2, method=cv2.RANSAC,
                                          ransacReprojThreshold=RANSAC)
    if A is None or mask is None or int(mask.ravel().sum()) < MIN_INLIERS:
        return None
    rot = float(np.degrees(np.arctan2(A[1, 0], A[0, 0])))
    return {"dx": float(A[0, 2]), "dy": float(A[1, 2]), "rot": rot,
            "inl": int(mask.ravel().sum())}


def main(d, pattern, out_stem):
    here = os.path.dirname(os.path.abspath(__file__))
    files = sorted(glob.glob(os.path.join(d, pattern)))
    n = len(files)
    print(f"{n} files", flush=True)
    imgs = [cv2.imread(f) for f in files]
    assert all(im is not None for im in imgs)

    motions = []
    for i in range(n - 1):
        m = pair_motion(imgs[i], imgs[i + 1])
        print(f"pair {i:02d}->{i+1:02d}: "
              f"{('dx=%6.0f dy=%6.0f rot=%5.1f inl=%d' % (m['dx'], m['dy'], m['rot'], m['inl'])) if m else 'NO MATCH'}",
              flush=True)
        motions.append(m)

    oks = [(i, m) for i, m in enumerate(motions)
           if m and abs(m["rot"]) <= MAX_ROT]
    angs = [math.degrees(math.atan2(m["dy"], m["dx"])) for _, m in oks]
    med = float(np.median(angs)) if angs else 0.0
    print(f"median motion direction: {med:.1f} deg", flush=True)

    def ang_diff(a, b):
        d = abs(a - b) % 360.0
        return min(d, 360.0 - d)

    keep = []
    for i, m in enumerate(motions):
        if m is None or abs(m["rot"]) > MAX_ROT:
            continue
        a = math.degrees(math.atan2(m["dy"], m["dx"]))
        if ang_diff(a, med) <= DIR_TOL_DEG:
            keep.append(i)
    print(f"kept {len(keep)}/{n - 1} links: {keep}", flush=True)

    # segments of consecutive kept links
    segs, cur = [], []
    for i in keep:
        if cur and i != cur[-1] + 1:
            segs.append(cur)
            cur = []
        cur.append(i)
    if cur:
        segs.append(cur)
    segs.sort(key=len, reverse=True)
    print(f"segments (link runs): {[[s[0], s[-1]] for s in segs]}", flush=True)

    outdir = os.path.join(here, "out")
    os.makedirs(outdir, exist_ok=True)
    for si, seg in enumerate(segs):
        ids = [seg[0]] + [i + 1 for i in seg]
        # accumulate translations relative to first frame of segment
        xs, ys = [0.0], [0.0]
        for i in seg:
            m = motions[i]
            xs.append(xs[-1] + m["dx"])
            ys.append(ys[-1] + m["dy"])
        # note: mapping frame i+1 -> frame i by (dx,dy); canvas = frame0 + offsets
        h, w = imgs[0].shape[:2]
        # canvas placement: invert sign (content moves opposite to camera)
        ox = [-x for x in xs]
        oy = [-y for y in ys]
        minx, miny = min(ox), min(oy)
        W = int(max(ox) - minx + w) + 1
        H = int(max(oy) - miny + h) + 1
        if W > 20000 or H > 20000 or W <= 0 or H <= 0:
            print(f"segment {si}: insane canvas {W}x{H}, skipped", flush=True)
            continue
        acc = np.zeros((H, W, 3), np.float64)
        cnt = np.zeros((H, W), np.float64)
        r = 40
        feather = np.ones((h, w), np.float32)
        feather[:, :r] *= np.linspace(0, 1, r, dtype=np.float32)
        feather[:, -r:] *= np.linspace(1, 0, r, dtype=np.float32)
        feather[:r, :] *= np.linspace(0, 1, r, dtype=np.float32)[:, None]
        feather[-r:, :] *= np.linspace(1, 0, r, dtype=np.float32)[:, None]
        for fid, dx, dy in zip(ids, ox, oy):
            X, Y = int(round(dx - minx)), int(round(dy - miny))
            acc[Y:Y + h, X:X + w] += imgs[fid].astype(np.float64) * feather[..., None]
            cnt[Y:Y + h, X:X + w] += feather
        mosaic = np.where(cnt[..., None] > 1e-6, acc / np.maximum(cnt, 1e-6)[..., None], 0)
        mosaic = np.clip(mosaic, 0, 255).astype(np.uint8)
        ys_, xs_ = np.where(cnt > 1e-6)
        mosaic = mosaic[ys_.min():ys_.max() + 1, xs_.min():xs_.max() + 1]
        p = os.path.join(outdir, f"{out_stem}_{si}.jpg")
        cv2.imwrite(p, mosaic, [cv2.IMWRITE_JPEG_QUALITY, 90])
        print(f"segment {si}: frames {ids[0]}..{ids[-1]} ({len(ids)}) -> {p} "
              f"{mosaic.shape[1]}x{mosaic.shape[0]}", flush=True)
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--pattern", default="*.png")
    ap.add_argument("--out", default="mosaic")
    a = ap.parse_args()
    sys.exit(main(a.dir, a.pattern, a.out))
