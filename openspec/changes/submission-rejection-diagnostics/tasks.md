## 1. rejection code の語彙定義

- [x] 1.1 `SubmissionRejectionCode` enum を `trading` の `decision` package に追加し、`wireValue` と wire 値からの復元関数を定義する
- [x] 1.2 `SubmissionRejectedException(code)` を追加し、既存の conflict / unknown 例外から code を引く写像を定義する
- [x] 1.3 全 enum 値が `[a-z][a-z0-9_]*` に一致し、`wireValue` が重複しないことを検証するテストを追加する

## 2. Gateway 側の分類と応答

- [x] 2.1 `handleRequest()` の各検査点（invocation / phase / manifest / effective hash binding、phase 認可、RISK_REDUCTION_ONLY 制約、payload invocationId、未知 operation）を `SubmissionRejectedException` へ置き換える
- [x] 2.2 `decodeTerminalEvidenceBundle()` の契約違反を `TERMINAL_EVIDENCE_CONTRACT_VIOLATION` へ分類する
- [x] 2.3 codec の decode 失敗を `MALFORMED_REQUEST` へ分類する
- [x] 2.4 `gatewayErrorResponse()` が `reason` を付与し、未分類 `Throwable` は `UNCLASSIFIED` へ落ちるようにする
- [x] 2.5 gateway テストに、拒否点ごとに異なる `reason` が返ることの検証を追加する（binding 4 種、phase 認可、risk-increasing、conflict、unknown）
- [x] 2.6 未分類例外の応答が message を含まないことの検証を追加する

## 3. Client と MCP tool error

- [x] 3.1 `LlmDecisionSubmissionGatewayClient.submit()` が `reason` を読み、`SubmissionRejectedException` を投げるようにする。`reason` 欠落時は従来の例外を維持する
- [x] 3.2 `toolErrorType` 表に `SubmissionRejectedException` → `submission_rejected` を追加する。`ToolErrorTypes` は線形探索のため、`IllegalArgumentException` を継承しない基底（`RuntimeException` 直下）にして写像順序への依存を作らない
- [x] 3.3 `mcpErrorResult` に optional な `rejectionCode` 引数を追加し、`throwableResult` から渡す
- [x] 3.4 client テストに、`reason` 付き応答から code が復元されること、`reason` なし応答で従来の例外になることの検証を追加する
- [x] 3.5 MCP server テストに、gateway 拒否時の tool error へ `rejection_code` が載ることの検証を追加する

## 4. 監査 payload

- [x] 4.1 `buildNoTradeFailurePayload()` が cause から code を抽出し、optional な `rejectionCode` キーとして載せるようにする
- [x] 4.2 gateway 由来でない失敗に `rejectionCode` が付かないことの検証を追加する
- [x] 4.3 本番経路（`ToolCallGuard.runDecisionTool` の catch 経由）で `rejectionCode` が `NO_TRADE_EXIT` に残ることの検証を追加する

## 5. 検証

- [x] 5.1 `make detekt` を通す
- [x] 5.2 `make test` を通す
- [x] 5.3 既存の冪等性・fail-closed 回帰テストが無変更で通ることを確認する
