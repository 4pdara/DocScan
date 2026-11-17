"""Frames of a DocScan screen recording for Replay.kt, named by their time in milliseconds.

frames.py <video> <out> <top> <height> [from_s] [to_s]: crops the camera view (rows top..top+height), and removes the
outline the app drew: its 2-dp line (painted in) and the 12% fill inside it (taken out). Faint traces remain where the
line was, so a replay is only as reliable as the frames away from the drawn outline.
"""
import os, sys
import cv2, numpy as np

video, out, top, height = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
t0 = float(sys.argv[5]) if len(sys.argv) > 5 else 0.0
t1 = float(sys.argv[6]) if len(sys.argv) > 6 else 1e9
os.makedirs(out, exist_ok=True)
mint = np.array([181, 199, 40], np.float32)  # ScanMint, BGR
cap = cv2.VideoCapture(video); n = 0
while True:
    ok, im = cap.read()
    if not ok: break
    t = cap.get(cv2.CAP_PROP_POS_MSEC)
    if t < t0 * 1000 or t > t1 * 1000: continue
    im = im[top:top + height]
    hsv = cv2.cvtColor(im, cv2.COLOR_BGR2HSV); h, s, v = [hsv[..., i].astype(int) for i in range(3)]
    line = ((h >= 78) & (h <= 95) & (s > 110) & (v > 120)).astype(np.uint8) * 255
    line[:50] = 0  # the timing line at the top
    line = cv2.morphologyEx(line, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))
    fat = cv2.dilate(line, np.ones((9, 9), np.uint8))
    clean = im.astype(np.float32)
    cs, _ = cv2.findContours(fat, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if cs:
        hull = cv2.convexHull(max(cs, key=cv2.contourArea))
        if cv2.contourArea(hull) > 20000:
            inside = np.zeros(im.shape[:2], np.uint8); cv2.fillPoly(inside, [hull], 255); m = inside > 0
            clean[m] = (clean[m] - .12 * mint) / .88
    clean = cv2.inpaint(np.clip(clean, 0, 255).astype(np.uint8), fat, 5, cv2.INPAINT_TELEA)
    cv2.imwrite(os.path.join(out, '%06d.png' % int(round(t))), clean); n += 1
print(n, 'frames')
