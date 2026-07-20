## ADDED Requirements

### Requirement: Read-only and external-call-free boundary

replay の実行経路は記録済みデータの読み取りだけを行う。system SHALL NOT ledger、`positions`、`orders`、`executions`、`paper_account`、`command_event_log`、`runtime_config_*` を含むいかなる table へ書き込む。system SHALL NOT production API の状態変更 endpoint を呼ぶ。system SHALL NOT runtime config を変更する。

system SHALL NOT 外部取引所 API を呼ぶ。取引所 API client は request audit を通じて `command_event_log` へ書きうるため、replay はこれを依存に含めない。

#### Scenario: Replay attempts no write

- **WHEN** replay スクリプトが完走する
- **THEN** DB への write 文が 1 件も発行されず、対象テーブルの内容は実行前後で同一である

#### Scenario: Read-only role is sufficient

- **WHEN** read-only 権限の DB role で replay を実行する
- **THEN** 権限エラーなく完走する

#### Scenario: No exchange API is contacted

- **WHEN** replay スクリプトが完走する
- **THEN** 外部取引所への HTTP request が 1 件も発行されない

### Requirement: Fidelity is declared and narrowly scoped

replay 出力は行ごとに fidelity を自己申告する。system SHALL 各出力行に `fidelity` を含める。

`EXACT` は次の 2 点が記録済み事実と一致することだけを意味する。system SHALL NOT これ以外の意味で `EXACT` を主張する。

1. 論理期限が記録済みの値と一致する
2. 約定を発火させた receipt が記録済み execution の source 情報と一致する

厳密性を保証できない対象について、system SHALL `EXACT` を主張せず `UNKNOWN` として出力する。

#### Scenario: Recorded parameters reproduce the recorded outcome

- **WHEN** 実約定した既知 order 1 件に対し、記録済みの TTL を与えて replay する
- **THEN** 約定を発火させた receipt が記録済み execution の source session、source sequence、source price と一致し、約定価格と手数料が記録済み execution と一致する

#### Scenario: Recorded expiry is reproduced

- **WHEN** 失効した既知 order 1 件に対し、記録済みの TTL を与えて replay する
- **THEN** 算出された論理期限が記録済みの論理期限と一致する

### Requirement: Targets whose exactness cannot be guaranteed become unknown

厳密性が崩れる対象を確定した結果へ変換しない。system SHALL 次の対象を `UNKNOWN` とし、理由を区別して出力する。system SHALL NOT これらの対象を約定とも失効とも判定する。

- **処理時刻の曖昧性**: 失効判定に使われる処理時刻は保存されない。候補期限の直前 1 poll 間隔以内に約定条件を満たす receipt が存在する対象は、production が失効と約定のどちらを先に観測したか決定できない。
- **同価格 order の queue 結合**: 発注時点の queue は同一指値の自 open order 数量を含む。対象の生存区間と重なる同一指値の別 order が存在する場合、候補 TTL の適用でその重なりが変化しうるため、記録済み queue を流用できない。
- **LLM time stop の未解決**: 実効期限は候補 TTL による期限と LLM time stop の早い方である。time stop を解決できない対象は期限を確定できない。

#### Scenario: A fill lands inside the expiry ambiguity window

- **WHEN** 候補期限の直前 1 poll 間隔以内に約定条件を満たす receipt が存在する
- **THEN** その対象は処理時刻の曖昧性を理由に `UNKNOWN` となり、約定とも失効とも判定されない

#### Scenario: A same-price sibling order overlaps the target

- **WHEN** 対象の生存区間と重なる同一指値の別 order が存在する
- **THEN** その対象は queue 結合を理由に `UNKNOWN` となる

#### Scenario: Expiry is outside the ambiguity window

- **WHEN** 候補期限の前後 1 poll 間隔以内に約定条件を満たす receipt が存在せず、同価格の重なりも無い
- **THEN** その対象は `EXACT` として約定または失効が確定する

### Requirement: Effective expiry combines the candidate TTL and the recorded time stop

system SHALL 実効期限を、候補 TTL による期限と記録済みの LLM time stop のうち早い方とする。system SHALL time stop を保存済みの実値から解決する。記録済みの期限区分値のみに依拠して time stop の存在を判断してはならない。

#### Scenario: Candidate TTL exceeds the recorded time stop

- **WHEN** 候補 TTL による期限が記録済みの LLM time stop より遅い
- **THEN** 実効期限は LLM time stop となり、候補 TTL まで延長されない

#### Scenario: Time stop cannot be resolved

