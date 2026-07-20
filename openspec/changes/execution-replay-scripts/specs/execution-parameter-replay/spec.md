## ADDED Requirements

### Requirement: Read-only replay boundary

replay の実行経路は記録済みデータの読み取りだけを行う。system SHALL NOT ledger、`positions`、`orders`、`executions`、`paper_account`、`runtime_config_*` を含むいかなる table へ書き込む。system SHALL NOT production API の状態変更 endpoint を呼ぶ。system SHALL NOT runtime config を変更する。replay の出力は標準出力および明示的に指定された出力 file に限る。

#### Scenario: Replay attempts no write

- **WHEN** どちらかの replay スクリプトが完走する
- **THEN** DB への write 文が 1 件も発行されず、ledger の状態は実行前後で同一である

#### Scenario: Read-only role is sufficient

- **WHEN** read-only 権限の DB role で replay を実行する
- **THEN** 権限エラーなく完走する

### Requirement: Fidelity is declared by the output itself

replay 出力は行ごとに fidelity を自己申告する。system SHALL 各出力行に `fidelity` を含め、値は `EXACT` または `APPROXIMATE` とする。`EXACT` は、production が実際に消費した因果的入力だけから再計算したことを意味する。`APPROXIMATE` は、production が消費した入力の一部が保存されておらず再構成を含むことを意味する。

`APPROXIMATE` の行は `basis` (再構成の根拠) と `usage` (許される用途) を併記する。system SHALL NOT `APPROXIMATE` の行を「実際にこう約定した」と読める語で表現する。

#### Scenario: TTL variation at the recorded limit price declares EXACT

- **WHEN** 指値を記録済みの値に固定し、記録済みの `queue_ahead_btc` を用いて TTL だけを変えた行を出力する
- **THEN** その行の `fidelity` は `EXACT` である

#### Scenario: Offset variation declares its unresolved queue

- **WHEN** 記録済みと異なる指値の行を出力する
- **THEN** その行は `fidelity` が `EXACT` ではなく、queue が未確定である旨と、約定に必要な queue の上限を含む

#### Scenario: Trailing replay declares APPROXIMATE

- **WHEN** trailing exit replay が再取得した 5 分足から ATR を再構成して exit を再計算する
- **THEN** 各行の `fidelity` は `APPROXIMATE`、`basis` は再取得 candle である旨、`usage` は候補間の相対順位づけに限る旨を含む

#### Scenario: Fidelity levels are not aggregated together

- **WHEN** 出力が集計値を含む
- **THEN** `EXACT` の行と `APPROXIMATE` の行は別々に集計され、単一の集計値へ混ぜられない

### Requirement: Cohort separation

replay 出力は execution semantics cohort を分離する。system SHALL 各対象へ `CURRENT` / `LEGACY_PRE_WS` / `UNSUPPORTED_EXECUTION_SEMANTICS` のいずれかを付与し、cohort ごとに分離して集計する。system SHALL NOT `LEGACY_PRE_WS` または `UNSUPPORTED_EXECUTION_SEMANTICS` の対象を `CURRENT` の集計へ含める。

`paper_market_event_receipts` は WebSocket execution semantics 導入以降にのみ存在するため、それ以前の対象は replay の因果的入力を持たない。system SHALL そのような対象を `NO_REPLAY_INPUT` として報告し、fill / 失効 / exit を推定しない。

#### Scenario: Legacy trade has no receipt journal

- **WHEN** 対象 order の生存区間に対応する receipt が 1 件も存在しない
- **THEN** その対象は `LEGACY_PRE_WS` cohort かつ `NO_REPLAY_INPUT` として出力され、fill 有無を推定されない

#### Scenario: Current cohort is reported separately

- **WHEN** 出力が cohort 横断の対象を含む
- **THEN** `CURRENT` の集計は `LEGACY_PRE_WS` の対象を母数にも分子にも含めない

### Requirement: Unknown periods remain unknown

market data gap と交差する対象を確定した結果へ変換しない。system SHALL 対象の因果的生存区間が `market_data_gaps` の未回復または回復済み区間と交差する場合、その対象の replay 結果を `UNKNOWN` とする。system SHALL `UNKNOWN` の対象を母集団に残し、件数を明示する。system SHALL NOT `UNKNOWN` の対象を fill、失効、exit のいずれかへ確定させる。

gap 判定は既存の `InfrastructureGapProjection` と同じ半開区間の交差規則に従う。

#### Scenario: Order lifetime intersects a market data gap

- **WHEN** resting order の生存区間が `market_data_gaps` の 1 件と交差する
- **THEN** その order の replay 結果は `UNKNOWN` となり、fill とも失効とも判定されない

#### Scenario: Unknown count is disclosed

- **WHEN** replay が完走する
- **THEN** 出力は eligible 件数、`UNKNOWN` 件数、`NO_REPLAY_INPUT` 件数をそれぞれ明示する

#### Scenario: Receipt journal has a sequence discontinuity

