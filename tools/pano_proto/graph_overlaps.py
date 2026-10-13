#!/usr/bin/env python3
"""Overlap graph for an unordered set: ORB matching over ALL pairs.

Finds which frames overlap at all (connected components) and how strongly,
so a rotational chain can be extracted for stitch_v3. Multiprocessed.

Usage: python3 tools/pano_proto/graph_overlaps.py --dir data_set --pattern '*.png'
"""
import argparse
import glob
import itertools
import multiprocessing as mp
import os
import sys
import cv2
import numpy as np

LOWE = 0.8
RANSAC = 4.0
MIN_INLIERS = 30


def load_small(path):
    im = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    h, w = im.shape
    s = 640 / w
    return cv2.resize(im, (640, int(h * s)), interpolation=cv2.INTER_AREA)


def feats(gray):
    orb = cv2.ORB_create(nfeatures=1500)
    k, d = orb.detectAndCompute(gray, None)
    if not k or d is None:
        return [], None
    return [(p.pt[0], p.pt[1]) for p in k], d


def pair_job(args):
    i, j, gi, gj = args
    if gi is None or gj is None or len(gi[1]) < 8 or len(gj[1]) < 8:
        return (i, j, 0, 0.0, 0.0)
    m = cv2.BFMatcher(cv2.NORM_HAMMING).knnMatch(gi[1], gj[1], k=2)
    good = [a for a, b in m if a.distance < LOWE * b.distance]
    if len(good) < MIN_INLIERS:
        return (i, j, len(good), 0.0, 0.0)
    p1 = np.float32([gi[0][g.queryIdx] for g in good])
    p2 = np.float32([gj[0][g.trainIdx] for g in good])
    A, mask = cv2.estimateAffinePartial2D(p1, p2, method=cv2.RANSAC,
                                          ransacReprojThreshold=RANSAC)
    if A is None or mask is None:
        return (i, j, len(good), 0.0, 0.0)
    inl = int(mask.ravel().sum())
    rot = float(np.degrees(np.arctan2(A[1, 0], A[0, 0])))
    tx = float(np.hypot(A[0, 2], A[1, 2]))
    return (i, j, inl, rot, tx)


def main(d, pattern, jobs):
    files = sorted(glob.glob(os.path.join(d, pattern)))
    n = len(files)
    print(f"{n} files", flush=True)
    grays = [load_small(f) for f in files]
    desc = [feats(g) for g in grays]
    pairs = list(itertools.combinations(range(n), 2))
    print(f"{len(pairs)} pairs x{jobs}", flush=True)
    with mp.Pool(jobs) as pool:
        res = pool.map(pair_job, [(i, j, desc[i], desc[j]) for i, j in pairs])
    strong = [(i, j, inl, rot, tx) for i, j, inl, rot, tx in res if inl >= MIN_INLIERS]
    strong.sort(key=lambda t: -t[2])
    print(f"strong pairs: {len(strong)}", flush=True)
    for i, j, inl, rot, tx in strong[:40]:
        print(f"  {i:02d}-{j:02d} inl={inl} rot={rot:.1f} t={tx:.0f}", flush=True)
    # union-find components
    parent = list(range(n))
    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a
    for i, j, _, _, _ in strong:
        ri, rj = find(i), find(j)
        if ri != rj:
            parent[ri] = rj
    from collections import defaultdict
    comp = defaultdict(list)
    for i in range(n):
        comp[find(i)].append(i)
    print("components:", flush=True)
    for root, members in sorted(comp.items(), key=lambda kv: -len(kv[1])):
        print(f"  size {len(members)}: {members[0]}..{members[-1]} (n={len(members)})", flush=True)
    return 0


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=None)
    ap.add_argument("--pattern", default="*.jpg")
    ap.add_argument("--jobs", type=int, default=os.cpu_count() or 4)
    a = ap.parse_args()
    d = a.dir if (a.dir and os.path.isabs(a.dir)) else os.path.join(here, a.dir or "ref/all36")
    sys.exit(main(d, a.pattern, a.jobs))
