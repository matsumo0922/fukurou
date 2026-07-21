## ADDED Requirements

### Requirement: Read-only boundary

replay の実行経路は記録済みデータの読み取りだけを行う。system SHALL NOT ledger、`positions`、`orders`、`executions`、`paper_account`、`command_event_log`、`runtime_config_*` を含むいかなる table へ書き込む。system SHALL NOT production API の状態変更 endpoint を呼ぶ。system SHALL NOT runtime config を変更する。

TTL replay の経路は、system SHALL NOT 外部取引所 API を呼ぶ。trailing replay の経路は ATR 再構成のため candle を read-only で取得してよいが、system SHALL その取得に DB write を行わない audit sink を用い、取得によって `command_event_log` を含むいかなる table も書き換えない。

#### Scenario: Replay attempts no write

- **WHEN** いずれかの replay スクリプトが完走する
- **THEN** DB への write 文が 1 件も発行されず、対象テーブルの内容は実行前後で同一である

#### Scenario: Read-only role is sufficient

- **WHEN** read-only 権限の DB role で replay を実行する
- **THEN** 権限エラーなく完走する

#### Scenario: TTL replay contacts no exchange

- **WHEN** TTL replay が完走する
- **THEN** 外部取引所への HTTP request が 1 件も発行されない

#### Scenario: Trailing candle fetch writes nothing

- **WHEN** trailing replay が candle を取得する
- **THEN** その取得は audit を含むいかなる DB table も書き換えない

### Requirement: Fidelity is declared and narrowly scoped

replay 出力は行ごとに fidelity を自己申告する。system SHALL 各行に `fidelity` を含める。

`EXACT` は次のいずれかだけを意味する。system SHALL NOT これ以外の意味で `EXACT` を主張する。

- TTL replay: 論理期限が記録済みの値と一致し、約定を発火させた receipt が記録済み execution の source 情報と一致する
- trailing の壊滅的 tail: stored fact (実際の最安値と初期リスク) だけから計算される

`APPROXIMATE` は再構成を含むことを意味する。`APPROXIMATE` の行は `basis` と `usage` を併記する。system SHALL NOT `APPROXIMATE` の行を「実際にこう約定した」と読める語で表現する。

#### Scenario: TTL EXACT reproduces the recorded outcome

- **WHEN** 実約定した既知 order 1 件に対し、記録済みの TTL を与えて replay する
- **THEN** 約定を発火させた receipt が記録済み execution の source session、source sequence、source price と一致し、約定価格と手数料が記録済み execution と一致する

#### Scenario: Recorded expiry is reproduced

- **WHEN** 失効した既知 order 1 件に対し、記録済みの TTL を与えて replay する
- **THEN** 算出された論理期限が記録済みの論理期限と一致する

#### Scenario: Trailing ranking declares APPROXIMATE

- **WHEN** trailing の候補 exit ランキングを出力する
- **THEN** 各行は `fidelity` が `APPROXIMATE`、`usage` が相対順位づけに限る旨を含む

### Requirement: Targets whose exactness cannot be guaranteed become unknown

厳密性が崩れる対象を確定した結果へ変換しない。system SHALL 次を `UNKNOWN` とし、理由を区別して出力する。system SHALL NOT これらを約定・失効・exit のいずれかへ確定させる。

- **処理時刻の曖昧性**: 候補期限の前後いずれか 1 poll 間隔以内に約定条件を満たす receipt が存在する対象 (処理時刻が保存されないため、失効と約定の先後を決定できない)
- **同価格 order の queue 結合**: 対象の生存区間と重なる同一指値の別 order が存在する対象
- **LLM time stop の未解決**: time stop を解決できない対象
- **価格経路の欠損**: trailing で、receipt から復元した生存中の最高値が保存済みの真の最高値に満たない対象
- **ATR 取得不能**: trailing で ATR 再構成用 candle を取得できない対象

#### Scenario: A fill lands inside the expiry ambiguity window

