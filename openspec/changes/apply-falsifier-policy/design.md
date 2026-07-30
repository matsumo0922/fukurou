## Context

Foundation は version 付き policy enum と、intent ごとに一意な `FalsifierPolicyDecision` / canonical event の原子保存を提供する。
現在の entry flow は Falsifier の fresh `APPROVED` がなければ停止し、`PlaceOrderCommand` と SafetyFloor の間に bypass authority はない。

Issue #207 は最初に Falsifier on/off を比較する。本 change はそのための attribution/permit foundation を runner に接続し、実際の gate replacement は後続 enforcement change に残す。

## Goals / Non-Goals

**Goals:**

- policy/action ごとの canonical decision attributes を決定する
- typed config と runtime snapshot identity の一致を検証する
- decision を Falsifier 起動前に durable に exact readback または保存する
- `OFF_V1` / `ENTER` 専用の immutable internal permit を生成する

**Non-Goals:**

- Falsifier の省略、SafetyFloor/Broker の gate または paper order behavior の変更
- permit の `PlaceOrderCommand` / MCP wire schema への追加
- reserved `runner-place-v2-` namespace、placement-lock 再検査、broker replay 検証、post-place outcome classification
- `CONDITIONAL_V1` の risk / regime / recent-loss 判定
- policy activation、rejected-intent shadow、評価集計、live trading

## Decisions

### 1. canonical attributes は policy と action だけから決定する

`ALWAYS_ON_V1` は action を問わず `required=true / ALWAYS_ON` とする。
`OFF_V1` の `ENTER` だけは `required=false / POLICY_OFF` とする。
`OFF_V1` の `ADD_LONG` は `required=true / ADD_LONG_REQUIRES_FALSIFIER` とする。
`CONDITIONAL_V1` は action を問わず `required=true / CONDITIONAL_NOT_APPLIED` とする。

この段階では `ADD_LONG` の group binding と conditional 判定を実装しない。したがって、どちらも既存 Falsifier gate を維持する。

### 2. runner は policy decision を exact readback してから欠損時だけ保存する

entry intent 発行後、runner は `FalsifierPolicyDecisionRepository.findFalsifierPolicyDecision(intentId)` を先に呼ぶ。
既存 decision は policy、required、reasonCodes、runtime config version/hash が canonical attributes と完全一致する場合だけ再利用する。
欠損時だけ新しい decision ID を生成し、foundation repository の原子保存を使う。

repository failure、監査片側欠損、属性不一致は no-trade とする。既存 row を現在の config に合わせて上書きしない。

### 3. config identity は typed config と実行 snapshot を照合する

runner は `RuntimeConfigCatalog.runtimeItems(tradingConfig)` の key/effective value から canonical hash を再計算する。
snapshot がある実行では hash が一致する場合だけ snapshot version ID/hash を decision に使う。
snapshot がない direct/test runner は `process-config-v1` と canonical typed hash を使う。

任意文字列の hash/version を trust しない。snapshot mismatch は decision、Falsifier、paper entry を開始しない。

### 4. OFF permit は internal data だけで表現する

runner は durable decision が `OFF_V1 / ENTER / required=false / POLICY_OFF` と完全一致する場合だけ、decision ID、intent ID、action、policy、required/reason codes、runtime config version/hash を持つ immutable internal permit を作る。

permit は runner 内部の audit と後続 enforcement への引数だけに使う。`PlaceOrderCommand`、`preview_order`、`place_order` の wire schema には含めない。この change では permit があっても Falsifier を省略せず、SafetyFloor/Broker は permit を読まない。

### 5. enforcement は別 change で原子的に導入する

後続 enforcement change は permit を command の internal-only path に束縛し、SafetyFloor の durable readback、place lock 内の open-position 再検査、`runner-place-v2-` namespace の pre-lookup validation、replay fingerprint、commit 可能性のある failure の outcome-unknown を同時に実装する。

基盤だけで Falsifier を省略することは禁止する。これにより、MCP caller が policy decision を発見しただけで bypass できない。

## Risks / Trade-offs

- [config switch 後に同じ intent を再実行する] → exact identity mismatch で no-trade にし attribution を書換えない
- [MCP caller が OFF decision を知る] → permit は wire に存在せず、現時点では既存 fresh-approval gate が残る
- [permit foundation を実験適用と誤認する] → docs に current behavior を明記し、activation と enforcement を後続に分離する

## Migration Plan

1. repository wiring、runner decision、internal permit generation を deploy する。
2. `ALWAYS_ON_V1` default のまま、全 entry が既存 Falsifier gate を通ることを確認する。
3. enforcement change が deploy されるまで `OFF_V1` / `CONDITIONAL_V1` を activate しない。
4. rollback は code を戻すだけで、保存済み decision/event を削除・書換えしない。

## Open Questions

なし。
