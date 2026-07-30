## Why

Issue #207 の on/off 実験を安全に適用するには、entry intent ごとの policy attribution を Falsifier 起動より先に固定し、後続の enforcement が検証できる internal permit を作る必要がある。
この change はその正本を runner に接続する基盤だけを扱う。Falsifier と SafetyFloor の現在の gate は変更しない。

## What Changes

- policy/action ごとに canonical な `required` と reason codes を解決する
- typed `TradingBotConfig` から canonical runtime config hash を再計算し、snapshot identity を検証する
- entry intent ごとに durable policy decision を exact readback または原子保存する
- `OFF_V1` の `ENTER` decision から、MCP wire に露出しない immutable internal permit を作る
- decision/permit を runner の内部 audit context に残す。ただし、この PR では Falsifier を省略せず、permit を preview/place command に渡さない
- `ALWAYS_ON_V1`、`CONDITIONAL_V1`、`ADD_LONG` は常に `required=true` として保存する
- runtime docs と foundation の回帰テストを更新する

## Capabilities

### New Capabilities

- `falsifier-policy-application`: policy attribution と internal permit foundation の契約

### Modified Capabilities

なし。

## Impact

- `OneShotLlmRunner` の entry intent 後の durable policy attribution
- `TradingRuntime` の policy decision repository wiring
- policy resolution / config identity / runner persistence のテスト
- `docs/mcp-runtime.md`

外部 API と MCP wire schema は変更しない。paper entry は引き続き fresh `APPROVED` を要求し、実取引と policy activation は対象外である。
