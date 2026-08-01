## 1. admission health への submission 判定 API 追加（design D4 / R4 の処置）

- [x] 1.1 `trading/.../daemon/LlmExecutionAdmissionHealth.kt` に `recoveryScanInProgress` flag と、tick の開始／完了を伝える API を追加する。完了 API は成否を受け取り、`recoveryScanHealthy` と `recoveryScanInProgress` を `admissionLock.write` の内側で同時に更新する
- [x] 1.2 `trading/.../runner/LlmExecutionClaimSupervisor.kt:182`（tick 冒頭の無条件 `setRecoveryScanHealthy(false)`）を開始 API の呼び出しへ置き換える。`recoveryScanHealthy` は前回の値を維持する
- [x] 1.3 tick の終了時に**成否によらず**完了 API を呼ぶ。`try`/`finally` で `recoveryScanInProgress` を確実に下ろし、成功時のみ `recoveryScanHealthy=true`、失敗・timeout・cancellation では false を確定させる（`:186-190` の置き換え）
- [x] 1.4 残り 9 箇所は `setRecoveryScanHealthy(false)` のまま変更しない。内訳は実障害 8 箇所（`LlmExecutionClaimSupervisor.kt:216,268,291,321,367,434`、`LlmExecutionRecoveryWorker.kt:71`、`Application.kt:992`）と初期化 1 箇所（`Application.kt:925`。worker start 前に初回 scan 未完了を fail-closed にする）。初期化も「まだ成功した scan が無い」を表すため false のままでよい
- [x] 1.5 submission gate 用の read API を追加する。条件は「3 集合（`ambiguousClaims` / `recoveryBlockers` / `heartbeatFailures`）が空、かつ `recoveryScanHealthy` が true」とし、`recoveryScanInProgress` は無視する。`admissionLock.read` の内側で判定する
- [x] 1.6 `isHealthy()` の内部式に `!recoveryScanInProgress` を加え、**外部から観測できる判定結果**を変更しない。tick 実行中に false を返す従来の挙動が保たれることを確認する
- [x] 1.7 新 API の KDoc に「submission gate 用であり、正常な scan 実行中は通すが scan の実障害は fail-closed にする」ことを現在形で書く

## 2. UNCERTAIN 履歴の照会 API（F1 の処置 / design D1）

- [x] 2.1 `trading/.../invoker/LlmInvoker.kt` の `LlmProcessTreeTerminationRegistry` に、`anyUncertain` のみを返す read API を追加する（完了済み child の UNCERTAIN 履歴。entry 不在は false）
- [x] 2.2 新 API が `childUnresolved` を判定に使わないことを確認する。実行中で未終了の child を UNCERTAIN 扱いしてはならない
- [x] 2.3 既存の `find()` / `record()` / `markChildStarted()` / `resolve()` の実装を変更しない
- [x] 2.4 新 API の KDoc に「gateway の submission gate 用であり、実行中 child を UNCERTAIN 扱いしない」ことを現在形で書く
- [x] 2.5 この変更が admission health を読み書きしないことを確認する（`LlmExecutionAdmissionHealth` への参照を追加しない）
- [x] 2.6 **one-shot の entry lifecycle を閉じる（H1 の処置 / design D1a）**: `OneShotLlmRunner` の `finally` で、proof 読み取りと blocker 登録の直後・**terminal persistence より前**に `LlmProcessTreeTerminationRegistry.resolve(invocationId)` を呼ぶ。`requireTerminalLlmRun` と `finish().getOrThrow()` は throw しうるため後ろに置かない
- [x] 2.7 `LlmExecutionAdmissionHealth.resolveClaim` と `LlmExecutionTerminationFenceRegistry.resolve` は従来どおり UNCERTAIN では呼ばない。これらは blocker 解除に相当し DB 確認を要するため、registry の resolve だけを条件から外す
- [x] 2.8 **単一 phase の呼び出し元の entry lifecycle を閉じる**: `LlmInvocationAuditor` に `retainsProcessTreeProof`（既定 false）を追加し、false のとき phase 終了時に registry を解放する。解放は gateway close と `terminalProjection` 生成の**後**に置く（`processExitTerminal` が `processResult` 不在時に `find()` へフォールバックするため）
- [x] 2.9 `OneShotLlmRunner` と daemon pre-filter が生成する auditor へ `retainsProcessTreeProof = true` を渡す。pre-filter は one-shot と同じ invocation ID で動き失敗時に同じ run へ fail-open するため、run 終了まで履歴を保持する。Reflection / EVALUATION_REPORT は既定の false のままとし、phase 終了で解放されることを確認する

