#!/usr/bin/env python3
"""Device lab for the 360-photo app: logcat capture and capture-frame forensics.

Needs `adb` on PATH and the debug build on a connected device. Stdlib only.

    python3 tools/photosphere_lab.py logcat [--follow] [--out FILE]
        Filtered logcat for every app TAG (PhotoSphere*, OrientationTracker,
        CameraOptics, PanoramaView) plus AndroidRuntime crashes. Without
        --follow it dumps the current buffer; with --follow it streams until
        Ctrl-C (use while reproducing a bug on the phone).

    python3 tools/photosphere_lab.py frames [--pull DIR]
        List cached capture sessions on the device (via run-as, debug builds
        only) and audit every pulled frame's EXIF orientation tag. A tag of 0
        (UNDEFINED) is the known Motorola quirk the stitcher's fallback
        handles -- if you see it, the fallback path is what placed the frame.

    python3 tools/photosphere_lab.py sessions
        Just the session inventory, no pulling.
"""

import argparse
import os
import struct
import subprocess
import sys

PKG = "com.n30dyn4m1c.photosphere.debug"
APP_TAGS = [
    "PhotoSphere",
    "PhotoSphereCamera",
    "PhotoSphereStitcher",
    "SphereImageStore",
    "OrientationTracker",
    "CameraOptics",
    "PanoramaView",
    "PanoramaResult",
    "ImageBufferManager",
    "MediaExporter",
]
EXIF_ORIENTATION_TAG = 0x0112


def adb(*args, capture=True):
    return subprocess.run(["adb", *args], capture_output=capture, check=False)


def cmd_logcat(args):
    spec = []
    for tag in APP_TAGS:
        spec += ["-s", f"{tag}:V"]
    spec += ["AndroidRuntime:E"]
    if args.follow:
        print("Streaming filtered logcat -- reproduce the bug now, Ctrl-C to stop.",
              flush=True)
        try:
            subprocess.run(["adb", "logcat", "-v", "threadtime", *spec])
        except KeyboardInterrupt:
            pass
        return
    out = args.out
    proc = adb("logcat", "-d", "-v", "threadtime", *spec)
    text = proc.stdout.decode("utf-8", "replace")
    if out:
        with open(out, "w", encoding="utf-8") as fh:
            fh.write(text)
        print(f"Wrote {len(text)} chars to {out}")
    else:
        print(text[-8000:])


def run_as(*remote):
    proc = adb("shell", "run-as", PKG, *remote)
    return proc


def cmd_sessions(_args):
    proc = run_as("ls", "cache/sphere_sessions/")
    if proc.returncode != 0:
        print("No sessions (or run-as failed -- debuggable build required):",
              proc.stderr.decode()[-500:])
        return []
    sessions = sorted(proc.stdout.decode().split())
    if not sessions:
        print("No cached sessions (frames are cleared after a stitch).")
        return []
    for session in sessions:
        listing = run_as("ls", "-l", f"cache/sphere_sessions/{session}/")
        files = listing.stdout.decode().strip().splitlines() if listing.returncode == 0 else []
        print(f"{session}: {len(files)} frame(s)")
    return sessions


def pull_frame(session, name, dest):
    with open(dest, "wb") as fh:
        proc = subprocess.run(
            ["adb", "exec-out", "run-as", PKG, "cat",
             f"cache/sphere_sessions/{session}/{name}"],
            stdout=fh, stderr=subprocess.PIPE, check=False)
    return proc.returncode == 0 and os.path.getsize(dest) > 0


def exif_orientation(path):
    """Minimal EXIF orientation reader: returns the tag value or None.

    Parses JPEG APP1/Exif + TIFF IFD0 by hand (tag 0x0112, SHORT). Anything
    unparseable is None, never an exception.
    """
    try:
        with open(path, "rb") as fh:
            data = fh.read(1 << 20)
        if data[:2] != b"\xff\xd8":
            return None
        pos = 2
        while pos + 4 <= len(data):
            if data[pos] != 0xFF:
                pos += 1
                continue
            marker = data[pos + 1]
            if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
                pos += 2
                continue
            if pos + 4 > len(data):
                break
            length = struct.unpack(">H", data[pos + 2:pos + 4])[0]
            if marker == 0xE1 and data[pos + 4:pos + 10] == b"Exif\x00\x00":
                return _tiff_orientation(data[pos + 10:pos + 2 + length])
            pos += 2 + length
    except OSError:
        pass
    return None


def _tiff_orientation(tiff):
    try:
        order = tiff[:2]
        endian = "<" if order == b"II" else ">" if order == b"MM" else None
        if endian is None:
            return None
        ifd0 = struct.unpack(endian + "I", tiff[4:8])[0]
        count = struct.unpack(endian + "H", tiff[ifd0:ifd0 + 2])[0]
        for i in range(count):
            entry = ifd0 + 2 + 12 * i
            tag, typ, num = struct.unpack(endian + "HHI", tiff[entry:entry + 8])
            if tag == EXIF_ORIENTATION_TAG and typ == 3 and num == 1:
                return struct.unpack(endian + "H", tiff[entry + 8:entry + 10])[0]
    except (struct.error, IndexError):
        pass
    return None


def cmd_frames(args):
    sessions = cmd_sessions(args)
    if not sessions:
        return
    session = sessions[-1]
    print(f"Auditing newest session: {session}")
    dest = args.pull or os.path.join("/tmp", "opencode", "sphere_frames", session)
    os.makedirs(dest, exist_ok=True)
    listing = run_as("ls", f"cache/sphere_sessions/{session}/")
    names = sorted(n for n in listing.stdout.decode().split() if n.endswith(".jpg"))
    bad = 0
    for name in names:
        local = os.path.join(dest, name)
        if not pull_frame(session, name, local):
            print(f"  {name}: pull FAILED")
            bad += 1
            continue
        orientation = exif_orientation(local)
        flag = "" if orientation not in (None, 0) else "   <-- UNDEFINED/MISSING"
        if orientation in (None, 0):
            bad += 1
        print(f"  {name}: EXIF orientation={orientation}{flag} "
              f"({os.path.getsize(local) // 1024} KiB)")
    print(f"Pulled to {dest}: {len(names)} frames, {bad} with missing/undefined orientation.")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    p_log = sub.add_parser("logcat", help="filtered logcat dump or live stream")
    p_log.add_argument("--follow", action="store_true")
    p_log.add_argument("--out", default=None)
    p_frames = sub.add_parser("frames", help="pull + audit newest session frames")
    p_frames.add_argument("--pull", default=None)
    sub.add_parser("sessions", help="list cached capture sessions")
    args = parser.parse_args()
    {"logcat": cmd_logcat, "frames": cmd_frames, "sessions": cmd_sessions}[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
