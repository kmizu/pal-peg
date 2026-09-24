# `proof/` — Lean 4 による `PAL ∈ PEG`

*English: [README.md](README.md)*

2 値の回文の言語

```
PAL = { w ∈ {0,1}* | wᴿ = w }
```

が **全域的な** 構文解析表現文法（PEG）で認識されることを、仮定なしに Lean 4 で証明する。
系として偶数長の回文 `{ w wᴿ }` も認識され、これは Loff, Moreira and Reis (2020) の予想 7 を
否定する。

## 主結果

```lean
theorem PalPeg.PalInPeg.unconditional : PegSeparation.RecognizedByTotalPEG PalPeg.PAL
theorem PalPeg.PalInPeg.evenPal_in_peg : PegSeparation.RecognizedByTotalPEG PalPeg.EvenPal
```

どちらも `PalPeg/PalInPegFinal.lean` にある。

- どちらも `#print axioms` の結果は `[propext, Classical.choice, Quot.sound]`、すなわち Lean と
  Mathlib の標準公理だけである。
- `sorry`・`admit`・`native_decide` は使わず、`axiom` も宣言していない。
- `PalPeg/Axioms.lean` がこれらの公理リストを `#guard_msgs` で固定しているので、穴があればビルドが
  失敗する。

`RecognizedByTotalPEG L` は Kim–Park の成果物（`PegSeparation/Common/Recognition.lean`）の定義で、
非終端記号の個数 `n` と PEG `G` が存在して次を満たす、という主張である。

- `G.IsLoffTotal`：`G` はどの入力でも停止する。
- `∀ w, G.Recognizes w ↔ w ∈ L`。

`PalPeg.EvenPal` は `{ w | ∃ u, w = u ++ uᴿ }`（`PalPeg/EvenLength.lean`）。

## 検査のしかた

```sh
cd proof
lake exe cache get     # 初回: Mathlib のビルド済みキャッシュを取得
lake build PalPeg      # 公理監査 PalPeg/Axioms.lean を含む
```

公理を自分で表示するには：

```sh
cd proof
printf 'import PalPeg.PalInPegFinal\n#print axioms PalPeg.PalInPeg.unconditional\n' > /tmp/Check.lean
lake env lean /tmp/Check.lean
# 'PalPeg.PalInPeg.unconditional' depends on axioms: [propext, Classical.choice, Quot.sound]
```

ツールチェーン：Lean v4.31.0 と Mathlib v4.31.0（`lean-toolchain` と `lakefile.toml` で固定）。

## 信頼すべきもの

1. Lean カーネルと Mathlib。
2. Kim–Park の成果物 `PegSeparation`（Zenodo 10.5281/zenodo.22099762、コミット `c364edb`）。
   使うのは次の 2 つ。
   - PEG と scaffolding automaton（SCA）の定義とその意味論。`RecognizedByTotalPEG` と
     `RecognizedBySCA` を含む。
   - 定理 `SCAToPEG.loffBackward`：どの SCA にも、それが受理する語の逆転を認識する全域 PEG がある。
3. 私たちの定義 `PalPeg.PAL := { w | w.reverse = w }`（`PalPeg/Basic.lean`）。

機械・ワーカー・Galil–Seiferas 照合アルゴリズムはすべて証明の内側にあるので、信頼する必要はない。

## 証明の流れ

要するに、`PAL` の scaffolding automaton を構成し、`SCAToPEG.loffBackward` と `PALᴿ = PAL` で
全域 PEG に変える。

その自動機械は、このリポジトリの Scala 生成器が明示的な PEG にコンパイルするものである
（[`../generator`](../generator) の `GenerateWindowPal`、*window-pal* 構成）。Lean ではこの構成を部品ごとに写した。
機械は 1 tick に 1 文字を読み、次のものを有限制御と有界個の永続スタックに持つ。

- **2 つの二進段**：`|w| ≥ 4` かつ `W = 2^(⌊log₂|w|⌋−1)` のとき、`w` が回文であることは次の
  2 条件と同値である。
  - `(w.take W)ᴿ` が `w` の接尾辞である。
  - 中央部 `w[W, |w|−W)` が回文である。
- **段ごとの照合器**：逆転した接頭辞を語の残りに対して Galil–Seiferas の実時間文字列照合で探す。
- **段ごとのフラグワーカー**：段が始まってから読んだ文字列のどの接頭辞が回文かを、1 段あたり
  4 つの仕事で計算する。中央部 `w[W, |w|−W)` はそうした接頭辞の 1 つである。

ワーカーは Scala の表そのもので、照合器が 294 命令、フラグが 492 命令。1 tick に決まった数の命令を
実行する（照合器 2048、フラグワーカー 32768）。

[PROOF.ja.md](PROOF.ja.md) が層ごとに証明をたどり、各段をファイルに対応づけている。

## 明示的な PEG との関係

[`../generator`](../generator) の Scala 生成器は、同じ自動機械の明示的な PEG を出力する。ただし自動機械を PEG に
変える部分は独自の変換器（`SymbolicSca2Peg` と規則の inline 展開）で、Kim–Park の `loffBackward` では
ない。

- **証明したこと。** Lean の定理は自動機械についてのもので、その PEG は `loffBackward` から得る。
  出力された文法そのものは検証していない。出力された文法が `PAL` を認識するという Lean の定理はない。
- **予算。** `GenerateWindowPal --verified` は Lean の証明と同じ 1 tick あたりの命令予算を使う
  （`GsBatchClock.VERIFIED_BATCH`）。
- **既定の予算。** 既定の予算（512 と 1024）の裏づけは実測だけである。見つけた最悪は 1 文字あたり
  約 104 歩で、予算は約 470。Lean の証明が大きい予算を使うのは、証明した歩数の上界がゆるいから
  である（分解は `1698·|x| + 230` 歩以内、フラグ仕事は `8098·|y| + 10` 歩以内）。

## 構成

| パス | 内容 |
|---|---|
| `PalPeg/PalInPegFinal.lean` | 主定理、予想 7 の系、2 つの歩数の事実 |
| `PalPeg/Sca*.lean` | window-pal 構成、そのワーカー、ヘッド VM、SCA への符号化 |
| `PalPeg/GS*.lean`、`Matching`、`Words`、`Stages`、`Assembly` など | リストの水準の理論：Galil–Seiferas の分解と実時間検証、二進段、語の組合せ論 |
| `PalPeg/PalInPegSca.lean` | Kim–Park による SCA → PEG |
| `PalPeg/Existence.lean`、`PalPeg/EvenLength.lean` | 以前の条件付き定理（実時間 TM から）と予想 7 の議論 |
| `PalPeg/Axioms.lean` | 公理監査 |

このリポジトリには最終的な証明だけを置いている。以前の経路や実験（118 本テープの実時間 TM を経由する経路など）は
含めていない。
