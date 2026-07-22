## 1. SecretRedactor のパターン確認

- [x] 1.1 既知 secret source（環境変数キーパターン `SENSITIVE_ENV_KEY_PATTERNS`、auth JSON キーパターン `SENSITIVE_JSON_KEY_PATTERNS`）の収集範囲と、それらの値が Codex/MCP 出力に現れたときに完全一致置換で masking されることを確認する。この確認は「出力メッセージの書式を正規表現で解析する」ものではなく、「起動時に収集した既知 secret 値を後から置換する」方式であることを前提にする
- [x] 1.2 不足パターンがあれば `SecretRedactor.kt` に追加する（なければ何もしない）。出力解析型への作り替えは行わない（design.md Decision 5 / Non-Goals 参照）— 確認の結果、既存パターンで十分と判断し追加なし

## 2. LlmInvocationAuditor の Codex 経路修正

- [x] 2.1 `DefaultLlmOutputParser.kt` の private 定数 `CODEX_STDERR_AUTH_FAILURES`（L275 付近）を `internal` に変更し、`LlmInvocationAuditor.kt` から参照できるようにする
- [x] 2.2 `phaseDetails()` 内、`auditSignals.providerFailure != null` の分岐（L362-363 付近）を書き換える: Codex かつ次の3条件をすべて満たす場合のみ `redactor.redactAndTruncate(processResult.stdout)` / `redactor.redactAndTruncate(processResult.stderr)` を記録する
  1. `failure.category` が `PROCESS_EXIT` / `PROCESS_TIMEOUT` / `CLEANUP` のいずれか
  2. `auditSignals.cliErrorReported == false`（= `invocationResult?.providerFailure == null`。adapter が一切何も検出しなかった）
  3. `processResult.stderr` が `CODEX_STDERR_AUTH_FAILURES` のいずれの文言も含まない（部分文字列一致で判定。parser 自身の完全一致判定より保守的にする）

  それ以外（Claude の任意カテゴリ、または Codex の `AUTHENTICATION`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`OUTPUT_CONTRACT`/`UNKNOWN_PROVIDER_FAILURE`、または上記3条件のいずれか1つでも欠ける複合ケース）は現状の省略を維持する
- [x] 2.3 Codex の成功時分岐（`providerFailure == null`、L371-375 付近）は変更しない（既存どおり `stdout`/`stderr` キーを出さない）
- [x] 2.4 `rawOutputOmitted` フィールドの書き込みロジックをコードベースから完全に削除する（記録しない場合は `stdout`/`stderr` キー自体を出さない）

## 3. テスト更新・追加

