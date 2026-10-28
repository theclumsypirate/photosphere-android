#!/usr/bin/env python3
"""Stitch a capture session with OpenStitching, no EXIF and no pose seeds.

This is the content-only counterpart to stitch_offline.py. It hands the
frames to the `stitching` package (the OpenStitching wrapper around
OpenCV's native stitcher), which finds corresponding features in the
pixels, estimates one camera per frame and blends the result. Nothing
here reads the EXIF comment, the sidecar, or any capture metadata: the
overlap between frames is the only thing it needs.

Use it for a quick look at a session when the pose path is not ready,
or to compare two independent answers on the same frames.

    python3 stitch_openstitching.py <directory> -o pano_openstitching.jpg --run yeah

Every run is gated by --run yeah so a stray enter key does not start a
multi-minute stitch.
"""

import argparse
import glob
import os
import re
import sys
import time

import cv2

RUN_CONFIRM = "yeah"

# The frames are upright portrait stills; a width around 800 to 1000 keeps
# the spherical canvas near 5000 by 2500 without spending gigabytes in the
# multiband pyramid, and the result still downsamples cleanly to 2048.
DEFAULT_WIDTH = 900
DEFAULT_FINAL_WIDTH = 2048

IMAGE_PATTERNS = ("*.jpg", "*.jpeg", "*.png")


def natural_key(path):
    """Sort frame_2 before frame_10, which plain string order does not."""
    name = os.path.basename(path)
    return [int(part) if part.isdigit() else part.lower()
            for part in re.split(r"(\d+)", name)]


def collect_images(directory):
    """Every image directly inside the directory, in natural order."""
    files = []
    for pattern in IMAGE_PATTERNS:
        files.extend(glob.glob(os.path.join(directory, pattern)))
        files.extend(glob.glob(os.path.join(directory, pattern.upper())))
    unique = sorted(set(files), key=natural_key)
    return unique


def prepare_working_copies(files, work_dir, width):
    """Resize each frame into [work_dir] and return the copies' paths.

    The stitcher's own medium and low resolution passes are separate from
    the final canvas, but resizing up front is what keeps the final
    spherical canvas, and therefore the blend, within reach. EXIF is not
    read by this path, so a clean re-encode loses nothing that matters.
    """
    os.makedirs(work_dir, exist_ok=True)
    prepared = []
    for position, path in enumerate(files):
        image = cv2.imread(path, cv2.IMREAD_COLOR)
        if image is None:
            raise SystemExit("could not read %s" % path)
        height, frame_width = image.shape[:2]
        if width > 0 and frame_width > width:
            scale = width / float(frame_width)
            image = cv2.resize(
                image,
                (width, max(1, int(round(height * scale)))),
                interpolation=cv2.INTER_AREA,
            )
        target = os.path.join(work_dir, "frame_%03d.jpg" % position)
        cv2.imwrite(target, image, [cv2.IMWRITE_JPEG_QUALITY, 95])
        prepared.append(target)
        print("  prepared %-28s %dx%d" % (
            os.path.basename(path), image.shape[1], image.shape[0]))
    return prepared


def make_stitcher(args):
    """The `stitching` package's Stitcher, configured for a rotation-only
    capture: homography per pair, ray bundle adjustment and a spherical
    canvas."""
    from stitching import Stitcher

    settings = {
        "detector": args.detector,
        "nfeatures": args.features,
        "matcher_type": "homography",
        "range_width": args.range_width,
        "estimator": args.estimator,
        "adjuster": args.adjuster,
        "refinement_mask": "xxxxx",
        # Horizontal wave correction is for a handheld pan. A capture with
        # rows and poles has no single horizon to level against, so it is
        # off unless asked for.
        "wave_correct_kind": args.wave_correct,
        "confidence_threshold": args.confidence,
        "warper_type": "spherical",
        "crop": not args.no_crop,
        "compensator": args.compensator,
        "nr_feeds": 1,
        "block_size": 32,
        "blender_type": args.blend,
        "blend_strength": args.blend_strength,
        "medium_megapix": args.medium_megapix,
        "low_megapix": args.low_megapix,
        # -1 keeps the prepared frames' own resolution for the final
        # canvas. A fixed number here would rescale them a second time.
        "final_megapix": -1,
        "try_use_gpu": False,
    }
    if args.detector in ("sift", "orb") and args.match_conf is not None:
        settings["match_conf"] = args.match_conf
    return Stitcher(**settings)


def downsample(image, target_width):
    """The stitched canvas, scaled to the width the result review uses."""
    height, width = image.shape[:2]
    if target_width <= 0 or width <= target_width:
        return image
    scale = target_width / float(width)
    return cv2.resize(
        image,
        (target_width, max(1, int(round(height * scale)))),
        interpolation=cv2.INTER_AREA,
    )


