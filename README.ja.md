# pal-peg — Lean 4 で機械検査した `PAL ∈ PEG`

[English](README.md) | **日本語**

このリポジトリには、2 値の回文の言語

```
PAL = { w ∈ {0,1}* | wᴿ = w }
```

が **全域的な** 構文解析表現文法（PEG）で認識されることの、機械検査済みの証明がある。証明に仮定は
ない。あわせて、同じ構成の明示的な PEG を出力する Scala の生成器もある。

```lean
theorem PalPeg.PalInPeg.unconditional : PegSeparation.RecognizedByTotalPEG PalPeg.PAL
theorem PalPeg.PalInPeg.evenPal_in_peg : PegSeparation.RecognizedByTotalPEG PalPeg.EvenPal
```

- どちらの定理も標準公理 `propext`・`Classical.choice`・`Quot.sound` だけに依存する。
- 2 つ目の定理は、偶数長の回文 `{ w wᴿ }` が PEG に入ることを言う。これは Loff, Moreira and Reis
  (2020) の予想 7 を否定する。
- PEG のモデルと SCA → PEG の定理は、Kim と Park の Lean 成果物（arXiv:2608.29592、
  Zenodo 10.5281/zenodo.22099762）による。

## 構成

| ディレクトリ | 内容 |
|---|---|
| [`proof/`](proof/) | 証明本体。Lean 4.31、Mathlib、Kim–Park の成果物を使う。まず [`proof/README.ja.md`](proof/README.ja.md)、次に [`proof/PROOF.ja.md`](proof/PROOF.ja.md) を読む。 |
| [`generator/`](generator/) | Scala 3 の生成器。*window-pal* 構成の明示的な PEG を出力し、圧縮し、実行器で検査する。 |
| [`runner/`](runner/) | Rust 製の素の PEG 実行器（packrat、回文のための仕掛けは持たない）と、生成された文法を速く圧縮する道具。 |

## 証明の検査

```sh
cd proof
lake exe cache get     # 初回: Mathlib のビルド済みキャッシュを取得
lake build PalPeg      # 証明をビルドし、公理監査 PalPeg/Axioms.lean も走らせる
```

## PEG の出力

```sh
cd generator
sbt "runMain pal.GenerateWindowPal --verified /tmp/pal.peg"     # Lean の証明と同じ予算
sbt "runMain pal.CompactScaffoldPeg /tmp/pal.peg /tmp/pal-compact.peg"
cargo build --release --manifest-path ../runner/Cargo.toml
sbt "runMain pal.VerifyWindowPal /tmp/pal-compact.peg --runner ../runner/target/release/plain-peg-runner --log /tmp/verify.log"
```

文法全体は数百万規則ある。生成には長い時間と大きなヒープが要る。Scala 生成器のメモリ使用量の
最大値はまだ測っていない。

Lean の定理が扱うのは自動機械であって、この出力された文法ではない。証明は自動機械から Kim–Park の
構成で PEG を得る。生成器は独自の SCA → PEG 変換器を使っており、有限個の入力で試験しているが
検証はしていない。

## テスト

```sh
make test    # lake build（証明と公理監査）、sbt test（生成器）、cargo test（実行器）
```

## ライセンス

MIT（[`LICENSE`](LICENSE) を参照）。依存物（Mathlib と Kim–Park の成果物）は Lake が取得するもので、それぞれのライセンスに従う。
