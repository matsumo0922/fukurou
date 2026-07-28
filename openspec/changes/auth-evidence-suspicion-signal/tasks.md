## 1. 実装

- [x] 1.1 `DefaultLlmOutputParser.kt` の `CODEX_KNOWN_AUTH_EVIDENCE_TEXTS` へ `Failed to refresh token` / `refresh_token_reused` / `token_expired` を追加し、KDoc の構成説明を更新する
- [x] 1.2 `LlmInvocationAuditor.kt` の `authFailureSuspected` 導出へ `authEvidenceObserved` を OR で加え、`LlmPhaseAuditSignals` の KDoc を更新する

## 2. テスト

- [x] 2.1 `DefaultLlmOutputParserTest` に、stderr が `Failed to refresh token` を含み stdout が JSONL contract を満たさない入力で `providerFailure.category == OUTPUT_CONTRACT` かつ `authEvidenceObserved == true` になることを確認する回帰テストを追加する
- [x] 2.2 `LlmInvocationAuditorTest` に、同形の入力で監査 payload に `authFailureSuspected="true"` が載り、`stdout` / `stderr` が載らないことを確認するテストを追加する
- [x] 2.3 `OneShotLlmRunnerTest` に、production call path（`OneShotLlmRunner` 経由の Codex proposer invocation）で同形の入力から `authFailureSuspected="true"` と runbook ログが出ることを確認するテストを追加する
- [x] 2.4 既存の #300 / #296 由来テストの前提が変わっていないことを確認する（既存テストの assertion を弱める修正を行わない）

## 3. 検証

- [ ] 3.1 `make detekt` を通す
- [ ] 3.2 `make test` を通す
