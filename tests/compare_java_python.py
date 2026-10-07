"""Frame-by-frame MD5 comparison of the Java decoder (CineDump) with Phantastic's Python reader.

The Python reader is itself anchored independently: pixel-identical to PCC's own decimated
cines (frame selection test), and its writer/reader conventions decode identically in the
vendor SDK. Needs Phantastic (pip install git+https://github.com/cpatb/phantastic).
Usage: python compare_java_python.py <classpath> <java> file.cine [maxFrames] ...
"""
import hashlib
import subprocess
import sys

import numpy as np
from phantastic.cine import CineReader

cp, java = sys.argv[1], sys.argv[2]
mx = '200'
files = []
for a in sys.argv[3:]:
    if a.isdigit():
        mx = a
    else:
        files.append(a)
total_bad = 0
for f in files:
    out = subprocess.run([java, '-cp', cp, 'CineDump', f, mx], capture_output=True, text=True, check=True).stdout.split('\n')
    meta, rows = out[0], [r.split() for r in out[1:] if r.strip()]
    r = CineReader(f)
    bad = 0
    for i, h in rows:
        a = r.read(int(i))
        b = a.astype('<u2').tobytes() if a.dtype == np.uint16 else a.tobytes()
        if hashlib.md5(b).hexdigest() != h:
            bad += 1
    total_bad += bad
    print(f'{f}: {meta} | frames compared {len(rows)}, mismatches {bad}')
print('TOTAL mismatches', total_bad)
sys.exit(1 if total_bad else 0)
