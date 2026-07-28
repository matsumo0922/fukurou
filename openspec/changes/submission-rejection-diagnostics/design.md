## Context

`submit_decision` の拒否は 2 本の独立した経路を通る。LLM へ戻る経路（gateway wire frame → client 例外 → MCP tool error）と、監査へ残る経路（`ToolCallGuard` の catch → `NO_TRADE_EXIT` payload）である。両者とも情報が失われる起点は同じ `gatewayErrorResponse()` で、ここで `{accepted, error}` の 2 キーへ潰れる。

- `LlmDecisionSubmissionGateway.kt:321` `gatewayErrorResponse()` は `Throwable` を 3 コードへ写像する。`handleRequest()` 内の `require(...)` message（binding mismatch、phase 認可、RISK_REDUCTION_ONLY 制約）と codec の decode 失敗はすべて `SUBMISSION_REJECTED` へ合流する
- `LlmDecisionSubmissionGatewayClient.kt:86-90` は未知コードを `error("App-owned submission gateway rejected request.")` にする。`toolErrorType` の写像表に `IllegalStateException` は無いため、LLM には `type=tool_call_failed` / `message="App-owned submission gateway rejected request."` だけが届く
- 監査側は `NoTradeAuditPayload.kt:12-22` が `cause` に例外クラス名を、`message` を allowlist 完全一致時のみ残す。gateway 拒否の message は allowlist 外なので `{"reason":"tool_call_failed","cause":"IllegalStateException","noTrade":true}` にしかならない

制約は `decision-submission-idempotency` spec の Requirement 6（canonical hashing は bounded、secret・path・raw exception を conflict 応答に露出しない）と、#323 で確立した「payload に載せる文字列は自由文でなく、コードが定義した定数に限る」方針である。`command_event_log.payload` に対する secret パターン検査は存在せず（`ManifestPersistencePolicy.validateCommandEvent` は payload に `validateBounded` のみ適用）、実質のゲートは値域の閉じ方だけである。

## Goals / Non-Goals

**Goals:**

- gateway が拒否したとき、どの拒否点で落ちたかを監査イベントから特定できる
- 同じ情報が LLM にも届き、次の提出で修正すべき箇所を LLM が判断できる
- 追加する文字列がコード定義の閉じた集合に限られ、secret 混入経路を新設しない

**Non-Goals:**

- run 内リトライの許容（accept loop の変更）。別 change `submission-retry-within-run` で扱う
- `LlmSemanticSubmissionState` の遷移変更。conflict / unknown が `IN_FLIGHT` のまま残り audit 上 `UNKNOWN` になる現在の挙動は維持する（commit 済みかもしれない状態を `REJECTED` へ落とすと paper 真実性を歪めるため）
- fail-closed 原則の変更
- `QueueSnapshotDiagnostics.PERSISTABLE_MESSAGES` allowlist の拡張

## Decisions

### D1: rejection code は enum を正本とし、wire では `reason` 文字列として運ぶ

`SubmissionRejectionCode`（`trading` module、`decision` package）を新設し、`wireValue: String` を持つ enum とする。値は `[a-z][a-z0-9_]*` の `snake_case`。

- `INVOCATION_BINDING_MISMATCH` / `PHASE_BINDING_MISMATCH` / `MANIFEST_BINDING_MISMATCH` / `EFFECTIVE_HASH_MISMATCH`
- `PHASE_NOT_AUTHORIZED`（operation が phase に許されない）
- `RISK_INCREASING_ACTION_REJECTED`
- `DECISION_INVOCATION_MISMATCH`（payload 内 invocationId 不一致）
- `TERMINAL_EVIDENCE_CONTRACT_VIOLATION`（`decodeTerminalEvidenceBundle` の activation/version 不整合）
- `MALFORMED_REQUEST`（codec の decode 失敗、未知 operation、frame 契約違反）
- `SUBMISSION_CONFLICT` / `SUBMISSION_UNKNOWN`（typed exception 由来）
- `UNCLASSIFIED`（上記に一致しない `Throwable`）