## 3. Rejection code の追加

- [x] 3.1 `trading/.../decision/SubmissionRejection.kt` の `SubmissionRejectionCode` に admission 由来の値を 1 つ追加する。`wireValue` は既存 18 値と重複しない `snake_case`、`message` は admission health の内部状態（invocation id、claimant token、blocker 種別、blocker 数）を含まない定型文にする
- [x] 3.2 `gatewayRejectionCode()` の分岐が新しい値を素通しすることを確認する。不要なら変更しない
- [x] 3.3 client 側（`mcp/.../LlmDecisionSubmissionGatewayClient.kt`）が wire の `reason` を enum lookup で復元しており、新しい値が自動的に伝播することを確認する

## 4. Gateway への action-aware precondition（design D2 / D3 / D4 / D5）

- [x] 4.1 `trading/.../runner/LlmDecisionSubmissionGateway.kt` の `SUBMIT_FALSIFICATION` 経路に、phase 認可の直後・payload decode の前で precondition を追加する
- [x] 4.2 `SUBMIT_DECISION` 経路に、payload decode の後・repository 呼び出しの直前で precondition を追加する。action が `EXIT` / `REDUCE` / `NO_TRADE` の場合は通す。**`ADJUST_PROTECTION` は通さない**（design D2: take-profit のみ変更し単調性の保証が無いため risk-reducing と言えない）
- [x] 4.3 gate 条件は「1.4 の submission 判定 API が false を返す」または「2.1 の UNCERTAIN 履歴 API が true を返す」の OR とする。`isHealthy()` を使わない
- [x] 4.4 gateway が admission health の状態を変更しないこと、reservation を終端させないことを実装で担保する（読み取りのみ、副作用なし）
- [x] 4.5 `make detekt` を通す

## 5. 回帰テスト

