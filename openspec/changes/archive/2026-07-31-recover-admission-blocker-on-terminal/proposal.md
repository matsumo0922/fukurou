## Why

2026-07-31、daemon run `05d2095d` で Proposer（codex CLI）が 190 秒で timeout し、process tree の終了を証明できないまま `UNCERTAIN` で終端した。`OneShotLlmRunner` は recovery blocker を登録したが、reservation は同じ `finally` で `FAILED` として DB 終端した。以後の recovery scan は `status = 'RUNNING'` の行しか候補にしないため、この blocker を触る経路が構造的に存在せず、`LlmExecutionAdmissionHealth` が **約 2 時間 15 分** fail-closed に固着した。daemon は 1 回も起動せず、`/health/ready` も not_ready のままで、`docker restart` まで回復しなかった（issue #350）。

fail-closed 自体は正しい。生死不明の CLI が注文を出しうる間、新規 admission を止めるのは資金保護として必要な挙動である。欠陥は解除経路の欠落であり、無人運用で自己回復できない点にある。

## What Changes

- recovery scan の各 tick に、in-memory blocker を DB 終端事実に照合する pass を追加する。blocker が指す reservation が terminal（`FINISHED` / `FAILED`）かつ `finished_at + hardTimeout + processTerminationGrace`（既定値で 580 秒）を超過している場合にだけ解除する
- 解除は blocker の claimant token が DB の `execution_claim_token` と厳密一致する場合に限る。`ReflectionTerminalPersistenceSupervisor` の合成 token（`reflection-terminal:<id>`）は DB token と一致しないため、この経路の対象外となり自前の retry loop に残る
- 解除時に `command_event_log` へ監査イベント 1 件を残す（新 `CommandEventType`）
- `LlmExecutionClaimSnapshot` に `finishedAt` を additive に追加する。既存の判定・比較ロジックの意味は変えない
- `LlmExecutionAdmissionHealth` に blocker を列挙する read API を追加する。runtime から blocker を無条件に消す public reset は追加しない

**BREAKING**: なし。DB schema 変更なし（`finished_at` は既存列）

### PR 分割

human-authored diff は約 300 行の見積りで、1 PR で収まる。分割すると「blocker 列挙 API だけがあって解除経路がない」中間状態が main に入り、fail-closed の解除条件が 2 つの PR にまたがってレビューしづらくなるため、分割しない。

## Capabilities

### New Capabilities

- `llm-admission-blocker-recovery`: process-local admission blocker が DB の終端事実だけを根拠に自己解除される条件、解除してはならない条件、監査証跡、および解除判定が recovery scan の bounded budget を壊さないことを定義する

### Modified Capabilities

- `llm-process-cleanup-terminal`: Scenario「Supervisor acknowledgement is absent」が要求する「execution admission remains fail-closed until operator verification or container restart」を改訂する。operator 介入と container restart に加えて、DB 終端が確認され遅延 fork の生存窓を超過した場合の自己解除を許可する

## Impact

- `trading/src/main/kotlin/me/matsumo/fukurou/trading/daemon/LlmExecutionAdmissionHealth.kt`: blocker 列挙 read API の追加
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/daemon/LlmLaunchReservationRepository.kt`: `LlmExecutionClaimSnapshot.finishedAt` の追加、in-memory 実装の `toClaimSnapshot()` 追随
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedLlmLaunchReservationRepository.kt`: `SELECT_LLM_EXECUTION_CLAIM_SQL` への `finished_at` 追加と mapper 追随
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/LlmExecutionClaimSupervisor.kt`: `LlmExecutionRecoveryService` への blocker 照合 pass 追加
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/audit/CommandEvent.kt`: 新 `CommandEventType`
- `fukurou/src/main/kotlin/me/matsumo/fukurou/OpsRoutes.kt`: activity audit event definition の網羅 `when` への追随
- `trading/src/test/.../LlmExecutionRecoveryServiceTest.kt`: 回帰テスト
- `docs/mcp-runtime.md` / `docs/design.md`: recovery blocker の解除条件の現在形記述

## 受け入れ条件との対応（issue #350）

| 受け入れ条件 | 対応 |
|---|---|
| UNKNOWN 終端で登録された blocker が DB 終端確認後の recovery scan で自動解除される | この change |
| 解除の監査イベントが command_event_log に残る | この change |
| 回帰テスト 1 本 | この change |
| codex CLI timeout 自体の解消 | scope 外（issue 記載） |
| fail-closed 機構の撤去・緩和 | scope 外（issue 記載） |
| blocker の永続化・分散対応 | scope 外（issue 記載） |
| MCP tool-call 経路への admission gate 追加 | scope 外（follow-up issue）。反証ゲートで既存の欠落として発見した。別 process への状態伝播という新機構を要し、受け入れ条件に紐付かない |
