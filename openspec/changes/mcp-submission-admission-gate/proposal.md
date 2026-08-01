## Why

`LlmExecutionAdmissionHealth` が fail-closed になっても、既に起動済みの LLM 子プロセスは terminal な判断（decision / falsification）を確定させられる。admission は「新規 LLM 起動」と「runner による発注」の gate としてしか働いておらず、実行中 invocation が結論を DB へ書き込む経路には一切かかっていない（issue #352）。

この非対称は実測で確認済みである。admission health を fail-closed にした状態で MCP tool を呼ぶ probe を同一 JVM 内（singleton へ到達できる最も有利な条件）で実行したところ、`get_ticker` と `submit_decision` の双方が成功し、`submit_decision` は decision を repository へ 1 件記録した。

さらに独立反証により、gate を置くだけでは足りないことが判明した。issue の中心シナリオ（PROPOSER が intent 発行 → termination UNCERTAIN → FALSIFIER が承認）において、**UNCERTAIN による blocker 登録は one-shot 全体の終了時にしか行われない**（`OneShotLlmRunner.kt:619-620`）。PROPOSER と FALSIFIER は同一 one-shot 内で順に走るため、FALSIFIER 実行中は admission が healthy のままで、admission blocker だけを条件にした gate は素通りする。

この穴を塞ぐのに admission health を早く fail-closed にする案は成立しない。phase 境界で blocker を登録すると、直後の `requireLiveClaimForInvocation`（`OneShotLlmRunner.kt:1377`）が `withHealthyAdmission` を通るため**自分自身の blocker で run が死ぬ**。代わりに、既に registry が保持している「完了済み child の UNCERTAIN 履歴」を gateway の第 2 の gate 条件として読む。admission health を一切変更しないので、新規起動 gate と `/health/ready` の意味論は不変に保たれる。

## What Changes

- **完了済み child の UNCERTAIN 履歴を照会する API**: `LlmProcessTreeTerminationRegistry` に、`anyUncertain`（完了した child の少なくとも 1 つが `UNCERTAIN` だった）だけを返す read API を追加する。実行中で未終了の child を `UNCERTAIN` 扱いしないため、正常な phase 自身の submission は妨げない。あわせて registry entry の解放条件を修正し、`UNCERTAIN` で終端した run でも one-shot 終了時に entry を解放する（従来は解放されず JVM 終了まで蓄積していた）。
- **gateway への precondition**: `LlmDecisionSubmissionGateway` が `SUBMIT_FALSIFICATION` と risk を増やす `SUBMIT_DECISION` を、gate 条件が該当するとき、または当該 invocation に `UNCERTAIN` 履歴があるときに拒否する。`EXIT` / `REDUCE` / `NO_TRADE` は通す。既存の `ToolCallGuard` が HARD_HALT 中でも decision を通す安全方向と揃える。`ADJUST_PROTECTION` は take-profit のみを変更して単調性の保証が無いため例外に含めない。
- **正常な scan 実行中と scan の実障害を分離する**: `recoveryScanHealthy` は tick 冒頭の無条件 false（正常な実行中）と、DB 障害・timeout・blocker 照会失敗など 10 箇所の実障害を同じ flag で表している。前者を別 flag へ分離し、submission gate は「3 集合が空、かつ実障害が無い」を条件とする。これにより正常 tick 窓での誤拒否を避けつつ、recovery が stale claim を発見できない状態は fail-closed に保つ。`isHealthy()` の判定結果は変更しない。
- **rejection code の追加**: `SubmissionRejectionCode` に admission 由来の値を 1 つ追加し、既存の `error=SUBMISSION_REJECTED` と併せて wire 応答へ載せる。client の typed exception、MCP tool error、`NO_TRADE_EXIT` の `rejectionCode` 監査はいずれも既存経路のまま新しい拒否点を運ぶ。
- **gate 範囲の明文化**: 新規起動・runner 発注・gateway submission が対象で、MCP server process の read-only tool call は対象外であることを requirement として書き下す。

**BREAKING** なし。blocker が無い通常系では wire も挙動も変わらない。

## Capabilities

### New Capabilities

なし。既存 capability の requirement 追加で表現できる。

### Modified Capabilities

- `llm-admission-blocker-recovery`: 完了済み child の `UNCERTAIN` 履歴を照会する read API と、それが後続の terminal submission を止める requirement を追加する。admission health の状態は変更しない。
- `submission-gateway-session`: gateway が terminal submission を処理する precondition に「admission blocker が無いこと」を追加する。risk を減らす action は例外とする。gate が best-effort であり残余 race を持つことを明記する。
- `submission-rejection-diagnostics`: 閉じた rejection code 語彙へ admission 由来の値を追加する。値は単一の拒否点に対応し、admission health の内部詳細を含まない。

## Impact

- `trading/src/main/kotlin/me/matsumo/fukurou/trading/invoker/LlmInvoker.kt` — `LlmProcessTreeTerminationRegistry` に `anyUncertain` のみを返す read API を 1 つ追加。既存 API の実装は不変。
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/OneShotLlmRunner.kt` — `finally` の registry 解放を `UNCERTAIN` の場合も行うよう条件から外す。admission blocker と termination fence の解放条件は不変。
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/LlmDecisionSubmissionGateway.kt` — `handleRequest` に action-aware な precondition。gate 条件は admission blocker と UNCERTAIN 履歴の OR。
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/daemon/LlmExecutionAdmissionHealth.kt` — scan 実行中を表す flag と submission gate 用 read API を追加。`isHealthy()` の判定結果は不変。
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/LlmExecutionClaimSupervisor.kt` — tick 冒頭の 1 箇所を実行中 flag へ置き換え。実障害の 6 箇所は不変。
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/decision/SubmissionRejection.kt` — enum 値 1 つ追加。
- `mcp/` 配下は無変更。MCP server は既存の rejection code 伝播経路をそのまま使う。
- 監査: `command_event_log` の `NO_TRADE_EXIT` payload に新しい `rejectionCode` 値が出現しうる。schema 変更なし。
- 運用: admission health の判定は変更しないため、`/health/ready` と新規起動 gate の挙動は不変。`UNCERTAIN` が出た run は以降の terminal submission が拒否され、NO_TRADE として終端する。
- テスト: gateway と registry の既存 test へケースを追加。`LlmExecutionAdmissionHealthTestFixture.reset()` による test 間隔離が前提。
- ドキュメント: `docs/design.md` の admission health 記述に gate の適用範囲と UNCERTAIN 履歴による拒否を反映する。
