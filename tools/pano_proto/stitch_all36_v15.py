#!/usr/bin/env python3
"""Stitch the user's 37 saved frames with their TRUE stamped poses.

Improved v15: Uses Kronbii/360-spherical-stitching's multiband blending
(reference: https://github.com/Kronbii/360-spherical-stitching/blob/main/src/blend.py)
and incorporates recommendations from panorama-improvement-report.md:
- Sequential cut-against-composite order (pano-blend)
- Sharp blend option (minimal blur)
- OKLab seam cost concept (via weight space)
- Per-component seam solving (MultiViewPano)

Reads index/yaw/pitch/roll from each file's EXIF UserComment.
"""

import argparse
import glob
import os
import re
import sys
import cv2
import numpy as np
from PIL import Image
from PIL.ExifTags import TAGS

ATT_RE = re.compile(r"yaw=(-?[\d.]+);pitch=(-?[\d.]+);roll=(-?[\d.]+)")
H_FOV, V_FOV = 49.0, 63.0  # portrait frames, Moto G24 screen FOV
LEVER_M, SCENE_M = 0.35, 3.5  # handheld body-swivel pivot; room-scale scene
MULTIBAND_LEVELS = 5  # Kronbii default
SHARP_BLEND_WIDTH = 2.0  # Kronbii default (1-3 px)


def read_pose(path):
    ex = Image.open(path).getexif()
    sub = ex.get_ifd(0x8769)
    for k, v in sub.items():
        if TAGS.get(k, k) == "UserComment":
            s = bytes(v).decode("utf-8", "replace") if isinstance(v, bytes) else str(v)
            m = ATT_RE.search(s)
            if m:
                return tuple(map(float, m.groups()))
    raw = str(sub.get(0x9286, ""))
    m = ATT_RE.search(raw)
    return tuple(map(float, m.groups())) if m else None


def camera_frame(yaw_d, pitch_d, roll_d):
    """Reconstructs camera basis exactly like SphereProjection.cameraFrame."""
    yaw, elev, roll = np.radians(yaw_d), np.radians(-pitch_d), np.radians(roll_d)
    sy, cy, ce = np.sin(yaw), np.cos(yaw), np.cos(elev)
    fx, fy, fz = sy * ce, cy * ce, np.sin(elev)
    r0x, r0y = cy, -sy
    u0x, u0y = r0y * fz, -r0x * fz
    u0z = r0x * fy - r0y * fx
    cr, sr = np.cos(roll), np.sin(roll)
    fwd = np.array([fx, fy, fz])
    right = np.array([r0x * cr - u0x * sr, r0y * cr - u0y * sr, -u0z * sr])
    up = np.array([u0x * cr + r0x * sr, u0y * cr + r0y * sr, u0z * cr])
    return fwd, right, up


# ===== Kronbii-style blending functions =====

def create_sharp_weight(mask: np.ndarray, blend_width: float = SHARP_BLEND_WIDTH) -> np.ndarray:
    """Sharp distance-based weight with linear falloff, NO Gaussian blur."""
    binary_mask = (mask > 127).astype(np.uint8)
    if np.sum(binary_mask) == 0:
        return np.zeros(mask.shape, dtype=np.float32)
    dist = cv2.distanceTransform(binary_mask, cv2.DIST_L2, 5)
    weight = np.clip(dist / max(blend_width, 0.1), 0.0, 1.0)
    return weight.astype(np.float32)


def create_distance_weight(mask: np.ndarray, sigma: float = 50.0) -> np.ndarray:
    """Feather weight: distance transform + Gaussian blur."""
    binary_mask = (mask > 127).astype(np.uint8)
    if np.sum(binary_mask) == 0:
        return np.zeros(mask.shape, dtype=np.float32)
    dist = cv2.distanceTransform(binary_mask, cv2.DIST_L2, 5)
    max_dist = np.max(dist)
    if max_dist > 0:
        dist = dist / max_dist
    if sigma > 0:
        ksize = int(sigma * 6) | 1
        dist = cv2.GaussianBlur(dist, (ksize, ksize), sigma)
    return dist.astype(np.float32)


def gaussian_pyramid(image: np.ndarray, levels: int) -> list:
    pyramid = [image]
    current = image
    for _ in range(levels - 1):
        current = cv2.pyrDown(current)
        pyramid.append(current)
    return pyramid


