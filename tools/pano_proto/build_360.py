#!/usr/bin/env python3
"""Build a true 2:1 equirectangular 360 from the closed Xue loop.

Takes the v6 stitch (cylindrical band, loop-closing) and:
  1. forces the width to exactly 2*pi*f (full yaw circle),
  2. wrap-blends the closure seam,
  3. remaps rows cylindrical -> equirectangular (elev linear),
producing out/v7.jpg, a real 360 photo any viewer (incl. html-360-viewer)
maps without deformation.
"""
import os
import sys
import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from stitch_proto import cylindrical, pair_transform, FOCAL  # noqa

SEAM_W = 48


def main(names, pano_in, out):
    here = os.path.dirname(os.path.abspath(__file__))
    d = os.path.join(here, "ref", "Xue")
    f = FOCAL
    imgs = [cylindrical(cv2.imread(f"{d}/{n}"), f) for n in names]
    assert all(im is not None for im in imgs)
    n = len(imgs)
    rel = []
    for i in range(1, n):
        A, info = pair_transform(imgs[i - 1], imgs[i])
        print(f"pair {i-1}->{i}: {info}", flush=True)
        assert A is not None
        rel.append(A)
    chains = [np.eye(2, 3)]
    for A in rel:
        chains.append((np.vstack([chains[-1], [0, 0, 1]])
                       @ np.vstack([A, [0, 0, 1]]))[:2])

    def centers(chs):
        xs, ys = [], []
        for im, A in zip(imgs, chs):
            h0, w0 = im.shape[:2]
            p = A @ np.array([w0 / 2.0, h0 / 2.0, 1.0])
            xs.append(p[0]); ys.append(p[1])
        return xs, ys

    # mirror v6 geometry exactly: middle rebase + horizon shear
    mid = n // 2
    base = np.vstack([chains[mid], [0, 0, 1]])
    chains = [(np.linalg.inv(base) @ np.vstack([A, [0, 0, 1]]))[:2]
              for A in chains]
    cxs, cys = centers(chains)
    slope = float(np.polyfit(cxs, cys, 1)[0])
    x0 = float(np.mean(cxs))
    S = np.array([[1.0, -slope, slope * x0], [0.0, 1.0, 0.0]])
    chains = [(S @ np.vstack([A, [0, 0, 1]]))[:2] for A in chains]

    cxs, cys = centers(chains)
    span_rad = (max(cxs) - min(cxs)) / f
    print(f"yaw span {np.degrees(span_rad):.1f} deg", flush=True)

    pano = cv2.imread(pano_in)
    assert pano is not None
    Hc, Wc = pano.shape[:2]
    h, w = imgs[0].shape[:2]
    corners = []
    for im, A in zip(imgs, chains):
        c = np.float32([[0, 0], [w, 0], [w, h], [0, h]]).reshape(-1, 1, 2)
        corners.append(cv2.transform(c, A).reshape(-1, 2))
    allc = np.vstack(corners)
    tx, ty = -allc.min(axis=0)
    yh_canvas = float(np.mean(cys)) + ty
    print(f"canvas {Wc}x{Hc}, horizon row {yh_canvas:.0f}", flush=True)

    # 1+2. force width to exactly 2*pi*f, then wrap-blend the closure seam
    W360 = int(round(2 * np.pi * f))
    pano = cv2.resize(pano, (W360, Hc), interpolation=cv2.INTER_AREA)
    yh_canvas *= 1.0  # rows unchanged by horizontal resize
    out_blend = pano.astype(np.float64)
    S = SEAM_W
    left = out_blend[:, :S].copy()
    right = out_blend[:, -S:].copy()
    a = (np.arange(S, dtype=np.float64) / S)[None, :, None]
    out_blend[:, :S] = (1 - a) * left + a * right
    pano = np.clip(out_blend, 0, 255).astype(np.uint8)

    # 3. cylindrical rows -> equirectangular rows (2:1 canvas).
    # Sign matters: image rows grow downward while elevation grows upward,
    # so sky (elev > 0) lives ABOVE the horizon row (smaller y).
    H360 = W360 // 2
    v = np.arange(H360, dtype=np.float64)
    elev = (0.5 - v / H360) * np.pi
    y_src = yh_canvas - f * np.tan(elev)
    y_src = np.clip(y_src, 0, Hc - 1).astype(np.float32)
    map_x = np.tile(np.arange(W360, dtype=np.float32)[None, :], (H360, 1))
    map_y = np.tile(y_src[:, None], (1, W360))
    equi = cv2.remap(pano, map_x, map_y, cv2.INTER_LINEAR,
                     borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    cv2.imwrite(out, equi, [cv2.IMWRITE_JPEG_QUALITY, 92])
    print(f"saved {out} {W360}x{H360}", flush=True)
    return 0


if __name__ == "__main__":
    import argparse
    here = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="v7")
    parser.add_argument("--pano", default=os.path.join(here, "out", "v6.jpg"))
    args = parser.parse_args()
    names = []
    with open(os.path.join(here, "ref", "Xue", "image_list.txt")) as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith("#"):
                names.append(line.split()[0])
    os.makedirs(os.path.join(here, "out"), exist_ok=True)
    sys.exit(main(names, args.pano, os.path.join(here, "out", args.out + ".jpg")))