- **WHEN** 候補期限の前後いずれか 1 poll 間隔以内に約定条件を満たす receipt が存在する
- **THEN** その対象は処理時刻の曖昧性を理由に `UNKNOWN` となる

#### Scenario: A same-price sibling order overlaps the target

- **WHEN** 対象の生存区間と重なる同一指値の別 order が存在する
- **THEN** その対象は queue 結合を理由に `UNKNOWN` となる

#### Scenario: Receipt path misses a REST-observed high

- **WHEN** trailing 対象で、receipt から復元した生存中の最高値が保存済みの真の最高値に満たない
- **THEN** その対象は価格経路の欠損を理由に `UNKNOWN` となり、候補 exit を確定しない

#### Scenario: ATR candles are unavailable

- **WHEN** trailing 対象の ATR 再構成用 candle を取得できない
- **THEN** その対象は `UNKNOWN` となり、代替値で exit を確定しない

### Requirement: TTL counterfactual over the receipt journal

TTL replay は、記録済みの resting LIMIT entry order に対し、指値を記録済みの値に固定したまま TTL 候補ごとの約定 / 失効を再計算する。system SHALL 判定入力を `paper_market_event_receipts` の trade event に限り、production と同じ queue consumption 規則を用いる。約定は、`side` が SELL かつ価格が指値以下である receipt の数量累積和が、記録済みの発注時 queue と注文数量の和に達した時点で成立する。

system SHALL 実効期限を、候補 TTL による期限と記録済みの LLM time stop のうち早い方とする。system SHALL time stop を保存済みの実値から解決し、記録済みの期限区分値のみに依拠しない。system SHALL 記録済みの eligibility 境界より前の receipt で約定させない。

system SHALL 各 order の約定レイテンシ (発注から約定条件成立までの経過) を出力する。system SHALL 本 replay が各 order を独立に変更した反実仮想であり、TTL 候補を全 order へ一律適用した結果ではない旨を明記する。

#### Scenario: Cumulative volume reaches the queue threshold

- **WHEN** 該当 receipt の数量累積和が発注時 queue と注文数量の和に達する
- **THEN** その時点の receipt で約定が成立する

#### Scenario: Candidate TTL exceeds the recorded time stop

- **WHEN** 候補 TTL による期限が記録済みの LLM time stop より遅い
- **THEN** 実効期限は LLM time stop となり、候補 TTL まで延長されない

#### Scenario: Receipts before the eligibility boundary are ignored

- **WHEN** eligibility 境界より前の receipt が約定条件を満たす
- **THEN** その receipt では約定が成立しない

### Requirement: Trailing catastrophic tail from stored facts

trailing 壊滅的 tail 確認は、記録済みの closed long position に対し、実際の最大逆行を stored fact だけから計算する。system SHALL 初期リスクを、平均約定価格と最古 entry order の保護 STOP 価格の差から復元する。system SHALL 実際の最大逆行を、平均約定価格と `positions.lowest_price_since_entry_jpy` の差から計算する。system SHALL NOT close で NULL 化される決済直前の stop 値を用いる。

system SHALL 最大逆行が初期リスクの指定倍数を超えた trade の件数を `EXACT` として出力する。

#### Scenario: Actual excursion breaches the catastrophic threshold

- **WHEN** ある closed position の実際の最大逆行が初期リスクの指定倍数を超える
- **THEN** その trade が壊滅的 tail 件数に計上され、その行は `fidelity` が `EXACT` である

#### Scenario: Initial risk uses the entry order stop

- **WHEN** closed position の初期リスクを復元する
- **THEN** 復元は entry order の保護 STOP 価格を用い、close で NULL 化された position 列を用いない

### Requirement: Trailing candidate exit ranking with gated reconstruction

trailing 候補 exit ランキングは、記録済みの closed long position に対し、trailing の起動条件 (即時 / 0.5R / 1R 到達後) と ATR 係数の組ごとの exit を再計算する。system SHALL trailing stop を production と同じ `highestPriceSinceEntry − ATR × 係数` の tick step floor と単調 tighten 規則で算出する。

