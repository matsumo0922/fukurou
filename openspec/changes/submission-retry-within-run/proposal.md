## Why

app-owned submission gateway は `server.accept().use { }` で 1 接続・1 フレームだけ処理して終了する single-shot 設計になっている。最初の提出が拒否されると、LLM が指摘を受けて修正した提出を送っても、閉じた接続への読み書きで `IOException` になり、その run では二度と提出が成立しない。2026-07-24 の 2 run はこの構造で、正しい判断を持っていた可能性のある Proposer が `proposer_missing_decision` に終わった。拒否理由が LLM に届くようになっても（先行 change `submission-rejection-diagnostics`）、直す機会が無ければ診断は活きない。

## What Changes

- gateway を「close されるまで複数フレームを受け付ける」形にし、拒否後も同一 run 内で再提出できるようにする
- 受理済み（COMMITTED）の後に届いた提出も接続を閉じずに処理し、repository の authority が冪等性を裁定する既存の意味論をそのまま使う
- `awaitCompletion()` の意味論を「最初の 1 request が完了するまで待つ」として維持し、canary の待ち合わせを壊さない

## Capabilities

### New Capabilities

- `submission-gateway-session`: 1 つの gateway が run の terminal まで複数の submission 要求を処理する接続ライフサイクルの契約

### Modified Capabilities

（なし。`decision-submission-idempotency` の Requirement はすべて維持する。同 spec の「same-payload retry は commit 済み結果を返す」「変更 retry は conflict」は、本 change で同一接続内の retry にも適用される）

## Impact

- `trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/LlmDecisionSubmissionGateway.kt`: `submissionTask()` の accept / read ループ、`close()` との停止協調
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/McpIsolationCanaryArtifacts.kt`: `awaitCompletion()` 呼び出しの前提確認
- wire protocol: 変更なし。frame 形式も request/response の対応も同じ
- DB schema: 変更なし
