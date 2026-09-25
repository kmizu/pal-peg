"""Read-only: N and rhs nodes when only a chosen set of rules is kept and every other rule is expanded in place (STRATEGY.md §1).
Usage: python3 probes/keep_policy.py <compact.peg.gz>"""
import gzip, re, sys
sys.setrecursionlimit(100000)
src = gzip.open(sys.argv[1], 'rt').read()
# --- a small parser for `Name = expr;` (sequence, /, !, &, *, (), "lit", ., names)
TOK = re.compile(r'\s*(?:(?P<str>"(?:[^"\\]|\\.)*")|(?P<name>[A-Za-z_][A-Za-z0-9_]*)|(?P<op>[/!&*().;=]))')
toks = []
pos = 0
while True:
    m = TOK.match(src, pos)
    if not m or m.end() == pos: break
    pos = m.end()
    toks.append(m.group('str') and ('lit', m.group('str')) or m.group('name') and ('name', m.group('name')) or ('op', m.group('op')))
i = 0
def peek(): return toks[i] if i < len(toks) else ('eof', '')
def take():
    global i; t = toks[i]; i += 1; return t
def choice():
    alts = [seq()]
    while peek() == ('op', '/'): take(); alts.append(seq())
    return alts[0] if len(alts) == 1 else ('C', alts)
def seq():
    parts = []
    while True:
        t = peek()
        if t[0] == 'op' and t[1] in '/);': break
        if t[0] == 'name' and i + 1 < len(toks) and toks[i + 1] == ('op', '='): break
        parts.append(prefix())
    return parts[0] if len(parts) == 1 else ('S', parts)
def prefix():
    t = peek()
    if t == ('op', '!'): take(); return ('N', prefix())
    if t == ('op', '&'): take(); return ('A', prefix())
    e = primary()
    while peek() == ('op', '*'): take(); e = ('*', e)
    return e
def primary():
    t = take()
    if t == ('op', '('): e = choice(); take(); return e
    if t == ('op', '.'): return ('.',)
    if t[0] == 'lit': return ('L',)
    return ('R', t[1])
rules = {}
order = []
while i < len(toks):
    n = take()[1]; take(); rules[n] = choice(); order.append(n)
    if peek() == ('op', ';'): take()
def refs(e, acc):
    k = e[0]
    if k == 'R': acc.append(e[1])
    elif k in ('S', 'C'): [refs(x, acc) for x in e[1]]
    elif k in ('N', 'A', '*'): refs(e[1], acc)
    return acc
def size(e):
    k = e[0]
    if k in ('S', 'C'): return 1 + sum(size(x) for x in e[1])
    if k in ('N', 'A', '*'): return 1 + size(e[1])
    return 1
R = {n: refs(rules[n], []) for n in order}
fanin = {n: 0 for n in order}
for n in order:
    for t in R[n]:
        if t in fanin: fanin[t] += 1
base_nodes = sum(size(rules[n]) for n in order)
start = order[0]
def evaluate(keep):
    # break cycles among the expanded rules by keeping the highest fan-in member of each cycle found
    keep = set(keep) | {start}
    while True:
        color = {}
        cyc = None
        def dfs(n, path):
            nonlocal cyc
            color[n] = 1; path.append(n)
            for t in R[n]:
                if t in keep or cyc: continue
                if color.get(t) == 1: cyc = path[path.index(t):]; return
                if t not in color: dfs(t, path)
            color[n] = 2; path.pop()
        for n in order:
            if n not in keep and n not in color and not cyc: dfs(n, [])
        if not cyc: break
        keep.add(max(cyc, key=lambda x: fanin[x]))
    memo = {}
    def exp(n):   # expanded size of rule n's body
        if n in memo: return memo[n]
        memo[n] = None
        e = rules[n]
        def sz(e):
            k = e[0]
            if k == 'R': return 1 if e[1] in keep else exp(e[1])
            if k in ('S', 'C'): return 1 + sum(sz(x) for x in e[1])
            if k in ('N', 'A', '*'): return 1 + sz(e[1])
            return 1
        v = sz(e); memo[n] = v; return v
    nodes = sum(exp(n) for n in keep)
    return len(keep), nodes
def kind(n):
    if re.match(r'^(Tail|Code|Mask)_', n): return 'helper'
    if re.match(r'^(Pal_|Short|Long)', n): return 'fallback'
    if re.match(r'^C_\d+_\d+_', n): return 'site'
    if re.match(r'^K\d+', n): return 'K'
    if re.match(r'^X\d+', n): return 'X'
    if n.startswith('y_') or n.startswith('o_'): return 'reg'
    return 'ctl'
print(f"{len(order)} rules, {base_nodes} rhs nodes")
for T in (200, 100, 64, 50, 40, 32):
    N, nodes = evaluate([n for n in order if fanin[n] >= T])
    print(f"keep fan-in >= {T:3d} (and break every cycle): N {N:5d}, rhs nodes {nodes:.3e} (x{nodes / base_nodes:.3g})")
core = [n for n in order if kind(n) in ('reg', 'site', 'helper', 'fallback', 'ctl')]
N, nodes = evaluate(core)
print(f"keep registers + site fields + helpers + fallback + controller, expand every K and X: N {N}, rhs nodes {nodes:.3e} (x{nodes / base_nodes:.3g})")
