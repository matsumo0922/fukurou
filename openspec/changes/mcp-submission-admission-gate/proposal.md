## Why

`LlmExecutionAdmissionHealth` が fail-closed になっても、既に起動済みの LLM 子プロセスは terminal な判断（decision / falsification）を確定させられる。admission は「新規 LLM 起動」と「runner による発注」の gate としてしか働いておらず、実行中 invocation が結論を DB へ書き込む経路には一切かかっていない（issue #352）。

この非対称は実測で確認済みである。admission health を fail-closed にした状態で MCP tool を呼ぶ probe を同一 JVM 内（singleton へ到達できる最も有利な条件）で実行したところ、`get_ticker` と `submit_decision` の双方が成功し、`submit_decision` は decision を repository へ 1 件記録した。

`LlmDecisionSubmissionGateway` は app process 内で動く Unix domain socket server であり、process-local singleton である `LlmExecutionAdmissionHealth` へそのまま到達できる。issue が問題視した実害シナリオ（遅延 fork child が PROPOSER と FALSIFIER の両方で生存し、DB 上の同一 intent に対して連携する）は、必ず intent 発行（`submit_decision`）と承認（`submit_falsification`）を通る。この 2 点を gateway で止めれば、別 process へ状態を伝える新レイヤーを追加せずにシナリオが閉じる。

## What Changes

- `LlmDecisionSubmissionGateway.handleRequest` の先頭に admission precondition を追加する。admission が unhealthy のとき、`SUBMIT_DECISION` / `SUBMIT_FALSIFICATION` の双方を拒否し、repository へ到達させない。
- 拒否を既存の rejection code 体系へ合流させる。`SubmissionRejectionCode` に admission 由来の値を 1 つ追加し、`error=SUBMISSION_REJECTED` と併せて wire 応答へ載せる。client 側の typed exception、MCP tool error、`NO_TRADE_EXIT` の `rejectionCode` 監査はいずれも既存経路のまま新しい拒否点を運ぶ。
- gateway は invocation を終端させない。reservation の FAILED 化と process 終端は既存の recovery scanner の責務のまま据え置く。
- 「admission health が gate する範囲」を spec 上で確定させる。新規起動・runner 発注・gateway submission が対象で、MCP server process の read-only tool call は対象外であることを requirement として書き下す。

**BREAKING** なし。admission が healthy な通常系では wire も挙動も変わらない。

## Capabilities

### New Capabilities

なし。既存 capability の requirement 追加で表現できる。

### Modified Capabilities

- `submission-gateway-session`: gateway が要求を処理する precondition に「admission health が healthy であること」を追加する。unhealthy 時は repository へ到達させず、`semanticSubmissionState` を `COMMITTED` から劣化させない既存契約も維持する。
- `submission-rejection-diagnostics`: 閉じた rejection code 語彙へ admission 由来の値を追加する。値は単一の拒否点に対応し、admission health の内部詳細（blocker token、invocation id、例外 message）を含まない。

## Impact

- `trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/LlmDecisionSubmissionGateway.kt` — `handleRequest` に precondition 1 つ。
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/decision/SubmissionRejection.kt` — enum 値 1 つ追加。
- `mcp/` 配下は無変更。MCP server は既存の rejection code 伝播経路をそのまま使う。
- 監査: `command_event_log` の `NO_TRADE_EXIT` payload に新しい `rejectionCode` 値が出現しうる。schema 変更なし。
- テスト: gateway の既存 test へ admission unhealthy 系のケースを追加。`LlmExecutionAdmissionHealthTestFixture.reset()` による test 間隔離が前提。
- ドキュメント: `docs/design.md` の admission health 記述に gate の適用範囲を反映する。
