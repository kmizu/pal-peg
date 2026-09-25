"""Raw words for the Rust plain-PEG runner: the three families of the rate search at lengths from the first
machine answer up to `max_len`, with their expected verdicts. Usage: python3 probes/runner_words.py <first> <max_len>
Prints `word expected` per line (expected = 1 for a palindrome)."""
import random
import sys


def tm(n):
    return "".join("a" if bin(i).count("1") % 2 == 0 else "b" for i in range(n))


def pal(k):
    h = tm(k // 2)
    return h + ("a" if k % 2 else "") + h[::-1]


def flip(w, i):
    return w[:i] + ("b" if w[i] == "a" else "a") + w[i + 1:]


def words(first, max_len):
    rnd = random.Random(5)
    out = []
    n = first
    while n <= max_len:
        for nn in (n - 1, n, n + 1):
            if nn < first or nn > max_len:
                continue
            out.append("b" + "a" * (nn - 2) + "b")                       # adversarial start-up
            out.append("b" + "a" * (nn - 3) + "bb" if nn > 3 else "bab")  # its one-flip non-palindrome
            p = pal(nn)
            out.append(p)                                                   # Thue–Morse palindrome
            out.append(flip(p, nn // 3))                                    # one flipped letter (non-centre)
            out.append(("aab" * nn)[:nn])                                   # period 3
            i = max(1, nn // 4)
            j = nn - 2 * i - 2
            if j >= 0:
                out.append("a" * i + "b" + "a" * j + "b" + "a" * i)          # a^i b a^j b a^i
            half = "".join(rnd.choice("ab") for _ in range(nn // 2))
            out.append(half + ("a" if nn % 2 else "") + half[::-1])         # random palindrome
        n = n * 2 if n >= 96 else n + (n // 2 or 1)
    seen = set()
    res = []
    for w in out:
        if w not in seen:
            seen.add(w)
            res.append(w)
    return res


if __name__ == "__main__":
    first, max_len = int(sys.argv[1]), int(sys.argv[2])
    for w in words(first, max_len):
        print(w, 1 if w == w[::-1] else 0)
