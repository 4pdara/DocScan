"""The combining rule (from combine.py, settings chosen on half of the Mendeley test photos) on our own photo sets:
the detector, the network, and the rule's choice, each scored against the hand-marked outlines."""
import os, subprocess, sys
import numpy as np, cv2, torch
S = sys.argv[1]; CKPT = sys.argv[2]; T = float(os.environ.get('AGREE', '.9')); MARGIN = float(os.environ.get('MARGIN', '0'))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from realeval import sets, iou, quad, W, H
from model import PageNet
from combine import edge_score, iou as niou
net = PageNet(1.0); net.load_state_dict(torch.load(CKPT)); net.eval()
for name, items in sets():
    paths = [p for p, _ in items]
    out = subprocess.run(['java', '-cp', f'{S}/kc/run.jar:{S}/kc/openpnp.jar:{S}/kc/kotlinc/lib/kotlin-stdlib.jar', 'RunKt'] + paths, capture_output=True, text=True).stdout
    found = {}
    for line in out.strip().splitlines():
        n, ms, pts = line.split('\t')
        found[n] = None if pts == 'none' else [(float(a), float(b)) for a, b in (p.split(',') for p in pts.split(';'))]
    d, n_, c = [], [], []; switched = 0
    for p, gts in items:
        img = cv2.imread(p); h, w = img.shape[:2]
        x = torch.from_numpy(cv2.resize(img, (256, 256), interpolation=cv2.INTER_AREA).transpose(2, 0, 1).astype(np.float32)[None] / 255 - .5)
        with torch.no_grad(): o = torch.sigmoid(net(x))[0].numpy()
        qq = quad(cv2.resize(o[0], (W, H))); nq = None if qq is None else [(a / W, b / H) for a, b in qq]
        cq = found.get(os.path.basename(p))
        def score(q): return 0 if q is None else max(iou([(a * W, b * H) for a, b in q], g) for g in gts)
        if cq is None: pick = nq
        elif nq is None: pick = cq
        elif niou(cq, nq) >= T: pick = cq
        else: pick = nq if edge_score(nq, o[1]) > edge_score(cq, o[1]) + MARGIN else cq
        switched += pick is not cq
        d.append(score(cq)); n_.append(score(nq)); c.append(score(pick))
    f = lambda v: f'{np.mean(v):.3f} (>=.9 {np.mean(np.array(v) >= .9):.0%}, fail {np.mean(np.array(v) < .5):.0%})'
    print(f'{name:9s} detector {f(d)}  network {f(n_)}  combined {f(c)}  switched {switched}/{len(items)}')
