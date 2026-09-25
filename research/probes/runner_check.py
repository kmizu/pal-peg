"""Run the Rust plain-PEG runner on the raw words of runner_words.py (or on a file of words) and compare with the oracle.
Read-only. Usage (from research/): python3 probes/runner_check.py <grammar.peg> <first> <max_len> [words.txt]"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(__file__))
import runner_words  # noqa: E402

RUNNER = os.path.join(os.path.dirname(__file__), "..", "..", "runner", "target", "release", "plain-peg-runner")


def main():
    peg, first, max_len = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    ws = runner_words.words(first, max_len)
    if len(sys.argv) > 4:
        ws += [l.strip() for l in open(sys.argv[4]) if l.strip()]
    bad, worst_steps = 0, 0
    for i in range(0, len(ws), 20):
        chunk = ws[i:i + 20]
        out = subprocess.run([RUNNER, peg] + chunk, capture_output=True, text=True).stdout.splitlines()
        rows = [l.split("\t") for l in out if l.startswith("match")]
        assert len(rows) == len(chunk), (len(rows), len(chunk))
        for w, r in zip(chunk, rows):
            worst_steps = max(worst_steps, int(r[4].split("=")[1]))
            if (r[2] == "true") != (w == w[::-1]):
                bad += 1
                print("MISMATCH", len(w), w[:60], r[2])
    print(f"{len(ws)} words ({min(map(len, ws))}-{max(map(len, ws))} letters): {bad} mismatches; at most {worst_steps} steps per word")


if __name__ == "__main__":
    main()
