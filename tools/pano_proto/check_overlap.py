#!/usr/bin/env python3
"""Ghost check for a capture chain: overlap score + ghost image per pair.

For each consecutive pair (plus the closing pair last-first) it matches
ORB features, estimates the alignment, and writes a ghost image: frame B
warped onto frame A at 50 percent opacity. Double edges in the ghost mean
a bad frame worth retaking. Prints OK / SLAB / PROST per pair.

Usage: python3 tools/pano_proto/check_overlap.py --dir ref/all36
Only reads the frames, never touches Kotlin or the app.
"""
import argparse
import glob
import os
import sys

import cv2
import numpy as np

LOWE = 0.8
RANSAC = 4.0
OK_INLIERS = 60
WEAK_INLIERS = 30


def load_small(path, width=640):
    im = cv2.imread(path)
    if im is None:
        return None, None
    h, w = im.shape[:2]
    s = width / w
    small = cv2.resize(im, (width, int(h * s)), interpolation=cv2.INTER_AREA)
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
    return small, gray


def feats(gray):
    orb = cv2.ORB_create(nfeatures=1500)
    k, d = orb.detectAndCompute(gray, None)
    if not k or d is None:
        return [], None
    return [(p.pt[0], p.pt[1]) for p in k], d


def match_pair(fi, fj):
    if fi[1] is None or fj[1] is None or len(fi[1]) < 8 or len(fj[1]) < 8:
        return 0, None
    m = cv2.BFMatcher(cv2.NORM_HAMMING).knnMatch(fi[1], fj[1], k=2)
    good = [a for a, b in m if a.distance < LOWE * b.distance]
    if len(good) < WEAK_INLIERS:
        return len(good), None
    p1 = np.float32([fi[0][g.queryIdx] for g in good])
    p2 = np.float32([fj[0][g.trainIdx] for g in good])
    A, mask = cv2.estimateAffinePartial2D(p1, p2, method=cv2.RANSAC,
                                          ransacReprojThreshold=RANSAC)
    if A is None or mask is None:
        return len(good), None
    return int(mask.ravel().sum()), A


def ghost(a_small, b_small, A_to_b):
    """Warp A into B's frame, blend 50/50. Sharp = good, doubled = bad."""
    h, w = b_small.shape[:2]
    # A maps to B via inverse of (A->B)? estimateAffinePartial2D above maps
    # A points onto B points, so warp A with A directly into B's canvas.
    warped = cv2.warpAffine(a_small, A_to_b, (w, h))
    return cv2.addWeighted(b_small, 0.5, warped, 0.5, 0)


def main(d, outdir):
    files = sorted(glob.glob(os.path.join(d, "*.jpg")))
    n = len(files)
    print(f"{n} files", flush=True)
    if n < 2:
        return 1
    os.makedirs(outdir, exist_ok=True)
    smalls = []
    feats_list = []
    for f in files:
        s, g = load_small(f)
        smalls.append(s)
        feats_list.append(feats(g) if g is not None else ([], None))
    pairs = [(i, (i + 1) % n) for i in range(n)]
    bad = 0
    report = []
    for i, j in pairs:
        inl, A = match_pair(feats_list[i], feats_list[j])
        if inl >= OK_INLIERS and A is not None:
            verdict = "OK"
        elif inl >= WEAK_INLIERS and A is not None:
            verdict = "SLAB"
        else:
            verdict = "PROST"
            bad += 1
        tag = f"{i:02d}-{j:02d}"
        if A is not None:
            g = ghost(smalls[i], smalls[j], A)
        else:
            # No alignment found: side by side so the gap is visible.
            h = max(smalls[i].shape[0], smalls[j].shape[0])
            g = np.hstack([smalls[i], smalls[j]])
        cv2.imwrite(os.path.join(outdir, f"ghost_{tag}_{verdict}.jpg"), g)
        line = f"{tag} inl={inl} {verdict}"
        print(f"  {line}", flush=True)
        report.append(line)
    with open(os.path.join(outdir, "report.txt"), "w") as fh:
        fh.write(f"frames: {n}\nbad: {bad}\n")
        fh.write("\n".join(report) + "\n")
    print(f"bad: {bad}/{n}, ghosts in {outdir}", flush=True)
    return 0


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="ref/all36")
    ap.add_argument("--out", default="out/overlap_check")
    a = ap.parse_args()
    d = a.dir if os.path.isabs(a.dir) else os.path.join(here, a.dir)
    o = a.out if os.path.isabs(a.out) else os.path.join(here, a.out)
    sys.exit(main(d, o))
