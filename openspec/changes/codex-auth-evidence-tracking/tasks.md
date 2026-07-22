## 1. モデル拡張（fail-closed: default なし）

- [ ] 1.1 `LlmInvocationModels.kt` の `ParsedLlmOutput` に `authEvidenceObserved: Boolean`（default なし）を追加し、KDoc を更新する
- [ ] 1.2 `LlmInvocationModels.kt` の `LlmInvocationResult` に `authEvidenceObserved: Boolean`（default なし）を追加し、KDoc を更新する
- [ ] 1.3 `LlmInvoker.kt` の `LlmInvocationResult` 構築箇所で `authEvidenceObserved = parsedOutput.authEvidenceObserved` を伝播する
- [ ] 1.4 `ParsedLlmOutput`/`LlmInvocationResult` を直接構築している既存コード・テスト全箇所（`parseClaude()`、`contractFailure()`、`ShellLlmInvokerTest.kt` 等）を grep で列挙し、`authEvidenceObserved = false`（Claude/非対象経路）を明示追加する。コンパイルが通ることで網羅性を確認する

## 2. parser: 認証 evidence の独立追跡

- [ ] 2.1 `DefaultLlmOutputParser.parseCodex()` に `var authEvidenceObserved = false` を追加する
- [ ] 2.2 `turn.failed` イベント分岐で、message が `knownCompatibilityFailureCategory() == AUTHENTICATION` の場合、first-win の結果に関わらず `authEvidenceObserved = true` にする
- [ ] 2.3 `error` イベント分岐で同様の判定を追加する
- [ ] 2.4 `CODEX_KNOWN_AUTH_EVIDENCE_TEXTS`（`CODEX_STDERR_AUTH_FAILURES` の2文言 ∪ `knownCompatibilityFailureCategory()` が `AUTHENTICATION` に分類する2文言 "Not logged in"/"Invalid authentication credentials"）を定義する。`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED` の分類文言は含めない（新設の安全カテゴリ自身の分類文言を evidence 扱いすると自己矛盾でブロックされ続けるため）
- [ ] 2.5 イベントループ終了後、`CODEX_KNOWN_AUTH_EVIDENCE_TEXTS` の各文言を `processResult.stdout` と `processResult.stderr` の両方に対して `.contains()` で検査し、一致すれば `authEvidenceObserved = true` にする
- [ ] 2.6 `parseCodex()` の戻り値 `ParsedLlmOutput` に `authEvidenceObserved` を設定する

## 3. auditor: 安全条件の拡張

- [ ] 3.1 `LlmPhaseAuditSignals` の `authEvidenceObserved: Boolean` と `cliErrorReported: Boolean` から default を撤廃する。`authFailureSuspected`/`cleanupFailed`/`providerFailure` は既存どおり default を維持する（`isSafeCodexLifecycleFailure()` の safety 条件として直接使われるのはこの2フィールドのみのため。design.md D5、round 2 Blocking B・round 3 Blocking C 参照）
- [ ] 3.2 `invokeAndAudit()` 内の唯一の `LlmPhaseAuditSignals` 構築箇所で `authEvidenceObserved = invocationResult?.authEvidenceObserved ?: false` と `cliErrorReported = invocationResult?.providerFailure != null` を明示する（`invocationResult` が null になりうるのは invocation 自体が完了しなかった経路であり、その場合 evidence 未観測・adapter failure 未検出を false として扱うのは妥当。default 撤廃の目的は「明示を強制すること」であり、この1箇所での `?:`/比較式自体は許容する）
- [ ] 3.3 `CODEX_SAFE_OUTPUT_INTERPRETED_FAILURE_CATEGORIES`（`OUTPUT_CONTRACT`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`UNKNOWN_PROVIDER_FAILURE`）を定義する
- [ ] 3.4 `isSafeCodexLifecycleFailure()` を、`authEvidenceObserved` チェックを最優先の否定条件として持つ2経路（lifecycle / output-interpreted）の disjunction に書き換え、`processResult` 引数を削除する。KDoc を新しい条件と Finding 1-4 の反証結果に合わせて更新する
- [ ] 3.5 `isSafeCodexLifecycleFailure()` の呼び出し元（`phaseDetails()`）を新しいシグネチャに更新する
- [ ] 3.6 `LlmInvocationAuditor.kt` から不要になった `CODEX_STDERR_AUTH_FAILURES` の import を削除する（`CODEX_KNOWN_AUTH_EVIDENCE_TEXTS` も含め、evidence 判定は parser 側に一元化し auditor は結果のフラグだけを読む）

