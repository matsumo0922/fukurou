## 1. PR 1: parser と auditor の credential lifecycle signal（`:trading`）

- [ ] 1.1 `DefaultLlmOutputParser` に `CODEX_CREDENTIAL_LIFECYCLE_FAILURE_TEXTS`（`refresh_token_reused` / `token_expired` / `Failed to refresh token`）を追加し、根拠となる障害の出典を KDoc に残す
- [ ] 1.2 `ParsedLlmOutput` に `credentialLifecycleFailureObserved` を default なしで追加し、`parseCodex()` が stdout / stderr の部分一致で立てる。`parseClaude()` は常に false とする
- [ ] 1.3 `LlmInvocationResult` へ signal を伝搬し、`LlmInvoker` の全構築経路を更新する
- [ ] 1.4 `LlmPhaseAuditSignals` に signal を追加し、`LlmInvocationAuditor.phaseDetails()` が true のときだけ `authTokenFailureObserved` を payload へ出す
- [ ] 1.5 signal が raw output 保持判定（`isSafeCodexLifecycleFailure`）に影響しないことを確認する
- [ ] 1.6 `DefaultLlmOutputParserTest` に、issue #305 の実 stderr を模した入力で signal が true になる test と、`401 Unauthorized` 単独で false のままである test を追加する
- [ ] 1.7 `LlmInvocationAuditorTest` に、signal true で `authTokenFailureObserved` が payload に出る test と、`OUTPUT_CONTRACT` + signal true でも redacted stdout / stderr が保持される test を追加する
- [ ] 1.8 `make test` / `make detekt` を通し、PR 1 を作成する

## 2. PR 2: 監視 status の降格（`:fukurou`、base は PR 1）

- [ ] 2.1 `LlmAuthStatus` に `TOKEN_SUSPECT("token_suspect")` を追加する
- [ ] 2.2 `LlmAuthEvidenceSource` 境界（provider と observation 下限時刻を受け、最新1件の evidence 有無を返す）を定義する
- [ ] 2.3 `command_event_log` を bounded read する Exposed 実装を追加する。`MonitoringRepository` と同じ `statement_timeout` / `lock_timeout` / row bound を適用し、`RUNNER_PHASE_COMPLETED` を `ts DESC` で読んで該当 provider の最初の1件で打ち切る
- [ ] 2.4 payload 解釈は fail-closed とする。JSON 不正、`details` 欠落、marker 値が想定外語彙、bound 到達で未解決 — いずれも malformed として扱う
- [ ] 2.5 `DefaultLlmAuthService.providerStatus()` を更新する。marker 検出時に marker の mtime を下限として evidence を照会し、failure evidence があれば `TOKEN_SUSPECT`、なければ `LOGGED_IN`、照会失敗は `UNKNOWN` とする。evidence source が null なら現行動作を維持する
- [ ] 2.6 `providerStatus()` が suspend になることによる `snapshot()` の呼び出し形を整える
- [ ] 2.7 `Application.kt` で DB 接続時に evidence source を注入する
- [ ] 2.8 `/ops/llm-auth` の `.describe {}` に `token_suspect` と `unknown` の意味を日本語で追記する
- [ ] 2.9 `LlmAuthServiceTest` に spec の Scenario 対応 test を追加する: credential lifecycle evidence で降格 / `authFailureSuspected` で降格 / 最新 run 成功で `logged_in`（回帰）/ evidence なしで `logged_in` / marker 更新後の古い evidence を無視 / 新旧2件で新しい方を採用 / query 失敗で `unknown` / payload malformed で `unknown` / evidence source 未注入で `logged_in`
- [ ] 2.10 `OpsRouteTest` に production route 経由で `token_suspect` が wire に出る test を追加する
- [ ] 2.11 evidence repository の bounded read を Postgres 統合テストで確認する（既存の統合テスト基盤に合わせる）
- [ ] 2.12 `docs/llm-obsidian-production-setup.md` の CLI auth 判定の記述を現在形で更新し、`token_suspect` が残る場合に auth.json の mtime を確認する運用を追記する
- [ ] 2.13 WebUI System 画面が `token_suspect` を「logged in ではない」として扱い、そのまま表示することを確認する（変更が不要なら不要と記録する）
- [ ] 2.14 `make test` / `make detekt` / web の test を通し、PR 2 を PR 1 の branch を base として作成する