system SHALL receipt から復元した生存中の最高値が保存済みの真の最高値と一致する対象に限りランキングを出力する。system SHALL ATR を date 指定で再取得した 5 分足から再構成する。system SHALL この再構成が production の ATR 値と厳密一致しないことを申告し、出力を候補間の相対順位づけに限定する。

system SHALL 一致対象で replay の exit が production より早くならない (より緩い) 側へ一方向に偏ることを出力に明記する。

#### Scenario: Candidates are ranked, not asserted

- **WHEN** 複数の起動条件と係数の組が指定される
- **THEN** 出力は組ごとの相対順位を示し、各行は `APPROXIMATE` と偏りの向きを申告する

#### Scenario: Current parameters preserve ordering

- **WHEN** 価格経路が一致する既知の closed trade に現行の起動条件と係数を与えて replay する
- **THEN** 再計算された exit は記録済み exit と同じ方向であり、他候補との相対順位が保たれる

### Requirement: Cohort is derived from lineage, independent of replay input availability

system SHALL cohort を `orders` / `executions` / `positions` の lineage から既存の導出規則で決める。system SHALL NOT receipt の有無を cohort 判定に使う。system SHALL 生存区間に receipt を持たない対象へ、cohort とは独立に入力欠如の状態を付与し、約定有無を推定しない。system SHALL cohort ごとに分離して集計し、現行以外の cohort を現行の集計へ含めない。

#### Scenario: A current-cohort order has no receipts

- **WHEN** 現行 lineage を持つ order の生存区間に receipt が 1 件も存在しない
- **THEN** cohort は現行のまま、入力欠如として出力され、約定有無を推定されない

#### Scenario: Legacy cohort is reported separately

- **WHEN** 出力が cohort 横断の対象を含む
- **THEN** 現行 cohort の集計は他 cohort の対象を母数にも分子にも含めない

### Requirement: Gap and receipt discontinuity remain unknown

system SHALL 対象の生存区間が market data gap または infrastructure gap と交差する場合、その対象を `UNKNOWN` とする。system SHALL 両 gap を別系統として投影する。

receipt の欠落判定に、system SHALL 接続 session 内の source sequence の連続性を用いる。system SHALL NOT 全順序 ordinal の欠番を欠落と解釈する。

#### Scenario: Target intersects a market data gap

- **WHEN** 対象の生存区間が market data gap と交差する
- **THEN** その対象は `UNKNOWN` となる

#### Scenario: Ordinal has a legitimate hole

- **WHEN** 全順序 ordinal に欠番があるが、session 内の source sequence は連続している
- **THEN** その区間は欠落と判定されず、対象は `UNKNOWN` にならない

### Requirement: Bounded scope and no silent truncation

system SHALL 対象期間の指定を必須とする。system SHALL 対象件数の上限を設ける。上限を超えた場合、system SHALL 結果を打ち切らず run 全体を失敗させる。gap の投影が件数上限により失敗した場合、system SHALL 部分結果を出力せず run 全体の失敗として扱い、run 全体の失敗と対象ごとの `UNKNOWN` を出力上で区別する。

#### Scenario: Target count exceeds the limit

- **WHEN** 指定期間の対象件数が上限を超える
- **THEN** run は失敗し、一部の対象だけを出力しない

#### Scenario: Gap projection fails

- **WHEN** gap の件数が投影の上限を超えて query が失敗する
- **THEN** run 全体が失敗し、対象ごとの結果を出力しない

### Requirement: Population counts are disclosed

system SHALL eligible 件数と、`UNKNOWN` の件数を理由ごとに分けて開示する。system SHALL 入力欠如の件数を開示する。

#### Scenario: Replay completes

- **WHEN** いずれかの replay が完走する
- **THEN** 出力は eligible 件数、理由別の `UNKNOWN` 件数、入力欠如の件数を明示する
