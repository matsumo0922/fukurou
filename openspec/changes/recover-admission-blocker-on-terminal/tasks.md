## 1. snapshot への finished_at 追加

- [x] 1.1 `LlmLaunchReservationRepository.kt:140` の `LlmExecutionClaimSnapshot` に `finishedAt: Instant?` を additive に追加する（`reservedAt` の後ろ）。KDoc は日本語で「reservation が terminal になった時刻。RUNNING の間は null」とする
- [x] 1.2 同ファイル `:906` 付近の in-memory 実装の `toClaimSnapshot()` に `finishedAt` を渡す。in-memory reservation record が `finishedAt` を保持していない場合は保持させる（`finish()` の set 経路を確認する）
- [x] 1.3 `ExposedLlmLaunchReservationRepository.kt:155` の `SELECT_LLM_EXECUTION_CLAIM_SQL` の select 列に `finished_at` を追加する
- [x] 1.4 同ファイル `:960` 付近の `ResultSet.toExecutionClaimSnapshot()` に `finishedAt = getNullableInstant("finished_at")` を追加する（`:550` に既存の `getNullableInstant` 利用例がある）
- [x] 1.5 `SELECT_STALE_LLM_EXECUTION_CLAIMS_SQL`（`:163`）は同じ mapper を通るため、両 UNION 枝と外側 SELECT の列に `finished_at` を追加する。stale scan 候補は `status = 'RUNNING'` なので値は常に null であり、既存判定は変わらない
- [x] 1.6 `LlmExecutionClaimSnapshot(` を直接構築している既存テスト（`LlmExecutionRecoveryServiceTest.kt:842` 付近ほか）をコンパイル可能にする。default 値 `= null` を付けて既存呼び出しを変更不要にするか、明示的に追随させるかは実装時に判断する（default を付ける方が diff が小さい）

## 2. blocker 列挙 read API

- [x] 2.1 `LlmExecutionAdmissionHealth.kt` に read-only な列挙 API を追加する。`recoveryBlockers` と `heartbeatFailures` の union を (invocationId, claimantToken) の集合として返す。戻り値は public な型が必要なので、`ClaimHealthKey` を public にするか public な data class を新設する（`@Immutable` 相当の KDoc を日本語で付ける）
- [x] 2.2 読み出しは `admissionLock.read {}` の中で行い、呼び出し側が iterate 中に mutation されない snapshot（コピー）を返す
- [x] 2.3 mutation API は追加しない。解除には既存の `resolveClaim(invocationId, claimantToken)` を使う（design Decision 4 / spec「Runtime gains no unconditional blocker reset」）

## 3. 監査 event type

- [x] 3.1 `CommandEvent.kt` の `CommandEventType` に `LLM_EXECUTION_ADMISSION_BLOCKER_RESOLVED` を追加する。既存 `LLM_EXECUTION_RECOVERY_STARTED`（`:110`）の直後に置き、KDoc を日本語で書く
- [x] 3.2 `OpsRoutes.kt:2247` 付近の `toActivityAuditEventDefinition()` の網羅 `when` に `llmExecutionAdmissionBlockerResolved` を追加する（網羅 when なのでコンパイルエラーが漏れを検出する）

## 4. recovery service への blocker 照合 pass

- [x] 4.1 `LlmExecutionRecoveryService` の constructor に `commandEventLog: CommandEventLog` を追加する。`LlmExecutionRecoveryWorker.kt:43` の生成箇所は既に `commandEventLog` を持っているのでそれを渡す
- [x] 4.2 `tickWithinBudget()` に blocker 照合 pass を追加する。配置は `reconcilePendingRecoveries(deadline)` の後、stale scan の前（design Decision 5）
- [x] 4.3 pass の各 blocker について: `requireRecoveryStartReserve(deadline)` → `repository.findExecutionClaim(invocationId)` → 解除条件判定。`findExecutionClaim` の失敗は `setRecoveryScanHealthy(false)` して throw する（既存の scan / mutation 失敗と同じ扱い）
- [x] 4.4 解除条件を実装する（design Decision 2 / spec Requirement 1）。4 条件すべてを満たす場合だけ解除する:
  - `snapshot.status != LlmLaunchReservationStatus.RUNNING`
  - `snapshot.finishedAt != null`
  - blocker の `claimantToken` が `snapshot.claimantToken`（null の場合は `MISSING_CLAIMANT_TOKEN`）と厳密一致
  - `now >= snapshot.finishedAt + policy.hardTimeout + policy.processTerminationGrace`
