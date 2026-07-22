## 1. SecretRedactor のパターン確認

- [ ] 1.1 `SecretRedactor.kt` の `SENSITIVE_ENV_KEY_PATTERNS` / `SENSITIVE_JSON_KEY_PATTERNS` が Codex CLI / MCP launcher の実際のエラーメッセージ形式（DB password、API key、token 系）を拾えるか確認する
- [ ] 1.2 不足パターンがあれば `SecretRedactor.kt` に追加する（なければ何もしない）

## 2. LlmInvocationAuditor の Codex 経路修正

- [ ] 2.1 `phaseDetails()` 内、`auditSignals.providerFailure != null` の分岐（L362-363 付近）を provider ごとに分割する: Codex かつ `failure.category != AUTHENTICATION` の場合は `redactor.redactAndTruncate(processResult.stdout)` / `redactor.redactAndTruncate(processResult.stderr)` を記録し、それ以外（Claude、または Codex の `AUTHENTICATION`）は現状の省略を維持する
- [ ] 2.2 `providerFailure == null` の Codex 分岐（L371-375 付近）を、Claude と同じ `redactor.redactAndTruncate(stdout)` / `redactor.redactAndTruncate(stderr)` 記録に変更する
- [ ] 2.3 `rawOutputOmitted` フィールドの書き込みロジックをコードベースから完全に削除する（`AUTHENTICATION` の場合は `stdout`/`stderr` キー自体を出さない）

## 3. テスト更新・追加

- [ ] 3.1 `LlmInvocationAuditorTest.kt` の `invokeAndAudit_preservesPartialCodexUsageWhileFailingClosed`（L154-199、`rawOutputOmitted` assertion を含む）を更新する。このテストは `authFailureSuspected=true` の認証失敗ケースなので、`AUTHENTICATION` は raw output 非保持のまま（`stdout`/`stderr` キーが存在しないことを確認する assertion は維持）
- [ ] 3.2 Codex が `AUTHENTICATION` 以外の失敗カテゴリ（例: `PROCESS_EXIT` または `UNKNOWN_PROVIDER_FAILURE`）で終了したとき、redact 済み stdout/stderr が監査記録に残ることを確認する新規テストを追加する
- [ ] 3.3 Codex が成功したとき、redact 済み stdout/stderr が監査記録に残ることを確認する新規テストを追加する（Claude の既存成功テストと対称の内容）
- [ ] 3.4 Codex の stdout/stderr に既知 secret 値（環境変数由来）を含めたとき、監査記録では `[REDACTED]` に置換されることを確認する回帰テストを追加する
- [ ] 3.5 `OneShotLlmRunnerTest.kt` の L2362 付近（`rawOutputOmitted` assertion）を、新しい期待値に更新する
- [ ] 3.6 `rawOutputOmitted` という文字列がテストコード・実装コードのどちらにも残っていないことを `grep` で確認する

## 4. 検証

- [ ] 4.1 `make test` を実行する
- [ ] 4.2 `make detekt` を実行する
- [ ] 4.3 `make build` を実行する
