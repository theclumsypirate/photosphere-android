#!/usr/bin/env python3
"""Prototype v2: cylindrical + translation-dominant stitcher with gain comp.

Lessons from v1 (which diverged to a 37k-px canvas): full-homography chaining
on perspective frames accumulates fatal drift. v2 instead:
  - cylindrical projection first (pano-native geometry, like the app's sphere),
  - AffinePartial2D (translation+rotation, no perspective wobble),
  - middle-anchored chain (drift grows both ways, halved),
  - per-image exposure gains from overlap means (like ExposureCompensation),
  - sanity gates per pair (inlier ratio, rotation, scale).

 Corpus: tools/pano_proto/ref/Xue (real photos, f=830px per image_list.txt).
 Outputs: tools/pano_proto/out/vN.jpg (git-ignored).
"""
import sys
import cv2
import numpy as np

FOCAL = 830.0
LOWE_RATIO = 0.75
RANSAC_THRESH = 3.0
MIN_INLIER_RATIO = 0.15
MAX_ROT_DEG = 3.0


def cylindrical(im, f):
    h, w = im.shape[:2]
    cx, cy = w / 2.0, h / 2.0
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    theta = (xs - cx) / f
    ho = (ys - cy) / f
    x_src = f * np.tan(theta) + cx
    y_src = f * ho / np.cos(theta) + cy
    return cv2.remap(im, x_src, y_src, cv2.INTER_LINEAR,
                     borderMode=cv2.BORDER_CONSTANT)


def pair_transform(prev, cur):
    sift = cv2.SIFT_create()
    k1, d1 = sift.detectAndCompute(prev, None)
    k2, d2 = sift.detectAndCompute(cur, None)
    if d1 is None or d2 is None:
        return None, "no-descriptors"
    m = cv2.BFMatcher().knnMatch(d1, d2, k=2)
    good = [a for a, b in m if a.distance < LOWE_RATIO * b.distance]
    if len(good) < 10:
        return None, f"only-{len(good)}-matches"
    p1 = np.float32([k1[g.queryIdx].pt for g in good])
    p2 = np.float32([k2[g.trainIdx].pt for g in good])
    A, mask = cv2.estimateAffinePartial2D(p2, p1, method=cv2.RANSAC,
                                          ransacReprojThreshold=RANSAC_THRESH)
    if A is None or mask is None:
        return None, "ransac-fail"
    inl = int(mask.ravel().sum())
    ratio = inl / len(good)
    rot = float(np.degrees(np.arctan2(A[1, 0], A[0, 0])))
    scale = float(np.sqrt(A[0, 0] ** 2 + A[1, 0] ** 2))
    info = f"inl {inl}/{len(good)} rot {rot:.2f} scale {scale:.3f}"
    if ratio < MIN_INLIER_RATIO:
        return None, info + " LOW-RATIO"
    if abs(rot) > MAX_ROT_DEG or not 0.9 < scale < 1.1:
        return None, info + " WARP-INSANE"
    return A, info + " OK"


def overlap_gains(imgs, chains, iters=20):
    """Iterative per-image gains from pairwise overlap means (Brown & Lowe)."""
    n = len(imgs)
    gray = [cv2.cvtColor(im, cv2.COLOR_BGR2GRAY).astype(np.float64) for im in imgs]
    gains = np.ones(n)
    for _ in range(iters):
        for i in range(n - 1):
            h, w = gray[i].shape
            warped = cv2.warpAffine(gray[i + 1], chains[i + 1], (w * 3, h))[:, :w]
            mine = gray[i] * gains[i]
            other = warped * gains[i + 1]
            m = (gray[i] > 8) & (warped > 8)
            if m.sum() < 500:
                continue
            mu_mine = mine[m].mean()
            mu_other = other[m].mean()
            if mu_mine < 1 or mu_other < 1:
                continue
            # nudge the pair toward equal means, anchored: gains[0] stays 1
            if i > 0:
                corr = float(np.sqrt(mu_other / mu_mine))
                gains[i] /= corr
            gains[i + 1] *= float(np.sqrt(mu_mine / mu_other))
    gains /= gains[0]
    return np.clip(gains, 0.5, 2.0)


def main(names, out):
    import os
    d = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ref", "Xue")
    imgs = [cylindrical(cv2.imread(f"{d}/{n}"), FOCAL) for n in names]
    assert all(im is not None for im in imgs)
    n = len(imgs)
    rel = []
    for i in range(1, n):
        A, info = pair_transform(imgs[i - 1], imgs[i])
        print(f"pair {i-1}->{i}: {info}", flush=True)
        if A is None:
            print("FATAL"); return 1
        rel.append(A)
    # middle-anchored chain
    mid = n // 2
    chains = [None] * n
    chains[mid] = np.eye(2, 3)
    for i in range(mid - 1, -1, -1):
        A = np.vstack([rel[i], [0, 0, 1]])
        chains[i] = (np.vstack([chains[i + 1], [0, 0, 1]]) @ np.linalg.inv(A))[:2]
    for i in range(mid + 1, n):
        A = np.vstack([rel[i - 1], [0, 0, 1]])
        chains[i] = (np.vstack([chains[i - 1], [0, 0, 1]]) @ A)[:2]

    h, w = imgs[0].shape[:2]
    gains = overlap_gains(imgs, [np.eye(2, 3)] + rel)
    print("gains", np.round(gains, 3), flush=True)
    imgs = [(np.clip(im.astype(np.float64) * g, 0, 255)).astype(np.uint8)
            for im, g in zip(imgs, gains)]

    corners = []
    for im, A in zip(imgs, chains):
        c = np.float32([[0, 0], [w, 0], [w, h], [0, h]]).reshape(-1, 1, 2)
        corners.append(cv2.transform(c, A).reshape(-1, 2))
    allc = np.vstack(corners)
    tx, ty = -allc.min(axis=0)
    W, Hh = (allc.max(axis=0) - allc.min(axis=0)).astype(int) + 1
    print(f"canvas {W}x{Hh}", flush=True)
    if W > 12000 or Hh > 6000:
        print("FATAL: diverged"); return 1
    T = np.array([[1, 0, tx], [0, 1, ty]])
    acc = np.zeros((Hh, W, 3), np.float64)
    wsum = np.zeros((Hh, W), np.float64)
    r = 60
    ramp = np.ones((h, w), np.float32)
    ramp[:, :r] *= np.linspace(0, 1, r, dtype=np.float32)
    ramp[:, -r:] *= np.linspace(1, 0, r, dtype=np.float32)
    for im, A in zip(imgs, chains):
        M = (T @ np.vstack([A, [0, 0, 1]]))[:2]
        warped = cv2.warpAffine(im, M, (W, Hh))
        m = cv2.warpAffine(ramp, M, (W, Hh), flags=cv2.INTER_NEAREST)
        acc += warped.astype(np.float64) * m[..., None]
        wsum += m
    pano = np.where(wsum[..., None] > 1e-6,
                    acc / np.maximum(wsum, 1e-6)[..., None], 0).astype(np.uint8)
    ys, xs = np.where(wsum > 1e-6)
    pano = pano[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    cv2.imwrite(out, pano, [cv2.IMWRITE_JPEG_QUALITY, 92])
    print(f"saved {out} {pano.shape[1]}x{pano.shape[0]}", flush=True)
    return 0


if __name__ == "__main__":
    import os
    here = os.path.dirname(os.path.abspath(__file__))
    os.makedirs(os.path.join(here, "out"), exist_ok=True)
    names = [f"DSC_017{i}.jpg" for i in range(1, 9)]
    sys.exit(main(names, os.path.join(here, "out", "v2.jpg")))
