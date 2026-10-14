#!/usr/bin/env python3
"""restitch v14: OpenStitching reference run (independent engine).

No sensor poses, no custom warp: the 37 room frames go through the
OpenStitching pipeline (SIFT + homography matching, bundle adjustment,
spherical warp, gain compensation, graph-cut seam, multiband blend) as
a blind reference. Expectation from the pano_proto README (stock
cv2.Stitcher): blind chaining cannot use poses and builds a mosaic,
not a sphere. This run checks whether OpenStitching beats that.

Needs: pip install stitching (OpenStitching/stitching on GitHub).

Usage:
    python3 tools/restitch_other/restitch_v14.py
"""
import glob
import os
import sys
import time

import cv2
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "pano_proto"))
from view360 import VIEWS, render_view

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "pano_proto", "ref", "all36")
OUT = os.path.join(HERE, "out")


def main():
    from stitching import Stitcher

    files = sorted(glob.glob(os.path.join(SRC, "*.jpg")))
    print(f"{len(files)} frames", flush=True)
    images = [cv2.imread(f) for f in files]

    stitcher = Stitcher(
        # ORB, not SIFT: binary descriptors match with brute-force Hamming,
        # while SIFT goes through FLANN and crashes on texture-poor frames
        # (knn > index size assert, same as stock cv2.Stitcher).
        detector="orb",
        matcher_type="hom",
        range_width=-1,
        try_use_gpu=False,
        # "no": bundle adjustment fails on these matches (same reason our
        # own refinement refuses everything). Skip it, keep estimated
        # cameras, and see what the blind pipeline builds.
        adjuster="no",
        warper_type="spherical",
        wave_correct_kind="horiz",
        compensator="gain_blocks",
        finder="gc_color",
        blender_type="multiband",
        blend_strength=5,
        crop=True,
        final_megapix=2,
    )
    t0 = time.time()
    pano = stitcher.stitch(images)
    print(f"stitched in {time.time() - t0:.0f}s, shape {pano.shape}", flush=True)

    pano_path = os.path.join(OUT, "restitch_v14.jpg")
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
    views_path = os.path.join(OUT, "views_restitch_v14.jpg")
    cv2.imwrite(views_path, sheet, [cv2.IMWRITE_JPEG_QUALITY, 90])
    print(f"saved {views_path}", flush=True)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    main()
