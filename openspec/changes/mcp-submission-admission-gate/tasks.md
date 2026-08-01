## 1. Rejection code の追加

- [ ] 1.1 `trading/src/main/kotlin/me/matsumo/fukurou/trading/decision/SubmissionRejection.kt` の `SubmissionRejectionCode` に admission 由来の値を 1 つ追加する。`wireValue` は既存値と重複しない `snake_case`、`message` は admission health の内部状態（invocation id、claimant token、blocker 種別、blocker 数）を含まない定型文にする
- [ ] 1.2 `gatewayRejectionCode()` の分岐が新しい値を素通しすることを確認する。`SubmissionRejectedException` 経路なので追加分岐は不要な想定で、不要なら変更しない
- [ ] 1.3 client 側（`mcp/.../LlmDecisionSubmissionGatewayClient.kt`）が wire の `reason` を enum lookup で復元しており、新しい値が自動的に伝播することを確認する。伝播しない場合のみ修正する

## 2. Gateway への precondition 追加

- [ ] 2.1 `trading/.../runner/LlmDecisionSubmissionGateway.kt` の `handleRequest` 先頭に admission precondition を追加する。gate 条件は design D5 の確定結果に従い、false なら 1.1 の code で `SubmissionRejectedException` を throw する
- [ ] 2.2 precondition を `trustedTerminalEvidence` 抽出と `validateGatewayBinding` より前に置く（design D3）
- [ ] 2.3 gateway が admission health の状態を変更しないこと、reservation を終端させないことを実装で担保する（読み取りのみ、副作用なし）
- [ ] 2.4 `make detekt` を通す

## 3. 回帰テスト

- [ ] 3.1 gateway の既存 test に admission unhealthy 系のケースを追加する。全ケースで `LlmExecutionAdmissionHealthTestFixture.reset()` により test 間隔離を担保する
- [ ] 3.2 admission unhealthy 時に `SUBMIT_DECISION` が `accepted=false` で拒否され、decision repository が呼ばれないことを検証する
- [ ] 3.3 admission unhealthy 時に `SUBMIT_FALSIFICATION` が `accepted=false` で拒否され、falsification repository が呼ばれないことを検証する
- [ ] 3.4 admission unhealthy かつ binding 不一致の要求で、`reason` が binding mismatch ではなく admission 由来の識別子になることを検証する（precondition の順序を固定する）
- [ ] 3.5 `COMMITTED` 到達後に admission unhealthy で拒否されても `semanticSubmissionState()` が `COMMITTED` のままであることを検証する
- [ ] 3.6 admission が healthy へ戻ったあと、同一 gateway への再提出が `accepted=true` になることを検証する
- [ ] 3.7 admission 由来の拒否によって当該 invocation の launch reservation の status と execution claim state が変化しないことを検証する
- [ ] 3.8 admission healthy 時の wire 応答と永続化がこの変更の前後で同一であることを、既存 test が変更なしで通ることをもって確認する
- [ ] 3.9 rejection code 語彙の閉性 test（既存があればそれ、なければ追加）に新しい値が含まれ、`[a-z][a-z0-9_]*` に一致することを検証する
- [ ] 3.10 `NO_TRADE_EXIT` の監査 payload の `rejectionCode` が admission 由来の識別子になり、`reason` が `tool_call_failed` のままであることを検証する
- [ ] 3.11 production call path のテストを用意する。gateway を実際に起動し、socket 越しの要求が admission gate で拒否されることを、手組み入力ではなく配線経由で確認する
- [ ] 3.12 `make test` を通す

## 4. ドキュメント

- [ ] 4.1 `docs/design.md` の LLM execution claim / admission health を扱う箇所に、admission gate の適用範囲を反映する。新規起動・runner 発注・gateway submission が対象で、MCP の read-only tool call は対象外であることを現在形で書く
- [ ] 4.2 `docs/` と `README` を `admission`、`LlmExecutionAdmissionHealth`、`submission gateway` で grep し、この変更で誤りになった記述がないか確認する
- [ ] 4.3 PR description に「ドキュメント影響: あり（対象ファイル）」を 1 行書く

## 5. 仕上げ

- [ ] 5.1 `make build` を通す
- [ ] 5.2 issue #352 の「検討すべき方向（未決定）」に対する結論（gateway gate を採用、DB 投影と IPC は却下）が design.md に記録されていることを確認する
