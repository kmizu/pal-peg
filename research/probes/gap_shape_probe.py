"""Shape of the sequences of the binary gap matcher (plan §4): probe only, exact lists.

Facts measured here (no PEG, no Lpm):
  1. every sequence the matcher forms (g_i, h_i, C, D, front) is a contiguous range of entry
     indices, so the persistent structure only has to answer "the entry after E_a" (succ) on a
     chain that grows at its end;
  2. storing every h_i as a flat forward list costs sum |h_i| = Theta(m^2) on unary patterns;
  3. storing h_i as a nested snapshot (a stack of chunks, unnested lazily on pop) makes a pop
     cost the number of chunk expansions; the maximum over patterns grows with the pattern length.
"""
import random
import sys
from itertools import product

sys.setrecursionlimit(100000)


def words(length):
    return map("".join, product("ab", repeat=length))


# ---- 1. the range lemma --------------------------------------------------------------------

def build_ranges(pattern):
    h, z = [], []
    f, g = 0, []
    for i, char in enumerate(pattern):
        if i == 0:
            hi, zi, nf, ng = [], -1, 0, []
        else:
            c = h[f] + [f] + g
            assert c == list(range(z[f] + 1, i)), ("C not a range", pattern, i)
            same = char == pattern[f]
            hi = c if same else g
            zi = z[f] if same else f
            d = (g if same else c) + [i]
            assert d == list(range(d[0], i + 1)), ("D not a range", pattern, i)
            nf, ng = d[0], d[1:]
        assert g == list(range(f + 1, i))
        assert hi == list(range(zi + 1, i))
        h.append(hi)
        z.append(zi)
        f, g = nf, ng
    return h, f, g + [len(pattern)]


def match_ranges(pattern, text):
    h, failure, reset_front = build_ranges(pattern)
    m = len(pattern)
    cur, front = 0, list(range(1, m + 1))
    for char in text:
        d = front if char == pattern[cur] else h[cur] + [cur] + front
        cur, front = d[0], d[1:]
        assert front == list(range(cur + 1, m + 1)), ("front not the suffix range", pattern, text)
        if cur == m:
            cur, front = failure, reset_front
            assert front == list(range(cur + 1, m + 1))


def range_lemma(max_pat=12, max_pair_pat=7, max_text=8):
    n = 0
    for m in range(1, max_pat + 1):
        for p in words(m):
            build_ranges(p)
            n += 1
    pairs = 0
    for m in range(1, max_pair_pat + 1):
        for p in words(m):
            for k in range(max_text + 1):
                for t in words(k):
                    match_ranges(p, t)
                    pairs += 1
    print(f"range lemma: every g_i, h_i, C, D is a range on {n} patterns <= {max_pat}; "
          f"front is the suffix range on {pairs} pattern/text pairs")


# ---- 2. flat storage cost -----------------------------------------------------------------

def flat_cost(pattern):
    h, _, _ = build_ranges(pattern)
    return sum(len(x) for x in h)


def flat_costs(max_m=16):
    print("flat storage: sum |h_i| (cells copied to store every gap flat)")
    for m in (4, 8, 16, 32, 64, 128, 256):
        print(f"  a^{m}: {flat_cost('a' * m)}  (m(m-1)/2 = {m * (m - 1) // 2})")
    worst = {}
    for m in range(1, max_m + 1):
        worst[m] = max((flat_cost(p), p) for p in words(m))
    for m in (8, 12, 16):
        print(f"  worst over all patterns of length {m}: {worst[m][0]} at {worst[m][1]}")


# ---- 3. nested snapshots: lazy unnesting cost --------------------------------------------

class Flat:
    __slots__ = ("elems",)

    def __init__(self, elems):
        self.elems = elems


class Cat:
    __slots__ = ("parts", "depth")

    def __init__(self, parts):
        self.parts = parts
        self.depth = 1 + max((p.depth if isinstance(p, Cat) else 0) for p in parts)


def snapshot(live):
    if len(live) == 1:
        return live[0]
    return Cat(tuple(live))