**なぜ enum か**: 「値域が閉じている」ことを型で保証でき、正規表現ゲートの後付けが不要になる。`safeDecisionRunFinalReason`（`DecisionRunProjectionRepository.kt:13`）が採る「公開可能識別子に限定する」既存方針の型による強化版。

**代替案**: 例外 message を正規表現でサニタイズして通す。却下 — 外部由来の `Throwable` が任意 message を持ちうるため、通過集合がコードから読めなくなる。#323 が prefix 一致を却下したのと同じ理由。

### D2: 分類は例外型ではなく拒否点で行う

`require(...)` は `IllegalArgumentException` を投げるため、例外型からは拒否点を復元できない。専用の `SubmissionRejectedException(val code: SubmissionRejectionCode)` を導入し、`handleRequest()` の各検査点をこの例外へ置き換える。typed な conflict / unknown は既存クラスを維持し、`gatewayErrorResponse()` で code へ写像する。

**なぜ既存 exception 型を残すか**: `decision-submission-idempotency` の Requirement 3 が `decision_submission_conflict` / `decision_submission_unknown` を典型の tool error type として固定しており、既存回帰テストが依存する。code は直交する追加情報として併記する。

### D3: LLM へは code と定型文だけを返す

client は `SubmissionRejectedException(code)` を復元し、`FukurouMcpServer.throwableResult()` が `mcpErrorResult` の `structuredContent` へ `rejection_code` を追加する。`message` は code から引く定型文とし、gateway 由来文字列は素通ししない。

`mcpErrorResult`（`mcp-core`）は redactor を通らないため、ここへ自由文字列を流す設計は採らない。`executed` / `failure_kind` と同じ「非 null のときだけ put」の optional 引数パターンに揃える。

**type の扱い**: `SubmissionRejectedException` を `toolErrorType` 表へ追加し `submission_rejected` とする。現在の `tool_call_failed`（fallback 落ち）より分類が正確になる。conflict / unknown は既存 type を維持する。

### D4: 監査 payload は `rejectionCode` を optional キーで足す

`buildNoTradeFailurePayload(reason, cause)` に cause から code を抽出する処理を足す。抽出は `cause as? SubmissionRejectionCodeCarrier` 相当の型判定だけで行い、文字列からのパースはしない。gateway 由来でない失敗には付与しない。

**cause との重複**: `cause` は `javaClass.simpleName` で、gateway 汎用拒否では `SubmissionRejectedException` としか出ない。`rejectionCode` が実質の診断情報になる。

**payload サイズ**: 追加は 1 キー・最長 40 バイト程度。`MAX_COMMAND_EVENT_PAYLOAD_BYTES`（512 KiB）に対して無視できる。

### D5: wire 互換性は response frame の additive 拡張で保つ

`reason` は response frame の optional key として足す。request frame の protocol version（v1 / v2）は変更しない。`readFrame` は `JsonObject` へパースするだけで unknown key 拒否をしないため、旧 client でも新 gateway の応答を読める。app process と MCP subprocess は同一 image から起動するためバージョン skew は本来起きないが、`reason` 欠落時に従来どおり typed exception を投げる分岐は残す。

## Risks / Trade-offs

- **[新しい情報経路が secret 漏洩面になる]** → 値域を enum に閉じ、`Throwable.message` を wire にも payload にも載せない。全 enum 値が `[a-z][a-z0-9_]*` に一致することをテストで強制する
- **[拒否点の追加時に code 付与を忘れる]** → `handleRequest()` 内の失敗経路を `SubmissionRejectedException` へ統一し、未分類は `UNCLASSIFIED` へ落ちる。`UNCLASSIFIED` が出た場合は「分類漏れ」として運用側で検知できる
- **[LLM が code に過剰適応して同じ誤りを繰り返す]** → Stage 1 の時点では run 内リトライができないため実害はない。Stage 2 で accept loop を入れた後の観測対象とする
- **[`decision_submission_conflict` の分類が変わる]** → 変えない。type は既存のまま、code は追加フィールド。既存の tool error 回帰テストは無変更で通る想定

## Migration Plan

DB schema 変更なし。`command_event_log.payload` は TEXT で、キー追加は既存行に影響しない。ロールバックは単純な revert で足りる。運用側は `rejectionCode` の有無で新旧イベントを区別できる。
