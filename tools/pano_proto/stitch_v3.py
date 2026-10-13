#!/usr/bin/env python3
"""Prototype v3: sequential L-to-R composite with center seams.

v2 critique (6.5/10): wide feather blending across misaligned overlaps ghosts
the sky into translucent vertical bands. Fix: hard seam at each overlap's
center + narrow 24px crossfade (the poor man's seam carving), L-to-R chaining
with the v2 sanity gates.
"""
import os
import sys
import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from stitch_proto import cylindrical, pair_transform, overlap_gains, FOCAL  # noqa

SEAM_HALF = 12


def main(names, out, d):
    here = os.path.dirname(os.path.abspath(__file__))
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
    chains = [np.eye(2, 3)]
    for A in rel:
        chains.append((np.vstack([chains[-1], [0, 0, 1]])
                       @ np.vstack([A, [0, 0, 1]]))[:2])

    h, w = imgs[0].shape[:2]
    gains = overlap_gains(imgs, chains)
    print("gains", np.round(gains, 3), flush=True)
    imgs = [(np.clip(im.astype(np.float64) * g, 0, 255)).astype(np.uint8)
            for im, g in zip(imgs, gains)]

    # Rebase the chain on the middle frame: drift grows both ways, halved.
    mid = n // 2
    base = np.vstack([chains[mid], [0, 0, 1]])
    chains = [(np.linalg.inv(base) @ np.vstack([A, [0, 0, 1]]))[:2]
              for A in chains]

    # Straighten the horizon: fit a line through the warped image centers and
    # shear it flat (end-to-end drift correction). Purely global, no seams.
    cx, cy = [], []
    for im, A in zip(imgs, chains):
        h0, w0 = im.shape[:2]
        p = (A @ np.array([w0 / 2.0, h0 / 2.0, 1.0]))
        cx.append(p[0])
        cy.append(p[1])
    slope = float(np.polyfit(cx, cy, 1)[0])
    print(f"horizon slope {slope:.4f} px/px", flush=True)
    if abs(slope * (max(cx) - min(cx))) > 2.0:
        x0 = float(np.mean(cx))
        S = np.array([[1.0, -slope, slope * x0], [0.0, 1.0, 0.0]])
        chains = [(S @ np.vstack([A, [0, 0, 1]]))[:2] for A in chains]

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

    canvas = np.zeros((Hh, W, 3), np.float64)
    valid = np.zeros((Hh, W), bool)
    order = [mid] + [i for i in range(mid + 1, n)] + [i for i in range(mid - 1, -1, -1)]
    for i in order:
        im, A = imgs[i], chains[i]
        M = (T @ np.vstack([A, [0, 0, 1]]))[:2]
        warped = cv2.warpAffine(im, M, (W, Hh))
        new = warped.sum(axis=2) > 0
        fresh = new & ~valid
        canvas[fresh] = warped[fresh]
        over = new & valid
        if over.any():
            cols = np.where(over.any(axis=0))[0]
            seam = (cols.min() + cols.max()) // 2
            # Which side is the new image on? Flip the ramp for left growth.
            old_cx = np.where(valid)[1].mean()
            new_cx = np.where(fresh | over)[1].mean()
            xs = np.arange(W, dtype=np.float64)
            a = np.clip((xs - (seam - SEAM_HALF)) / (2 * SEAM_HALF), 0, 1)
            if new_cx < old_cx:
                a = 1 - a
            a = np.broadcast_to(a[None, :], (Hh, W))
            both = over[..., None]
            canvas = np.where(both, canvas * (1 - a[..., None])
                              + warped.astype(np.float64) * a[..., None], canvas)
        valid |= new

    pano = canvas.astype(np.uint8)
    ys, xs = np.where(valid)
    pano = pano[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    cv2.imwrite(out, pano, [cv2.IMWRITE_JPEG_QUALITY, 92])
    print(f"saved {out} {pano.shape[1]}x{pano.shape[0]}", flush=True)
    return 0


if __name__ == "__main__":
    import argparse
    here = os.path.dirname(os.path.abspath(__file__))
    os.makedirs(os.path.join(here, "out"), exist_ok=True)
    parser = argparse.ArgumentParser(description="Pano prototype stitcher")
    parser.add_argument("--out", default="v5",
                        help="output name, saved as out/<name>.jpg")
    parser.add_argument("--images", nargs="*", default=None,
                        help="image files in stitch order; default: image_list.txt")
    parser.add_argument("--focal", type=float, default=FOCAL)
    parser.add_argument("--dir", default=None,
                        help="input dir (default: ref/Xue)")
    parser.add_argument("--pattern", default="*.jpg",
                        help="glob when no image_list.txt and no --images")
    args = parser.parse_args()
    d = args.dir if (args.dir and os.path.isabs(args.dir)) else os.path.join(
        here, args.dir or os.path.join("ref", "Xue"))
    if args.images:
        names = args.images
    else:
        names = []
        lst = os.path.join(d, "image_list.txt")
        if os.path.exists(lst):
            with open(lst) as fh:
                for line in fh:
                    line = line.strip()
                    if line and not line.startswith("#"):
                        names.append(line.split()[0])
        else:
            import glob as _glob
            names = sorted(os.path.basename(p)
                           for p in _glob.glob(os.path.join(d, args.pattern)))
    print(f"stitching {len(names)} images: {names[0]} .. {names[-1]}", flush=True)
    FOCAL = args.focal
    sys.exit(main(names, os.path.join(here, "out", args.out + ".jpg"), d))