def pop(live):
    """pop the first element of a chunk list; returns (element, new list, chunk steps)"""
    steps = 0
    live = list(live)
    while True:
        head = live[0]
        steps += 1
        if isinstance(head, Cat):
            live = list(head.parts) + live[1:]
        else:
            x = head.elems[0]
            rest = head.elems[1:]
            live = ([Flat(rest)] if rest else []) + live[1:]
            return x, live, steps


class Stats:
    def __init__(self):
        self.max_pop = 0
        self.total = 0
        self.pops = 0
        self.max_depth = 0
        self.max_chunks = 0

    def note(self, steps, live):
        self.max_pop = max(self.max_pop, steps)
        self.total += steps
        self.pops += 1
        self.max_chunks = max(self.max_chunks, len(live))


def build_chunks(pattern, st):
    """the build of plan §4.2 with the live sequence as a chunk list and every h_i a snapshot"""
    h, z = [], []
    f, g = 0, []
    for i, char in enumerate(pattern):
        if i == 0:
            hi, zi, nf, ng = Flat(()), -1, 0, []
        else:
            c = [h[f]] + [Flat((f,))] + g if h[f].__class__ is Cat or h[f].elems else [Flat((f,))] + g
            same = char == pattern[f]
            hi = snapshot(c) if same else snapshot(g) if g else Flat(())
            zi = z[f] if same else f
            d = (g if same else c) + [Flat((i,))]
            nf, ng, steps = pop(d)
            st.note(steps, ng)
        if isinstance(hi, Cat):
            st.max_depth = max(st.max_depth, hi.depth)
        h.append(hi)
        z.append(zi)
        f, g = nf, ng
    return h, f, g + [Flat((len(pattern),))]


def match_chunks(pattern, text, st):
    h, failure, reset_front = build_chunks(pattern, st)
    m = len(pattern)
    cur, front = 0, [Flat(tuple(range(1, m + 1)))]
    for char in text:
        if char == pattern[cur]:
            d = front
        else:
            hc = h[cur]
            d = ([hc] if (hc.__class__ is Cat or hc.elems) else []) + [Flat((cur,))] + front
        cur, front, steps = pop(d)
        st.note(steps, front)
        if cur == m:
            cur, front = failure, reset_front


def fib(n):
    a, b = "a", "ab"
    while len(b) < n:
        a, b = b, b + a
    return b[:n]


def thue(n):
    return "".join("a" if bin(i).count("1") % 2 == 0 else "b" for i in range(n))


def nested_costs():
    print("nested snapshots: chunk steps per pop (1 = a flat head)")
    for m in range(2, 15, 2):
        st = Stats()
        worst = (0, "")
        for p in words(m):
            s2 = Stats()
            build_chunks(p, s2)
            if s2.max_pop > worst[0]:
                worst = (s2.max_pop, p)
            st.max_pop = max(st.max_pop, s2.max_pop)
            st.total += s2.total
            st.pops += s2.pops
            st.max_depth = max(st.max_depth, s2.max_depth)
        print(f"  build, all patterns of length {m}: max pop steps {st.max_pop} at {worst[1]}, "
              f"mean {st.total / st.pops:.2f}, max snapshot depth {st.max_depth}")
    rnd = random.Random(7)
    for m in (16, 64, 256, 1024):
        fams = {"a^m b": "a" * (m - 1) + "b", "(ab)^k": ("ab" * m)[:m], "fib": fib(m), "thue": thue(m),
                "a^k b a^k": "a" * (m // 2) + "b" + "a" * (m - m // 2 - 1)}
        for name, p in fams.items():
            st = Stats()
            text = p * 3 + "".join(rnd.choice("ab") for _ in range(m))
            match_chunks(p, text, st)
            print(f"  m={m:5d} {name:10s}: max pop steps {st.max_pop:4d}, mean {st.total / st.pops:.2f}, "
                  f"max live chunks {st.max_chunks}, max snapshot depth {st.max_depth}")


if __name__ == "__main__":
    range_lemma()
    flat_costs()
    nested_costs()
