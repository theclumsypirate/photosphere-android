#!/usr/bin/env python3
"""Viewer page for the ghost check: click through consecutive pairs.

Reads the ghosts + report.txt written by check_overlap.py and builds one
self-contained HTML sheet: verdict chips per pair, click to zoom the ghost
large. Open the HTML in a browser, no server needed.

Usage: python3 tools/pano_proto/overlap_viewer.py [--ghosts out/overlap_check]
Only reads local files, never touches Kotlin or the app.
"""
import argparse
import os

PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>Ghost check</title>
<style>
body{{background:#111;color:#eee;font-family:sans-serif;margin:0;padding:16px}}
h1{{font-size:18px}}p.sub{{color:#aaa;font-size:13px}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px}}
.card{{background:#222;border-radius:8px;overflow:hidden;cursor:pointer;border:2px solid transparent}}
.card img{{width:100%;display:block;aspect-ratio:4/3;object-fit:cover}}
.card .t{{padding:4px 8px;font-size:12px}}
.OK{{border-color:#2c6}}
.SLAB{{border-color:#da0}}
.PROST{{border-color:#c33}}
#big{{position:fixed;inset:0;background:rgba(0,0,0,.92);display:none;align-items:center;justify-content:center;flex-direction:column;z-index:9}}
#big.show{{display:flex}}
#big img{{max-width:94vw;max-height:86vh}}
#big .t{{padding:10px;font-size:15px}}
</style></head><body>
<h1>Ghost check: {n} pairs, {bad} bad</h1>
<p class="sub">Sharp ghost = frames agree. Doubled edges = retake the frame.
Click any card to zoom. Generated locally, no uploads.</p>
<div class="grid">
{cards}
</div>
<div id="big" onclick="this.classList.remove('show')"><img id="bi"><div class="t" id="bt"></div></div>
<script>
function zoom(src,t){{document.getElementById('bi').src=src;
document.getElementById('bt').textContent=t;
document.getElementById('big').classList.add('show');}}
</script></body></html>
"""


def main(ghosts):
    reps = []
    for f in sorted(os.listdir(ghosts)):
        if f.startswith("ghost_") and f.endswith(".jpg"):
            tag = f[len("ghost_"):-len(".jpg")]
            verdict = tag.rsplit("_", 1)[-1]
            reps.append((f, tag, verdict))
    cards = "\n".join(
        f'<div class="card {v}" onclick="zoom(\'{f}\',\'{t}\')">'
        f'<img src="{f}" loading="lazy"><div class="t">{t}</div></div>'
        for f, t, v in reps
    )
    bad = sum(1 for _, _, v in reps if v == "PROST")
    html = PAGE.format(n=len(reps), bad=bad, cards=cards)
    out = os.path.join(ghosts, "index.html")
    with open(out, "w") as fh:
        fh.write(html)
    print(f"{len(reps)} pairs, {bad} bad -> {out}", flush=True)
    return 0


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--ghosts", default="out/overlap_check")
    a = ap.parse_args()
    g = a.ghosts if os.path.isabs(a.ghosts) else os.path.join(here, a.ghosts)
    raise SystemExit(main(g))
