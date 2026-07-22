## 1. Implementation

- [x] 1.1 `OneShotLlmRunner.kt` の `mcpServerConfig()` が構築する `LlmMcpServerConfig` に `forwardedEnvironmentVariables = listOf(FUKUROU_INVOCATION_ID_ENV)` を追加する

## 2. Regression Test

- [x] 2.1 `OneShotLlmRunnerTest.kt` に、`runnerFixture` の production call path（`ShellLlmInvoker` → `DefaultLlmCommandRenderer` → `FakeProcessRunner`）経由で、`proposerProvider = LlmProvider.CODEX` の Codex proposer launch に対して生成された config TOML の `env_vars` に `FUKUROU_INVOCATION_ID` が含まれることを検証するテストを追加する

## 3. Docs

- [x] 3.1 `docs/mcp-runtime.md` の Codex MCP config 説明に `env_vars` 経由の `FUKUROU_INVOCATION_ID` 転送を追記する

## 4. Validation

- [x] 4.1 `make test` を実行する
- [x] 4.2 `make detekt` を実行する
