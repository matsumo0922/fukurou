## 1. モデル拡張

- [ ] 1.1 `LlmInvocationModels.kt` の `ParsedLlmOutput` に `authEvidenceObserved: Boolean = false` を追加し、KDoc を更新する
- [ ] 1.2 `LlmInvocationModels.kt` の `LlmInvocationResult` に `authEvidenceObserved: Boolean = false` を追加し、KDoc を更新する
- [ ] 1.3 `LlmInvoker.kt` の `LlmInvocationResult` 構築箇所で `authEvidenceObserved = parsedOutput.authEvidenceObserved` を伝播する

## 2. parser: 認証 evidence の独立追跡

- [ ] 2.1 `DefaultLlmOutputParser.parseCodex()` に `var authEvidenceObserved = false` を追加する
- [ ] 2.2 `turn.failed` イベント分岐で、message が `knownCompatibilityFailureCategory() == AUTHENTICATION` の場合、first-win の結果に関わらず `authEvidenceObserved = true` にする
- [ ] 2.3 `error` イベント分岐で同様の判定を追加する
- [ ] 2.4 イベントループ終了後、`CODEX_STDERR_AUTH_FAILURES` の各文言を `processResult.stdout` と `processResult.stderr` の両方に対して `.contains()` で検査し、一致すれば `authEvidenceObserved = true` にする
- [ ] 2.5 `parseCodex()` の戻り値 `ParsedLlmOutput` に `authEvidenceObserved` を設定する

## 3. auditor: 安全条件の拡張

- [ ] 3.1 `LlmPhaseAuditSignals` に `authEvidenceObserved: Boolean = false` を追加する
- [ ] 3.2 `invokeAndAudit()` 内の `LlmPhaseAuditSignals` 構築箇所で `authEvidenceObserved = invocationResult?.authEvidenceObserved ?: false` を設定する
- [ ] 3.3 `CODEX_SAFE_OUTPUT_INTERPRETED_FAILURE_CATEGORIES`（`OUTPUT_CONTRACT`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`UNKNOWN_PROVIDER_FAILURE`）を定義する
- [ ] 3.4 `isSafeCodexLifecycleFailure()` を、`authEvidenceObserved` チェックを最優先の否定条件として持つ2経路（lifecycle / output-interpreted）の disjunction に書き換え、`processResult` 引数を削除する。KDoc を新しい条件に合わせて更新する
- [ ] 3.5 `isSafeCodexLifecycleFailure()` の呼び出し元（`phaseDetails()`）を新しいシグネチャに更新する
- [ ] 3.6 `LlmInvocationAuditor.kt` から不要になった `CODEX_STDERR_AUTH_FAILURES` の import を削除する

## 4. テスト: parser 単体

- [ ] 4.1 `DefaultLlmOutputParserTest.kt` に、`turn.failed` の message が `AUTHENTICATION` に分類されるが他の event が先に別カテゴリを確定させる複合ケースで `authEvidenceObserved == true` になることを確認するテストを追加する
- [ ] 4.2 同様に `error` イベントでの複合ケースのテストを追加する
- [ ] 4.3 完全な成功 event stream（`providerFailure == null`）だが stderr に既知認証文言が含まれるケースで `authEvidenceObserved == true` になることを確認するテストを追加する
- [ ] 4.4 非 JSON stdout（schema drift、#282 と同形の起動失敗）で、stdout/stderr に既知認証文言が含まれない場合は `authEvidenceObserved == false` になることを確認するテストを追加する
- [ ] 4.5 同じ非 JSON stdout ケースで、stdout 側に既知認証文言が含まれる場合は `authEvidenceObserved == true` になることを確認するテストを追加する

## 5. テスト: auditor 統合

- [ ] 5.1 `ConfigurableAuditLlmInvoker` に `authEvidenceObserved: Boolean = false` パラメータを追加する
- [ ] 5.2 既存テスト `invokeAndAudit_omitsRawOutputWhenStderrCarriesKnownAuthSignatureAlongsideProcessExit` を、`authEvidenceObserved = true` を明示的に渡す形へ更新する
- [ ] 5.3 #282 と同形の fixture（`OUTPUT_CONTRACT`/`SCHEMA_DRIFT`、非 JSON stdout、認証 evidence なし）で、redact 済み stdout/stderr が監査記録に残ることを確認する回帰テストを追加する（受け入れ条件1）
- [ ] 5.4 同じ fixture 形状で、stdout または stderr に既知認証文言が含まれる場合は raw output が記録されないことを確認する回帰テストを追加する（受け入れ条件2）
- [ ] 5.5 `RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`UNKNOWN_PROVIDER_FAILURE` の各カテゴリについて、認証 evidence なしなら記録・ありなら非記録になることを確認するテストを追加する
- [ ] 5.6 実際の `DefaultLlmOutputParser.parseCodex()` を通した production call path で、成功 event stream + stderr 既知認証文言のケースが `authEvidenceObserved = true` を生成し、auditor がそれを正しく尊重することを確認する end-to-end テストを追加する

## 6. ドキュメント

- [ ] 6.1 `docs/design.md` の Codex raw output 記述（#296 で更新した箇所）を新しい2経路の条件に更新する

## 7. 検証

- [ ] 7.1 `make test` を実行し、対象テストがすべて pass することを確認する
- [ ] 7.2 `make detekt` を実行する
- [ ] 7.3 `make build` を実行する
- [ ] 7.4 検証結果（コマンド、結果、実行時 HEAD SHA）を記録する
