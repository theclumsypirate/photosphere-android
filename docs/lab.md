# Device lab

Every log in the app uses the single tag `PhotoSphere`, so one filter shows
everything — nothing is hidden behind per-file tags:

```bash
adb logcat -d -s PhotoSphere:* | tail -50
```

`tools/photosphere_lab.py` (stdlib only, needs `adb` + the debug build) wraps
that plus frame forensics:

```bash
# Filtered logcat: every app TAG + AndroidRuntime crashes.
python3 tools/photosphere_lab.py logcat --out /tmp/opencode/bug.log

# Live: stream while reproducing the bug on the phone, Ctrl-C to stop.
python3 tools/photosphere_lab.py logcat --follow

# Capture sessions on the device (debug builds only, via run-as).
python3 tools/photosphere_lab.py sessions

# Pull the newest session's frames and audit EXIF orientation.
# 0/UNDEFINED is the known Motorola quirk the stitcher fallback handles.
python3 tools/photosphere_lab.py frames --pull /tmp/opencode/frames
```

What it deliberately does **not** do: drive the capture UI (guided capture
needs a hand turning a real phone — no script can aim it), or re-run the
stitch in Python. Frame placement forensics come from the EXIF audit plus the
stitch diagnostics line on the result screen (debug builds).

## Bug reports from the Settings screen

Settings (top-left on the entry screen) builds one `.txt` with the app and
device line, how the app last ended, saved crashes, the last four hours of
JSON logs and a live logcat tail — emails, tokens and account names redacted.
Save it through the system picker or send it through the share sheet. Nothing
leaves the device unless you tap.
