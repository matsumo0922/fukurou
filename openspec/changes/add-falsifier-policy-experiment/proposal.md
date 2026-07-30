## Why

Issue #207 Phase 2 では、Falsifier の常時起動に downside 防御としての価値があるかを、別々の production paper 期間で比較する。
現在は entry intent ごとに Falsifier を必ず起動するため、Falsifier OFF と条件起動を同じ Proposer / model / prompt / SafetyFloor の下で切り替え、各 run を policy version へ帰属させる手段がない。

## What Changes

- Falsifier policy を `ALWAYS_ON_V1` / `OFF_V1` / `CONDITIONAL_V1` の version 付き runtime config として追加する
- 条件起動では、大口リスク、不利または不明な market regime、current cohort の直近 2 連敗のいずれかで Falsifier を起動する
- policy 判定を intent に一意な durable record と command event に記録する
- Falsifier を起動しない entry でも、偽の falsification を作らず、persisted intent の一致・未消費と SafetyFloor の全資金保護ルールを維持する

## Capabilities

### New Capabilities

- `falsifier-policy-experiment`: version 付き Falsifier policy の選択、監査、期間比較を定める

### Modified Capabilities

- なし

## Impact

- `TradingBotConfig` / runtime config catalog と validation
- one-shot entry flow、`PlaceOrderCommand`、SafetyFloor の Falsifier gate
- current cohort の直近 closed trade 読み取り
- policy decision persistence、command event audit、runner / SafetyFloor の回帰テスト
- `docs/design.md`、`docs/mcp-runtime.md`、`docs/deploy.md`

## Out of Scope

- 8-arm ablation
- blind scorer や provider architecture の変更
- 実資金取引
- production runtime config の activation
- 比較期間が終わる前の優劣判定
- rejected-intent shadow と descriptive comparison（次の stacked change）
