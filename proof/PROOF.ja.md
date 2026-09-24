# `PAL ∈ PEG` の証明案内

*English: [PROOF.md](PROOF.md)*

この文書は `PalPeg.PalInPeg.unconditional : RecognizedByTotalPEG PAL` の証明の流れを説明する。
最終定理から機械へと上から下へ進み、各段についてファイル名と定理名を挙げる。ファイルは
`PalPeg/` 以下にある。ここで挙げる定理はすべて標準公理だけを使う。

## 1. 主張

- `PalPeg.PAL : Language (Fin 2) := { w | w.reverse = w }`（`Basic.lean`）。
- `PegSeparation.RecognizedByTotalPEG L` は Kim–Park の成果物で定義されている。`Fin 2` 上のある
  PEG `G` が次の両方を満たす、という主張である。
  - `IsLoffTotal`：`G` は全域的（どの入力でも停止する）。
  - すべての `w` について `G.Recognizes w ↔ w ∈ L`。

最終定理は `PalInPegFinal.lean` にある。

```lean
theorem unconditional : RecognizedByTotalPEG PAL :=
  pal_in_peg_of_fast startupFast_all flagsFast
```

同じファイルで `evenPal_in_peg : RecognizedByTotalPEG EvenPal` も示す（`EvenPal = { u uᴿ }`）。
`PAL` と偶数長の語からなる正規言語との共通部分を取る（`EvenLength.lean`）。これで
Loff–Moreira–Reis の予想 7 が否定される。

## 2. 自動機械から PEG へ

PEG は手で書かない。Kim–Park の成果物が形式化した Loff–Moreira–Reis の意味での
*scaffolding automaton*（SCA）を構成し、PEG は成果物に作らせる。

| 段 | ファイル | 定理 |
|---|---|---|
| SCA → 全域 PEG。Kim–Park の `SCAToPEG.loffBackward`（逆転言語の PEG を与える）と `PAL_reverse_mem` を使う | `PalInPegSca.lean` | `pal_recognizedByTotalPEG_of_sca` |
| 型付き SCA（状態とラベルを構造のある型で書いたもの）→ Kim–Park の `Fin` 添字の SCA | `ScaTyped.lean` | `pal_in_peg_of_typed` |
| 有限制御・永続スタック `K` 本・1 文字ごとに半径有界の *局所的* スタックプログラム 1 本を持つ機械 → 型付き SCA | `ScaStackMachine.lean`、`ScaLocal.lean`、`ScaProg.lean` | コンパイラ `ScaStackMachine` |
| 1 文字ずつ進む抽象機械、表現関係 `Rep`、各歩を模倣する 1 文字ごとのスタックプログラム → `PAL ∈ PEG` | `ScaEncode.lean` | `ScaEncode.pal_in_peg` |

したがって残りの証明が用意すべきものは次の 3 つである。

- `PAL` を 1 文字ずつ判定する抽象機械。
- その機械を模倣する、1 文字ごとのスタックプログラム。
- 模倣が保つ表現不変量。

## 3. 抽象機械：window-pal 制御器

機械は Scala 生成器 `GenerateWindowPal`（`ScaffoldWindowPal.scala`）が出すものそのもので、
`ScaWindowPal.lean` に写してある。機械は次の 3 つからなる。

- 2 つの **二進段**（dyadic stage）を持つ制御器。
- 段ごとに 1 つの **照合器**（matcher）ワーカー。
- 段ごとに 1 つの **フラグ**（flag）ワーカー。

1 tick に 1 文字を読む。

**なぜ `PAL` を判定できるか。** `|w| ≥ 4` とし、`W = stageOf |w| = 2^(⌊log₂|w|⌋−1)` と置く。
このとき `2W ≤ |w| < 4W` で、`w` が回文であることは次の 2 条件がともに成り立つことと同値である。

- (a) `(w.take W)ᴿ` が `w` の接尾辞である。
- (b) 中央部 `w[W, |w|−W)` が回文である。

`ScaWindowOutput.window_correct` は、次の 2 つの約束のもとで制御器がちょうど `PAL` を受理する
ことを示す。

- 答える段の照合器が (a) を出力する。
- その段の `middle` ビットが (b) である。

`|w| < 4` は制御器が自分で処理する。

**段の運び**（`ScaWindowSchedule.lean`）。

- 段は tick `2^j` にスロット `idx j` で生まれる。
- 生まれた時点で、照合器はパターン `(w.take 2^j)ᴿ`（それまでに読んだ文字列の逆）で動き始める。
- フラグワーカーは tick `2^j + (r+1)·2^(j−1)`（`r < 4`）に 4 つの仕事を出す。
- `answering_run` は長さ `|w|` で答える段を特定する。それは `stageOf |w|` に生まれた段である。

残る義務は `ScaWindowReal.pal_in_peg_of_real_promises` にまとまっている。どれも *実際の*
ワーカーについての主張である。

- `hmatch`：答える照合器の出力が (a) である。
- `FlagsContract`：各フラグ仕事が次の放出より前に正しいビットで回収される。
  `ScaWindowPlumbing` がここから `middle` ビット (b) と「制御器の違反なし」を導く。
