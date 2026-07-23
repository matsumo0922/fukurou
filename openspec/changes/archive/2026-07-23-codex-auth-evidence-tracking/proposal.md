## Why

Issue #291（PR #296）は、Codex の raw output 監査記録を「出力テキストを一切解釈しない process-lifecycle 3カテゴリ（`PROCESS_EXIT`/`PROCESS_TIMEOUT`/`CLEANUP`）」だけに限定して安全に導入した。しかし issue #282 の実際の production 障害（`failureCategory=OUTPUT_CONTRACT, providerCode=SCHEMA_DRIFT`）はこのスコープに含まれず、同じ障害が再発しても引き続き stderr を確認できない。

`OUTPUT_CONTRACT`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`UNKNOWN_PROVIDER_FAILURE` を単純に allowlist へ追加できないのは、これらが Codex の出力テキストを解釈して分類されるカテゴリであり、`DefaultLlmOutputParser` の先勝ち方式カテゴリ決定により、認証 evidence が別カテゴリ（または lifecycle カテゴリ）に埋もれて raw output に混入するリスクがあるため（#291 の独立反証で複数の反例が実証済み）。この risk を解消するには、primary category の決定とは独立に「認証 evidence を出力中に一度でも観測したか」を追跡する仕組みが要る。

## What Changes

- `DefaultLlmOutputParser.parseCodex()` に、primary category の先勝ち解決とは独立した認証 evidence 追跡を追加する。`turn.failed`/`error` イベントの message が `AUTHENTICATION` に分類される場合、および stdout/stderr の生テキストに既知の認証失敗文言（`CODEX_STDERR_AUTH_FAILURES`）が含まれる場合、first-win で他カテゴリに確定済みかどうかに関わらず evidence を記録する
- 追跡結果を `ParsedLlmOutput`/`LlmInvocationResult` の新規フィールド `authEvidenceObserved: Boolean` として運ぶ（`LlmProviderFailure` ではなく `ParsedLlmOutput` に置く理由は design.md 参照）
- `LlmInvocationAuditor.isSafeCodexLifecycleFailure()` を、次の2経路の disjunction へ拡張する
  1. 既存の lifecycle 経路（`PROCESS_EXIT`/`PROCESS_TIMEOUT`/`CLEANUP` かつ `cliErrorReported == false`）
  2. 新規の output-interpreted 経路（`OUTPUT_CONTRACT`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`UNKNOWN_PROVIDER_FAILURE`）— `authEvidenceObserved == false` の場合のみ
  - どちらの経路も `authEvidenceObserved == true` なら raw output を記録しない（最優先の否定条件）
  - `AUTHENTICATION` は両経路から除外されたまま変わらず、raw output を一切保持しない
- #282 と同形の production payload（`OUTPUT_CONTRACT`/`SCHEMA_DRIFT`、MCP handshake 失敗を模した非 JSON stdout）を fixture 化し、redact 済み stderr が監査記録に残ることを確認する回帰テストを追加する
- 既存テストのうち、auditor 側で stderr を直接 inspect していた前提（旧 condition 3）に依存するものを、新しい `authEvidenceObserved` フラグ経由の検証に更新する

## Capabilities

### New Capabilities

（なし）

### Modified Capabilities

- `llm-cli-invocation-contract`: 「Provider failures have stable typed categories」要件のうち、Issue #291 で追加された Codex raw output 保持条件を、認証 evidence 独立追跡を用いた2経路の条件へ拡張する

## Impact

- `trading/src/main/kotlin/me/matsumo/fukurou/trading/invoker/LlmInvocationModels.kt`（`ParsedLlmOutput`/`LlmInvocationResult` にフィールド追加）
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/invoker/DefaultLlmOutputParser.kt`（`parseCodex()` の evidence 追跡）
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/invoker/LlmInvoker.kt`（`ParsedLlmOutput` → `LlmInvocationResult` のフィールド伝播）
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/LlmInvocationAuditor.kt`（`isSafeCodexLifecycleFailure()` の条件拡張）
- `trading/src/test/kotlin/me/matsumo/fukurou/trading/invoker/DefaultLlmOutputParserTest.kt`（evidence 追跡の単体テスト）
- `trading/src/test/kotlin/me/matsumo/fukurou/trading/runner/LlmInvocationAuditorTest.kt`（#282 同形 fixture、既存テストの更新）
- `docs/design.md`（安全カテゴリの記述更新）
- 影響範囲は Codex provider の failure-path audit のみ。Claude の audit 挙動、成功時の audit 挙動、`AUTHENTICATION` カテゴリの非保持方針は変更しない
