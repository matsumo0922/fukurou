## Why

生死不明の LLM child process を検出した runner は `LlmExecutionAdmissionHealth` に recovery blocker を登録し、新規 admission と `/health/ready` を fail-closed にする。この fail-closed 自体は正しいが、blocker を解除できるのは同一 process が terminal を観測した経路だけで、DB 上で reservation が終端確認済みになった後も blocker が in-memory に残り続ける。2026-07-31 の daemon run `05d2095d` では、この残留により約 2 時間 15 分にわたり daemon が 1 度も起動せず、container 再起動でしか復旧できなかった。無人運用の前提が崩れている。

## What Changes

- recovery scan の各 tick に、既存 blocker を DB の終端事実に照らして自動解除する step を追加する
- 解除条件は「同一 claimant token の reservation が DB 上で FINISHED / FAILED」かつ「blocker 登録から `hardTimeout + processTerminationGrace` を経過」の両方が成立した場合に限定する。どちらか一方でも欠ける場合は blocker を維持する
- blocker registry が登録時刻を保持し、recovery scan から読める最小の read API を持つ
- 自動解除ごとに `LLM_ADMISSION_BLOCKER_AUTO_RESOLVED` 監査イベントを `command_event_log` へ 1 件残す。audit の append に失敗した場合は解除しない
- **BREAKING（spec-level）**: `llm-process-cleanup-terminal` の「execution admission remains fail-closed until operator verification or container restart」を、DB 終端確認と静穏期間の両方が成立した場合の自動解除を含む形へ改める

## Capabilities

### New Capabilities
- `llm-admission-self-heal`: DB 終端事実に基づく execution admission blocker の自動解除条件、静穏期間、監査、および解除しない条件

### Modified Capabilities
- `llm-process-cleanup-terminal`: 未確認 process exit 後の admission 復帰経路が operator verification / container restart だけでなくなる

## Impact

- `trading/.../daemon/LlmExecutionAdmissionHealth.kt`: blocker registry が登録時刻を持ち、read / conditional-resolve API を追加する
- `trading/.../runner/LlmExecutionClaimSupervisor.kt`: `LlmExecutionRecoveryService.tick()` に自動解除 step を追加し、`CommandEventLog` を受け取る
- `trading/.../runner/OneShotLlmRunner.kt`, `trading/.../reflection/ReflectionTerminalPersistenceSupervisor.kt`: blocker 登録時に観測時刻を渡す
- `trading/.../audit/CommandEvent.kt`, `fukurou/.../OpsRoutes.kt`: 監査イベント種別を 1 つ追加する
- `fukurou/.../LlmExecutionRecoveryWorker.kt`: recovery service へ `CommandEventLog` を配線する
- `docs/mcp-runtime.md`: 未確認終端後の復旧経路の現在形記述を更新する
- 資金・注文の実行経路そのものは変更しない。変わるのは admission gate が閉じたまま留まる条件だけ
