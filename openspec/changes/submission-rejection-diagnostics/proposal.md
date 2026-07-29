## Why

本番の Proposer run が `submit_decision` の拒否から復帰できずに `proposer_missing_decision` で終わったとき、拒否の実理由が事後調査できない。gateway は拒否を `DECISION_SUBMISSION_CONFLICT` / `DECISION_SUBMISSION_UNKNOWN` / `SUBMISSION_REJECTED` の 3 コードに潰し、binding mismatch も phase 認可違反も payload decode 失敗も最後の 1 コードへ合流する。client はそれを固定文言の `IllegalStateException` に変換するため、LLM には「拒否された」以上の情報が届かず、監査には例外クラス名しか残らない。2026-07-24 に観測した 2 run（invocation `a9912856` / `3aeb4577`）の初回拒否は、この情報損失により今も原因が特定できていない。

## What Changes

- gateway の拒否点を有限の rejection code 語彙へ分類し、拒否応答フレームに optional な `reason` フィールドとして載せる。code は `snake_case` 識別子の閉じた集合のみで、例外 message・filesystem path・payload 断片を含めない
- client が `reason` を保持した専用例外へ復元し、MCP tool error の `type` と `message` を通じて LLM に返す
- `NO_TRADE_EXIT` の監査 payload に `rejectionCode` を追加し、gateway 由来の拒否だけに付与する

## Capabilities

### New Capabilities

- `submission-rejection-diagnostics`: gateway が拒否した submission の理由を、有限語彙の rejection code として LLM と監査イベントの双方へ伝える契約

### Modified Capabilities

（なし。`decision-submission-idempotency` の既存 Requirement は変更しない。本 change は同 spec の Requirement 6「conflict 応答に raw exception や secret を露出しない」を満たす範囲でのみ診断情報を足す）

## Impact

- `trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/LlmDecisionSubmissionGateway.kt`: 拒否分類と wire frame
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/tool/NoTradeAuditPayload.kt`: 監査 payload
- `mcp/src/main/kotlin/me/matsumo/fukurou/mcp/LlmDecisionSubmissionGatewayClient.kt`: 拒否応答の復元
- `mcp/src/main/kotlin/me/matsumo/fukurou/mcp/FukurouMcpServer.kt`: tool error type の写像
- wire protocol: 追加は optional field 1 つのみ。既存の `accepted` / `error` は変えない
