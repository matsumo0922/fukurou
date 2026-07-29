## Why

2026-07-23 の production 障害で、Codex の refresh token 失効により全 run が失敗したにもかかわらず、監査 payload の `authFailureSuspected` が一度も立たなかった。stderr には認証エラー（`Failed to refresh token: 401 Unauthorized` / `token_expired`）が出ていたが、primary failure category は stdout が JSONL contract を満たさなかったことで `OUTPUT_CONTRACT`、あるいは `UNKNOWN_PROVIDER_FAILURE` に解決された。`authFailureSuspected` は primary category が `AUTHENTICATION` の場合しか true にならないため、認証障害でありながら認証障害として観測されない状態が続いた。

PR #300 で導入済みの `authEvidenceObserved`（parser が primary category の先勝ち解決と独立に追跡する認証 evidence の観測フラグ）は既に存在するが、raw output の抑止条件にしか使われておらず、運用通知シグナルへ接続されていない。

## What Changes

- `LlmInvocationAuditor` の `authFailureSuspected` 導出を、primary category の判定に `authEvidenceObserved` を OR で加えた形へ変更する。認証 evidence が観測された invocation は、primary category を問わず `authFailureSuspected=true` として監査 payload と運用ログに現れる
- `CODEX_KNOWN_AUTH_EVIDENCE_TEXTS` へ、今回の実障害で観測された 3 文言（`Failed to refresh token` / `refresh_token_reused` / `token_expired`）を追加する。これらは evidence 追跡（部分一致）専用の集合にだけ加え、primary category を確定させる `CODEX_STDERR_AUTH_FAILURES`（完全一致）や `knownCompatibilityFailureCategory()` の分類表は変更しない
- 上記文言を含む Codex invocation は raw stdout/stderr が監査へ記録されなくなる（`authEvidenceObserved` が raw output 抑止条件を兼ねるため）。認証障害の診断は `authFailureSuspected=true` で足りるため、この抑止はトレードオフとして受容する

## Capabilities

### New Capabilities

なし。

### Modified Capabilities

- `llm-cli-invocation-contract`: 認証 evidence の観測が primary category と独立に運用通知シグナル（`authFailureSuspected`）を立てることを新しい要件として追加し、既存の typed-category 要件が記述する `CODEX_KNOWN_AUTH_EVIDENCE_TEXTS` の構成を、追加する 3 文言を含む形へ更新する

## Impact

- `trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/LlmInvocationAuditor.kt`: `authFailureSuspected` の導出式と KDoc
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/invoker/DefaultLlmOutputParser.kt`: `CODEX_KNOWN_AUTH_EVIDENCE_TEXTS` の定義と KDoc
- 下流の観測面（挙動は自動的に追従し、コード変更は不要）:
  - `MonitoringRepository`: `/ops` の provider 集計における authentication-failure カウント
  - `OneShotLlmRunner.noDecisionAuditReason()`: decision 未保存時の no-trade 理由が `proposer_no_tool_calls` ではなく `proposer_missing_decision` に解決されるケースが増える
  - `LLM_CLI_AUTH_FAILURE_RUNBOOK_MESSAGE` の運用ログ出力
- Claude provider は影響を受けない（`parseClaude()` は `authEvidenceObserved = false` を常に設定するため）
