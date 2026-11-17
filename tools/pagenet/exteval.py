"""Compares the app's current page detector (its own Kotlin code, via the desktop harness) with a PageNet checkpoint
on an outside dataset of phone photos with hand-marked corners (Mendeley "Document Corner Localization", CC BY 4.0).
Both are scored by the IoU of their four-corner outline with the marked one."""
import glob, json, os, subprocess, sys, random
import numpy as np, cv2, torch
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from model import PageNet
from realeval import quad

S = sys.argv[1]; DATA = sys.argv[2]; CKPT = sys.argv[3]; N = int(sys.argv[4]) if len(sys.argv) > 4 else 300
OUT = os.path.join(S, 'net', 'exteval'); os.makedirs(OUT, exist_ok=True)

def iou(a, b, w=1000, h=1000):
    ma = np.zeros((h, w), np.uint8); mb = np.zeros((h, w), np.uint8)
    cv2.fillPoly(ma, [np.int32(np.array(a) * [w, h])], 1); cv2.fillPoly(mb, [np.int32(np.array(b) * [w, h])], 1)
    return (ma & mb).sum() / max(1, (ma | mb).sum())

def main():
    pairs = []
    for c in sorted(glob.glob(f'{DATA}/*.csv')):
        j = c[:-4] + '.jpg'
        if os.path.exists(j): pairs.append((j, c))
    # Only the photos kept for testing (never trained on), when asked.
    if os.environ.get('TESTONLY'):
        from mendeley_prep import part
        pairs = [(j, c) for j, c in pairs if part(os.path.basename(j)[:-4].replace('(', '').replace(')', '').replace(' ', '_')) == 'test']
    random.seed(1); random.shuffle(pairs); pairs = pairs[:N]
    items = []
    for j, c in pairs:
        v = open(c).read().strip().split(',')
        pts = [(float(v[i]), float(v[i + 1])) for i in range(0, 8, 2)]
        im = cv2.imread(j, cv2.IMREAD_COLOR)  # the marks are on the upright photo (EXIF orientation applied)
        if im is None: continue
        h, w = im.shape[:2]
        if not all(0 <= x <= w * 1.05 and 0 <= y <= h * 1.05 for x, y in pts): continue
        # Frames as the camera preview gives them: long side 640, the photo's own shape.
        f = 640 / max(w, h); small = cv2.resize(im, (round(w * f), round(h * f)), interpolation=cv2.INTER_AREA)
        name = os.path.basename(j)[:-4].replace('(', '').replace(')', '').replace(' ', '_')
        path = f'{OUT}/{name}.png'; cv2.imwrite(path, small)
        items.append((path, [(x / w, y / h) for x, y in pts], small))
    print('photos', len(items), flush=True)
    # The current detector (the app's Kotlin code).
    out = subprocess.run(['java', '-cp', f'{S}/kc/run.jar:{S}/kc/openpnp.jar:{S}/kc/kotlinc/lib/kotlin-stdlib.jar', 'RunKt'] + [p for p, _, _ in items], capture_output=True, text=True).stdout
    found = {}
    for line in out.strip().splitlines():
        name, ms, pts = line.split('\t')
        found[name] = None if pts == 'none' else [(float(a), float(b)) for a, b in (p.split(',') for p in pts.split(';'))]
    net = PageNet(float(os.environ.get('WIDTH', '1.0'))); net.load_state_dict(torch.load(CKPT)); net.eval()
    cur, nn, dump = [], [], []
    for path, gt, small in items:
        q = found.get(os.path.basename(path))
        cur.append(0 if q is None else iou(gt, q))
        x = torch.from_numpy(cv2.resize(small, (256, 256), interpolation=cv2.INTER_AREA).transpose(2, 0, 1).astype(np.float32)[None] / 255 - .5)
        with torch.no_grad(): out2 = torch.sigmoid(net(x))[0].numpy()
        p = out2[0]
        h, w = small.shape[:2]; prob = cv2.resize(p, (w, h))
        qq = quad(prob)
        nn.append(0 if qq is None else iou(gt, [(a / w, b / h) for a, b in qq]))
        dump.append(dict(name=os.path.basename(path), gt=gt, cur=q, net=None if qq is None else [(a / w, b / h) for a, b in qq], mask=out2[0], outline=out2[1], size=(w, h)))
    cur = np.array(cur); nn = np.array(nn)
    for label, v in (('current detector', cur), ('network', nn)):
        print(f'{label:16s} mean IoU {v.mean():.3f}  median {np.median(v):.3f}  >=0.9: {(v >= .9).mean():.0%}  failures (<0.5): {(v < .5).mean():.0%}')
    if os.environ.get('DUMP'):
        import pickle; pickle.dump(dump, open(os.environ['DUMP'], 'wb'))
    best = np.maximum(cur, nn); print(f'{"best of both":16s} mean IoU {best.mean():.3f}  (an upper bound for combining them)')

if __name__ == '__main__':
    main()
