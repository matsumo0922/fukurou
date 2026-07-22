## Why

本番 paper daemon の Codex proposer run が2026-07-15頃から全滅している（issue #287）。原因は、Codex CLI 0.142.5 が MCP subprocess 起動時に `env_clear()` で環境を再構築し、config TOML の `env_vars` に明示された変数しか渡さないのに対し、本番の `LlmMcpServerConfig` が `forwardedEnvironmentVariables` を未設定のまま構築されているため、`FUKUROU_INVOCATION_ID` が MCP launcher（#217 で必須化済み）に届かず launcher が即死し MCP handshake が失敗すること。

## What Changes

- `OneShotLlmRunner.mcpServerConfig()` が構築する `LlmMcpServerConfig` に `forwardedEnvironmentVariables = listOf(FUKUROU_INVOCATION_ID_ENV)` を設定する
- 回帰テストを1本追加し、`mcpServerConfig()` → `DefaultLlmCommandRenderer` の実配線経由で生成される Codex config TOML に `env_vars = ["FUKUROU_INVOCATION_ID"]` が出力されることを検証する

## Capabilities

### New Capabilities

なし

### Modified Capabilities

- `llm-cli-invocation-contract`: `OneShotLlmRunner.mcpServerConfig()` が構築する Codex MCP invocation（decision run 用）が MCP subprocess へ `FUKUROU_INVOCATION_ID` を明示的に転送する要件を追加する。他の Codex MCP config 構築箇所（CLI acceptance canary、MCP isolation canary 等）は対象外

## Impact

- `trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/OneShotLlmRunner.kt`（`mcpServerConfig()`）
- `trading/src/test/kotlin/me/matsumo/fukurou/trading/runner/OneShotLlmRunnerTest.kt`（回帰テスト追加）
- `docs/mcp-runtime.md`（Codex MCP config の env 転送挙動を追記）
- 対象外: fd 3/4/5 経路、C launcher (`scripts/runtime/fukurou-mcp-launcher.c`)、supervisor、Claude 経路（既に env allowlist に含まれ対象外）、`CliAcceptanceCanaryMain.kt` / `McpIsolationCanaryArtifacts.kt` 等の他の Codex MCP config 構築箇所（falsify で確認済み: `forwardedEnvironmentVariables` 未設定のままで、本 issue のスコープ外）