## 4. テスト: parser 単体

- [ ] 4.1 `DefaultLlmOutputParserTest.kt` に、`turn.failed` の message が `AUTHENTICATION` に分類されるが他の event が先に別カテゴリを確定させる複合ケースで `authEvidenceObserved == true` になることを確認するテストを追加する
- [ ] 4.2 同様に `error` イベントでの複合ケースのテストを追加する
- [ ] 4.3 完全な成功 event stream（`providerFailure == null`）だが stderr に既知認証文言が含まれるケースで `authEvidenceObserved == true` になることを確認するテストを追加する
- [ ] 4.4 非 JSON stdout（schema drift、#282 と同形の起動失敗）で、stdout/stderr に既知認証文言が含まれない場合は `authEvidenceObserved == false` になることを確認するテストを追加する
- [ ] 4.5 同じ非 JSON stdout ケースで、stdout 側に "Not logged in" のような `knownCompatibilityFailureCategory()` 由来の文言が含まれる場合（`CODEX_STDERR_AUTH_FAILURES` の2長文ではない方）に `authEvidenceObserved == true` になることを確認するテストを追加する（Finding 1 の回帰防止）
- [ ] 4.6 stderr にのみ既知認証文言が含まれ stdout は無関係な非 JSON garbage であるケースでも `authEvidenceObserved == true` になることを確認するテストを追加する

## 5. テスト: auditor 統合（double ベース）

- [ ] 5.1 `ConfigurableAuditLlmInvoker` に `authEvidenceObserved: Boolean = false` パラメータを追加する
- [ ] 5.2 既存テスト `invokeAndAudit_omitsRawOutputWhenStderrCarriesKnownAuthSignatureAlongsideProcessExit` を、`authEvidenceObserved = true` を明示的に渡す形へ更新する
- [ ] 5.3 `RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`UNKNOWN_PROVIDER_FAILURE` の各カテゴリについて、`authEvidenceObserved = false` なら記録・`= true` なら非記録になることを double ベースで確認するテストを追加する

## 6. テスト: production-wiring（#282 受け入れ条件の直接証明。Finding 4 対応、double での代替不可）

- [ ] 6.1 `LlmInvocationAuditorTest.kt`（または新規ファイル）に、`ShellLlmInvokerTest.kt` の `RecordingProcessRunner` 相当の fake `ProcessRunner` を用意する
- [ ] 6.2 fake `ProcessRunner` が #282 と同形の `ProcessRunResult`（非 JSON stdout、既知認証文言なし、exitCode 非0 または任意）を返すよう設定し、`ShellLlmInvoker(commandRenderer = stub, processRunner = fake, outputParser = DefaultLlmOutputParser())` を構築する
- [ ] 6.3 この `ShellLlmInvoker` を `LlmInvocationAuditor.invokeAndAudit()` の `llmInvoker` 引数にそのまま渡し、実際の parser → invoker → auditor の配線を経由して監査 payload に `failureCategory=OUTPUT_CONTRACT`/`providerCode=SCHEMA_DRIFT` かつ redact 済み `stdout`/`stderr` が記録されることを確認する（受け入れ条件1）
- [ ] 6.4 同じ配線で、非 JSON stdout に既知認証文言を埋め込んだケースでは `stdout`/`stderr` キーが記録されないことを確認する（受け入れ条件2）

## 7. ドキュメント

- [ ] 7.1 `docs/design.md` の Codex raw output 記述（#296 で更新した箇所）を新しい2経路の条件と残存リスクに更新する

## 8. 検証

- [ ] 8.1 `make test` を実行し、対象テストがすべて pass することを確認する
- [ ] 8.2 `make detekt` を実行する
- [ ] 8.3 `make build` を実行する
- [ ] 8.4 検証結果（コマンド、結果、実行時 HEAD SHA）を記録する
