#!/usr/bin/env python3
"""runner_suffixes.py <grammar.peg> <words file> [maxWords]: every suffix length t of each word whose palindromicity the grammar
must decide (all lengths from 3 on, sampled every 7 plus the last 40), run through the Rust runner; prints the mismatches."""
import subprocess, sys
g, wf = sys.argv[1], sys.argv[2]
mx = int(sys.argv[3]) if len(sys.argv) > 3 else 10**9
words = [l.split()[-1] for l in open(wf) if l.strip()][:mx]
import os
runner = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'runner', 'target', 'release', 'plain-peg-runner')
inputs = []
for w in words:
    n = len(w)
    ts = sorted(set(list(range(3, n + 1, 7)) + list(range(max(3, n - 40), n + 1))))
    inputs += [w[n - t:] for t in ts]
bad = 0; total = 0
for i in range(0, len(inputs), 200):
    chunk = inputs[i:i + 200]
    out = subprocess.run([runner, g] + chunk, capture_output=True, text=True).stdout.splitlines()
    res = [l.split('\t')[2] == 'true' for l in out if l.startswith('match')]
    assert len(res) == len(chunk), (len(res), len(chunk))
    for x, r in zip(chunk, res):
        total += 1
        if r != (x == x[::-1]):
            bad += 1
            if bad <= 5: print(f"mismatch len {len(x)} grammar={r}: {x[:80]}")
print(f"{g.split('/')[-1]}: {len(words)} words, {total} inputs, {bad} mismatches")
