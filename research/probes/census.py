"""Census of an exported plain PEG by rule-name pattern (UNDER1000.md §1, POOLS.md §4).

Usage: python3 probes/census.py <grammar.peg[.gz]> [schedule-layout]
Prints the rule counts by kind (register/bit rules per module prefix, site fields C_l_i_*, conditions K*, sub-expressions
X*, fallback Pal_*/Short/Long, helpers Tail_*/Code_*, controller) and, per layer number, the core (registers, bits and site
fields) and the total. Layer numbers are the `_<layer>` suffixes the compiler gives (the slot index in the schedule).
"""
import gzip
import re
import sys
from collections import Counter, defaultdict


def names(path):
    op = gzip.open if path.endswith(".gz") else open
    with op(path, "rt", encoding="utf-8") as f:
        for line in f:
            if " = " in line:
                yield line.split(" = ", 1)[0].strip()


CTRL = {"S", "pw", "nb", "clk", "sm0", "sm1", "first", "b0", "b1", "out"}
PREFIXES = ("m0_", "m1_", "y_", "o_")


def kind_and_layer(n):
    if re.fullmatch(r"(Pal_\d+|Short|Long)", n):
        return "fallback", None
    if re.fullmatch(r"(Tail|Code|Mask)_\d+", n):
        return "helpers", None
    m = re.fullmatch(r"C_(\d+)_\d+_\w+", n)
    if m:
        return "site fields", int(m.group(1))
    m = re.fullmatch(r"K\d+_(\d+)(_c|_kL)?", n)
    if m:
        return "K conditions", int(m.group(1))
    m = re.fullmatch(r"X\d+_(\d+)(_c|_kL)?", n)
    if m:
        return "X sub-expressions", int(m.group(1))
    for p in PREFIXES:
        if n.startswith(p):
            m = re.fullmatch(re.escape(p) + r"\w+?_(\d+)(_c|_kL)?", n)
            return f"registers/bits {p}", int(m.group(1)) if m else None
    base = re.sub(r"_(\d+)(_c|_kL)?$", "", n)
    m = re.search(r"_(\d+)(_c|_kL)?$", n)
    if base in CTRL or base.startswith("dj_"):
        return "controller", int(m.group(1)) if m else None
    return "other", int(m.group(1)) if m else None


def main():
    path = sys.argv[1]
    kinds = Counter()
    per_layer = defaultdict(Counter)
    total = 0
    for n in names(path):
        total += 1
        k, layer = kind_and_layer(n)
        kinds[k] += 1
        if layer is not None:
            per_layer[layer][k] += 1
    print(f"{path}: {total} rules")
    for k, c in sorted(kinds.items(), key=lambda kv: -kv[1]):
        print(f"  {k:24s} {c:6d}")
    print("  per layer: layer core(registers+bits+site fields) K X total")
    for layer in sorted(per_layer):
        c = per_layer[layer]
        core = sum(v for k, v in c.items() if k.startswith("registers") or k == "site fields" or k == "controller")
        print(f"  {layer:4d} {core:5d} {c['K conditions']:5d} {c['X sub-expressions']:5d} {sum(c.values()):6d}")


if __name__ == "__main__":
    main()
