"""Measures a PageNet checkpoint on the real frame sets (480 x 640) against their hand-marked outlines: IoU of the
four-corner outline taken from the predicted region, and of the region itself."""
import glob, json, os, sys
import numpy as np, cv2, torch
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from model import PageNet

S = sys.argv[1]; CKPT = sys.argv[2]; WIDTH = float(os.environ.get('WIDTH', '1.0')); SHOW = os.environ.get('SHOW')
W, H = 480, 640

def sets():
    sys.path.insert(0, f'{S}/scripts'); from gt import expected, alternatives
    yield 'old16', [(f'{S}/det2/frames/{n}.png', [expected[n]] + alternatives.get(n, [])) for n in expected]
    for name, d in (('magazine', 'mag/det'), ('booklet', 'mag/det2')):
        gt = json.load(open(f'{S}/{d}/gt.json'))
        yield name, [(f'{S}/{d}/{n}.png', [gt[n]]) for n in sorted(gt)]

def iou(a, b):
    ma = np.zeros((H, W), np.uint8); mb = np.zeros((H, W), np.uint8)
    cv2.fillPoly(ma, [np.array(a, np.int32)], 1); cv2.fillPoly(mb, [np.array(b, np.int32)], 1)
    return (ma & mb).sum() / max(1, (ma | mb).sum())

def quad(prob):
    """Four corners from the page probability (480 x 640): the largest region's outline, simplified to 4 points."""
    m = (prob > .5).astype(np.uint8)
    n, lab, st, _ = cv2.connectedComponentsWithStats(m, 8)
    if n < 2: return None
    k = 1 + int(np.argmax(st[1:, cv2.CC_STAT_AREA])); m = (lab == k).astype(np.uint8)
    cnts, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    hull = cv2.convexHull(max(cnts, key=cv2.contourArea))
    per = cv2.arcLength(hull, True)
    for f in np.linspace(.01, .1, 30):
        ap = cv2.approxPolyDP(hull, f * per, True)
        if len(ap) == 4: return ap.reshape(4, 2).astype(np.float32)
        if len(ap) < 4: break
    return cv2.boxPoints(cv2.minAreaRect(hull)).astype(np.float32)

def main():
    net = PageNet(WIDTH); net.load_state_dict(torch.load(CKPT)); net.eval()
    summary = {}
    for name, items in sets():
        qs, rs, shows = [], [], []
        for path, gts in items:
            im = cv2.imread(path); im = cv2.resize(im, (W, H))
            x = torch.from_numpy(cv2.resize(im, (256, 256), interpolation=cv2.INTER_AREA).transpose(2, 0, 1).astype(np.float32)[None] / 255 - .5)
            with torch.no_grad(): p = torch.sigmoid(net(x))[0].numpy()
            prob = cv2.resize(p[0], (W, H), interpolation=cv2.INTER_LINEAR)
            q = quad(prob)
            qi = 0 if q is None else max(iou(g, q) for g in gts)
            region = (prob > .5).astype(np.uint8); g = np.zeros((H, W), np.uint8); cv2.fillPoly(g, [np.array(gts[0], np.int32)], 1)
            ri = (region & g).sum() / max(1, (region | g).sum())
            qs.append(qi); rs.append(ri)
            if SHOW:
                v = im.copy(); v[region > 0] = (v[region > 0] * .6 + np.array([0, 80, 0])).astype(np.uint8)
                cv2.polylines(v, [np.array(gts[0], np.int32)], True, (255, 0, 255), 1)
                if q is not None: cv2.polylines(v, [q.astype(np.int32)], True, (0, 255, 255), 2)
                cv2.putText(v, f'{qi:.2f}', (8, 28), 0, .9, (0, 0, 255), 2); shows.append(v)
        summary[name] = (float(np.mean(qs)), float(np.mean(rs)), len(qs))
        print(f'{name:9s} outline IoU {np.mean(qs):.3f}  region IoU {np.mean(rs):.3f}  (n={len(qs)})', flush=True)
        if SHOW:
            while len(shows) % 8: shows.append(np.zeros_like(shows[0]))
            grid = np.vstack([np.hstack(shows[i:i + 8]) for i in range(0, len(shows), 8)])
            cv2.imwrite(f'{SHOW}_{name}.jpg', cv2.resize(grid, None, fx=.35, fy=.35))
    return summary

if __name__ == '__main__':
    main()