def main():
    parser = argparse.ArgumentParser(
        description="Stitch frames with OpenStitching, from pixels only.")
    parser.add_argument("directory", nargs="?", default=None,
                        help="session folder holding the frames")
    parser.add_argument("-o", "--output", default="openstitching_pano.jpg")
    parser.add_argument("--w", type=int, default=DEFAULT_FINAL_WIDTH,
                        help="saved width; the native canvas is wider")
    parser.add_argument("--width", type=int, default=DEFAULT_WIDTH,
                        help="frame width the stitcher works at; 0 keeps "
                             "the originals")
    parser.add_argument("--every", type=int, default=0,
                        help="work on every Nth frame only, for a first look")
    parser.add_argument("--work", default=None,
                        help="where the downsized frames go "
                             "(default: <output>_work beside the output)")
    parser.add_argument("--detector", default="sift",
                        choices=("sift", "orb"),
                        help="feature detector; sift finds far more on the "
                             "plain walls and ceilings")
    parser.add_argument("--features", type=int, default=2000,
                        help="feature cap for sift and orb")
    parser.add_argument("--match-conf", type=float, default=None,
                        help="pair match confidence; left to the library's "
                             "per-detector default when unset")
    parser.add_argument("--range-width", type=int, default=-1,
                        help="only match frames within this many capture "
                             "positions; -1 matches every pair")
    parser.add_argument("--estimator", default="homography",
                        choices=("homography", "affine"))
    parser.add_argument("--adjuster", default="ray",
                        choices=("ray", "reproj", "affine"))
    parser.add_argument("--wave-correct", default="no",
                        choices=("no", "horiz", "vert"))
    parser.add_argument("--confidence", type=float, default=1.0,
                        help="image filter threshold; above 1 enables it")
    parser.add_argument("--blend", default="multiband",
                        choices=("multiband", "feather", "no"))
    parser.add_argument("--blend-strength", type=float, default=5.0)
    parser.add_argument("--compensator", default="gain_blocks",
                        choices=("gain_blocks", "gain", "channel",
                                 "channel_blocks", "no"))
    parser.add_argument("--medium-megapix", type=float, default=0.6,
                        help="resolution the matching runs at, megapixels; "
                             "lower is faster and less accurate")
    parser.add_argument("--low-megapix", type=float, default=0.1)
    parser.add_argument("--no-crop", action="store_true",
                        help="keep the warped canvas' empty borders")
    parser.add_argument("--run", default="no",
                        help="must be 'yeah' to actually stitch")
    args = parser.parse_args()

    if args.run != RUN_CONFIRM:
        parser.error("pass --run yeah to start the stitch")
    if not args.directory or not os.path.isdir(args.directory):
        parser.error("give the session directory that holds the frames")

    files = collect_images(args.directory)
    if args.every and args.every > 1:
        files = files[::args.every]
    if len(files) < 2:
        parser.error("need at least two frames, found %d" % len(files))
    print("frames: %d from %s" % (len(files), args.directory))

    work_dir = args.work or os.path.splitext(args.output)[0] + "_work"
    print("working copies: %s (width %s)" % (work_dir, args.width or "native"))
    prepared = prepare_working_copies(files, work_dir, args.width)

    print("stitcher: detector=%s features=%d match_conf=%s blend=%s "
          "compensator=%s crop=%s" % (
              args.detector, args.features, args.match_conf, args.blend,
              args.compensator, not args.no_crop))
    started = time.time()
    stitcher = make_stitcher(args)
    try:
        panorama = stitcher.stitch(prepared)
    except Exception as error:  # the library raises on failure in places
        panorama = None
        print("stitcher raised: %s" % error, file=sys.stderr)
    elapsed = time.time() - started

    if panorama is None or getattr(panorama, "size", 0) == 0:
        print("no panorama came back after %.1f s. The usual causes are "
              "too little overlap between frames, or a band with no "
              "texture at all. Try --medium-megapix 1.0, --features 4000, "
              "or --every 2 for a first look." % elapsed, file=sys.stderr)
        return 1

    native_height, native_width = panorama.shape[:2]
    saved = downsample(panorama, args.w)
    os.makedirs(os.path.dirname(os.path.abspath(args.output)) or ".",
                exist_ok=True)
    if not cv2.imwrite(args.output, saved, [cv2.IMWRITE_JPEG_QUALITY, 95]):
        print("could not write %s" % args.output, file=sys.stderr)
        return 1

    print("stitched in %.1f s: canvas %dx%d, saved %dx%d as %s" % (
        elapsed, native_width, native_height,
        saved.shape[1], saved.shape[0], args.output))

    # A full sphere is about 2:1, so anything far from it is either a ring
    # session or a canvas the adjuster did not close.
    ratio = native_width / float(native_height)
    print("aspect ratio: %.2f (2.0 is a complete sphere)" % ratio)
    if ratio < 1.2:
        print("warning: the result is not close to 2:1, so part of the "
              "sphere is likely missing", file=sys.stderr)
    print("working copies kept in %s; they are reused on the next run"
          % work_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
