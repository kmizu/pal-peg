"""Which rules survive the budgeted inliner, and why (STRATEGY.md §1). Read-only.

Usage:
  python3 probes/survivors.py <inlined.peg[.gz]>             # the kinds of the surviving rules, with total references
  python3 probes/survivors.py --cost <compact.peg[.gz]> [N]   # the N most expensive rules to inline, by kind, and the
                                                            # fan-in / size medians per kind (cost = fan-in x (size - 1))
Kinds by rule name: registers `y_*` / `o_*`, site fields `C_l_i_f`, conditions `K*`, sub-expressions `X*`, helpers
`Tail_*`/`Code_*`/`Mask_*`, fallback `Pal_*`/`Short`/`Long`, the rest controller.
"""
import gzip
import re
import sys
from collections import Counter, defaultdict

TOKEN = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')


def load(path):
    opener = gzip.open if path.endswith('.gz') else open
    rules, order = {}, []
    with opener(path, 'rt', encoding='utf-8') as f:
        for line in f:
            if ' = ' not in line:
                continue
            name, body = line.split(' = ', 1)
            rules[name] = body.rstrip().rstrip(';')
            order.append(name)
    return rules, order


def kind(n, fine=False):
    if re.match(r'^(Tail|Code|Mask)_', n):
        return (n.split('_')[0] + ' helper') if fine else 'helper'
    if re.match(r'^(Pal_|Short|Long)', n):
        return 'fallback'
    if re.match(r'^C_\d+_\d+_', n):
        return 'site field'
    if re.match(r'^K\d+', n):
        return 'K'
    if re.match(r'^X\d+', n):
        return 'X'
    m = re.match(r'^(y|o)_([A-Za-z]+\d?)_\d+(_c|_kL)?$', n)
    if m:
        return f'{m.group(1)}_{m.group(2)}{m.group(3) or ""}' if fine else f'{m.group(1)} register'
    return 'controller'


def references(rules):
    uses = Counter()
    for body in rules.values():
        for t in TOKEN.findall(body):
            if t in rules:
                uses[t] += 1
    return uses


def size(body):
    # an approximation of the AST size: names, terminals and operators
    return len(TOKEN.findall(body)) + body.count('.') + body.count('"') // 2 + body.count('!') + body.count('&') + body.count('/')


def survivors(path):
    rules, order = load(path)
    uses = references(rules)
    agg = defaultdict(lambda: [0, 0])
    for n in order:
        a = agg[kind(n, fine=True)]
        a[0] += 1
        a[1] += uses[n]
    print(f'{path}: {len(order)} rules; kind, rules, total references to them')
    for k, (c, u) in sorted(agg.items(), key=lambda kv: -kv[1][0]):
        print(f'  {k:18s} {c:5d} {u:9d}')
    print('  self-referencing rules (the inliner never removes them):', sum(1 for n in order if n in TOKEN.findall(rules[n])))


def cost(path, top):
    rules, order = load(path)
    uses = references(rules)
    c = {n: uses[n] * (size(rules[n]) - 1) - size(rules[n]) for n in order}
    ranked = sorted(order, key=lambda n: -c[n])
    print(f'{path}: the {top} most expensive rules to inline, by kind:', dict(Counter(kind(n) for n in ranked[:top])))
    per = defaultdict(list)
    for n in order:
        per[kind(n)].append((uses[n], size(rules[n])))
    print('  kind, count, median fan-in, max fan-in, median size')
    for k, v in sorted(per.items(), key=lambda kv: -len(kv[1])):
        us = sorted(u for u, _ in v)
        ss = sorted(s for _, s in v)
        print(f'  {k:12s} {len(v):5d} {us[len(us) // 2]:6d} {us[-1]:6d} {ss[len(ss) // 2]:6d}')


if __name__ == '__main__':
    if sys.argv[1] == '--cost':
        cost(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 1000)
    else:
        survivors(sys.argv[1])
