"""The app's current detector (its Kotlin code, via the desktop harness) on the same own-photo sets as realeval."""
import os, subprocess, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

S = sys.argv[1]; sys.argv = sys.argv[:2] + ["-"]
from realeval import sets, iou, W, H
for name, items in sets():
    paths = [p for p, _ in items]
    out = subprocess.run(['java', '-cp', f'{S}/kc/run.jar:{S}/kc/openpnp.jar:{S}/kc/kotlinc/lib/kotlin-stdlib.jar', 'RunKt'] + paths, capture_output=True, text=True).stdout
    found = {}
    for line in out.strip().splitlines():
        n, ms, pts = line.split('\t')
        found[n] = None if pts == 'none' else [(float(a) * W, float(b) * H) for a, b in (p.split(',') for p in pts.split(';'))]
    v = []
    for p, gts in items:
        q = found.get(os.path.basename(p))
        v.append(0 if q is None else max(iou(q, g) for g in gts))
    v = np.array(v); print(f'{name:9s} detector outline IoU {v.mean():.3f}  >=.9 {(v >= .9).mean():.0%}  fail<.5 {(v < .5).mean():.0%}  (n={len(v)})')
