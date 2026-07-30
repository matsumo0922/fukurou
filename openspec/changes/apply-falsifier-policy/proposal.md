## Why

Issue #207 の段階実験を始めるには、foundation が保存する `ALWAYS_ON_V1` / `OFF_V1` の policy decision を runner と SafetyFloor の両方で同じ権限として適用する必要がある。
Falsifier を省略しても intent integrity と資金保護を維持し、policy decision の保存や読取に失敗した場合は entry を閉じる。

## What Changes

- entry intent ごとに active runtime config から `ALWAYS_ON_V1` / `OFF_V1` の policy decision を作り、Falsifier 起動前に durable 保存する
- `ALWAYS_ON_V1` は従来どおり Falsifier の fresh `APPROVED` を必須とする
- `OFF_V1` は durable decision と active runtime config identity が一致する場合だけ Falsifier を省略する
- SafetyFloor は runner 内の分岐だけを信用せず、同じ durable decision を読み直して bypass authority を検証する
- policy decision の保存・読取・identity 照合が失敗または不一致なら entry を fail closed にする
- `CONDITIONAL_V1` はこの変更では適用せず、選択された場合は Falsifier 必須として扱う
- runtime docs と回帰テストを同じ変更で更新する

## Capabilities

### New Capabilities

- `falsifier-policy-application`: version 付き policy decision を runner と SafetyFloor の entry gateへ適用する契約

### Modified Capabilities

なし。

## Impact

- `OneShotLlmRunner` の entry flow と Falsifier phase selection
- `TradingRuntime` / `PaperBroker` の policy repository wiring
- `SafetyFloor` の fresh falsification gate
- runner、broker、SafetyFloor、PostgreSQL wiring のテスト
- `docs/mcp-runtime.md`

外部 API と schema は foundation から変更しない。
実取引は引き続き未実装で、対象は paper entry のみ。
