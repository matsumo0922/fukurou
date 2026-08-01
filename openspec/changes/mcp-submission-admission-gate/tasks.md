## 1. admission health への submission 判定 API 追加

- [ ] 1.1 `trading/.../daemon/LlmExecutionAdmissionHealth.kt` に、3 集合（`ambiguousClaims` / `recoveryBlockers` / `heartbeatFailures`）が空であることだけを見る read API を追加する。`admissionLock.read` の内側で判定する
- [ ] 1.2 既存の `isHealthy()` と `withHealthyAdmission()` を変更しない。新規起動と `/health/ready` の意味論は不変であることを確認する
- [ ] 1.3 新 API の KDoc に「submission gate 用であり、periodic recovery scan の一時的な flag 低下を無視する」ことを現在形で書く

## 2. phase 境界での blocker 登録（F1 の処置 / design D1）

- [ ] 2.1 `trading/.../runner/LlmInvocationAuditor.kt` の `invokeAndAudit` で `llmInvoker.invoke(request)` から戻った直後に、`LlmProcessTreeTerminationRegistry.find(invocationId)` を確認する
- [ ] 2.2 proof が `UNCERTAIN` のとき `LlmExecutionAdmissionHealth.registerRecoveryBlocker` を呼ぶ。claimant token の解決経路を確認し、`OneShotLlmRunner` の finally と同じ key で登録されることを担保する
- [ ] 2.3 proof が `PROVEN_EXITED` または未確定（`null`）のときは登録しない
- [ ] 2.4 `OneShotLlmRunner.kt:619-620` の既存登録は削除しない。重複登録が冪等であることを確認する
- [ ] 2.5 例外経路（invoke が throw した場合）でも登録が行われることを確認する

## 3. Rejection code の追加

- [ ] 3.1 `trading/.../decision/SubmissionRejection.kt` の `SubmissionRejectionCode` に admission 由来の値を 1 つ追加する。`wireValue` は既存 18 値と重複しない `snake_case`、`message` は admission health の内部状態（invocation id、claimant token、blocker 種別、blocker 数）を含まない定型文にする
- [ ] 3.2 `gatewayRejectionCode()` の分岐が新しい値を素通しすることを確認する。不要なら変更しない
- [ ] 3.3 client 側（`mcp/.../LlmDecisionSubmissionGatewayClient.kt`）が wire の `reason` を enum lookup で復元しており、新しい値が自動的に伝播することを確認する

## 4. Gateway への action-aware precondition（design D2 / D3 / D4 / D5）

- [ ] 4.1 `trading/.../runner/LlmDecisionSubmissionGateway.kt` の `SUBMIT_FALSIFICATION` 経路に、phase 認可の直後・payload decode の前で admission precondition を追加する
- [ ] 4.2 `SUBMIT_DECISION` 経路に、payload decode の後・repository 呼び出しの直前で admission precondition を追加する。action が `RISK_REDUCTION_ONLY_ACTIONS`（`EXIT` / `REDUCE` / `ADJUST_PROTECTION` / `NO_TRADE`）に含まれる場合は通す
- [ ] 4.3 判定には 1.1 の新 API を使う。`isHealthy()` を使わない
- [ ] 4.4 gateway が admission health の状態を変更しないこと、reservation を終端させないことを実装で担保する（読み取りのみ、副作用なし）
- [ ] 4.5 `make detekt` を通す

## 5. 回帰テスト

- [ ] 5.1 全ケースで `LlmExecutionAdmissionHealthTestFixture.reset()` により test 間隔離を担保する
- [ ] 5.2 blocker 有りで `SUBMIT_FALSIFICATION` が `accepted=false` で拒否され、falsification repository が呼ばれないことを検証する
- [ ] 5.3 blocker 有りで risk を増やす `SUBMIT_DECISION`（`ENTER`）が拒否され、decision repository が呼ばれないことを検証する
- [ ] 5.4 blocker 有りでも `EXIT` / `REDUCE` / `ADJUST_PROTECTION` の decision submission が `accepted=true` で通ることを検証する
- [ ] 5.5 blocker 有りでも `NO_TRADE` の decision submission が通ることを検証する
- [ ] 5.6 binding 不一致の要求では、blocker があっても拒否理由が binding mismatch になることを検証する（precondition の順序を固定する）
- [ ] 5.7 `COMMITTED` 到達後に blocker で拒否されても `semanticSubmissionState()` が `COMMITTED` のままであることを検証する
- [ ] 5.8 admission 由来の拒否によって launch reservation の status と execution claim state が変化しないことを検証する
- [ ] 5.9 `recoveryScanHealthy` が false でも blocker が無ければ submission が通ることを検証する（D4 の誤拒否回避）
- [ ] 5.10 blocker 無し時の wire 応答と永続化がこの変更の前後で同一であることを、既存 test が変更なしで通ることをもって確認する
- [ ] 5.11 rejection code 語彙の閉性 test に新しい値が含まれ、`[a-z][a-z0-9_]*` に一致することを検証する
- [ ] 5.12 `NO_TRADE_EXIT` の監査 payload の `rejectionCode` が admission 由来の識別子になり、`reason` が `tool_call_failed` のままであることを検証する
- [ ] 5.13 phase 境界登録の test: `UNCERTAIN` 終端した phase の直後に blocker が登録されていることを検証する
- [ ] 5.14 phase 境界登録の test: `PROVEN_EXITED` 終端では登録されないことを検証する
- [ ] 5.15 phase 境界登録の test: 重複登録が冪等であることを検証する
- [ ] 5.16 **production call path のテスト**: 中心シナリオ（PROPOSER が `UNCERTAIN` 終端 → FALSIFIER の `SUBMIT_FALSIFICATION` が拒否される）を、手組み入力ではなく auditor と gateway の配線経由で確認する
- [ ] 5.17 `make test` を通す

## 6. ドキュメント

- [ ] 6.1 `docs/design.md` の LLM execution claim / admission health を扱う箇所に、admission gate の適用範囲と phase 境界登録を反映する。新規起動・runner 発注・gateway submission が対象で、MCP の read-only tool call は対象外、risk を減らす decision は例外であることを現在形で書く
- [ ] 6.2 `docs/` と `README` を `admission`、`LlmExecutionAdmissionHealth`、`submission gateway` で grep し、この変更で誤りになった記述がないか確認する
- [ ] 6.3 PR description に「ドキュメント影響: あり（対象ファイル）」を 1 行書く

## 7. 仕上げ

- [ ] 7.1 `make build` を通す
- [ ] 7.2 issue #352 の「検討すべき方向（未決定）」に対する結論（phase 境界登録 + gateway gate を採用、DB 投影と IPC は却下）が design.md に記録されていることを確認する
