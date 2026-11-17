"""Rules for choosing between the app's detector and the network, from exteval's per-photo dump. The rule's settings
are chosen on one half of the test photos (split by name) and reported on the other half, so the report is not tuned
on the photos it is measured on."""
import hashlib, pickle, sys
import numpy as np, cv2

def iou(a, b, w=500, h=500):
    if a is None or b is None: return 0.
    ma = np.zeros((h, w), np.uint8); mb = np.zeros((h, w), np.uint8)
    cv2.fillPoly(ma, [np.int32(np.array(a) * [w, h])], 1); cv2.fillPoly(mb, [np.int32(np.array(b) * [w, h])], 1)
    return (ma & mb).sum() / max(1, (ma | mb).sum())

def mask_score(q, mask):
    """How well the outline's inside matches the network's page region (IoU with the mask above one half)."""
    if q is None: return -1.
    h, w = mask.shape; m = np.zeros((h, w), np.uint8); cv2.fillPoly(m, [np.int32(np.array(q) * [w, h])], 1)
    p = mask > .5
    return (m.astype(bool) & p).sum() / max(1, (m.astype(bool) | p).sum())

def edge_score(q, outline):
    """The mean outline probability along the four sides."""
    if q is None: return -1.
    h, w = outline.shape; m = np.zeros((h, w), np.uint8); cv2.polylines(m, [np.int32(np.round(np.array(q) * [w, h]))], True, 1, 2)
    return float(outline[m > 0].mean()) if m.any() else -1.

def half(name): return int(hashlib.sha256(name.encode()).hexdigest(), 16) % 2

def main():
    items = pickle.load(open(sys.argv[1], 'rb'))
    for it in items:
        it['ic'] = iou(it['gt'], it['cur']); it['in'] = iou(it['gt'], it['net']); it['agree'] = iou(it['cur'], it['net'])
        it['mc'] = mask_score(it['cur'], it['mask']); it['mn'] = mask_score(it['net'], it['mask'])
        it['ec'] = edge_score(it['cur'], it['outline']); it['en'] = edge_score(it['net'], it['outline'])
    def rule(it, kind, t, margin):
        if it['cur'] is None: return it['in']
        if it['net'] is None: return it['ic']
        if it['agree'] >= t: return it['ic']
        c, n = (it['mc'], it['mn']) if kind == 'mask' else (it['ec'], it['en']) if kind == 'edge' else (it['mc'] + it['ec'], it['mn'] + it['en'])
        return it['in'] if n > c + margin else it['ic']
    def stats(v):
        v = np.array(v); return f'mean {v.mean():.3f} median {np.median(v):.3f} >=.9 {(v >= .9).mean():.0%} fail<.5 {(v < .5).mean():.0%}'
    A = [it for it in items if half(it['name']) == 0]; B = [it for it in items if half(it['name']) == 1]
    print(f'photos {len(items)}: tuning half {len(A)}, report half {len(B)}')
    for label, part in (('tuning', A), ('report', B)):
        print(f'  {label}: detector {stats([it["ic"] for it in part])}')
        print(f'  {label}: network  {stats([it["in"] for it in part])}')
        print(f'  {label}: best of both {np.mean([max(it["ic"], it["in"]) for it in part]):.3f}')
    for kind in ('mask', 'edge', 'both'):
        best = max(((np.mean([rule(it, kind, t, m) for it in A]), t, m) for t in (.5, .7, .8, .85, .9, .95, 1.01) for m in (0, .02, .05, .1, .15, .2)))
        _, t, m = best
        print(f'rule {kind}: agree>={t} keeps the detector, else the network when its score beats by {m}')
        print(f'  tuning {stats([rule(it, kind, t, m) for it in A])}')
        print(f'  report {stats([rule(it, kind, t, m) for it in B])}')
        changed = [it for it in B if rule(it, kind, t, m) != it['ic']]
        print(f'  on the report half it changed {len(changed)} photos: better {sum(rule(it, kind, t, m) > it["ic"] for it in changed)}, worse {sum(rule(it, kind, t, m) < it["ic"] for it in changed)}')

if __name__ == '__main__':
    main()
