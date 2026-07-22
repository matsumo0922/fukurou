## 1. Implementation

- [ ] 1.1 `OneShotLlmRunner.kt` の `mcpServerConfig()` が構築する `LlmMcpServerConfig` に `forwardedEnvironmentVariables = listOf(FUKUROU_INVOCATION_ID_ENV)` を追加する

## 2. Regression Test

- [ ] 2.1 `OneShotLlmRunnerTest.kt` に、`runnerFixture` の production call path（`ShellLlmInvoker` → `DefaultLlmCommandRenderer` → `FakeProcessRunner`）経由で、Codex 向けに生成された config TOML の `env_vars` に `FUKUROU_INVOCATION_ID` が含まれることを検証するテストを追加する

## 3. Validation

- [ ] 3.1 `make test` を実行する
- [ ] 3.2 `make detekt` を実行する
