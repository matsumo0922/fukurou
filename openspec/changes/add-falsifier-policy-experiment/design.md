## Context

Issue #207 は Falsifier の correctness fix と strategy experiment を別 rollback 単位に分けている。
Phase 1 の stacked parent PR は Falsifier を read-only にし、deterministic preview を runner 所有へ戻す。
Phase 2 は同じ execution path のまま Falsifier 起動 policy だけを期間ごとに切り替える。

既存の `llm_runs`、phase manifest、command event は runtime config version ID / hash を記録する。
一方、active snapshot のどの値が Falsifier policy かを直接集計できず、OFF を表すために偽の `APPROVED` falsification を保存すると paper truth を歪める。

## Goals / Non-Goals

**Goals**

- 常時 ON、OFF、条件起動を version 付き policy として next-restart で切り替える
- 同じ run 内で policy 判定を一度だけ行い、Falsifier 起動と SafetyFloor gate を一致させる
- OFF / 条件非該当を falsification verdict と混同せず監査する
- 後続の期間比較が policy decision を intent 単位で一意に参照できる

**Non-Goals**

- runtime activation の自動化
- 期間の途中での adaptive threshold 変更
- Falsifier の model / prompt / provider の変更
- shadow 結果から paper fill を遡及生成すること
- rejected-intent shadow と descriptive comparison。reviewer APPROVED 後の次の stacked change で扱う

## Decisions

### 1. Policy 名が semantic version を兼ねる

runtime key `decisionProtocol.falsifierPolicy` は次の enum だけを受け入れる。

- `ALWAYS_ON_V1`
- `OFF_V1`
- `CONDITIONAL_V1`

default は既存挙動を維持する `ALWAYS_ON_V1`。
閾値や入力意味を変える場合は既存名を再利用せず、新しい version を追加する。
run は既存の runtime config version ID / hash を保持し、entry policy 判定 event は enum 名を保持する。

### 2. CONDITIONAL_V1 は三つの deterministic predicate の OR

entry intent 保存後、runner は一度だけ次を評価する。

1. planned risk が SafetyFloor の最大 1 trade risk の 50% 以上
2. TradePlan setup tag が `regime:trend_up` 以外、または regime tag が欠ける
3. current cohort の直近 2 closed trade がともに post-cost loss

一つでも該当すれば Falsifier を起動する。
active account epoch / current cohort の最新 2 closed position を `closed_at DESC, position_id DESC` で先に固定する。
2 件未満、attribution missing、infrastructure / market-data gap、execution semantics 不一致、または読み取り失敗が一つでもあれば `RECENT_OUTCOME_UNKNOWN` として Falsifier を起動する。
unknown row を飛ばして古い eligible trade へ遡ってはならない。
regime は `regime:trend_up` / `regime:trend_down` / `regime:range` / `regime:unknown` の exact tag を prompt で要求し、欠損・未知 tag は不明として起動側に倒す。

planned risk は、同じ order command と `SafetyFloorContext` に対して `SafetyFloorRiskCalculator.placeOrderRiskDetails` が返す `groupRiskAfterOrderJpy / maxRiskPerTradeJpy` を使う。
これにより MARKET の ask、slippage、volatility、cost reserve と ADD_LONG の merge 後 group risk を SafetyFloor と一致させる。
runner は取引 mutation を行わない internal risk-assessment path からこの snapshot を取得する。
risk assessment、recent outcome の取得失敗は Falsifier 起動側に倒す。

### 3. Policy decision は falsification と分離して durable に保存する

runner は intent ID を unique key にする `falsifier_policy_decisions` へ次を保存する。

- policy decision ID
- policy version
- `required`
- reason code の集合
- intent ID
- runtime config version ID / hash

reason code は bounded enum とし、raw prompt、自由文、価格、secret を保存しない。
同じ intent / 同じ payload の retry は既存 record を返し、異なる payload は conflict として fail closed する。
durable record の commit 後に、同じ ID を持つ `FALSIFIER_POLICY_EVALUATED` command event を append する。
event append に失敗した場合は entry を行わず、同じ policy decision ID で retry できる。
Falsifier を起動しない場合も falsifications row を作らない。

### 4. SafetyFloor bypass は runner 内部の型だけで表す

`PlaceOrderCommand` に、MCP wire schema から設定できない sealed な Falsifier policy permit を追加する。
permit は policy decision ID、intent ID、policy version、runtime config hash を保持する。
既定は permit なしの `REQUIRED` とし、runner が durable decision と event append 成功を確認した command だけが `NOT_REQUIRED_BY_POLICY` permit を持つ。

SafetyFloor snapshot は durable policy decision を含む。
SafetyFloor は permit の全 identity が command intent と snapshot の durable decision に一致することを検証する。
どちらでも persisted intent の存在、未消費、command との完全一致を検証する。
`REQUIRED` の場合だけ fresh `APPROVED` を追加で要求する。
OFF / 条件非該当でも最大損失、損切り、ナンピン、drawdown、exposure、EV、cost など他の SafetyFloor rule は変えない。

### 5. 期間 attribution は observed policy decision を正本にする

runtime activation は next restart の予定境界であり、実効 policy の証拠には使わない。
後続比較は各 intent の durable policy decision と、その runtime config version ID / hash を正本にする。
activation から新 policy decision が初めて観測されるまでの run、policy decision 欠損、run と decision の config identity 不一致は `UNKNOWN` とする。
daemon と manual trigger の config 解決経路が異なっても、active version の時刻から policy を推測しない。

## Risks / Trade-offs

- [OFF が Falsifier gate を弱める] → paper mode の strategy experiment に限定し、他の SafetyFloor rule と intent integrity を維持する。production activation はこの PR に含めない
- [setup tag が欠けて条件起動が常時化する] → prompt に bounded regime tag を要求し、欠損は監査 reason として起動側へ倒す
- [recent outcome query failure が launch cost を増やす] → fail-safe で Falsifier を起動し、policy event に unknown reason を残す
- [durable decision 後に event append が失敗する] → mutation を行わず、同じ decision ID の idempotent retry だけを許可する

## Rollback Plan

runtime config を `ALWAYS_ON_V1` の version へ戻して process を restart する。
既存 falsification、policy decision、policy event、runtime version は append-only の監査として残す。
schema destructive rollback や既存履歴の書き換えは行わない。
