# Checks for the three parts of the repository.

LAKE_ENV = command -v lake >/dev/null 2>&1 || . $$HOME/.elan/env;

.PHONY: test proof generator runner

test: proof generator runner

# The proof and its axiom audit (PalPeg/Axioms.lean). The first run fetches the Mathlib cache.
proof:
	cd proof && $(LAKE_ENV) lake exe cache get && lake build PalPeg

generator:
	cd generator && sbt -batch test

runner:
	cargo test --offline --manifest-path runner/Cargo.toml