- [x] 5.1 全ケースで `LlmExecutionAdmissionHealthTestFixture.reset()` により test 間隔離を担保する
- [x] 5.2 blocker 有りで `SUBMIT_FALSIFICATION` が `accepted=false` で拒否され、falsification repository が呼ばれないことを検証する
- [x] 5.3 blocker 有りで risk を増やす `SUBMIT_DECISION`（`ENTER`）が拒否され、decision repository が呼ばれないことを検証する
- [x] 5.4 blocker 有りでも `EXIT` / `REDUCE` の decision submission が `accepted=true` で通ることを検証する
- [x] 5.5 blocker 有りでも `NO_TRADE` の decision submission が通ることを検証する
- [x] 5.5a blocker 有りで `ADJUST_PROTECTION` の decision submission が拒否されることを検証する（R3 の処置）
- [x] 5.6 binding 不一致の要求では、blocker があっても拒否理由が binding mismatch になることを検証する（precondition の順序を固定する）
- [x] 5.7 `COMMITTED` 到達後に blocker で拒否されても `semanticSubmissionState()` が `COMMITTED` のままであることを検証する
- [x] 5.8 admission 由来の拒否によって launch reservation の status と execution claim state が変化しないことを検証する — **型で保証済みのため専用テストは追加しない**。`LlmDecisionSubmissionGateway.start()` は `DecisionRepository` のみを受け取り、launch reservation repository への参照を持たない（構造上、変更が不可能）
- [x] 5.9 recovery scan が正常に実行中（1.1 の実行中 flag が立っている）でも、blocker が無く実障害も無ければ submission が通ることを検証する（F3 の誤拒否回避）
- [x] 5.9a `recoveryScanHealthy` が false（実障害）のとき、blocker が無くても risk を増やす submission が拒否されることを検証する（R4 の処置）
- [x] 5.9b `isHealthy()` の判定結果がこの変更の前後で同一であることを検証する。特に tick 実行中に false になる従来の挙動が保たれること
- [x] 5.9c tick が失敗・timeout・cancellation で終わったとき `recoveryScanInProgress` が false へ戻り、`recoveryScanHealthy` が false で確定することを検証する（1.3 の `finally` 保証）
- [x] 5.9d 初回 tick 成功前は risk を増やす submission が拒否され、成功後に通ることを検証する
- [x] 5.9e UNCERTAIN で終端した run の `finally` 完了後、registry entry が resolve されていることを検証する（H1 の処置）
- [x] 5.9f 同一 invocationId で新しい gateway を作ったとき、前の run の UNCERTAIN 履歴によって拒否されないことを検証する（H1 の処置）
- [x] 5.9g **failure injection**: terminal persistence（`requireTerminalLlmRun` または `finish()`）が throw しても registry entry が解放されていることを検証する（B1 の処置）
- [x] 5.9h `retainsProcessTreeProof = false` の auditor が phase 終了時に registry を解放することを検証する（B2 の処置）
- [x] 5.9i `retainsProcessTreeProof = true` の auditor が phase 終了時に解放せず、後続 phase が UNCERTAIN 履歴を参照できることを検証する
- [x] 5.10 blocker 無し時の wire 応答と永続化がこの変更の前後で同一であることを、既存 test が変更なしで通ることをもって確認する
- [x] 5.11 rejection code 語彙の閉性 test に新しい値が含まれ、`[a-z][a-z0-9_]*` に一致することを検証する
- [ ] 5.12 `NO_TRADE_EXIT` の監査 payload の `rejectionCode` が admission 由来の識別子になり、`reason` が `tool_call_failed` のままであることを検証する — **未着手**。MCP server process と `McpToolCallLimiter` を含む統合 fixture が要る。伝播経路は既存の rejection code 機構をそのまま使うため（3.2 / 3.3 で確認済み）新しい分岐はないが、end-to-end の観測は残る
- [x] 5.13 UNCERTAIN 履歴 API の test: `record(UNCERTAIN)` 後に true を返すことを検証する
- [x] 5.14 UNCERTAIN 履歴 API の test: `markChildStarted` のみ（実行中で未終了）では false を返すことを検証する。**実行中の PROPOSER 自身の submission を拒否しないことの根拠**
- [x] 5.15 UNCERTAIN 履歴 API の test: `record(PROVEN_EXITED)` のみでは false を返すことを検証する
- [x] 5.16 UNCERTAIN 履歴 API の test: UNCERTAIN 記録後に次 child が `markChildStarted` しても true を保つことを検証する
- [x] 5.17 UNCERTAIN 履歴による拒否で admission health の blocker 集合と flag が変化しないことを検証する
- [x] 5.18 **production call path のテスト**: 中心シナリオ（PROPOSER が `UNCERTAIN` 終端 → FALSIFIER の `SUBMIT_FALSIFICATION` が拒否される）を、手組み入力ではなく registry と gateway の配線経由で確認する
- [x] 5.19 **production call path のテスト**: 正常系（最初の PROPOSER が実行中に `submit_decision` を送る）が拒否されないことを、同じ配線経由で確認する
- [x] 5.20 `make test` を通す

## 6. ドキュメント

- [x] 6.1 `docs/design.md` の LLM execution claim / admission health を扱う箇所に、gate の適用範囲を反映する。新規起動・runner 発注・gateway submission が対象で、MCP の read-only tool call は対象外、risk を減らす decision は例外であること、gateway が admission blocker と UNCERTAIN 履歴の 2 条件を見ることを現在形で書く
- [x] 6.2 `docs/` と `README` を `admission`、`LlmExecutionAdmissionHealth`、`submission gateway` で grep し、この変更で誤りになった記述がないか確認する
- [x] 6.3 PR description に「ドキュメント影響: あり（対象ファイル）」を 1 行書く

## 7. 仕上げ

- [x] 7.1 `make build` を通す
- [x] 7.2 issue #352 の「検討すべき方向（未決定）」に対する結論（UNCERTAIN 履歴 + gateway gate を採用、DB 投影・IPC・phase 境界での blocker 登録は却下）が design.md に記録されていることを確認する
