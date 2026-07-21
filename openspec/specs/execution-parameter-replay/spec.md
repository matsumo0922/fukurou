# execution-parameter-replay Specification

## Purpose
TBD - created by archiving change execution-replay-scripts. Update Purpose after archive.
## Requirements
### Requirement: Read-only boundary over a single snapshot

replay の実行経路は記録済みデータの読み取りだけを行う。system SHALL NOT ledger、`positions`、`orders`、`executions`、`paper_account`、`command_event_log`、`runtime_config_*` を含むいかなる table へ書き込む。system SHALL NOT production API の状態変更 endpoint を呼ぶ。system SHALL NOT runtime config を変更する。system SHALL NOT 外部取引所 API を呼ぶ。

system SHALL 全入力を単一の read-only transaction snapshot から読み、query 間で異なる snapshot を混ぜない。

#### Scenario: Replay attempts no write

- **WHEN** いずれかのスクリプトが完走する
- **THEN** DB への write 文が 1 件も発行されず、対象テーブルの内容は実行前後で同一である

#### Scenario: No exchange API is contacted

- **WHEN** いずれかのスクリプトが完走する
- **THEN** 外部取引所への HTTP request が 1 件も発行されない

#### Scenario: Inputs share one snapshot

- **WHEN** 実行中に production が新しい receipt を追加する
- **THEN** replay は開始時 snapshot だけを見て、追加分を混ぜた組合せを結果に出さない

### Requirement: Fidelity is declared and narrowly scoped

replay 出力は行ごとに fidelity を自己申告する。system SHALL 各行に `fidelity` を含める。

`EXACT` は次のいずれかだけを意味する。system SHALL NOT これ以外の意味で `EXACT` を主張する。

- market 応答レイテンシ (発注から、約定を発火させた market event の socket 受信までの経過) が記録済み execution から一意に決まる
- 短縮 TTL が約定を確実に取りこぼすこと (candidate の論理期限が、約定を発火させた market event の socket 受信時刻以下) が一意に決まる

position の逆行は台帳事実として出力し、market が到達した最安値そのものとは表現しない。

#### Scenario: Market-response latency is exact

- **WHEN** 約定した既知 order の market 応答レイテンシを出力する
- **THEN** レイテンシは約定を発火させた market event の socket 受信時刻と発注時刻の差であり、その行は `fidelity` が `EXACT` である

#### Scenario: A shorter cutoff before the market response is a confirmed drop

- **WHEN** 短縮 TTL の論理期限が、約定を発火させた market event の socket 受信時刻以下である
- **THEN** その候補は約定を確実に取りこぼす (DROPPED) と `EXACT` で判定される

### Requirement: TTL counterfactual is shortening-only, and only confirmed drops are exact

system SHALL TTL 反実仮想の対象を、記録済み TTL 以下への短縮に限る。system SHALL NOT TTL を延長した場合の約定を出力する。

system SHALL 約定の権威を記録済み execution 行に置く。約定した order について、候補 TTL の論理期限 `E' = created_at + T'` を、約定を発火させた market event の socket 受信時刻 `executed_at` と比較する。`E' ≤ executed_at` なら約定を確実に取りこぼす (DROPPED) と EXACT で判定する。

失効判定に用いる処理 wall-clock は保存されず、WS event 経路と独立な REST 周期 tick 経路の両方で評価され、socket 受信から無上限に遅延しうる。したがって `E' > executed_at` の候補について、system SHALL RETAINED を主張せず `RETENTION_UNCONFIRMED` で `UNKNOWN` とする。

system SHALL 実効期限を、候補 TTL による期限と記録済み LLM time stop のうち早い方とし、time stop を保存済みの実値から解決する。

#### Scenario: A shorter cutoff before the market response drops the fill

- **WHEN** 記録済みでは約定した order に、約定を発火させた market event の socket 受信時刻以下で失効する短縮 TTL を与える
- **THEN** その候補は約定を確実に取りこぼす (DROPPED) と `EXACT` で判定される

#### Scenario: A cutoff after the market response is unconfirmed

- **WHEN** 短縮 TTL の論理期限が、約定を発火させた market event の socket 受信時刻より後にある
- **THEN** その候補は `RETENTION_UNCONFIRMED` で `UNKNOWN` となり、RETAINED を主張されない

#### Scenario: Lengthening is refused

- **WHEN** 記録済み TTL より長い候補が要求される
- **THEN** その候補は評価対象にならない

#### Scenario: Time stop dominates

- **WHEN** 短縮候補による期限が記録済み LLM time stop より後にある
- **THEN** 実効期限は LLM time stop となる

### Requirement: No fill is synthesized for orders without a recorded execution

system SHALL NOT execution 行を持たない order に約定を主張する。queue 到達後に production の hard-halt ゲートまたは resting-entry fill invariant で棄却され、execution 行を持たず `cancel_reason` を持つ (`expired_at` は NULL) CANCELED order を、system SHALL `NON_TTL_TERMINAL` として分類し、TTL retention の母数から除外する。

system SHALL queue consumption 規則を fill 生成に使わず、記録済み execution の source receipt との整合を検証する cross-check にのみ用いる。

#### Scenario: An order was cancelled by a safety gate

- **WHEN** queue 閾値に到達したが安全ゲートで棄却され、execution 行を持たず `cancel_reason` を持つ order を処理する
- **THEN** その order は `NON_TTL_TERMINAL` として分類され、約定を主張されず、TTL retention の母数に入らない

