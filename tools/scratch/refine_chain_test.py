"""Tiny unit test for the offline stitcher's geometry, no file I/O.

Builds five known camera bases, unprojects a handful of pixels per pair,
and checks that:
1. the Horn rotation fit recovers the true relative rotation
2. refine_chain + loop spreading lands every frame within a degree, even
   when the chain starts from perturbed guesses and the measurements
   carry noise

Light by construction: dozens of tiny matrix solves. Run:

    python3 tools/scratch/refine_chain_test.py
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "offline_stitch"))
import stitch_offline as so  # noqa: E402

FW, FH = 160.0, 120.0
HFOV = 65.0


def focal():
    fy = (FH / 2) / np.tan(np.radians(HFOV / 2) / (FW / FH))
    fx = (FW / 2) / np.tan(np.radians(HFOV / 2))
    return fx, fy


def basis_of(yaw_d, pitch_d):
    right, up, fwd = so.camera_frame(yaw_d, pitch_d, 0.0)
    return np.stack([right, up, fwd], axis=1)


def project_ray_to_pixels_of_directions(directions, basis, fx, fy):
    """World rays into this camera's pixels; only forward rays count."""
    cam = directions @ basis  # rows: camera-frame rays
    z = cam[:, 2]
    front = z > 0.1
    safe = np.where(front, z, 1.0)
    x = cam[:, 0] / safe
    y = cam[:, 1] / safe
    px = x * fx + FW / 2
    py = FH / 2 - y * fy
    inside = front & (px >= 0) & (px < FW) & (py >= 0) & (py < FH)
    return np.stack([px, py], axis=1), inside


def main():
    fx, fy = focal()
    rng = np.random.default_rng(7)

    # Ten frames on a full ring, one pitched, yaw step 35 degrees so the
    # last and the first neighbour and the closure edge is real overlap.
    yaws = [i * 35.0 for i in range(10)]
    pitches = [0.0, 0.0, -12.0, 0.0, 6.0, 0.0, 0.0, -5.0, 0.0, 4.0]
    true_bases = []
    for yaw, pitch in zip(yaws, pitches):
        basis = basis_of(yaw, pitch)
        true_bases.append(basis)

    # World rays with good coverage of the ring: random directions.
    world = rng.normal(size=(3000, 3))
    world /= np.linalg.norm(world, axis=1, keepdims=True)

    pairs_true = {}
    for i in range(9):
        src_px, ok_i = project_ray_to_pixels_of_directions(
            world, true_bases[i], fx, fy)
        dst_px, ok_j = project_ray_to_pixels_of_directions(
            world, true_bases[i + 1], fx, fy)
        keep = ok_i & ok_j
        assert keep.sum() > 30, "pair %d coverage" % i
        pairs_true[i] = (src_px[keep], dst_px[keep])
    # Closure pair: last back onto first, measured in frame last rays onto
    # frame first rays? The stitcher's closure maps last rays onto the
    # first frame's rays.
    src_px, ok_last = project_ray_to_pixels_of_directions(
        world, true_bases[9], fx, fy)
    dst_px, ok_first = project_ray_to_pixels_of_directions(
        world, true_bases[0], fx, fy)
    keep = ok_last & ok_first
    assert keep.sum() > 30, "closure coverage"
    closure_px = (src_px[keep], dst_px[keep])

    # 1: measured vs true relative rotation, per pair.
    for i, (src, dst) in pairs_true.items():
        A_meas, resid = so.pair_measured_rotation(src, dst, fx, fy, FW, FH)
        A_true = true_bases[i + 1].T @ true_bases[i]
        err = np.degrees(np.arccos(np.clip(
            (np.trace(A_meas @ A_true.T) - 1) / 2, -1, 1)))
        print("pair %d: measured-vs-true %.4f deg, fit residual %.4f"
              % (i, err, resid))
        assert err < 1.0, "measured rotation wrong on pair %d" % i
    closure_meas, _ = so.pair_measured_rotation(*closure_px, fx, fy, FW, FH)
    closure_true = true_bases[0].T @ true_bases[9]
    err = np.degrees(np.arccos(np.clip(
        (np.trace(closure_meas @ closure_true.T) - 1) / 2, -1, 1)))
    print("closure measured-vs-true: %.4f deg" % err)
    assert err < 1.0, "closure rotation wrong"

    # 2: chain from perturbed guesses (up to 4 degrees apart) + closure
    # spreading, lands inside a degree of truth everywhere.
    guess = []
    for k in range(10):
        yaw = yaws[k] + [3.0, -2.0, 4.0, -3.0, 2.0, 1.0, -2.5, 3.5,
                         -1.0, 0.5][k]
        pitch = pitches[k] + [2.0, -1.5, 3.0, -1.0, 0.5, -2.0, 1.5,
                              1.0, -1.0, 0.5][k]
        g = basis_of(yaw, pitch)
        guess.append(g)
    measured = {}
    for i, (src, dst) in pairs_true.items():
        measured[i] = so.pair_measured_rotation(src, dst, fx, fy, FW, FH)[0]
    closure_rot = so.pair_measured_rotation(*closure_px, fx, fy, FW, FH)[0]
    refined = so.refine_chain(guess, measured, closure_rot)
    # The absolute orientation is a gauge: matching only sees relative
    # rotations, so frame 0 keeps its guess and every other frame can only
    # agree with it. The assertion strips the common offset and demands
    # the pairwise geometry inside one degree.
    errs = []
    for k in range(10):
        b = refined[k]
        fwd = b[:, 2]
        yaw_out = np.degrees(np.arctan2(fwd[0], fwd[1]))
        yaw_err = ((yaw_out - yaws[k] + 180) % 360) - 180
        errs.append(yaw_err)
        print("frame %d: yaw %.2f (true %.2f), error %.3f deg"
              % (k, yaw_out, yaws[k], yaw_err))
    offset = np.mean(errs)
    worst = max(abs(err - offset) for err in errs)
    spread = ((errs[1] - errs[0] + 180) % 360) - 180
    assert worst < 1.0, ("chain did not converge: worst pairwise error "
                         "%.2f deg, offset %.2f" % (worst, offset))
    assert abs(spread) < 1.0, "closure mismatch %.2f deg" % spread
    print("spread between first two errors: %.3f deg" % spread)
    print("PASS")


if __name__ == "__main__":
    main()
