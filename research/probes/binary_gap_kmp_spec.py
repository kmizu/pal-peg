"""Binary KMP with persistent gap sequences: algebra/oracle probe only.

Python lists implement sequences by copying. This is NOT a real-time machine,
an Lpm implementation, a PEG, or a grammar-size measurement.
"""
from itertools import product


def build(pattern):
    """Return truncated shortcut gaps, full-match failure/front, and debug rows."""
    h = []
    z = []
    rows = []
    f, g = 0, []
    for i, char in enumerate(pattern):
        if i == 0:
            hi, zi, nf, ng = [], -1, 0, []
        else:
            c = h[f] + [f] + g
            same = char == pattern[f]
            hi = c if same else g
            zi = z[f] if same else f
            d = (g if same else c) + [i]
            nf, ng = d[0], d[1:]
        assert g == list(range(f + 1, i)), (pattern, i, f, g)
        assert hi == list(range(zi + 1, i)), (pattern, i, zi, hi)
        rows.append(dict(i=i, char=char, f=f, z=zi, g=g, h=hi))
        h.append(hi)
        z.append(zi)
        f, g = nf, ng
    # E_m is an END marker. It is added to g only after its allocation.
    return h, f, g + [len(pattern)], rows


def outputs(pattern, text):
    h, failure, reset_front, _ = build(pattern)
    m = len(pattern)
    cur, front = 0, list(range(1, m + 1))
    for char in text:
        if char == pattern[cur]:
            d = front
        else:
            d = h[cur] + [cur] + front
        cur, front = d[0], d[1:]
        accept = cur == m
        yield accept
        if accept:
            cur, front = failure, reset_front


def words(length):
    return map("".join, product("ab", repeat=length))


if __name__ == "__main__":
    patterns = 0
    for m in range(1, 13):
        for p in words(m):
            _, f, _, _ = build(p)
            oracle = max(j for j in range(m) if p[:j] == p[m-j:])
            assert f == oracle, (p, f, oracle)
            patterns += 1
    print(f"table/gap invariants: {patterns} nonempty binary patterns, length <= 12")
    pairs = 0
    for m in range(1, 8):
        for p in words(m):
            for n in range(9):
                for text in words(n):
                    actual = list(outputs(p, text))
                    expected = [text[:k].endswith(p) for k in range(1, n+1)]
                    assert actual == expected, (p, text, actual, expected)
                    pairs += 1
    print(f"online outputs: {pairs} pattern/text pairs, pattern <= 7, text <= 8")
    for p in ("aaab", "ababa"):
        _, f, front, rows = build(p)
        print(p)
        for row in rows:
            print(row)
        print("end", dict(i=len(p), f=f, front=front))
