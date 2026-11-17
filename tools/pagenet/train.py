"""Trains PageNet on the synthetic scenes (data/train, data/val) and keeps the best checkpoint by validation IoU."""
import glob, math, os, random, sys, time
import numpy as np, cv2, torch, torch.nn.functional as F
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from model import PageNet

DATA = sys.argv[1]; OUT = sys.argv[2]; EPOCHS = int(sys.argv[3]) if len(sys.argv) > 3 else 12
WIDTH = float(os.environ.get('WIDTH', '1.0')); THREADS = int(os.environ.get('THREADS', '3'))
# Fine-tuning: a lower top learning rate, and shorter epochs (STEPS per epoch) to watch progress more often.
LR = float(os.environ.get('LR', '2e-3')); STEPS = int(os.environ.get('STEPS', '0'))
torch.set_num_threads(THREADS)

class Scenes(torch.utils.data.Dataset):
    """Scenes from one or more data folders (comma-separated), each with train/ and val/. A folder written as
    name*k is drawn k times as often as the others (real photos among many synthetic ones)."""
    def __init__(self, folder, augment):
        self.files = []; self.weights = []
        for d in folder.split(','):
            d, _, k = d.partition('*')
            found = sorted(glob.glob(f'{d}/*.jpg')); self.files += found; self.weights += [float(k or 1)] * len(found)
        self.augment = augment
    def __len__(self): return len(self.files)
    def __getitem__(self, i):
        img = cv2.imread(self.files[i]); lab = cv2.imread(self.files[i][:-4] + '.png')
        mask = lab[..., 0]; edge = lab[..., 1]
        if self.augment:
            if random.random() < .5: img, mask, edge = img[:, ::-1], mask[:, ::-1], edge[:, ::-1]
            k = random.choice([0, 0, 0, 1, 2, 3])
            if k: img, mask, edge = np.rot90(img, k), np.rot90(mask, k), np.rot90(edge, k)
            if random.random() < .5:   # brightness, contrast and colour
                img = np.clip(img.astype(np.float32) * random.uniform(.7, 1.3) + random.uniform(-30, 30), 0, 255).astype(np.uint8)
            if random.random() < .2: img = cv2.cvtColor(cv2.cvtColor(np.ascontiguousarray(img), cv2.COLOR_BGR2GRAY), cv2.COLOR_GRAY2BGR)
        img = np.ascontiguousarray(img); mask = np.ascontiguousarray(mask); edge = np.ascontiguousarray(edge)
        target = np.stack([cv2.resize(mask, (128, 128), interpolation=cv2.INTER_AREA), cv2.resize(edge, (128, 128), interpolation=cv2.INTER_AREA)]).astype(np.float32) / 255
        x = torch.from_numpy(img.transpose(2, 0, 1).astype(np.float32) / 255 - .5)
        return x, torch.from_numpy(target)

def loss_fn(logits, target):
    p = torch.sigmoid(logits)
    region = F.binary_cross_entropy_with_logits(logits[:, 0], target[:, 0])
    inter = (p[:, 0] * target[:, 0]).sum((1, 2)); dice = 1 - (2 * inter + 1) / (p[:, 0].sum((1, 2)) + target[:, 0].sum((1, 2)) + 1)
    outline = F.binary_cross_entropy_with_logits(logits[:, 1], target[:, 1], pos_weight=torch.tensor(8.0))
    inter_e = (p[:, 1] * target[:, 1]).sum((1, 2)); dice_e = 1 - (2 * inter_e + 1) / (p[:, 1].sum((1, 2)) + target[:, 1].sum((1, 2)) + 1)
    return region + dice.mean() + .5 * outline + .5 * dice_e.mean()

def main():
    def split(part): return ','.join(f'{d.partition("*")[0]}/{part}' + (f'*{d.partition("*")[2]}' if '*' in d else '') for d in DATA.split(','))
    scenes = Scenes(split('train'), True)
    sampler = torch.utils.data.WeightedRandomSampler(scenes.weights, STEPS * 32 if STEPS else len(scenes), replacement=True, generator=torch.Generator().manual_seed(1))
    train = torch.utils.data.DataLoader(scenes, batch_size=32, sampler=sampler, num_workers=2, drop_last=True, persistent_workers=True)
    # Validation per folder; the best checkpoint is the one with the best mean over folders (each counts the same).
    vals = [(d.partition('*')[0], torch.utils.data.DataLoader(Scenes(f'{d.partition("*")[0]}/val', False), batch_size=64, num_workers=1)) for d in DATA.split(',')]
    net = PageNet(WIDTH)
    # Starting from earlier weights (a new run on more data): INIT names them.
    if os.environ.get('INIT') and not os.path.exists(f'{OUT}/last.pt'): net.load_state_dict(torch.load(os.environ['INIT']))
    start = 0; best = 0; skip = 0
    if os.path.exists(f'{OUT}/last.pt'):
        # Resumes where it stopped: within an epoch from its last saved step.
        state = torch.load(f'{OUT}/last.pt'); net.load_state_dict(state['net']); best = state['best']
        if state.get('step') is None: start = state['epoch'] + 1
        else: start = state['epoch']; skip = state['step'] + 1
    opt = torch.optim.AdamW(net.parameters(), LR, weight_decay=1e-4)
    steps = EPOCHS * len(train)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, LR, total_steps=steps, pct_start=.05)
    if os.path.exists(f'{OUT}/last.pt'):
        opt.load_state_dict(state['opt']); sched.load_state_dict(state['sched'])
    os.makedirs(OUT, exist_ok=True)
    for epoch in range(start, EPOCHS):
        net.train(); t = time.time(); total = 0
        steps = 0
        for i, (x, y) in enumerate(train):
            if epoch == start and i < skip: continue
            loss = loss_fn(net(x), y); opt.zero_grad(); loss.backward(); opt.step(); sched.step(); total += loss.item(); steps += 1
            if i % 200 == 0: print(f'epoch {epoch} step {i}/{len(train)} loss {total / steps:.4f} {time.time() - t:.0f}s', flush=True)
            if i % 150 == 149:
                torch.save({'net': net.state_dict(), 'opt': opt.state_dict(), 'sched': sched.state_dict(), 'epoch': epoch, 'step': i, 'best': best}, f'{OUT}/last.tmp'); os.replace(f'{OUT}/last.tmp', f'{OUT}/last.pt')
        total = total * len(train) / max(1, steps)
        net.eval(); per = []
        with torch.no_grad():
            for name, val in vals:
                ious = []
                for x, y in val:
                    p = torch.sigmoid(net(x))[:, 0] > .5; g = y[:, 0] > .5
                    ious += ((p & g).sum((1, 2)).float() / (p | g).sum((1, 2)).clamp(min=1)).tolist()
                per.append((name, float(np.mean(ious))))
        iou = float(np.mean([v for _, v in per]))
        print(f'epoch {epoch} done loss {total / len(train):.4f} val IoU {iou:.4f} ' + ' '.join(f'{n} {v:.4f}' for n, v in per) + f' {time.time() - t:.0f}s', flush=True)
        if iou > best: best = iou; torch.save(net.state_dict(), f'{OUT}/best.pt')
        torch.save({'net': net.state_dict(), 'opt': opt.state_dict(), 'sched': sched.state_dict(), 'epoch': epoch, 'step': None, 'best': best}, f'{OUT}/last.tmp'); os.replace(f'{OUT}/last.tmp', f'{OUT}/last.pt')

if __name__ == '__main__':
    main()
