"""
Synthetic training scenes for DocScan's page detector: a document, cover or open book photographed on a surface,
with the page region (mask) and its outline as labels. Everything is drawn here from scratch: OFL fonts, random
text, procedural pictures and textures. No photos, no other app's output.
"""
import math, os, random, sys
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont

FONTS_DIR = os.environ.get('FONTS', os.path.join(os.path.dirname(__file__), '..', '..', 'app', 'src', 'main', 'assets', 'fonts'))
FONTS = [os.path.join(FONTS_DIR, f) for f in sorted(os.listdir(FONTS_DIR)) if f.endswith('.ttf')]
HEB = 'אבגדהוזחטיכלמנסעפצקרשתםןץףך'
LAT = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'
DIG = '0123456789'
TEXTURED = int(os.environ.get('TEXTURED', '0'))
# Version 2 (pagenet-v2): photographed surfaces from a folder of CC0 textures (Poly Haven), an open book marked as the
# one page the app outlines (as CamScanner does), and documents lying on a larger sheet.
PHOTOS = os.environ.get('PHOTOS')
PHOTO_FILES = sorted(os.path.join(PHOTOS, f) for f in os.listdir(PHOTOS) if f.endswith('.jpg')) if PHOTOS else []
BOOKONE = int(os.environ.get('BOOKONE', '0'))
STACKED = float(os.environ.get('STACKED', '0'))

def rnd(a, b): return random.uniform(a, b)

def word(alphabet, n=None):
    return ''.join(random.choice(alphabet) for _ in range(n or random.randint(2, 9)))

def sentence(n_words):
    alpha = random.choice([HEB, HEB, LAT, DIG + '.,-/'])
    return ' '.join(word(alpha if random.random() > .1 else DIG) for _ in range(n_words))

_font_cache = {}
def font(size):
    path = random.choice(FONTS); key = (path, size)
    if key not in _font_cache:
        try: _font_cache[key] = ImageFont.truetype(path, size)
        except Exception: _font_cache[key] = ImageFont.load_default()
    return _font_cache[key]

def noise_field(h, w, scale, octaves=3):
    """Smooth random field in 0..1 (sum of upscaled random grids)."""
    out = np.zeros((h, w), np.float32); amp = 1.0; total = 0
    for o in range(octaves):
        gh = max(2, int(h / scale * 2 ** o)); gw = max(2, int(w / scale * 2 ** o))
        g = np.random.rand(gh, gw).astype(np.float32)
        out += amp * cv2.resize(g, (w, h), interpolation=cv2.INTER_CUBIC); total += amp; amp *= .5
    out /= total
    return np.clip((out - out.min()) / max(1e-6, out.max() - out.min()), 0, 1)

def random_colour(light=None):
    if light is True: return np.array([rnd(170, 255) for _ in range(3)])
    if light is False: return np.array([rnd(0, 110) for _ in range(3)])
    return np.array([rnd(0, 255) for _ in range(3)])

# ---------------------------------------------------------------- surfaces under the page
def muted():
    """Table and floor colours: mostly greys, browns and beiges, sometimes a strong colour."""
    c = random_colour()
    if random.random() < .75:
        grey = rnd(40, 230); c = c * rnd(.15, .5) + grey * (1 - rnd(.15, .5))
    return np.clip(c, 0, 255)

def photo_surface(h, w):
    """A photographed surface: a random part of a CC0 texture, turned, scaled and recoloured a little."""
    tex = cv2.imread(random.choice(PHOTO_FILES), cv2.IMREAD_COLOR)
    th, tw = tex.shape[:2]
    f = rnd(.25, 1.0); ch, cw = int(th * f), int(tw * f)
    y0, x0 = random.randint(0, th - ch), random.randint(0, tw - cw)
    tex = tex[y0:y0 + ch, x0:x0 + cw]
    k = random.randint(0, 3)
    if k: tex = np.rot90(tex, k)
    if random.random() < .5: tex = tex[:, ::-1]
    img = cv2.resize(np.ascontiguousarray(tex), (w, h), interpolation=cv2.INTER_AREA).astype(np.float32)
    img = img * rnd(.6, 1.25) + rnd(-25, 25)
    img *= np.array([rnd(.85, 1.15), rnd(.9, 1.1), rnd(.85, 1.15)])[None, None, :]
    return np.clip(img, 0, 255).astype(np.uint8)