- **WHEN** 対象の time stop を解決できない
- **THEN** その対象は `UNKNOWN` となり、候補 TTL をそのまま実効期限としない

### Requirement: Fill follows the production queue consumption rule

system SHALL production と同じ queue consumption 規則を用いる。約定は、`side` が SELL かつ価格が指値以下である receipt の数量累積和が、記録済みの発注時 queue と注文数量の和に達した時点で成立する。

system SHALL 記録済みの eligibility 境界より前の receipt で約定を成立させない。

system SHALL 指値を記録済みの値に固定する。指値を変えた反実仮想を出力してはならない。

#### Scenario: Cumulative volume reaches the queue threshold

- **WHEN** 該当 receipt の数量累積和が発注時 queue と注文数量の和に達する
- **THEN** その時点の receipt で約定が成立する

#### Scenario: Receipts before the eligibility boundary are ignored

- **WHEN** eligibility 境界より前の receipt が約定条件を満たす
- **THEN** その receipt では約定が成立しない

### Requirement: Cohort is derived from lineage, independent of replay input availability

system SHALL cohort を `orders` / `executions` / `positions` の lineage から既存の導出規則で決める。system SHALL NOT receipt の有無を cohort の判定に使う。

system SHALL 生存区間に receipt を持たない対象へ、cohort とは独立に入力欠如の状態を付与し、約定有無を推定しない。

system SHALL cohort ごとに分離して集計する。system SHALL NOT 現行以外の cohort の対象を現行 cohort の集計へ含める。

#### Scenario: A current-cohort order has no receipts

- **WHEN** 現行 lineage を持つ order の生存区間に receipt が 1 件も存在しない
- **THEN** cohort は現行のまま、入力欠如として出力され、約定有無を推定されない

#### Scenario: Legacy cohort is reported separately

- **WHEN** 出力が cohort 横断の対象を含む
- **THEN** 現行 cohort の集計は他 cohort の対象を母数にも分子にも含めない

### Requirement: Gap and receipt discontinuity remain unknown

system SHALL 対象の生存区間が market data gap または infrastructure gap と交差する場合、その対象を `UNKNOWN` とする。system SHALL 両 gap を別系統として投影する。

receipt の欠落判定に、system SHALL 接続 session 内の source sequence の連続性を用いる。system SHALL NOT 全順序 ordinal の欠番を欠落と解釈する。ordinal は永続 sequence 由来であり、正当な欠番を含むためである。

#### Scenario: Target intersects a market data gap

- **WHEN** 対象の生存区間が market data gap と交差する
- **THEN** その対象は `UNKNOWN` となり、約定とも失効とも判定されない

#### Scenario: Ordinal has a legitimate hole

- **WHEN** 全順序 ordinal に欠番があるが、session 内の source sequence は連続している
- **THEN** その区間は欠落と判定されず、対象は `UNKNOWN` にならない

#### Scenario: Source sequence is discontinuous

- **WHEN** session 内の source sequence に欠落がある
- **THEN** その区間を跨ぐ対象は `UNKNOWN` となる

### Requirement: Bounded scope and no silent truncation

system SHALL 対象期間の指定を必須とする。system SHALL 対象件数の上限を設ける。上限を超えた場合、system SHALL 結果を打ち切らず run 全体を失敗させる。

gap の投影が件数上限により失敗した場合、system SHALL 部分結果を出力せず run 全体の失敗として扱う。system SHALL run 全体の失敗と対象ごとの `UNKNOWN` を出力上で区別する。

#### Scenario: Target count exceeds the limit

- **WHEN** 指定期間の対象件数が上限を超える
- **THEN** run は失敗し、一部の対象だけを出力しない

#### Scenario: Gap projection fails

- **WHEN** gap の件数が投影の上限を超えて query が失敗する
- **THEN** run 全体が失敗し、対象ごとの結果を出力しない

### Requirement: Population counts are disclosed

system SHALL eligible 件数と、`UNKNOWN` の件数を理由ごとに分けて開示する。system SHALL 入力欠如の件数を開示する。

#### Scenario: Replay completes

- **WHEN** replay が完走する
- **THEN** 出力は eligible 件数、理由別の `UNKNOWN` 件数、入力欠如の件数を明示する

### Requirement: Per-order independence is disclosed

本 replay は各 order を独立に変更した場合の反実仮想であり、候補 TTL を全 order へ一律適用した場合の結果ではない。system SHALL この限定を出力に明記する。

#### Scenario: Output states its interpretation

- **WHEN** replay が完走する
- **THEN** 出力は各 order を独立に変更した反実仮想である旨を含む