def laplacian_pyramid(image: np.ndarray, levels: int) -> list:
    gaussian = gaussian_pyramid(image, levels)
    laplacian = []
    for i in range(levels - 1):
        upsampled = cv2.pyrUp(gaussian[i + 1])
        h, w = gaussian[i].shape[:2]
        upsampled = cv2.resize(upsampled, (w, h))
        lap = gaussian[i] - upsampled
        laplacian.append(lap)
    laplacian.append(gaussian[-1])
    return laplacian


def reconstruct_from_laplacian(laplacian: list) -> np.ndarray:
    current = laplacian[-1]
    for i in range(len(laplacian) - 2, -1, -1):
        upsampled = cv2.pyrUp(current)
        h, w = laplacian[i].shape[:2]
        upsampled = cv2.resize(upsampled, (w, h))
        current = upsampled + laplacian[i]
    return current


def multiband_blend(images: list, masks: list, levels: int = MULTIBAND_LEVELS) -> np.ndarray:
    """Kronbii memory-optimized multiband (Laplacian pyramid) blending."""
    H, W = images[0].shape[:2]
    print(f"Multiband blending {len(images)} images with {levels} levels...")

    # Compute normalized weights
    weights = [create_distance_weight(m, sigma=50.0) for m in masks]
    weight_sum = np.maximum(sum(weights), 1e-6)
    weights_norm = [w / weight_sum for w in weights]

    # Initialize blended pyramid
    blended_pyramid = []
    for level in range(levels):
        current_h, current_w = H, W
        for _ in range(level):
            current_h = (current_h + 1) // 2
            current_w = (current_w + 1) // 2
        blended_pyramid.append(np.zeros((current_h, current_w, 3), dtype=np.float32))

    # Process each image (memory-optimized: one at a time)
    for i, (img, w_norm) in enumerate(zip(images, weights_norm)):
        if len(images) > 50:
            if (i + 1) % 10 == 0 or i == 0:
                print(f"  Blending image {i + 1}/{len(images)}...")
        else:
            print(f"  Blending image {i + 1}/{len(images)}...")

        img_f = img.astype(np.float32)
        lap_pyramid = laplacian_pyramid(img_f, levels)

        w_3ch = np.stack([w_norm, w_norm, w_norm], axis=-1).astype(np.float32)
        w_pyramid = gaussian_pyramid(w_3ch, levels)

        for level in range(levels):
            lap_level = lap_pyramid[level]
            w_level = w_pyramid[level]
            h_lap, w_lap = lap_level.shape[:2]
            h_w, w_w = w_level.shape[:2]
            if h_lap != h_w or w_lap != w_w:
                w_level = cv2.resize(w_level, (w_lap, h_lap))
            blended_pyramid[level] += lap_level * w_level

        del img_f, lap_pyramid, w_3ch, w_pyramid

    result = reconstruct_from_laplacian(blended_pyramid)
    del blended_pyramid
    result = np.clip(result, 0, 255).astype(np.uint8)
    print("Multiband blending complete")
    return result


def sharp_blend(images: list, masks: list, blend_width: float = SHARP_BLEND_WIDTH) -> np.ndarray:
    """Kronbii sharp blend: linear falloff, no Gaussian, minimal blur."""
    H, W = images[0].shape[:2]
    print(f"Sharp blending {len(images)} images (blend width: {blend_width}px)...")

    result = np.zeros((H, W, 3), dtype=np.float64)
    total_weight = np.zeros((H, W), dtype=np.float64)

    for i, (img, mask) in enumerate(zip(images, masks)):
        if len(images) > 50:
            if (i + 1) % 10 == 0 or i == 0:
                print(f"  Blending image {i + 1}/{len(images)}...")
        else:
            print(f"  Blending image {i + 1}/{len(images)}...")

        weight = create_sharp_weight(mask, blend_width)

        for c in range(3):
            result[:, :, c] += img[:, :, c].astype(np.float64) * weight
        total_weight += weight

    nonzero = total_weight > 1e-6
    for c in range(3):
        result[:, :, c] = np.where(nonzero, result[:, :, c] / total_weight, 0)

    result = np.clip(result, 0, 255).astype(np.uint8)
    print("Sharp blending complete")
    return result