- `hworkers`：どのワーカーも fault しない。

## 4. 制御器をスタックに符号化する

`ScaWindowEncode.lean`、`ScaWindowEncodeTick.lean`、`ScaWorkerEnc.lean` が次を与える。

- 1 tick 分のスタックプログラム `letterProg`。
- ワーカーが fault しない限りそれが `tick` を模倣するという証明 `sim_tick`。
- 2 種類の実ワーカーのスタック符号化（`matcherEnc`、`flagsEnc`）。

これらの符号化では、ヘッドは永続スタックに置き、カウンタとフラグは単項スタックと真偽値スタックに
置く（`ScaHeadRep.lean`、`ScaProgEmbed.lean`）。

## 5. ワーカーとそのヘッド VM

**ワーカー。**

- ワーカーは Scala の表そのものである（`ScaGsTables.lean`）。照合器の表は 294 行、フラグの表は
  492 行。
- 表はジェネレータ風のコルーチンを Scala がコンパイルしたもので、コルーチンは
  `ScaGsCoroutine.lean` に写してある。
- `ScaGsCertData.lean` は `decide +kernel` で証明書を検査し、表と写したコルーチンが一致することを
  示す。結果は `ScaGsCertFunctional.matcher_certified` と `flags_certified`。
- ワーカーは 1 tick に決まった行数だけ表を実行する。これを *サービス量子* と呼ぶ。照合器は
  2048、フラグは 32768。ワーカーの意味論は `ScaWindowWorker.lean` と `ScaWindowInstance.lean`。

**ヘッド VM。** 推論は論理的な *ヘッド VM*（`ScaHeadVM.lean`）の上で行う。これは語
`pattern ++ text` 上の名前付きヘッド位置を持ち、制御はコルーチンである。

- `ScaWorkerLink.service_link`（照合器）と `ScaFlagsLink.service_link`（フラグ）は、実ワーカーの
  サービス量子 1 回分がヘッド VM の同じ歩数に等しいことを示す。
- どちらも、VM が毎歩保つべき側条件（`StepSide`）のもとで成り立つ。
  - 読み出しはパターン境界の正しい側で行う。
  - `available` は順方向のヘッドを調べる。
  - 逆方向のヘッドはパターンの中に留まる。
  - `match` は到着の最前線で起きる。
- これらの接続に必要な表の事実（読み手の生存性）は、`ScaMatcherReaders.lean` と
  `ScaFlagsReaders.lean` で証明書から示す。
- レジスタ不変量は `ScaWorkerRegs.lean`。

## 6. 照合器

### 6.1 リストの水準

これらのファイルはそれ以前の開発から再利用している。

- **分解。**
  - `GSPreprocess.decompose x k` は Galil–Seiferas 分解 `x = u v` である。`v` は一意な最短の
    `k`-反復周期 `p₁` と到達長 `r` を持つ。
  - `GSDecomposeL1.lean` は `k = 8` について検証器が必要とする性質を示す。
  - `ScaMatcherAnswer.lean` は周期がない場合の最初の周期を正規化し（`normP1`）、側条件
    `ShiftDeadline`、`KSimple`、L1 を示す。
- **実時間検証器。**
  - `GSVerifier.vStep` は検証器の 1 歩。Galil–Seiferas のシフトで `v` を走査し、短い接頭辞 `u` を
    1 歩あたり 2 回の比較で検査する。
  - `GSDrained.orbit` は `(⟨|u|, 0⟩, 0)` から始まるその軌道。
  - `GSReportDeadline.orbit_deadlineInv`：`v` が照合し終わった時点で、接頭辞検査は完了しているか、
    本物の不一致で失敗しているかのどちらかである。
- **答え。** `ScaMatcherAnswer.answer_iff_occursAt`：軌道上に `n = |T|` で終わる一致イベントがある
  ことと、`x` が `T` の接尾辞であることは同値。

### 6.2 ヘッドの水準

- **ガード付きの歩**（`ScaHeadSafe.lean`）。
  - `stepS` / `iterS` は側条件でガードしたヘッド VM の歩。ガード付きの run はすべての状態で側条件を
    運ぶ（`iterS_safe`）。
  - ガード付きの run は文字の到着と可換でもある（`iterS_append`）。したがって run の途中で文字が
    届いてもよい。
- **主ループの部品。**
  - `ScaShiftSafe.lean`：シフトの生成器。
  - `ScaMatcherLoop.lean`：ループ頭の各場合、すなわち一致、不一致、`v` を完了する一致（報告する）。
  - `ScaMatcherRun.seg`：1 つの *区間*。軌道状態 `z` を符号化したループ頭から、次の文字が届いて
    いれば、VM は `vStep^[d] z`（`d ∈ {1, 2}`）のループ頭に着く。
- **歩数。** 区間はポテンシャル `Φ = (k+1)·pos + q` で支払う。
  - `k = 8` で `n + 35·Φ(z) ≤ 35·Φ(z')`。
  - したがって 1 文字あたり主ループの歩数は高々 `35 · 9 = 315` で、量子 2048 を下回る。
