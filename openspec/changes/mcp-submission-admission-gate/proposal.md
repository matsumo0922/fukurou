## Why

`LlmExecutionAdmissionHealth` が fail-closed になっても、既に起動済みの LLM 子プロセスは terminal な判断（decision / falsification）を確定させられる。admission は「新規 LLM 起動」と「runner による発注」の gate としてしか働いておらず、実行中 invocation が結論を DB へ書き込む経路には一切かかっていない（issue #352）。

この非対称は実測で確認済みである。admission health を fail-closed にした状態で MCP tool を呼ぶ probe を同一 JVM 内（singleton へ到達できる最も有利な条件）で実行したところ、`get_ticker` と `submit_decision` の双方が成功し、`submit_decision` は decision を repository へ 1 件記録した。

さらに独立反証により、gate を置くだけでは足りないことが判明した。issue の中心シナリオ（PROPOSER が intent 発行 → termination UNCERTAIN → FALSIFIER が承認）において、**UNCERTAIN による blocker 登録は one-shot 全体の終了時にしか行われない**（`OneShotLlmRunner.kt:619-620`）。PROPOSER と FALSIFIER は同一 one-shot 内で順に走るため、FALSIFIER 実行中は admission が healthy のままで、gate があっても素通りする。blocker 登録のタイミングを phase 境界へ前倒しする必要がある。

## What Changes

- **phase 境界での blocker 登録**: `LlmInvocationAuditor` が LLM invoke から戻った直後に termination proof を確認し、`UNCERTAIN` なら recovery blocker を登録する。これにより次 phase の起動と submission より前に admission が fail-closed になる。
- **gateway への admission precondition**: `LlmDecisionSubmissionGateway` が `SUBMIT_FALSIFICATION` と risk を増やす `SUBMIT_DECISION` を、admission blocker がある間は拒否する。risk を減らす action（`EXIT` / `REDUCE` / `ADJUST_PROTECTION`）と `NO_TRADE` は通す。既存の `ToolCallGuard` が HARD_HALT 中でも decision を通す安全方向と揃える。
- **submission gate 専用の判定条件**: 3 集合（`ambiguousClaims` / `recoveryBlockers` / `heartbeatFailures`）のみを条件とする read API を `LlmExecutionAdmissionHealth` へ追加する。periodic recovery scan 中に周期的に false になる `recoveryScanHealthy` を条件から外し、正常な run の誤拒否を避ける。`isHealthy()` は新規起動と `/health/ready` の意味論を保つため変更しない。
- **rejection code の追加**: `SubmissionRejectionCode` に admission 由来の値を 1 つ追加し、既存の `error=SUBMISSION_REJECTED` と併せて wire 応答へ載せる。client の typed exception、MCP tool error、`NO_TRADE_EXIT` の `rejectionCode` 監査はいずれも既存経路のまま新しい拒否点を運ぶ。
- **gate 範囲の明文化**: 新規起動・runner 発注・gateway submission が対象で、MCP server process の read-only tool call は対象外であることを requirement として書き下す。

**BREAKING** なし。blocker が無い通常系では wire も挙動も変わらない。

## Capabilities

### New Capabilities

なし。既存 capability の requirement 追加で表現できる。

### Modified Capabilities

- `llm-admission-blocker-recovery`: `UNCERTAIN` 終端による blocker 登録を phase 境界へ前倒しする requirement を追加する。one-shot 終了時の既存登録は維持し、重複は冪等とする。
- `submission-gateway-session`: gateway が terminal submission を処理する precondition に「admission blocker が無いこと」を追加する。risk を減らす action は例外とする。gate が best-effort であり残余 race を持つことを明記する。
- `submission-rejection-diagnostics`: 閉じた rejection code 語彙へ admission 由来の値を追加する。値は単一の拒否点に対応し、admission health の内部詳細を含まない。

## Impact

- `trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/LlmInvocationAuditor.kt` — invoke 直後の phase 境界に blocker 登録を追加。
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/LlmDecisionSubmissionGateway.kt` — `handleRequest` に action-aware な precondition。
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/daemon/LlmExecutionAdmissionHealth.kt` — 3 集合のみを見る read API を 1 つ追加。既存 API は不変。
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/decision/SubmissionRejection.kt` — enum 値 1 つ追加。
- `mcp/` 配下は無変更。MCP server は既存の rejection code 伝播経路をそのまま使う。
- 監査: `command_event_log` の `NO_TRADE_EXIT` payload に新しい `rejectionCode` 値が出現しうる。schema 変更なし。
- 運用: `UNCERTAIN` 終端で admission が従来より早く fail-closed になるため、`/health/ready` が false になる頻度が上がりうる。PR #351 の blocker 自己解除が回復経路として働く。
- テスト: gateway と auditor の既存 test へケースを追加。`LlmExecutionAdmissionHealthTestFixture.reset()` による test 間隔離が前提。
- ドキュメント: `docs/design.md` の admission health 記述に gate の適用範囲と phase 境界登録を反映する。