- **WHEN** 対象区間の receipt に `admission_ordinal` の欠落がある
- **THEN** その区間を跨ぐ対象は `UNKNOWN` として扱われ、欠落を無視した再計算をしない

### Requirement: TTL and offset counterfactual over the receipt journal

TTL×offset replay は、記録済みの resting LIMIT entry order に対し、指定された TTL と指値 offset の組ごとに約定条件を再計算する。system SHALL 判定入力を `paper_market_event_receipts` の trade event に限り、`admission_ordinal` の昇順で適用する。

system SHALL production と同じ queue consumption 規則を用いる。すなわち約定は、`side` が SELL かつ価格が指値以下である receipt の数量累積和が `queue_ahead_btc` と注文数量の和に達した時点で成立する。

system SHALL order の実効期限を、system TTL による期限と記録済みの LLM time stop のうち早い方とする。system SHALL 記録済みの `expiry_source` が `LLM_TIME_STOP` である order について、system TTL を変えても実効期限が変わらないことを保持する。

system SHALL 記録済みの eligibility 境界より前の receipt で約定を成立させない。

#### Scenario: Recorded parameters reproduce the recorded outcome

- **WHEN** 実約定した既知 order 1 件に対し、記録済みの指値と TTL を与えて replay する
- **THEN** 約定を成立させた receipt が記録済み execution の `source_sequence` および `source_price_jpy` と一致し、約定価格と手数料が記録済み execution と一致する

#### Scenario: TTL is varied at the recorded limit price

- **WHEN** 指値を記録済みの値に固定して TTL だけを変える
- **THEN** 記録済みの `queue_ahead_btc` を用いて約定 / 失効が確定し、その行は `fidelity` が `EXACT` である

#### Scenario: Recorded expiry came from the LLM time stop

- **WHEN** `expiry_source` が `LLM_TIME_STOP` の order に、より長い system TTL を与える
- **THEN** 実効期限は LLM time stop のまま変わらない

### Requirement: Offset counterfactual reports observable volume, not a binary fill

指値を記録済みの値から変えた場合、その価格レベルの queue は保存されていないため確定できない。system SHALL この場合に fill / no-fill の二値判定を出力してはならない。

system SHALL 代わりに、TTL 窓内に観測された `side` が SELL かつ価格が当該指値以下である receipt の数量累積和を出力する。system SHALL その累積和から注文数量を引いた値を、約定に必要な queue の上限として出力する。

この上限が負である場合、queue が非負であることから約定は成立しえない。system SHALL この場合に限り約定しないことを確定してよい。上限が 0 以上である場合、system SHALL 約定したとも約定しなかったとも表現せず、上限値を条件として提示する。

#### Scenario: Volume cannot cover the order size

- **WHEN** ある offset の TTL 窓内で観測された該当数量の累積和が注文数量に満たない
- **THEN** その組は約定しないと確定され、queue の値に依存しない旨が示される

#### Scenario: Volume could cover the order under some queue

- **WHEN** 累積和が注文数量を上回る
- **THEN** 出力は約定に必要な queue の上限を示し、約定したとは表現しない

#### Scenario: Counterfactual offsets are not marked EXACT

- **WHEN** 記録済みと異なる指値の行が出力される
- **THEN** その行の `fidelity` は `EXACT` ではない

#### Scenario: Multiple pairs are evaluated over one journal read

- **WHEN** 複数の TTL×offset の組が指定される
- **THEN** 各組の結果が組ごとに分離して出力される

### Requirement: Trailing exit counterfactual with declared reconstruction

trailing exit replay は、記録済みの closed trade に対し、trailing の起動条件と ATR 係数の組ごとに exit を再計算する。起動条件は即時、0.5R 到達後、1R 到達後を扱う。system SHALL trailing stop を production と同じ `highestPriceSinceEntry − ATR × 係数` の式および同じ単調 tighten 規則で算出する。

ATR14 系列および REST tick の価格経路は保存されていないため、system SHALL 価格経路を receipt journal から導出し、ATR を再取得した 5 分足から再構成する。system SHALL この再構成を `APPROXIMATE` として申告し、production が観測した ATR と一致する保証がない旨を出力に含める。

#### Scenario: Candidates are ranked, not asserted

- **WHEN** 複数の起動条件と係数の組が指定される
- **THEN** 出力は組ごとの相対順位と壊滅的 tail の有無を示し、各行は `APPROXIMATE` を申告する

#### Scenario: Current parameters preserve ordering against the recorded trade

- **WHEN** 既知の closed trade 1 件に対し現行の起動条件と係数を与えて replay する
- **THEN** 再計算された exit は記録済み exit と同じ方向であり、他候補との相対順位が保たれる

#### Scenario: Catastrophic tail is surfaced

- **WHEN** ある候補で最大逆行幅が閾値を超える trade が存在する
- **THEN** その候補の出力にその件数が明示される

#### Scenario: Candle refetch fails

- **WHEN** ATR 再構成に必要な 5 分足を取得できない
- **THEN** 対象は `UNKNOWN` となり、欠損を無視した ATR 代替値で exit を確定させない
