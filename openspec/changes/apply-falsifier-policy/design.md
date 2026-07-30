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

`ALWAYS_ON_V1` は `required=true / ALWAYS_ON`、`OFF_V1` は `required=false / POLICY_OFF` とする。
`CONDITIONAL_V1` は `required=true / CONDITIONAL_NOT_APPLIED` として従来の Falsifier gate を維持する。

これにより Issue #207 の「まず on/off」の順序を守り、SafetyFloor risk 計算、regime taxonomy、closed-position attribution をこの PR に持ち込まない。
conditional を推測で実装する案は paper truth を歪めるため採用しない。

### 2. runner は既存 decision を exact readback してから必要なら作る

entry intent 発行後、runner は `FalsifierPolicyDecisionRepository.findFalsifierPolicyDecision(intentId)` を先に呼ぶ。
既存 decision がある場合は active process の policy と runtime config version/hash が完全一致する場合だけ再利用する。
欠損時だけ新しい decision ID を生成し、foundation の原子 repository で decision と event を保存する。

repository の failure、片側欠損、既存 decision の policy/config 不一致は no-trade とする。
既存 row を現在の config に合わせて上書きする案は、intent の因果時点を改変するため採用しない。

### 3. runtime config identity は runner が実際に使う snapshot に固定する

production entry flow は `RuntimeConfigAuditSnapshot` の version ID / hash を decision と event に保存する。
snapshot がない direct/test runner は、実際の typed config から安定導出した process-local config identity を使う。
空値や任意文字列での bypass は許さない。

後から active config を再読して attribution を変更する案は、run 中の config switch と競合するため採用しない。

### 4. OFF bypass は internal permit と durable readback の両方を要求する

runner は `required=false` の durable decision から、次を含む immutable な internal permit を作る。

- decision ID
- intent ID
- policy
- runtime config version ID / hash

permit は runner の `PlaceOrderCommand` 構築経路だけが設定し、MCP `preview_order` / `place_order` の wire schema には追加しない。
`PaperBroker` は intent ID で durable policy decision を読み、`SafetyFloorContext` に渡す。
SafetyFloor は permit と durable decision の全 identity が一致し、policy が `OFF_V1`、`required=false` の場合だけ fresh `APPROVED` の代替 authority とする。

runner の Boolean だけで Falsifier を省略する案は、別 caller や再試行時に SafetyFloor が権限を検証できないため採用しない。
durable decision だけで自動 bypass する案も、MCP caller が既存 intent ID を渡すだけで省略権限を再利用できるため採用しない。

### 5. intent integrity と他の SafetyFloor rule は変更しない

消費済み intent、intent/command payload 不一致、STOP、最大 risk、drawdown、exposure、cash、EV、blackout の検証順と意味は維持する。
OFF permit は fresh falsification の条件だけを置換し、resting fill 再評価や他 action へは伝播させない。

注文理由は実際の authority に合わせ、fresh approval の場合と policy bypass の場合を区別する。
Falsifier を実行していない entry に「Falsifier APPROVED」と記録しない。

### 6. policy event は LLM phase を捏造しない

policy decision の canonical event は proposer の decision-run context と runtime config identity で保存する。
OFF では Falsifier invocation、falsification record、Falsifier phase observation を作らない。
runner phase audit は machine-readable に `policy`, `required`, `reasonCodes`, `decisionId` を記録する。

## Risks / Trade-offs

- [direct/test runner に persisted runtime version がない] → typed config から安定した fallback identity を導出し、production snapshot がある場合は必ずそちらを使う
- [既存 decision と再起動後 config が違う] → 上書きせず no-trade にして、intent の attribution を保存する
- [MCP caller が OFF decision を発見する] → wire command から internal permit を設定できず、SafetyFloor で拒否する
- [policy repository read が停止する] → preview/place を実行せず no-trade にする
- [`CONDITIONAL_V1` が誤って activate される] → Falsifier 必須へ倒し、conditional 実験としては使用禁止を docs に残す

## Migration Plan

1. repository wiring、runner decision、internal permit、SafetyFloor 検証を同時に deploy する。
2. default `ALWAYS_ON_V1` のまま回帰を確認する。
3. この PR では `OFF_V1` / `CONDITIONAL_V1` を production activate しない。
4. rollback は code を戻すだけで、保存済み decision/event を削除・書換えしない。

## Open Questions

なし。
