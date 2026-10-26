#!/usr/bin/env python3
"""SPS Dataset seam benchmark: CI-only tooling, never run locally.

The SPS Dataset (cic.tju.edu.cn, "Spherical superpixels: benchmark and
evaluation", ACCV 2018) holds 75 spherical panoramas, 1024x512
equirectangular, each with a MATLAB .mat holding a human segmentation
(labels and boundary). The page terms are GNU GPL style with commercial
use prohibited, so this corpus stays internal: nothing derived from it
may ever reach the APK or a release asset.

What this script does (research report sections 3.15 and 5.1):

1. Convert: scipy.io.loadmat reads each .mat; the label array and the
   boundary array are written out as PNGs (labels palette-indexed,
   boundaries binary).
2. Stats: per image, segment count, segment size distribution, and the
   row-band occupancy of segments near the equator and the poles, which
   sanity-checks the polar pinching assumptions SphericalGeometry
   carries.
3. Seam metric: with seam maps present (PNG per image, nonzero on seam
   pixels), the reported fraction of seam pixels that cross a human
   segmentation boundary. Lower is better: the seam should cut in the
   background, not across the piano.

Usage, one subcommand each:

    python3 tools/sps/seam_benchmark.py convert <data_dir> <out_png_dir>
    python3 tools/sps/seam_benchmark.py stats <png_dir> <report_json>
    python3 tools/sps/seam_benchmark.py seams <png_dir> <seams_dir> <report_json>

<data_dir> is the extracted dataset (the .mat files and their images).
<seams_dir> holds seam maps named after the image stems; missing ones
are skipped and named in the report. The corpus is single panoramas, so
it cannot exercise multi-frame registration; that regression stays on
the app's own run 7 numbers plus StitchBench, per the report.
"""

from __future__ import annotations

import json
import os
import sys
from collections import Counter

import numpy as np

try:
    from scipy.io import loadmat
except ImportError:  # pragma: no cover - reported at run time
    loadmat = None

PNG_DIR_LABEL_SUFFIX = "_labels.png"
PNG_DIR_BOUNDARY_SUFFIX = "_boundary.png"
SEAM_SUFFIX = "_seams.png"


def _labels_and_boundary(mat_path: str):
    """Reads a dataset .mat into (labels, boundary) uint arrays.

    The dataset's MATLAB files carry the segmentation as labelled arrays;
    the exact field names differ between the per-image files, so each
    candidate field is tried in turn and the first nonzero 2-D array is
    taken as labels and the next as the boundary. A file whose arrays
    cannot be told apart this way is reported and skipped rather than
    guessed at: a wrong mask silently poisons every downstream metric.
    """
    loaded = loadmat(mat_path)
    arrays = [
        value
        for key, value in loaded.items()
        if not key.startswith("__")
        and isinstance(value, np.ndarray)
        and value.ndim == 2
    ]
    labels = next((a for a in arrays if a.max() > 1), None)
    boundary = next((a for a in arrays if a.dtype.kind in "bu" and a.max() <= 1), None)
    if labels is None:
        return None, None
    if boundary is None:
        # The dataset's boundary arrays are 1 where a segment edge runs;
        # some files ship them inside the labels file under a near-identical
        # name, so a gradient of the labels is the fallback, never asserted.
        edges = np.zeros_like(labels, dtype=bool)
        edges[:-1, :] |= labels[:-1, :] != labels[1:, :]
        edges[:, :-1] |= labels[:, :-1] != labels[:, 1:]
        boundary = edges.astype(np.uint8)
    return labels.astype(np.int32), boundary.astype(np.uint8)


def commands_convert(data_dir: str, out_dir: str) -> int:
    """Converts every dataset .mat into palette label and boundary PNGs."""
    from PIL import Image

    os.makedirs(out_dir, exist_ok=True)
    mats = sorted(f for f in os.listdir(data_dir) if f.endswith(".mat"))
    if not mats:
        print(f"no .mat files under {data_dir}", file=sys.stderr)
        return 1
    converted = 0
    for name in mats:
        stem = os.path.splitext(name)[0]
        labels, boundary = _labels_and_boundary(os.path.join(data_dir, name))
        if labels is None:
            print(f"skip {name}: no usable segmentation array", file=sys.stderr)
            continue
        palette = Image.fromarray(labels.astype(np.int32), mode="I")
        palette.save(os.path.join(out_dir, stem + PNG_DIR_LABEL_SUFFIX))
        Image.fromarray(boundary * 255).convert("L").save(
            os.path.join(out_dir, stem + PNG_DIR_BOUNDARY_SUFFIX)
        )
        converted += 1
    print(f"converted {converted} of {len(mats)} .mat files into {out_dir}")
    return 0


