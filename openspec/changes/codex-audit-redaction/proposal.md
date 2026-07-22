## Why

Issue #291: `LlmInvocationAuditor` は Codex provider の stdout/stderr を一律 `rawOutputOmitted=true` として監査 payload から破棄している。#282 の本番障害（2026-07-15〜）では、MCP launcher の失敗メッセージ（stderr の1行）が監査記録に残っていれば即日特定できたはずの原因究明に1週間を要した。secret 混入を恐れた出力隠蔽が診断可能性を殺しており、single-owner 構成ではコストが利益を大きく上回る。Claude provider は既に `redactor.redactAndTruncate()` で secret masking + truncation して記録しており、Codex にも同じパターンを適用する。

## What Changes

- Codex の成功時（`providerFailure == null`）に、Claude と同様 `redactor.redactAndTruncate(stdout)` / `redactor.redactAndTruncate(stderr)` を監査 payload に記録する（現状: 常に `rawOutputOmitted=true`）
- Codex の失敗時（`providerFailure != null`）に、失敗カテゴリが `AUTHENTICATION` 以外であれば同じく redactor 経由で stdout/stderr を記録する（現状: 全カテゴリで `rawOutputOmitted=true`）
- `AUTHENTICATION` カテゴリは Codex・Claude 問わず既存仕様通り raw output・token・path・prompt を一切残さない（変更しない）
- Claude の挙動は変更しない（成功時は既存通り redactor 記録、失敗時は既存通り省略のまま）
- `rawOutputOmitted` フィールドと関連ロジックをコードベースから撤去する
- `SecretRedactor` の masking パターンが Codex 出力（DB password・API key・token 系）に対して十分か確認し、不足があれば同じ PR でパターンを追加する

## Capabilities

### New Capabilities

（なし）

### Modified Capabilities

- `llm-cli-invocation-contract`: 「Provider failures have stable typed categories」の Requirement のうち、Codex 関連 Scenario を修正する。`AUTHENTICATION` は既存通り raw output 非保持のまま、それ以外のカテゴリ（`PROCESS_EXIT` / `PROCESS_TIMEOUT` / `CLEANUP` / `OUTPUT_CONTRACT` / `UNKNOWN_PROVIDER_FAILURE` 等）と成功時は、Codex についてのみ redactor 経由で stdout/stderr を記録するよう明文化する

## Impact

- `trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/LlmInvocationAuditor.kt`: `phaseDetails()` 内の provider 分岐（L340-380 付近）
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/SecretRedactor.kt`: masking パターンの確認、必要なら追加
- `trading/src/test/kotlin/me/matsumo/fukurou/trading/runner/LlmInvocationAuditorTest.kt`: 既存の `rawOutputOmitted` assertion の更新、Codex 失敗時・成功時の redacted 出力確認テストの追加、secret masking 回帰テストの追加
- `trading/src/test/kotlin/me/matsumo/fukurou/trading/runner/OneShotLlmRunnerTest.kt`: L2362 付近の `rawOutputOmitted` assertion の更新
- 依存: なし（Epic #286 内の他 sub-issue と独立）
- 破壊的変更: なし（監査 payload に新しいキー `stdout`/`stderr` が Codex の行にも出現するようになるが、既存キーの削除は `rawOutputOmitted` のみで、これは内部監査用フィールドでありコンシューマー契約ではない）
