"""Isolated mathematical model, not PAL2's interpreter or PEG compiler.
Checks a proposed flags iterator from KMP's longest palindromic prefix.
No production source is modified. Pass an optional exhaustive max length.
"""
from itertools import product
import sys


def check(y, r, S):
    m = len(y)
    lo = r * S
    pi = [0] * m
    for i in range(1, m):
        k = pi[i - 1]
        while k and y[k] != y[i]:
            k = pi[k - 1]
        if y[k] == y[i]:
            k += 1
        pi[i] = k
    k = 0
    rem = S
    overflow = False
    distance = 0

    def lose(n):
        nonlocal rem, overflow, distance
        for _ in range(n):
            distance += 1
            if rem:
                rem -= 1
            else:
                overflow = True

    # Root mismatch consumes a text letter without growing the match.
    # Every unit of a fallback shortens the match by one.
    for c in y[::-1]:
        while k and (k == m or y[k] != c):
            nxt = pi[k - 1]
            lose(k - nxt)
            k = nxt
        if k < m and y[k] == c:
            k += 1
        else:
            lose(1)
    L = k
    assert distance == m - L
    assert rem == max(L - lo, 0)
    assert overflow == (L < lo)
    G = []
    cnt = 0
    units = 0
    if rem == 0:
        pf = not overflow
    else:
        while True:
            d = k - pi[k - 1]
            gw = d
            built = 0
            # In the pointer machine: decrement gw by below(gw),
            # decrement rem by prevIn(rem), push one UNARY onto built.
            while rem and gw:
                rem -= 1
                gw -= 1
                built += 1
                units += 1
            if rem == 0:
                if gw == 0:
                    # A palindromic prefix lies exactly at lo.
                    G.insert(0, d)
                    cnt = 0
                    pf = True
                else:
                    # lo lies strictly between two palindromic prefixes.
                    cnt = built
                    pf = False
                break
            assert gw == 0
            G.insert(0, d)
            k = pi[k - 1]
    assert units == max(L - lo, 0) <= S
    for length in range(lo, m + 1):
        assert pf == (y[:length] == y[:length][::-1]), (y, r, S, length, G, cnt, pf)
        # Exact integer analogue of Flags.advance / LpsPal.advance.
        if pf:
            if G:
                d = G.pop(0)
                cnt = d - 1
                pf = cnt == 0
            else:
                cnt = 0
                pf = False
        elif cnt:
            cnt -= 1
            pf = cnt == 0


def main(max_len=14):
    count = 0
    for m in range(1, max_len + 1):
        for tup in product('ab', repeat=m):
            y = ''.join(tup)
            for r in range(4):
                if m % (r + 1) == 0:
                    check(y, r, m // (r + 1))
                    count += 1
    print(f'PASS: {count:,} (binary y,r,S) cases, lengths 1..{max_len}, m=(r+1)S; every flag lo..m inclusive')


if __name__ == '__main__':
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 14)