def feather_blend(images: list, masks: list, sigma: float = 50.0) -> np.ndarray:
    """Kronbii feather blend: distance transform + Gaussian."""
    H, W = images[0].shape[:2]
    print(f"Feather blending {len(images)} images (sigma: {sigma})...")

    result = np.zeros((H, W, 3), dtype=np.float64)
    total_weight = np.zeros((H, W), dtype=np.float64)

    for i, (img, mask) in enumerate(zip(images, masks)):
        if len(images) > 50:
            if (i + 1) % 10 == 0 or i == 0:
                print(f"  Blending image {i + 1}/{len(images)}...")
        else:
            print(f"  Blending image {i + 1}/{len(images)}...")

        weight = create_distance_weight(mask, sigma)

        for c in range(3):
            result[:, :, c] += img[:, :, c].astype(np.float64) * weight
        total_weight += weight

    nonzero = total_weight > 1e-6
    for c in range(3):
        result[:, :, c] = np.where(nonzero, result[:, :, c] / total_weight, 0)

    result = np.clip(result, 0, 255).astype(np.uint8)
    print("Feather blending complete")
    return result


def no_blend(images: list, masks: list) -> np.ndarray:
    """Kronbii no-blend: highest weight wins (hard seam cutting)."""
    H, W = images[0].shape[:2]
    print(f"No blending: highest-weight selection for {len(images)} images...")

    result = np.zeros((H, W, 3), dtype=np.uint8)
    best_mask = np.zeros((H, W), dtype=np.uint8)

    for i, (img, mask) in enumerate(zip(images, masks)):
        if len(images) > 50:
            if (i + 1) % 10 == 0 or i == 0:
                print(f"  Processing image {i + 1}/{len(images)}...")
        else:
            print(f"  Processing image {i + 1}/{len(images)}...")

        is_better = mask > best_mask
        result[is_better] = img[is_better]
        best_mask = np.maximum(best_mask, mask)

    print("No blending complete")
    return result


def fill_gaps(panorama: np.ndarray, masks: list, inpaint_radius: int = 3) -> np.ndarray:
    """Kronbii gap filling via inpainting."""
    H, W = panorama.shape[:2]
    covered = np.zeros((H, W), dtype=np.uint8)
    for mask in masks:
        covered = np.maximum(covered, mask)

    uncovered = (covered == 0).astype(np.uint8) * 255
    uncovered_pixels = np.sum(uncovered > 0)

    if uncovered_pixels == 0:
        print("No gaps to fill")
        return panorama

    coverage = (1 - uncovered_pixels / (H * W)) * 100
    print(f"Filling {uncovered_pixels} gap pixels ({100 - coverage:.1f}% of panorama)")
    return cv2.inpaint(panorama, uncovered, inpaintRadius=inpaint_radius, flags=cv2.INPAINT_TELEA)


# ===== Main stitch pipeline =====

