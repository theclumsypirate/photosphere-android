#!/usr/bin/env python3
"""Reference baseline: stock OpenCV Stitcher (same engine family xpano uses).

Usage: python3 tools/pano_proto/stitch_opencv.py --out ref_cv2 [--w 2048]
"""
import argparse
import glob
import os
import sys
import cv2

MODES = {"pano": cv2.Stitcher_PANORAMA, "scans": cv2.Stitcher_SCANS}


def main(d, out, w, mode):
    files = sorted(glob.glob(os.path.join(d, "*.jpg")))
    print(f"{len(files)} frames", flush=True)
    imgs = []
    for f in files:
        im = cv2.imread(f)
        h, ww = im.shape[:2]
        if ww > w:
            im = cv2.resize(im, (w, int(h * w / ww)), interpolation=cv2.INTER_AREA)
        imgs.append(im)
    stitcher = cv2.Stitcher_create(MODES[mode])
    orb = cv2.ORB_create()
    kept, dropped = [], []
    for f, im in zip(files, imgs):
        kp = orb.detect(im, None)
        if len(kp) < 15:
            dropped.append((f, len(kp)))
        else:
            kept.append(im)
    print(f"kept {len(kept)}, dropped {dropped}", flush=True)
    status, pano = stitcher.stitch(kept)
    print(f"status: {status} (OK={cv2.Stitcher_OK})", flush=True)
    if status != cv2.Stitcher_OK:
        return 1
    cv2.imwrite(out, pano, [cv2.IMWRITE_JPEG_QUALITY, 92])
    print(f"saved {out} {pano.shape[1]}x{pano.shape[0]}", flush=True)
    return 0


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="ref_cv2")
    ap.add_argument("--w", type=int, default=2048)
    ap.add_argument("--mode", default="scans", choices=list(MODES))
    a = ap.parse_args()
    os.makedirs(os.path.join(here, "out"), exist_ok=True)
    sys.exit(main(os.path.join(here, "ref", "all36"),
                  os.path.join(here, "out", a.out + ".jpg"), a.w, a.mode))
