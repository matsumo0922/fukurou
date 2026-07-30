## Context

Foundation は version 付き policy enum と、intent ごとに一意な `FalsifierPolicyDecision` / canonical event の原子保存を提供するが、runner と SafetyFloor はまだ参照しない。
現在の entry flow は Falsifier の fresh `APPROVED` がなければ必ず停止し、`PlaceOrderCommand` と SafetyFloor の間に policy bypass authority は存在しない。

Issue #207 は最初に Falsifier on/off を比較し、その後に conditional policy を試す。
この変更は最初の on/off 適用だけを扱い、評価期間や rejected-intent shadow を混ぜない。

## Goals / Non-Goals

**Goals:**

- `ALWAYS_ON_V1` と `OFF_V1` を paper entry flow に適用する
- policy decision を Falsifier 起動または省略より前に durable 保存する
- `OFF_V1` の省略権限を intent、decision、policy、runtime config identity に束縛する
- runner 分岐と SafetyFloor の独立検証を一致させる
- 欠損、不一致、保存失敗、読取失敗を fail closed にする

**Non-Goals:**

- `CONDITIONAL_V1` の risk / regime / recent-loss 判定
- policy の production activation
- rejected intent の反実仮想 shadow
- policy 間の成績集計や実験期間の判定
- live trading

## Decisions

### 1. on/off 適用を conditional より先に独立 PR にする

`ALWAYS_ON_V1` は `required=true / ALWAYS_ON` とする。
`OFF_V1` の `ENTER` は `required=false / POLICY_OFF`、`ADD_LONG` は `required=true / ADD_LONG_REQUIRES_FALSIFIER` とする。
`CONDITIONAL_V1` は `required=true / CONDITIONAL_NOT_APPLIED` として従来の Falsifier gate を維持する。

これにより Issue #207 の「まず on/off」の順序を守り、SafetyFloor risk 計算、regime taxonomy、closed-position attribution をこの PR に持ち込まない。
`ADD_LONG` は action と target group を durable intent に束縛し、place lock 内で再検証する後続変更まで OFF bypass の対象外にする。
conditional を推測で実装する案は paper truth を歪めるため採用しない。

### 2. runner は既存 decision を exact readback してから必要なら作る

entry intent 発行後、runner は `FalsifierPolicyDecisionRepository.findFalsifierPolicyDecision(intentId)` を先に呼ぶ。
既存 decision がある場合は、policy と action から導出した canonical `required` / `reasonCodes`、runtime config version/hash が全て完全一致する場合だけ再利用する。
欠損時だけ新しい decision ID を生成し、foundation の原子 repository で decision と event を保存する。

repository の failure、片側欠損、既存 decision の属性不一致は no-trade とする。
既存 row を現在の config に合わせて上書きする案は、intent の因果時点を改変するため採用しない。

### 3. runtime config identity は runner が実際に使う snapshot に固定する

runner は `RuntimeConfigCatalog.runtimeItems(tradingConfig)` の全 key/effective value から `calculateRuntimeConfigHash` で canonical typed config hash を再計算する。
production entry flow は、注入された `RuntimeConfigAuditSnapshot.hash` がこの canonical hash と一致する場合だけ version ID / hash を decision と event に保存する。
snapshot がない direct/test runner は version ID `process-config-v1` と同じ canonical hash を使う。
空値や任意文字列での bypass は許さない。

後から active config を再読して attribution を変更する案は、run 中の config switch と競合するため採用しない。
typed config と snapshot を照合せず別々に信用する案も、別 config の identity へ OFF 挙動を帰属できるため採用しない。

### 4. OFF bypass は internal permit と durable readback の両方を要求する

runner は `required=false` の durable decision から、次を含む immutable な internal permit を作る。

- decision ID
- intent ID
- policy
- runtime config version ID / hash

permit は runner の `PlaceOrderCommand` 構築経路だけが設定し、MCP `preview_order` / `place_order` の wire schema には追加しない。
`PaperBroker` は intent ID で durable policy decision を読み、`SafetyFloorContext` に渡す。
SafetyFloor は permit と durable decision の全 identity が一致し、policy が `OFF_V1`、`required=false`、reason が `POLICY_OFF`、action が `ENTER` の場合だけ fresh `APPROVED` の代替 authority とする。

runner の Boolean だけで Falsifier を省略する案は、別 caller や再試行時に SafetyFloor が権限を検証できないため採用しない。
durable decision だけで自動 bypass する案も、MCP caller が既存 intent ID を渡すだけで省略権限を再利用できるため採用しない。

### 5. intent integrity と他の SafetyFloor rule は変更しない

消費済み intent、intent/command payload 不一致、STOP、最大 risk、drawdown、exposure、cash、EV、blackout の検証順と意味は維持する。
OFF permit は fresh falsification の条件だけを置換し、resting fill 再評価や他 action へは伝播させない。

注文理由は実際の authority に合わせ、fresh approval の場合と policy bypass の場合を区別する。
Falsifier を実行していない entry に「Falsifier APPROVED」と記録しない。

### 6. commit 済み idempotent replay と新規副作用を分ける

新しい preview/place 副作用の前に policy repository を読めない場合は fail closed にする。
一方、同じ deterministic `clientRequestId` の ledger result が既に durable なら、broker は SafetyFloor と policy repository を再実行せず mutation なしで既存結果を replay する。
これは新しい entry authority ではなく、既に成立した paper truth の応答回復として扱う。

runner の guarded tool request payload は、mutation 前の durable audit として policy decision ID、policy、required、reason、runtime config identity を含む。
completion audit / ACK loss 後も、元の authority と replay result を照合できるようにする。
commit 済み order を policy read failure によって no-trade へ書き換える案は paper truth を壊すため採用しない。

### 7. policy event は LLM phase を捏造しない

policy decision の canonical event は proposer の decision-run context と runtime config identity で保存する。
OFF では Falsifier invocation、falsification record、Falsifier phase observation を作らない。
runner phase audit は machine-readable に `policy`, `required`, `reasonCodes`, `decisionId` を記録する。

## Risks / Trade-offs

- [direct/test runner に persisted runtime version がない] → typed config から安定した fallback identity を導出し、production snapshot がある場合は必ずそちらを使う
- [既存 decision と再起動後 config または canonical attributes が違う] → 上書きせず no-trade にして、intent の attribution を保存する
- [snapshot と typed config が別々に注入される] → canonical typed hash を再計算して snapshot hash と一致しなければ no-trade にする
- [MCP caller が OFF decision を発見する] → wire command から internal permit を設定できず、SafetyFloor で拒否する
- [新規 side effect 前に policy repository read が停止する] → preview/place を実行せず no-trade にする
- [commit 後 ACK loss の retry 時に policy repository が停止する] → deterministic client request の durable result だけを mutation なしで replay し、元 authority は request audit から追跡する
- [ADD_LONG が preview/place 間に ENTER へ化ける] → OFF bypass を ENTER に限定し、ADD_LONG は後続の action/group binding まで Falsifier 必須にする
- [`CONDITIONAL_V1` が誤って activate される] → Falsifier 必須へ倒し、conditional 実験としては使用禁止を docs に残す

## Migration Plan

1. repository wiring、runner decision、internal permit、SafetyFloor 検証を同時に deploy する。
2. default `ALWAYS_ON_V1` のまま回帰を確認する。
3. この PR では `OFF_V1` / `CONDITIONAL_V1` を production activate しない。
4. rollback は code を戻すだけで、保存済み decision/event を削除・書換えしない。

## Open Questions

なし。
