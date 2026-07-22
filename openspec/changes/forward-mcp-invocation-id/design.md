## Context

Codex CLI 0.142.5 は MCP subprocess 起動時に `env_clear()` で環境を再構築し、config TOML の `env_vars` に明示された変数のみを渡す。#217 が MCP launcher（`scripts/runtime/fukurou-mcp-launcher.c`）に `FUKUROU_INVOCATION_ID` の必須チェックを追加したため、この変数が転送されない Codex proposer/falsifier run は launcher が即死し MCP handshake に失敗する。本番の `LlmMcpServerConfig`（`OneShotLlmRunner.mcpServerConfig()` が構築）は `forwardedEnvironmentVariables` を未設定のままにしており、これが2026-07-15頃からの proposer 全滅の直接原因（issue #287 で確定済み）。

`forwardedEnvironmentVariables` フィールドと renderer 側の転送・forbidden pattern チェック（`DefaultLlmCommandRenderer.kt` L417-428, `isForbiddenMcpEnvironmentVariable`）は既存実装として存在し、`CliAcceptanceCanaryMain.kt` L127-139 が provider == CODEX 限定で同様の設定をする参考パターンをすでに使っている。

## Goals / Non-Goals

**Goals:**
- 本番構成で生成される Codex config TOML に `env_vars = ["FUKUROU_INVOCATION_ID"]` を出力させ、MCP launcher の必須チェックを満たす
- renderer の実配線を通した回帰テストで、この配線が production call path 経由で機能することを担保する

**Non-Goals**（agent 仮決め、issue #287 の「やらないこと」に準拠）:
- fd 3/4/5 経路・C launcher・supervisor の変更
- password 受け渡し方式の変更
- `required = true` の変更（fail-closed のため維持）
- Claude 経路の変更（既に env allowlist に `FUKUROU_INVOCATION_ID` を含み対象外）
- MCP handshake 失敗時の診断性改善

## Decisions

- **`mcpServerConfig()` に `forwardedEnvironmentVariables = listOf(FUKUROU_INVOCATION_ID_ENV)` を無条件で設定する**（ユーザー確認済み: issue #287 の「やること」1）。provider 分岐は行わない。理由: `mcpServerConfig()` は Codex 専用の MCP config 構築であり（Claude は別経路の `--mcp-config` を使用）、呼び出し側で provider ガードは不要
- **`FUKUROU_INVOCATION_ID_ENV` は既に `runEnvironment(context)` 経由で child process env に含まれている**（agent 仮決め、コード確認済み: L2137-2143）ため、renderer の `require(forwardedEnvironmentVariables.all(environment::containsKey))` は追加変更なしで満たされる
- **`FUKUROU_INVOCATION_ID` は forbidden パターン（`MCP_SENSITIVE_ENVIRONMENT_NAME_PARTS`: CREDENTIAL/KEY/PASSWORD/SECRET/TOKEN）に該当しない**（agent 仮決め、issue #287 で確認済み）ため、renderer の forbidden チェックも通過する
- **回帰テストは既存の `falsifierMcpConfigCarriesServerSideAllowlistWithoutTradeTools` と同じ production call path（`runnerFixture` → `ShellLlmInvoker` → `DefaultLlmCommandRenderer` → `FakeProcessRunner` → `codexConfigContent()`）を再利用する**（agent 仮決め）。理由: `requestCapturingRunnerFixture` は `LlmInvocationRequest` を捕捉するのみで renderer を経由しないため、issue #287 が要求する「renderer の実配線経由」の検証にならない

## Risks / Trade-offs

- [Risk] `forwardedEnvironmentVariables` に将来 secret 相当の変数が追加された場合の漏洩 → Mitigation: 既存の `isForbiddenMcpEnvironmentVariable` チェックが renderer 側で機能しており、本変更はそのガードを回避しない（対象外）
- [Risk] 本 fix だけでは launcher 即死の根本要因（#217 の必須チェック自体）は変わらない → Mitigation: 本 issue のスコープは env 転送の欠落修正のみであり、launcher 側の診断性改善は別 issue（S4）に委ねる

## Migration Plan

デプロイのみ（schema/データ移行なし）。ロールバックは通常の revert で可能。デプロイ後の確認（owner 作業、受け入れ条件外）: daemon run で MCP tool call が記録され `proposer_missing_decision` が解消すること。

## Open Questions

なし