def surface(h, w):
    if PHOTO_FILES and random.random() < .6: return photo_surface(h, w)
    _rc = random_colour
    kind = random.choice(['wood', 'wood', 'fabric', 'plain', 'plain', 'stone', 'gradient', 'cluttered', 'light', 'dark'] + ['carpet', 'carpet', 'carpet', 'linen', 'linen', 'marble', 'paperlike', 'paperlike'] * TEXTURED)
    random_colour_surface = muted
    if kind == 'wood':
        base = random_colour_surface(); base = base * .5 + np.array([140, 95, 55]) * .5
        y, x = np.mgrid[0:h, 0:w].astype(np.float32)
        ang = rnd(0, math.pi); u = x * math.cos(ang) + y * math.sin(ang)
        rings = .5 + .5 * np.sin(u / rnd(4, 18) + 6 * noise_field(h, w, rnd(60, 200)))
        img = base[None, None, :] * (.7 + .35 * rings[..., None])
    elif kind == 'fabric':
        base = random_colour_surface()
        y, x = np.mgrid[0:h, 0:w].astype(np.float32)
        weave = .5 + .25 * np.sin(x / rnd(1.2, 3)) + .25 * np.sin(y / rnd(1.2, 3))
        img = base[None, None, :] * (.75 + .3 * weave[..., None] + .2 * noise_field(h, w, 40)[..., None])
    elif kind == 'stone':
        base = random_colour_surface(); img = base[None, None, :] * (.6 + .6 * noise_field(h, w, rnd(10, 60), 4)[..., None])
    elif kind == 'gradient':
        a, b = random_colour_surface(), random_colour_surface(); t = noise_field(h, w, max(h, w), 1)[..., None]
        img = a * t + b * (1 - t)
    elif kind == 'light':
        base = random_colour(True); img = base[None, None, :] * (.92 + .1 * noise_field(h, w, 80)[..., None])
    elif kind == 'dark':
        base = random_colour(False); img = base[None, None, :] * (.8 + .4 * noise_field(h, w, 80)[..., None])
    elif kind == 'carpet':
        # Pile and fibres: strong fine noise in a grey, beige or brown, with short strokes in all directions.
        base = np.clip(muted() * rnd(.6, 1.2), 30, 230)
        fine = np.random.randn(h, w).astype(np.float32); fine = cv2.GaussianBlur(fine, (0, 0), rnd(.6, 1.6))
        img = base[None, None, :] * (1 + rnd(.12, .3) * fine[..., None] + .15 * (noise_field(h, w, rnd(20, 80)) - .5)[..., None])
        for _ in range(random.randint(300, 1500)):
            x, y = random.randint(0, w), random.randint(0, h); a = rnd(0, math.pi); l = rnd(2, 9)
            c = tuple(float(v) for v in np.clip(base * rnd(.6, 1.35), 0, 255))
            cv2.line(img, (x, y), (int(x + l * math.cos(a)), int(y + l * math.sin(a))), c, 1)
    elif kind == 'linen':
        base = muted(); fine = np.random.randn(h, w).astype(np.float32)
        k = random.randint(5, 15); kern = np.zeros((k, k), np.float32)
        if random.random() < .5: kern[k // 2, :] = 1 / k
        else: kern[:, k // 2] = 1 / k
        fibres = cv2.filter2D(fine, -1, kern) * 2
        img = base[None, None, :] * (1 + rnd(.08, .2) * fibres[..., None])
    elif kind == 'marble':
        base = random_colour(True); y, x = np.mgrid[0:h, 0:w].astype(np.float32)
        vein = np.abs(np.sin((x * rnd(.01, .04) + y * rnd(.01, .04)) + 8 * noise_field(h, w, rnd(40, 120), 4)))
        img = base[None, None, :] * (.75 + .25 * vein[..., None])
    elif kind == 'paperlike':
        # A light, smooth surface close to the paper's own colour: the page differs only by its edge.
        base = np.array([rnd(185, 240)] * 3) + np.random.randn(3) * 6
        img = base[None, None, :] * (.95 + .07 * noise_field(h, w, 60)[..., None] + .02 * np.random.randn(h, w, 1))
    else:
        base = random_colour_surface(); img = base[None, None, :] * (.85 + .2 * noise_field(h, w, 120)[..., None])
    img = np.clip(img, 0, 255).astype(np.uint8)
    if kind == 'cluttered' or random.random() < .3:
        for _ in range(random.randint(1, 5)):
            # Other things on the table: papers, a keyboard, a pen, a mug seen from above.
            c = tuple(int(v) for v in random_colour())
            cx, cy = random.randint(0, w), random.randint(0, h)
            if random.random() < .5:
                rw, rh = random.randint(w // 10, w // 2), random.randint(h // 10, h // 2)
                box = cv2.boxPoints(((cx, cy), (rw, rh), rnd(0, 180))).astype(np.int32)
                cv2.fillPoly(img, [box], c)
                if random.random() < .5:
                    for k in range(random.randint(2, 8)):
                        cv2.line(img, (cx - rw // 3, cy - rh // 3 + k * 9), (cx + rw // 3, cy - rh // 3 + k * 9), tuple(int(v * .6) for v in c), 2)
            elif random.random() < .5:
                cv2.line(img, (cx, cy), (cx + random.randint(-w // 3, w // 3), cy + random.randint(-h // 3, h // 3)), c, random.randint(3, 10))
            else:
                cv2.circle(img, (cx, cy), random.randint(w // 20, w // 7), c, -1)
    return img

# ---------------------------------------------------------------- pages
def text_page(pw, ph):
    paper = np.array(random.choice([(250, 250, 248), (246, 242, 230), (240, 238, 232), (235, 228, 205), (225, 225, 225), (252, 248, 235)]), np.float32)
    paper = np.clip(paper + np.random.randn(3) * 4, 0, 255)
    img = Image.new('RGB', (pw, ph), tuple(int(v) for v in paper)); d = ImageDraw.Draw(img)
    margin = int(pw * rnd(.06, .14)); y = int(ph * rnd(.05, .12))
    ink = tuple(int(v) for v in random.choice([(20, 20, 20), (40, 40, 60), (60, 30, 30), (10, 10, 10)]))
    if random.random() < .7:
        f = font(int(pw * rnd(.04, .08))); d.text((margin, y), sentence(random.randint(1, 4)), fill=ink, font=f); y += int(pw * .1)
    size = int(pw * rnd(.018, .035)); f = font(size)
    while y < ph - margin:
        r = random.random()
        if r < .06:   # a picture block
            bh = int(ph * rnd(.08, .25)); x0 = margin; x1 = pw - margin if random.random() < .5 else pw // 2
            pic = picture(x1 - x0, bh); img.paste(Image.fromarray(pic), (x0, y)); y += bh + size
        elif r < .1:  # a table
            rows = random.randint(3, 8); cols = random.randint(2, 5); rh = int(size * 1.8)
            for i in range(rows + 1): d.line([(margin, y + i * rh), (pw - margin, y + i * rh)], fill=ink, width=1)
            for j in range(cols + 1):
                x = margin + j * (pw - 2 * margin) // cols; d.line([(x, y), (x, y + rows * rh)], fill=ink, width=1)
            y += rows * rh + size
        else:
            line = sentence(random.randint(3, 12))
            d.text((margin, y), line, fill=ink, font=f); y += int(size * rnd(1.3, 1.9))
    return np.array(img)

def picture(w, h):
    """A procedural picture: smooth colour fields, shapes and stripes."""
    a, b, c = random_colour(), random_colour(), random_colour()
    t = noise_field(h, w, rnd(20, 120))[..., None]; s = noise_field(h, w, rnd(10, 60))[..., None]
    img = (a * t + b * (1 - t)) * (.6 + .4 * s) + c * .2 * (1 - s)
    img = np.clip(img, 0, 255).astype(np.uint8)
    for _ in range(random.randint(0, 6)):
        col = tuple(int(v) for v in random_colour())
        if random.random() < .5: cv2.circle(img, (random.randint(0, w), random.randint(0, h)), random.randint(5, max(6, min(w, h) // 3)), col, -1)
        else: cv2.rectangle(img, (random.randint(0, w), random.randint(0, h)), (random.randint(0, w), random.randint(0, h)), col, -1)
    return img

def cover_page(pw, ph):
    """A cover or magazine: printed to the edges, often colourful or dark."""
    img = picture(pw, ph) if random.random() < .6 else np.full((ph, pw, 3), random_colour(), np.uint8)
    if random.random() < .3: img = (img * rnd(.15, .45)).astype(np.uint8)   # a dark cover
    pil = Image.fromarray(img); d = ImageDraw.Draw(pil)
    for _ in range(random.randint(1, 4)):
        f = font(int(pw * rnd(.05, .14))); col = tuple(int(v) for v in random_colour())
        d.text((random.randint(0, pw // 2), random.randint(0, ph - ph // 6)), sentence(random.randint(1, 3)), fill=col, font=f)
    if random.random() < .3:  # a frame printed near the edge
        m = int(pw * rnd(.02, .06)); d.rectangle([m, m, pw - m, ph - m], outline=tuple(int(v) for v in random_colour()), width=max(2, pw // 80))
    return np.array(pil)

def card_page(pw, ph):
    img = text_page(pw, ph) if random.random() < .5 else cover_page(pw, ph)
    return img

# ---------------------------------------------------------------- geometry
def curl(page, amount):
    """Bends a page as a book page bends: top and bottom edges bow, the middle stays."""
    ph, pw = page.shape[:2]
    y, x = np.mgrid[0:ph, 0:pw].astype(np.float32)
    u = x / (pw - 1)
    shape = np.sin(np.pi * u) if random.random() < .5 else (u ** rnd(1.5, 3))
    dy = amount * ph * shape * ((y / (ph - 1)) - .5) * 2
    mapy = (y - dy).astype(np.float32)
    return cv2.remap(page, x, mapy, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0)), \
        cv2.remap(np.full((ph, pw), 255, np.uint8), x, mapy, cv2.INTER_NEAREST, borderMode=cv2.BORDER_CONSTANT, borderValue=0)

def place(page, alpha, frame_w, frame_h):
    """A random camera view of the page: the homography from the page to the frame."""
    ph, pw = page.shape[:2]
    scale = rnd(.35, 1.0) * min(frame_w / pw, frame_h / ph)
    if random.random() < .15: scale *= rnd(1.0, 1.25)   # partly outside the frame
    cx = frame_w / 2 + rnd(-.2, .2) * frame_w; cy = frame_h / 2 + rnd(-.2, .2) * frame_h
    ang = math.radians(rnd(-25, 25) if random.random() < .8 else rnd(-90, 90))
    src = np.float32([[0, 0], [pw, 0], [pw, ph], [0, ph]])
    pts = []
    for (px, py) in src:
        x = (px - pw / 2) * scale; y = (py - ph / 2) * scale
        pts.append([cx + x * math.cos(ang) - y * math.sin(ang), cy + x * math.sin(ang) + y * math.cos(ang)])
    pts = np.float32(pts)
    # Perspective: corners pulled towards a vanishing point (camera tilt).
    tilt = rnd(0, .22) if random.random() < .85 else rnd(.2, .35)
    side = random.randint(0, 3)
    a, b = side, (side + 1) % 4
    centre = pts.mean(0)
    pts[a] += (centre - pts[a]) * tilt; pts[b] += (centre - pts[b]) * tilt
    pts += np.random.randn(4, 2).astype(np.float32) * frame_w * .01
    M = cv2.getPerspectiveTransform(src, pts)
    return M, pts

def compose(frame_w=384, frame_h=512):
    """One scene: (image BGR uint8, page mask uint8, outline uint8)."""
    scene = surface(frame_h, frame_w).astype(np.float32)
    kind = random.choices(['text', 'cover', 'book', 'card', 'receipt'], [.42, .23, .2, .08, .07])[0]
    base = 900
    if kind == 'card':
        pw, ph = base, int(base / 1.586)
    elif kind == 'receipt':
        pw, ph = int(base * .5), int(base * rnd(1.6, 3))
    elif kind == 'book':
        pw, ph = int(base * 1.3), int(base * 1.0)
    else:
        a = random.choice([1.414, 1.294, 1.5, 1.33, 1.25]); pw, ph = int(base / a * rnd(.95, 1.05)) if a else base, base
        pw = int(base / a); ph = base
    if kind == 'book':
        half = pw // 2
        left = text_page(half, ph) if random.random() < .8 else cover_page(half, ph)
        right = text_page(pw - half, ph) if random.random() < .8 else cover_page(pw - half, ph)
        page = np.hstack([left, right]).astype(np.float32)
        # The fold: shading towards the gutter.
        x = np.arange(pw, dtype=np.float32); g = np.exp(-((x - half) / (pw * rnd(.02, .08))) ** 2)
        page *= (1 - rnd(.2, .6) * g)[None, :, None]
        page = page.astype(np.uint8)
    elif kind == 'cover':
        page = cover_page(pw, ph)
    elif kind == 'card':
        page = card_page(pw, ph)
    else:
        page = text_page(pw, ph)
    if kind == 'text' and random.random() < .15: page = cover_page(pw, ph)
    if kind == 'book' or random.random() < .15:
        page, alpha = curl(page, rnd(.0, .05) if kind == 'book' else rnd(0, .02))
    else:
        alpha = np.full(page.shape[:2], 255, np.uint8)
    label_alpha = alpha
    if kind == 'book' and BOOKONE:
        # The page the app outlines: one whole page, placed in view; the facing page lies beside it, often running
        # off the picture. Only that page is the page region.
        on_right = random.random() < .5
        one = page[:, half:] if on_right else page[:, :half]
        M1, pts1 = place(one, None, frame_w, frame_h)
        shift = np.float64([[1, 0, -half if on_right else 0], [0, 1, 0], [0, 0, 1]])
        M = M1 @ shift
        spread = np.float32([[0, 0], [pw, 0], [pw, ph], [0, ph]]).reshape(-1, 1, 2)
        pts = cv2.perspectiveTransform(spread, M).reshape(4, 2)
        label_alpha = alpha.copy()
        if on_right: label_alpha[:, :half] = 0
        else: label_alpha[:, half:] = 0
    else:
        M, pts = place(page, alpha, frame_w, frame_h)
    # A document lying on a larger sheet (a card on a letter, a receipt on a printout): the sheet is not the page.
    if kind in ('card', 'receipt', 'text') and random.random() < STACKED:
        c = pts.mean(0); grow = rnd(1.25, 2.2)
        sheet = (pts - c) * grow + c + np.random.randn(1, 2).astype(np.float32) * frame_w * .05
        ang = math.radians(rnd(-20, 20)); rot = np.float32([[math.cos(ang), -math.sin(ang)], [math.sin(ang), math.cos(ang)]])
        sheet = (sheet - c) @ rot.T + c
        tone = rnd(200, 245); col = (tone + rnd(-8, 8), tone + rnd(-8, 8), tone + rnd(-8, 8))
        cv2.fillPoly(scene, [sheet.astype(np.int32)], col)
        if random.random() < .6:
            # Print on the sheet, seen around the document.
            sh = (sheet - sheet.min(0)); bw, bh = int(sh[:, 0].max()) + 1, int(sh[:, 1].max()) + 1
            for k in range(random.randint(5, 25)):
                y = sheet[:, 1].min() + rnd(.05, .95) * bh; x0 = sheet[:, 0].min() + rnd(.05, .4) * bw
                cv2.line(scene, (int(x0), int(y)), (int(x0 + rnd(.2, .5) * bw), int(y)), (60, 60, 60), random.randint(1, 3))
    # A book: the cover and page stack around the pages, which are not part of the page region.
    if kind == 'book' and random.random() < .8:
        grow = rnd(1.03, 1.12)
        c = pts.mean(0); cover_pts = (pts - c) * grow + c
        col = tuple(float(v) for v in random_colour())
        cv2.fillPoly(scene, [cover_pts.astype(np.int32)], col)
        stack = (pts - c) * rnd(1.005, 1.03) + c
        cv2.fillPoly(scene, [stack.astype(np.int32)], (225, 222, 210))
    warped = cv2.warpPerspective(page, M, (frame_w, frame_h), flags=cv2.INTER_AREA if random.random() < .5 else cv2.INTER_LINEAR)
    mask = cv2.warpPerspective(alpha, M, (frame_w, frame_h), flags=cv2.INTER_LINEAR)
    label = cv2.warpPerspective(label_alpha, M, (frame_w, frame_h), flags=cv2.INTER_LINEAR) if label_alpha is not alpha else mask
    m = (mask.astype(np.float32) / 255)[..., None]
    img = scene * (1 - m) + warped.astype(np.float32) * m
    # A soft shadow of the page on the table.
    if random.random() < .5:
        sh = cv2.GaussianBlur(cv2.warpAffine(mask, np.float32([[1, 0, rnd(-8, 8)], [0, 1, rnd(-8, 8)]]), (frame_w, frame_h)), (0, 0), rnd(3, 12)) / 255.
        img *= (1 - .4 * sh * (1 - m[..., 0]))[..., None]
    # Fingers holding the page.
    if random.random() < .15:
        skin = np.array(random.choice([(196, 224, 255), (140, 172, 224), (36, 85, 141), (110, 150, 200)]), np.float32)
        for _ in range(random.randint(1, 3)):
            e = random.randint(0, 3); t = rnd(.2, .8)
            p0 = pts[e] + (pts[(e + 1) % 4] - pts[e]) * t
            inward = pts.mean(0) - p0; inward /= max(1e-3, np.linalg.norm(inward))
            p1 = p0 + inward * frame_w * rnd(.05, .15); p0 = p0 - inward * frame_w * .1
            cv2.line(img, tuple(int(v) for v in p0), tuple(int(v) for v in p1), tuple(float(v) for v in skin), int(frame_w * rnd(.04, .07)))
    # Lighting: an uneven light, a hard shadow of the phone or hand, a reflection.
    h, w = frame_h, frame_w
    img *= (.65 + .5 * noise_field(h, w, max(h, w), 1))[..., None]
    if random.random() < .35:
        poly = np.int32([[random.randint(-w // 2, w), random.randint(-h // 2, h)] for _ in range(random.randint(3, 5))])
        sm = np.zeros((h, w), np.float32); cv2.fillPoly(sm, [poly], 1.0); sm = cv2.GaussianBlur(sm, (0, 0), rnd(2, 20))
        img *= (1 - rnd(.25, .6) * sm)[..., None]
    if random.random() < .25:
        cx, cy = random.randint(0, w), random.randint(0, h); r = rnd(.05, .25) * w
        y, x = np.mgrid[0:h, 0:w].astype(np.float32)
        img += (rnd(60, 200) * np.exp(-((x - cx) ** 2 + (y - cy) ** 2) / (2 * r * r)))[..., None]
    img *= np.array([rnd(.85, 1.1), rnd(.9, 1.05), rnd(.85, 1.15)])[None, None, :]  # white balance
    img = np.clip(img, 0, 255).astype(np.uint8)
    if random.random() < .4: img = cv2.GaussianBlur(img, (0, 0), rnd(.3, 1.8))
    if random.random() < .15:
        k = random.randint(3, 9); kern = np.zeros((k, k), np.float32); kern[k // 2, :] = 1 / k
        kern = cv2.warpAffine(kern, cv2.getRotationMatrix2D((k / 2, k / 2), rnd(0, 180), 1), (k, k)); img = cv2.filter2D(img, -1, kern / max(1e-6, kern.sum()))
    img = np.clip(img.astype(np.float32) + np.random.randn(h, w, 1) * rnd(0, 6), 0, 255).astype(np.uint8)
    if random.random() < .7:
        ok, enc = cv2.imencode('.jpg', img, [cv2.IMWRITE_JPEG_QUALITY, random.randint(40, 95)]); img = cv2.imdecode(enc, 1)
    page_mask = (label > 127).astype(np.uint8) * 255
    outline = np.zeros((h, w), np.uint8)
    cnts, _ = cv2.findContours(page_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    cv2.drawContours(outline, cnts, -1, 255, 3)
    # The frame border is not a page edge where the page runs out of the picture.
    outline[:2, :] = 0; outline[-2:, :] = 0; outline[:, :2] = 0; outline[:, -2:] = 0
    return img, page_mask, outline, kind

def make(out_dir, start, count, size=256, seed=0):
    os.makedirs(out_dir, exist_ok=True)
    for i in range(start, start + count):
        random.seed(seed * 1_000_003 + i); np.random.seed((seed * 1_000_003 + i) % 2 ** 32)
        fw, fh = random.choice([(384, 512), (384, 512), (360, 640), (512, 384)])
        img, mask, outline, kind = compose(fw, fh)
        img = cv2.resize(img, (size, size), interpolation=cv2.INTER_AREA)
        lab = np.dstack([cv2.resize(mask, (size, size), interpolation=cv2.INTER_AREA), cv2.resize(outline, (size, size), interpolation=cv2.INTER_AREA), np.zeros((size, size), np.uint8)])
        cv2.imwrite(f'{out_dir}/{i:06d}.jpg', img, [cv2.IMWRITE_JPEG_QUALITY, 92])
        cv2.imwrite(f'{out_dir}/{i:06d}.png', lab)

if __name__ == '__main__':
    out, start, count = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    seed = int(sys.argv[4]) if len(sys.argv) > 4 else 0
    make(out, start, count, seed=seed)