- [x] 4.5 解除は「監査 append を先に成功させてから `resolveClaim`」の順で行う。append 失敗は throw して解除しない（design Decision 6 / spec「Audit append fails」Scenario）
- [x] 4.6 監査 payload を組み立てる。`invocationId` / `claimantToken` / `reservationStatus` / `finishedAt` / `resolvedAt` / `clearanceWindowSeconds` を入れ、secret は入れない。tool name は既存の `llm_execution_recovery` を使う（`LlmExecutionRecoveryWorker.kt:154` の定数を共有できるか確認する。できなければ同値の定数を service 側に置く）
- [x] 4.7 `LlmExecutionTerminationFenceRegistry` の fence entry も解除するか判断する。**判断: 触らない**。admission health は fence を見ないため解除に不要で、fence を持つ claim は stale scan の正規経路が claim transition lock の内側で解除する。この pass は lock を取らないため、lock 外から registry を触る理由をなくす方が安全（レビュー指摘対応）
- [x] 4.8 blocker 照合 pass が stale scan の候補集合・cursor・`check(pendingRecoveries.isEmpty())` invariant に触れないことを確認する（spec「Stale claim recovery runs in the same tick」Scenario）

## 5. 回帰テスト

- [x] 5.1 `LlmExecutionRecoveryServiceTest.kt` に主回帰テストを 1 本追加する: UNCERTAIN 相当の blocker を `registerRecoveryBlocker` で登録し、reservation を terminal + `finishedAt` set 済みにして、`finishedAt + hardTimeout + grace` を超えた clock で tick を回すと `isHealthy()` が true に戻り、監査イベントが 1 件残ることを assert する（issue #350 DoD）
- [x] 5.2 解除しないケースを assert する: reservation が `RUNNING` / 窓未経過 / `finishedAt == null` / token 不一致 / reservation 行なし。1 テストにまとめず、条件ごとに分けるか parameterized にするかは実装時に判断する
- [x] 5.3 `heartbeatFailures` に同一 key が残っている状態から `isHealthy()` が true に戻ることを assert する（design Decision 4）
- [x] 5.4 `reflection-terminal:<invocationId>` 形式の合成 token で登録された blocker が解除されないことを assert する（design Decision 4 / spec「Claimant token does not match」Scenario）
- [x] 5.5 監査 append が失敗する `CommandEventLog` で tick が失敗し、blocker が残ることを assert する（`OneShotLlmRunnerTest.kt:3191` の `FailFirstAppendCommandEventLog` を参考にする）
- [x] 5.6 既存 `LlmExecutionRecoveryService(...)` 呼び出し（`LlmExecutionRecoveryServiceTest.kt:127,855` / `PostgresPersistenceIntegrationTest.kt` の 7 箇所）に `commandEventLog` を渡す。監査を検証しないものは `InMemoryCommandEventLog()` を渡す
- [x] 5.7 テストが `LlmExecutionAdmissionHealth` の process-global state を汚染しないよう、既存の reset 作法（`test-admission-health-isolation` spec）に従う

## 6. ドキュメント

- [x] 6.1 `docs/mcp-runtime.md:150` の「recovery blocker を登録して次 tick の recovery scan に委ねる」の記述を、実際に何が起こるか（DB 終端確認と clearance window による自己解除）が分かる現在形に更新する
- [x] 6.2 `docs/design.md:3569` の同趣旨の記述を同様に更新する
- [x] 6.3 fail-closed の解除条件（operator 介入 / container restart / DB 終端 + 窓超過）を記述する場所が docs にあるか確認し、あれば追随させる。新規ファイルは作らない

## 7. 検証

- [x] 7.1 編集した Kotlin ファイルに対し IntelliJ MCP の `get_file_problems` で warning を確認する。使えない場合は `make detekt` で代替する
- [x] 7.2 `make test` を実行して通す
- [x] 7.3 `make detekt` を実行して通す
- [x] 7.4 `openspec validate recover-admission-blocker-on-terminal --strict` を実行して通す
- [x] 7.5 検証記録（コマンド、結果、HEAD SHA、scope）を PR description に転記する。ドキュメント影響を 1 行明記する