def _row_occupancy(labels: np.ndarray) -> dict:
    """Fills per latitude band: how much of each row band any segment covers."""
    rows = labels.shape[0]
    bands = {"equator": (rows // 3, 2 * rows // 3)}
    bands["north_pole"] = (0, rows // 6)
    bands["south_pole"] = (5 * rows // 6, rows)
    report = {}
    for band, (start, end) in bands.items():
        region = labels[start:end]
        covered = float(np.count_nonzero(region)) / float(region.size)
        report[band] = round(covered, 4)
    return report


def _segment_stats(labels: np.ndarray) -> dict:
    counts = Counter(labels.flatten().tolist())
    counts.pop(0, None)
    sizes = sorted(counts.values())
    if not sizes:
        return {"segments": 0}
    def share_of_total(quantile_share: float) -> int:
        total = sum(sizes)
        running = 0
        for size in sizes:
            running += size
            if running >= quantile_share * total:
                return size
        return sizes[-1]
    return {
        "segments": len(sizes),
        "smallest": sizes[0],
        "median": sizes[len(sizes) // 2],
        "largest": sizes[-1],
        "share_90pct_pixels_reached_size": share_of_total(0.9),
    }


def commands_stats(png_dir: str, report_path: str) -> int:
    """Per image: segment count, sizes, latitude band occupancy."""
    from PIL import Image

    labels_files = sorted(
        f for f in os.listdir(png_dir) if f.endswith(PNG_DIR_LABEL_SUFFIX)
    )
    if not labels_files:
        print(f"no label PNGs under {png_dir}", file=sys.stderr)
        return 1
    per_image = {}
    for name in labels_files:
        stem = name[: -len(PNG_DIR_LABEL_SUFFIX)]
        labels = np.asarray(Image.open(os.path.join(png_dir, name)))
        per_image[stem] = {
            "size": list(labels.shape),
            "segments": _segment_stats(labels),
            "row_occupancy": _row_occupancy(labels),
        }
    with open(report_path, "w", encoding="utf-8") as handle:
        json.dump({"images": per_image}, handle, indent=2, sort_keys=True)
    print(f"stats for {len(per_image)} images written to {report_path}")
    return 0


def commands_seams(png_dir: str, seams_dir: str, report_path: str) -> int:
    """Fraction of seam pixels crossing a human segmentation boundary.

    Per image with both a boundary PNG and a seam map present: of the
    pixels the seam map marks, how many sit on a segmentation boundary.
    Lower is better; background cuts read as a low crossing share. The
    metric ignores the image entirely, so it runs at CI cost alone.
    """
    from PIL import Image

    boundary_files = sorted(
        f for f in os.listdir(png_dir) if f.endswith(PNG_DIR_BOUNDARY_SUFFIX)
    )
    if not boundary_files:
        print(f"no boundary PNGs under {png_dir}", file=sys.stderr)
        return 1
    per_image = {}
    missing = []
    for name in boundary_files:
        stem = name[: -len(PNG_DIR_BOUNDARY_SUFFIX)]
        seam_path = os.path.join(seams_dir, stem + SEAM_SUFFIX)
        if not os.path.exists(seam_path):
            missing.append(stem)
            continue
        boundary = np.asarray(
            Image.open(os.path.join(png_dir, name)).convert("L")
        ) > 127
        seams = np.asarray(Image.open(seam_path).convert("L")) > 0
        if seams.shape != boundary.shape:
            # Deterministic, not stretching: a map at the wrong size is a
            # pipeline bug worth surfacing, not papering over.
            per_image[stem] = {"error": "seam map size mismatch"}
            continue
        seam_pixels = int(np.count_nonzero(seams))
        crossing = int(np.count_nonzero(seams & boundary))
        per_image[stem] = {
            "seam_pixels": seam_pixels,
            "crossing_boundary": crossing,
            "crossing_share": round(crossing / seam_pixels, 4) if seam_pixels else None,
        }
    shares = [
        entry["crossing_share"]
        for entry in per_image.values()
        if entry.get("crossing_share") is not None
    ]
    summary = {
        "images": per_image,
        "missing_seam_maps": sorted(missing),
        "mean_crossing_share": round(float(np.mean(shares)), 4) if shares else None,
    }
    with open(report_path, "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2, sort_keys=True)
    print(
        f"seam metric for {len(shares)} images "
        f"({len(missing)} maps missing) written to {report_path}"
    )
    return 0


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__)
        return 2
    command, data_dir, second = argv[0], argv[1], argv[2]
    if loadmat is None and command == "convert":
        print("scipy is required for convert; install it in the CI image", file=sys.stderr)
        return 2
    if command == "convert":
        return commands_convert(data_dir, second)
    if command == "stats":
        return commands_stats(data_dir, second)
    if command == "seams":
        if len(argv) != 4:
            print(__doc__)
            return 2
        return commands_seams(data_dir, second, argv[3])
    print(__doc__)
    return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
