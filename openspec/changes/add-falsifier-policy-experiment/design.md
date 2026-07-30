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
- current cohort の proposal / approval / fill / post-cost outcome / unknown coverage を期間・policy 別に descriptive comparison できる

**Non-Goals**

- runtime activation の自動化
- 期間の途中での adaptive threshold 変更
- Falsifier の model / prompt / provider の変更
- shadow 結果から paper fill を遡及生成すること

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
current cohort の recent trade 読み取りに失敗した場合は `RECENT_OUTCOME_UNKNOWN` として Falsifier を起動する。
regime は `regime:trend_up` / `regime:trend_down` / `regime:range` / `regime:unknown` の exact tag を prompt で要求し、欠損・未知 tag は不明として起動側に倒す。

planned risk は `abs(entry reference price - protective stop) * size`。
MARKET entry の reference price は policy 評価時の ticker last、LIMIT / STOP は intent price を使う。
最大 1 trade risk は policy 評価時 account equity と active SafetyFloor `maxRiskPerTradeRatio` の積。
参照価格、account、recent outcome の取得失敗は Falsifier 起動側に倒す。

### 3. Policy decision は falsification と分離する

runner は `FALSIFIER_POLICY_EVALUATED` command event に次を保存する。

- policy version
- `required`
- reason code の集合
- intent ID
- runtime config version ID / hash

reason code は bounded enum とし、raw prompt、自由文、価格、secret を保存しない。
Falsifier を起動しない場合も falsifications row を作らない。

### 4. SafetyFloor bypass は runner 内部の型だけで表す

`PlaceOrderCommand` に、MCP wire schema から設定できない code-owned の Falsifier gate requirement を追加する。
既定値は `REQUIRED` とし、runner が同じ policy decision から作る command だけが `NOT_REQUIRED_BY_POLICY` を設定できる。

SafetyFloor はどちらでも persisted intent の存在、未消費、command との完全一致を検証する。
`REQUIRED` の場合だけ fresh `APPROVED` を追加で要求する。
OFF / 条件非該当でも最大損失、損切り、ナンピン、drawdown、exposure、EV、cost など他の SafetyFloor rule は変えない。

### 5. 期間比較は runtime version 境界を正本にする

比較期間は runtime config activation の `activated_at` を境界とし、各 window は重ねない。
推奨順序は Phase 1 修正版の `ALWAYS_ON_V1`、`OFF_V1`、`CONDITIONAL_V1`。
C1/C3 の window と重なる期間は採用しない。

Proposer provider / model / effort、system prompt hash、SafetyFloor policy version が window 内で固定されていることを SQL で確認し、変化した window は比較不能として分離する。
descriptive comparison は proposal、Falsifier required、approval / rejection、fill、post-cost outcome、current cohort coverage、infrastructure / market-data unknown を件数と比率で返す。
#193 の gate-shadow は TTL 失効 order の `CROSSED` / `UNKNOWN` だけを補助事実として扱い、Falsifier rejection に paper fill を遡及させない。

## Risks / Trade-offs

- [OFF が Falsifier gate を弱める] → paper mode の strategy experiment に限定し、他の SafetyFloor rule と intent integrity を維持する。production activation はこの PR に含めない
- [setup tag が欠けて条件起動が常時化する] → prompt に bounded regime tag を要求し、欠損は監査 reason として起動側へ倒す
- [recent outcome query failure が launch cost を増やす] → fail-safe で Falsifier を起動し、policy event に unknown reason を残す
- [period 間で他 config が変わる] → runtime version と prompt / SafetyFloor identity を照合し、固定できなかった window を比較不能として報告する
- [shadow を約定反実仮想と誤読する] → `CROSSED` / `UNKNOWN` の語彙を維持し、fill や outcome に変換しない

## Rollback Plan

runtime config を `ALWAYS_ON_V1` の version へ戻して process を restart する。
既存 falsification、policy event、runtime version は append-only の監査として残す。
schema destructive rollback や既存履歴の書き換えは行わない。