- **時刻**（`ScaMatcherTick.lean`）。
  - 報告は、その出現の最後の文字の tick の間に起きなければならない。そうでなければワーカーは
    fault する（`match` は `B` が最前線にあることを要求するから）。
  - `Cur` は歩と到着をまたいで VM を追う。基準 `(gr, pr)` を記録する。これは VM が最後に待ちを
    抜けたスロットと、そこでのポテンシャルである。
  - 基準以降 VM は遊ばないので、`g + 35·pr ≤ gr + 35·Φ + e`。
  - `refOK_wait` はポテンシャルの算術である。`L` 文字での待ちのあと、`n` で終わる報告には
    `315(n − L) − 304` 歩以内に着き、これは `2048(n − L)` 未満である。
  - `cur_step` は 1 歩で側条件と `Cur` を保つ。
  - `cur_live` は、tick `L` の終わりまでに `L` までに終わる報告がすべて出ていることを示す。
- **起動。**
  - `ScaHeadDecompose.lean` / `ScaDecomposeSafe.decompose_runS`：ヘッドの `Decompose` は
    `1698·|x| + 229` 歩以内に `decompose x 8` を返す。
  - `ScaMatcherStart.lean`：コピーと、最初のループ頭までのオフセット歩き。
  - `refOK_start` は最初のループ頭での時刻の基準。
- **一生。**
  - `ScaMatcherLife.lean` / `ScaMatcherLife2.lean` は各照合器スロットを制御器に沿って追う。
    スロットは `2^j` に逆転接頭辞の上で生まれ、以降の文字を与えられる。
  - `ScaMatcherLifeSafe.lifeSafe'`：どの一生もすべての量子を安全に保つので、照合器は fault しない。
  - `tick_output_last`：答える照合器の `|w|` での出力は、`|w|` で終わる報告があるかどうかを表す。

## 7. フラグワーカー

- `ScaFlagsHead.flags_head_gs_palindromes`：フラグのヘッドプログラムは、`y` の長さ `up − 1` から
  `lo` までの接頭辞の回文ビットを `8098·|y| + 10` 歩以内に計算する。
- `ScaFlagsLife.flagsContract`：各仕事が `32768·S` 歩以内に停止するなら、仕事・放出・回収から
  `FlagsContract` が出て、フラグワーカーは fault しない。
- `ScaFlagsJob.lean` が 2 つの主張をつなぐ。

## 8. 2 つの歩数の事実

`ScaWindowFast.pal_in_peg_of_fast` は §6–§7 から `hmatch`、`FlagsContract`、`hworkers` を組み立てる。
残るのは 2 つの歩数の事実で、`PalInPegFinal.lean` が証明済みの線形上界から算術で両方を放電する。

| 事実 | 必要な上界 | 証明した上界 |
|---|---|---|
| `startupFast_all`：照合器の起動がサイト 3 に着くまで | `2013·|x| + 32·s + 2063` 歩 | `1698·|x| + 230`（`start_pre`） |
| `flagsFast`：`|y| = (r+1)·S ≤ 4S` のフラグ仕事が停止するまで | `32768·S` 歩 | `8098·|y| + 10`（`flags_head_gs_palindromes`。停止時刻は一意、`ScaFlagsLife.halt_unique`） |

必要な上界はサービス量子で決まる。

- **証明が使う量子。** 証明は量子 2048 と 32768 を使う（Scala では `GsBatchClock.VERIFIED_BATCH`、
  `GenerateWindowPal --verified`）。
- **Scala の既定値。** 既定値（512 と 1024）はもっと細かい勘定から導かれている。実測でも足りて
  いる。分解で見つけた最悪は 1 文字あたり約 104 歩で、フラグ仕事は予算の高々約 0.37 しか使わない。
- **既定値を証明するには。** Lean で既定値を証明するには、分解の仕事量についてもっと鋭い償却上界が
  要る。
- **範囲。** Lean の証明が扱うのは検証済みの量子である。既定値については実測しかない。

## 9. 補足

- **Scala への忠実さ。** ワーカーは Scala の表そのもの（証明書で検査済み）。制御器は
  `ScaffoldWindowPal.scala` をフィールドごとに写している（`ScaWindowPal.lean` と
  `ScaWindowWorker.lean` の docstring を参照）。Scala の既定の出力との意図的な違いは上の量子だけ。
- **出力された文法。** Lean の定理の PEG は Kim–Park の `loffBackward` から得る。Scala 生成器が出力する
  明示的な文法は別の変換器（`SymbolicSca2Peg`）を通っており、検証していない。
- **Galil の機械との関係。** この構成は Galil の実時間回文認識機械を形式化したものではない。
  二進段の中で、Galil–Seiferas の実時間文字列照合（条件 (a)）と、まとめて計算する回文フラグ
  （条件 (b)）を組み合わせている。118 本テープの実時間 TM を経由する以前の経路は
  このリポジトリに含めていない。実時間 TM からの条件付き定理 `Existence.pal_in_peg_of_realTime` は
  含めている。
- **規模。** 78 モジュール、約 50,000 行。これに Mathlib と Kim–Park の成果物が加わる。
