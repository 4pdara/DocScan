"""Turns the Mendeley corner dataset (CC BY 4.0) into training scenes like the synthetic ones (256 x 256 picture,
page mask and outline), split once and for all by a hash of the file name: 60% for training, 40% kept for testing
only. Training repeats each real photo [REPEAT] times (different flips and turns come from the loader)."""
import glob, hashlib, os, sys
import numpy as np, cv2

SRC, OUT = sys.argv[1], sys.argv[2]; REPEAT = int(os.environ.get('REPEAT', '8'))

def part(name):
    return 'test' if int(hashlib.sha256(name.encode()).hexdigest(), 16) % 10 < 4 else 'train'

def main():
    os.makedirs(f'{OUT}/train', exist_ok=True); os.makedirs(f'{OUT}/val', exist_ok=True); os.makedirs(f'{OUT}/test', exist_ok=True)
    counts = {'train': 0, 'test': 0, 'skipped': 0}
    for c in sorted(glob.glob(f'{SRC}/*.csv')):
        j = c[:-4] + '.jpg'
        if not os.path.exists(j): continue
        v = open(c).read().strip().split(',')
        try: pts = np.float32([(float(v[i]), float(v[i + 1])) for i in range(0, 8, 2)])
        except (ValueError, IndexError): counts['skipped'] += 1; continue
        im = cv2.imread(j, cv2.IMREAD_COLOR)
        if im is None: counts['skipped'] += 1; continue
        h, w = im.shape[:2]
        if not ((pts[:, 0] >= -.05 * w) & (pts[:, 0] <= 1.05 * w) & (pts[:, 1] >= -.05 * h) & (pts[:, 1] <= 1.05 * h)).all(): counts['skipped'] += 1; continue
        name = os.path.basename(j)[:-4].replace('(', '').replace(')', '').replace(' ', '_')
        p = part(name); counts[p] += 1
        f = 256 / np.array([w, h], np.float32)
        img = cv2.resize(im, (256, 256), interpolation=cv2.INTER_AREA)
        mask = np.zeros((256, 256), np.uint8); cv2.fillPoly(mask, [np.int32(np.round(pts * f))], 255)
        outline = np.zeros((256, 256), np.uint8); cv2.polylines(outline, [np.int32(np.round(pts * f))], True, 255, 2)
        outline[:1, :] = 0; outline[-1:, :] = 0; outline[:, :1] = 0; outline[:, -1:] = 0
        lab = np.dstack([mask, outline, np.zeros_like(mask)])
        if p == 'test':
            cv2.imwrite(f'{OUT}/test/{name}.jpg', img, [cv2.IMWRITE_JPEG_QUALITY, 92]); cv2.imwrite(f'{OUT}/test/{name}.png', lab)
            np.save(f'{OUT}/test/{name}.npy', pts / np.array([w, h], np.float32))
        else:
            for k in range(REPEAT):
                cv2.imwrite(f'{OUT}/train/{name}_{k}.jpg', img, [cv2.IMWRITE_JPEG_QUALITY, 92]); cv2.imwrite(f'{OUT}/train/{name}_{k}.png', lab)
            # A few of the training photos also watch the training's own validation.
            if counts['train'] % 10 == 0:
                cv2.imwrite(f'{OUT}/val/{name}.jpg', img, [cv2.IMWRITE_JPEG_QUALITY, 92]); cv2.imwrite(f'{OUT}/val/{name}.png', lab)
    print(counts)

if __name__ == '__main__':
    main()
