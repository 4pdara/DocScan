"""Summary of Replay.kt outputs: looks without an outline (while the network saw a page) and the detector's share."""
import math, sys

def quad(s): return None if s == 'none' else [tuple(map(float, p.split(','))) for p in s.split(';')]
for path in sys.argv[1:]:
    rows = [l.rstrip('\n').split('\t') for l in open(path)]
    hidden = sum(1 for r in rows if quad(r[2]) is None and quad(r[4]) is not None)
    detector = sum(1 for r in rows if r[1] == 'det')
    print('%s: %d looks, no outline on %d, the detector\'s outline on %d' % (path, len(rows), hidden, detector))