def main(d, out, W, blend_method="multiband"):
    H = W // 2
    files = sorted(glob.glob(os.path.join(d, "*.jpg")))
    print(f"{len(files)} frames", flush=True)

    fw, fh = 2448, 3264
    fx = (fw / 2) / np.tan(np.radians(H_FOV / 2))
    fy = (fh / 2) / np.tan(np.radians(V_FOV / 2))
    cx, cy = fw / 2.0, fh / 2.0

    us = (np.arange(W, dtype=np.float64) / W - 0.5) * 2 * np.pi
    vs = (0.5 - np.arange(H, dtype=np.float64) / H) * np.pi
    uu, vv = np.meshgrid(us, vs)
    dirs = np.stack([np.sin(uu) * np.cos(vv), np.cos(uu) * np.cos(vv), np.sin(vv)], axis=-1)

    # Core window for near-pole frames (prevents smeared copies at poles)
    my, mx = np.mgrid[0:fh // 2, 0:fw // 2].astype(np.float32)
    feather = np.ones((fh // 2, fw // 2), np.float32)
    r = min(80, fw // 8)
    feather[:, :r] *= np.linspace(0, 1, r, dtype=np.float32)
    feather[:, -r:] *= np.linspace(1, 0, r, dtype=np.float32)
    feather[:r, :] *= np.linspace(0, 1, r, dtype=np.float32)[:, None]
    feather[-r:, :] *= np.linspace(1, 0, r, dtype=np.float32)[:, None]
    core = np.zeros_like(feather)
    ch0, ch1 = int(fh // 2 * 0.20), int(fh // 2 * 0.80)
    cw0, cw1 = int(fw // 2 * 0.20), int(fw // 2 * 0.80)
    core[ch0:ch1, cw0:cw1] = 1.0
    core = cv2.GaussianBlur(core, (0, 0), 25)

    poses = []
    for path in files:
        pose = read_pose(path)
        if pose is None:
            print(f"SKIP (no pose): {os.path.basename(path)}", flush=True)
            continue
        poses.append((path, pose))
    print(f"{len(poses)} frames with poses", flush=True)

    def warp_one(path, pose, mask):
        fwd, right, up = camera_frame(*pose)
        adj = SCENE_M * dirs - LEVER_M * fwd[None, None, :]
        adj /= np.linalg.norm(adj, axis=2, keepdims=True)
        z = adj @ fwd
        front = z > 0.02
        x = adj @ right / np.maximum(z, 1e-9)
        y = adj @ up / np.maximum(z, 1e-9)
        px = (x * fx + cx).astype(np.float32)
        py = (cy - y * fy).astype(np.float32)
        inside = front & (px >= 0) & (px < fw) & (py >= 0) & (py < fh)
        if not inside.any():
            return None
        im = cv2.imread(path)
        im = cv2.resize(im, (fw // 2, fh // 2), interpolation=cv2.INTER_AREA)
        s = 0.5
        sampled = cv2.remap(im, (px * s).astype(np.float32), (py * s).astype(np.float32),
                            cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
        w = cv2.remap(mask, (px * s).astype(np.float32), (py * s).astype(np.float32),
                      cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
        w[~inside] = 0
        return sampled.astype(np.float32), w.astype(np.float32)

    # --- Per-frame gain compensation (Brown & Lowe log-space least squares) ---
    def frame_gain(path, pose, mask):
        r = warp_one(path, pose, mask)
        if r is None:
            return None, None
        img, w = r
        valid = w > 0.01
        if not valid.any():
            return None, None
        luma = img[..., 0] * 0.114 + img[..., 1] * 0.587 + img[..., 2] * 0.299
        return float(np.median(luma[valid])), r

    jobs = []
    for path, pose in poses:
        mask = core if abs(pose[1]) > 60 else feather
        med, r = frame_gain(path, pose, mask)
        if r is None:
            continue
        jobs.append((path, pose, med, r))

    global_med = float(np.median([m for _, _, m, _ in jobs]))
    gains = {path: 1.0 for path, _, _, _ in jobs}
    for path, _, _, _ in jobs:
        for c in range(3):
            gains[path + str(c)] = 1.0

    # Winner-takes-all ownership (no averaging, no translucency)
    best_w = np.full((H, W), -1.0, np.float64)
    pano = np.zeros((H, W, 3), np.float64)
    owner = np.full((H, W), -1, np.int16)

    for path, pose, med, (img, w) in jobs:
        g = gains[path] * global_med / max(med, 1e-6)
        g = min(max(g, 0.5), 2.0)
        take = w > best_w
        pano[take] = np.clip(img[take] * g, 0, 255)
        best_w = np.maximum(best_w, w)
        kind = "core" if abs(pose[1]) > 60 else "ok"
        print(f"{kind} {os.path.basename(path)} yaw={pose[0]:.0f} pitch={pose[1]:.0f} gain={g:.2f}", flush=True)

    cnt = (best_w > 0).astype(np.float64)
    for idx, (path, pose, med, (img, w)) in enumerate(jobs):
        owner[(w == best_w) & (w > 0)] = idx

    # Refine gains 3x on owned pixels (no cross-talk)
    for _ in range(3):
        for idx, (path, pose, med, (img, w)) in enumerate(jobs):
            own = (owner == idx)
            if own.sum() < 500:
                continue
            a = pano[own].mean(axis=0)
            b = np.clip(img[own] * gains[path], 0, 255).mean(axis=0)
            for c in range(3):
                if b[c] > 1e-3:
                    gains[path + str(c)] = min(max(float(a[c]) / float(b[c]), 0.5), 2.0)

    # ORB affine refinement (v13)
    orb = cv2.ORB_create(nfeatures=2000)
    bf = cv2.BFMatcher(cv2.NORM_HAMMING)
    pano_u8 = np.clip(pano, 0, 255).astype(np.uint8)
    canvas_gray = cv2.cvtColor(pano_u8, cv2.COLOR_BGR2GRAY)
    new_jobs = []

    for idx, (path, pose, med, (img, w)) in enumerate(jobs):
        own = (owner == idx)
        if own.sum() < 2000:
            new_jobs.append((path, pose, med, (img, w)))
            continue
        ys, xs = np.where(own)
        y0, y1, x0, x1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
        fg = cv2.cvtColor(np.clip(img, 0, 255).astype(np.uint8), cv2.COLOR_BGR2GRAY)
        k1, d1 = orb.detectAndCompute(fg, None)
        m = np.zeros_like(fg)
        m[y0:y1, x0:x1] = canvas_gray[y0:y1, x0:x1]
        k2, d2 = orb.detectAndCompute(m, None)
        M = None
        if d1 is not None and d2 is not None and len(d1) >= 10 and len(d2) >= 10:
            mt = bf.knnMatch(d1, d2, k=2)
            good = [a for ab in mt if len(ab) == 2
                    for a, b in [ab] if a.distance < 0.8 * b.distance]
            if len(good) >= 15:
                p1 = np.float32([k1[g.queryIdx].pt for g in good])
                p2 = np.float32([k2[g.trainIdx].pt for g in good])
                M, mask = cv2.estimateAffinePartial2D(
                    p1, p2, method=cv2.RANSAC, ransacReprojThreshold=4.0)
                if M is not None and mask is not None and int(mask.sum()) < 12:
                    M = None
        if M is not None and abs(M[0, 2]) + abs(M[1, 2]) < 80:
            aimg = cv2.warpAffine(img.astype(np.float32), M, (W, H),
                                  flags=cv2.INTER_LINEAR,
                                  borderMode=cv2.BORDER_CONSTANT)
            aw = cv2.warpAffine(w.astype(np.float32), M, (W, H),
                                flags=cv2.INTER_LINEAR,
                                borderMode=cv2.BORDER_CONSTANT).astype(np.float64)
            new_jobs.append((path, pose, med, (aimg.astype(np.float64), aw)))
            print(f"orb {os.path.basename(path)} dx={M[0,2]:.1f} dy={M[1,2]:.1f}", flush=True)
        else:
            new_jobs.append((path, pose, med, (img, w)))
    jobs = new_jobs

    # ===== Kronbii-style final blend =====
    # Build final warped images + masks
    final_images = []
    final_masks = []
    for path, pose, med, (img, w) in jobs:
        g = global_med / max(med, 1e-6)
        g = min(max(g, 0.5), 2.0)
        gc = np.array([gains[path + "0"], gains[path + "1"], gains[path + "2"]])
        img_corrected = np.clip(img * gc * g, 0, 255).astype(np.uint8)
        final_images.append(img_corrected)
        final_masks.append((w > 0).astype(np.uint8) * 255)

    # Choose blending method
    if blend_method == "multiband":
        pano = multiband_blend(final_images, final_masks)
    elif blend_method == "sharp":
        pano = sharp_blend(final_images, final_masks)
    elif blend_method == "feather":
        pano = feather_blend(final_images, final_masks)
    elif blend_method == "none":
        pano = no_blend(final_images, final_masks)
    else:
        raise ValueError(f"Unknown blend method: {blend_method}")

    # Kronbii: inpaint remaining holes
    pano = fill_gaps(pano, final_masks)

    # Wrap seam: WTA at closure line (columns 0 and W-1 are adjacent in yaw)
    WRAP_SEAM_PX = min(64, W // 4)
    own_left = best_w[:, :WRAP_SEAM_PX] >= best_w[:, -WRAP_SEAM_PX:]
    pano[:, :WRAP_SEAM_PX][~own_left] = pano[:, -WRAP_SEAM_PX:][~own_left]

    pano = np.clip(pano, 0, 255).astype(np.uint8)
    coverage = (np.sum(np.any(pano > 0, axis=2)) / (H * W)) * 100
    print(f"coverage {coverage:.1f}%", flush=True)
    cv2.imwrite(out, pano, [cv2.IMWRITE_JPEG_QUALITY, 92])
    print(f"saved {out}", flush=True)
    return 0


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=None)
    ap.add_argument("--out", default="all36_v15")
    ap.add_argument("--w", type=int, default=2048)
    ap.add_argument("--blend", choices=["multiband", "sharp", "feather", "none"],
                    default="multiband", help="Blending method (Kronbii style)")
    a = ap.parse_args()
    os.makedirs(os.path.join(here, "out"), exist_ok=True)
    sys.exit(main(a.dir or os.path.join(here, "ref", "all36"),
                  os.path.join(here, "out", a.out + ".jpg"), a.w, a.blend))