#### Scenario: Queue rule does not generate a fill

- **WHEN** ある order の queue 累積が閾値へ到達したが execution 行が存在しない
- **THEN** system はその order を約定として出力しない

### Requirement: Tail is computed from stored facts and labeled as ledger-recorded

tail 事実シートは、記録済み position に対し実際の逆行を stored fact から集計する。system SHALL 初期リスクを既存 evaluation と同じ fill-weighted stop から復元する。system SHALL 実際の逆行を平均約定価格と `positions.lowest_price_since_entry_jpy` の差から求める。system SHALL この最安値が exit fill slippage を含む台帳値である旨を出力に明記し、market が到達した最安値そのものとは表現しない。

system SHALL 最安値または初期リスクの基準が欠ける position、および fill-weighted stop が average entry 以上で risk width が非正になる position を `TAIL_BASIS_UNAVAILABLE` で `UNKNOWN` とする。system SHALL 部分決済で基準数量が変わる position にその旨を注記する。system SHALL 逆行が初期リスクの指定倍数を超えた件数を出力する。

#### Scenario: Adverse excursion breaches the threshold

- **WHEN** ある position の逆行が初期リスクの指定倍数を超える
- **THEN** その position が壊滅的 tail 件数に計上され、出力に台帳値である旨が付く

#### Scenario: Initial risk uses the fill-weighted stop

- **WHEN** pyramiding を含む position の初期リスクを復元する
- **THEN** 復元は fill-weighted stop を用い、同時点に存在しない価格の組合せを作らない

#### Scenario: Tail basis is missing

- **WHEN** position の最安値または entry stop が null、あるいは risk width が非正である
- **THEN** その position は `TAIL_BASIS_UNAVAILABLE` で `UNKNOWN` となる

### Requirement: Cohort is derived from lineage, independent of replay input availability

system SHALL cohort を `orders` / `executions` / `positions` の lineage から既存の導出規則で決める。system SHALL NOT receipt の有無を cohort 判定に使う。system SHALL 生存区間に receipt を持たない対象へ、cohort とは独立に入力欠如の状態を付与し、約定有無を推定しない。system SHALL cohort ごとに分離して集計し、現行以外の cohort を現行の集計へ含めない。

#### Scenario: A current-cohort order has no receipts

- **WHEN** 現行 lineage を持つ order の生存区間に receipt が 1 件も存在しない
- **THEN** cohort は現行のまま、入力欠如として出力され、約定有無を推定されない

#### Scenario: Legacy cohort is reported separately

- **WHEN** 出力が cohort 横断の対象を含む
- **THEN** 現行 cohort の集計は他 cohort の対象を母数にも分子にも含めない

### Requirement: Gap and receipt discontinuity remain unknown

system SHALL 対象の生存区間が market data gap または infrastructure gap と交差する場合、その対象を `UNKNOWN` とする。system SHALL 両 gap を別系統として投影する。receipt の欠落判定に、system SHALL 接続 session 内の source sequence の連続性を用いる。system SHALL NOT 全順序 ordinal の欠番を欠落と解釈する。

#### Scenario: Target intersects a market data gap

- **WHEN** 対象の生存区間が market data gap と交差する
- **THEN** その対象は `UNKNOWN` となる

#### Scenario: Ordinal has a legitimate hole

- **WHEN** 全順序 ordinal に欠番があるが、session 内の source sequence は連続している
- **THEN** その区間は欠落と判定されず、対象は `UNKNOWN` にならない

### Requirement: Bounded scope with indexed receipt access and no silent truncation

system SHALL 対象期間の指定を必須とする。system SHALL receipt を対象 order が跨ぐ session と source sequence 範囲で駆動して読み、全 receipt の無索引 time scan を発行しない。system SHALL 対象件数の上限と statement timeout を設ける。上限を超えた場合、system SHALL 結果を打ち切らず run 全体を失敗させる。gap の投影が件数上限により失敗した場合、system SHALL 部分結果を出力せず run 全体の失敗として扱い、run 全体の失敗と対象ごとの `UNKNOWN` を区別する。

#### Scenario: Target count exceeds the limit

- **WHEN** 指定期間の対象件数が上限を超える
- **THEN** run は失敗し、一部の対象だけを出力しない

#### Scenario: Receipts are read by session and sequence

- **WHEN** 対象 order の receipt を読む
- **THEN** 読み取りは対象 order が跨ぐ session と source sequence 範囲に限られ、全 receipt を time predicate で scan しない

### Requirement: Population counts are disclosed

system SHALL eligible 件数と、`UNKNOWN` の件数を理由ごとに分けて開示する。system SHALL 入力欠如の件数、`NON_TTL_TERMINAL` の件数、および snapshot 時点で OPEN の order (`OPEN_AT_SNAPSHOT`) の件数を開示する。system SHALL いかなる終端状態の対象も母数開示から黙って落とさない。system SHALL 各 order を独立に変更した反実仮想である旨を出力に含める。

#### Scenario: Replay completes

- **WHEN** いずれかのスクリプトが完走する
- **THEN** 出力は eligible 件数、理由別の `UNKNOWN` 件数、入力欠如・`NON_TTL_TERMINAL`・`OPEN_AT_SNAPSHOT` の件数を明示する

#### Scenario: An order is still open at snapshot

- **WHEN** window 終端付近の order が execution・`expired_at`・`cancel_reason` のいずれも持たない
- **THEN** その order は `OPEN_AT_SNAPSHOT` として開示され、約定を主張されない