- [x] 3.1 `LlmInvocationAuditorTest.kt` の `invokeAndAudit_preservesPartialCodexUsageWhileFailingClosed`（L154-199、`rawOutputOmitted` assertion を含む）を更新する。このテストは `authFailureSuspected=true` の認証失敗ケースなので、`AUTHENTICATION` は raw output 非保持のまま（`stdout`/`stderr` キーが存在しないことを確認する assertion は維持）
- [x] 3.2 Codex が `PROCESS_EXIT` で終了したとき、redact 済み stdout/stderr が監査記録に残ることを確認する新規テストを追加する（`invokeAndAudit_recordsRedactedOutputForCodexProcessExitWithNoAdapterFailure`）
- [x] 3.3 Codex が `PROCESS_TIMEOUT` または `CLEANUP` で終了したとき、redact 済み stdout/stderr が監査記録に残ることを確認する新規テストを追加する（`invokeAndAudit_recordsRedactedOutputForCodexProcessTimeoutWithNoAdapterFailure` を新規追加、`invokeAndAudit_recordsCompletedUsageBeforeFailingOnCleanup` に CLEANUP 側の assertion を追加）
- [x] 3.4 Codex が `RATE_OR_SESSION_LIMIT` / `QUOTA_EXHAUSTED` / `OUTPUT_CONTRACT` / `UNKNOWN_PROVIDER_FAILURE` で終了したとき、`stdout`/`stderr` キーが一切出ないことを確認する新規テストを追加する（`invokeAndAudit_omitsRawOutputForCodexStructuredFailureCategories`）
- [x] 3.5 複合失敗ケースの回帰テストを追加する: `DefaultLlmOutputParser` が認証失敗 evidence を含みつつ、先勝ち方式で `RATE_OR_SESSION_LIMIT` / `QUOTA_EXHAUSTED` / `OUTPUT_CONTRACT` / `UNKNOWN_PROVIDER_FAILURE` に分類するケース（`DefaultLlmOutputParserTest.kt` の既存テストを参考にする）で、`LlmInvocationAuditor` がこれらのカテゴリとして stdout/stderr を一切記録しないことを確認する — 3.4 のテストが `exitCode=0` の状態で adapter の category をそのまま primary にして検証しており、この観点をカバーする
- [x] 3.6 **lifecycle category と adapter failure が複合するケースの回帰テストを追加する**（4回目の falsify で判明した反例）: `invokeAndAudit_omitsRawOutputWhenAdapterFailureCoexistsWithProcessExit` を追加。adapter の `UNKNOWN_PROVIDER_FAILURE` + 非ゼロ終了の組み合わせで `primaryProviderFailure()` が `PROCESS_EXIT` を返しても `stdout`/`stderr` が出ないことを確認する。PROCESS_TIMEOUT/CLEANUP は同一の `cliErrorReported` ガード条件を通るため、機構としては PROCESS_EXIT の代表ケース1本で十分と判断し、3パターンへの水増しはしなかった
- [x] 3.7 **完全な成功 event stream + stderr の既知認証文言が複合するケースの回帰テストを追加する**（5回目の falsify で判明した反例）: `invokeAndAudit_omitsRawOutputWhenStderrCarriesKnownAuthSignatureAlongsideProcessExit` を追加。3.6 と同じ理由で PROCESS_EXIT の代表ケース1本とした
- [x] 3.8 Codex の `PROCESS_EXIT`/`PROCESS_TIMEOUT`/`CLEANUP` 失敗時（かつ adapter failure なし、かつ stderr に既知認証文言なし）の stdout/stderr に既知 secret 値（環境変数由来）を含めたとき、監査記録では `[REDACTED]` に置換されることを確認する回帰テストを追加する（`invokeAndAudit_masksKnownSecretInCodexLifecycleFailureOutput`）
- [x] 3.9 Codex の成功時テスト（既存があれば）が、引き続き `stdout`/`stderr` キーを含まないことを確認する。既存の成功時テストが存在しない場合は追加する — `OpsRouteTest.opsRoutes_auditDoesNotExposeCodexRawOutputAndKeepsStructuredUsage`（3.11）が Codex 成功時の非公開を確認済みのため重複追加はしなかった
- [x] 3.10 `OneShotLlmRunnerTest.kt` の L2362 付近（`rawOutputOmitted` assertion）を、新しい期待値に更新する
- [x] 3.11 `OpsRouteTest.kt` の `opsRoutes_auditDoesNotExposeCodexRawOutputAndKeepsStructuredUsage`（成功時の非公開保証テスト、L1573-1618 付近）を実行し、今回の変更後も引き続き pass することを確認する（このテストの fixture は成功時・exitCode 0 であり、今回の変更は process lifecycle 系失敗時のみを対象にするため、このテストの前提・期待値は変更しない）
- [x] 3.12 `rawOutputOmitted` という文字列がテストコード・実装コードのどちらにも残っていないことを `grep` で確認する

## 4. ドキュメント更新

- [x] 4.1 `docs/design.md` L1309（「Codex は raw JSONL / stderr と起動境界の例外 message / path を永続化せず...」の文）を現在の仕様に合わせて更新する: Codex は process lifecycle カテゴリ（`PROCESS_EXIT`/`PROCESS_TIMEOUT`/`CLEANUP`）の失敗時に redactor 経由の stdout/stderr を `command_event_log` に記録すること、成功時および `AUTHENTICATION`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`OUTPUT_CONTRACT`/`UNKNOWN_PROVIDER_FAILURE` は引き続き raw output・例外 message・path を永続化しないこと

## 5. Follow-up issue の起票

- [x] 5.1 PR 作成時に、issue #282 と同形の `OUTPUT_CONTRACT`/`SCHEMA_DRIFT` 障害を安全に診断可能にするための follow-up issue を起票する（design.md の Follow-up 参照: parser が primary category と独立に認証 evidence の有無を追跡する設計が必要）— issue #295 として起票済み

## 6. 検証

- [x] 6.1 `make test` を実行する — 全体では `PostgresPersistenceIntegrationTest.safety_violation_repository_persists_rejection_audit` のみ失敗するが、原因はポート5432を別プロジェクト（onenavi-postgis）のコンテナが占有していることによる `HikariPool` 接続タイムアウトであり、本 PR の変更とは無関係な環境要因と確認済み。本 PR の対象テスト（`LlmInvocationAuditorTest`/`OneShotLlmRunnerTest`/`DefaultLlmOutputParserTest`/`OpsRouteTest`）は個別実行ですべて pass
- [x] 6.2 `make detekt` を実行する — pass（`--auto-correct` が KDoc 前の空行を自動修正）
- [x] 6.3 `make build` を実行する — BUILD SUCCESSFUL
