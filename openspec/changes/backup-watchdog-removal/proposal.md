## Why

2026-07-30、deploy の `BACKUP_AND_MIGRATE` 段階と standalone 実行の両方で backup が `DUMP_FAILED` により恒常的に失敗し、main の全デプロイが停止した（issue #336）。`bash -x` trace により、`backup-fukurou` の watchdog が dump deadline（60 秒 − grace 5 秒）到達時に pg_dump backend を `pg_terminate_backend()` で終了させていることを確認した。pg_dump 自体は健全（単体 14 秒、空 repo へのパイプライン 9.3 秒）で、`--no-cache` 固定の restic が蓄積した本番 repo の index 読み込みで stdin 消費を止め、パイプ背圧で pg_dump が stall して deadline を食い潰していた。

deploy-fukurou は既に `timeout 900` で backup 全体を外側から囲い、systemd timer 経由の日次実行も `TimeoutStartSec=20min` で囲われている。watchdog は二重の時間制限の内側であり、single-owner の paper trading DB に 55 秒で backend を強制 kill すべき脅威は存在しない。Epic #286 の線引き（保守側の理解を超える防御機構は撤去する）に従い、防御装置を調整ではなく撤去して復旧する。

## What Changes

- `backup-fukurou` から watchdog 一式（backend PID 追跡、`pg_terminate_backend`、result file、deadline 検証、関連 helper と state）を削除する。dump producer は container 内側の `timeout`（TERM + KILL 追撃）で bound し、job 全体は既存の外側 timeout（deploy の `timeout 900` / systemd の `TimeoutStartSec=20min`）が囲う。host 側 exec client の死が container 内へ伝播しない問題（反証ゲートで実機確認）は container 内 timeout が構造的に閉じる
- `restic_command()` と partial snapshot cleanup の `--no-cache` を撤去し、root の既定 cache を使う
- pg_dump / restic パイプラインの stderr 破棄（`2>/dev/null`）を撤去し、stderr をそのまま流す
- 上記に伴い不要になる result code（`WATCHDOG_TERMINATION_FAILED`）、selftest assertion、`docs/deploy.md` の記述を同じ変更で更新する

## Capabilities

### New Capabilities

（なし）

### Modified Capabilities

- `database-backup-restore`: dump phase の 60 秒 bound と exact-backend termination watchdog を要求する記述を撤去し、時間制限は呼び出し側の外側 timeout に委ねる。watchdog 前提の 2 Scenario（host-side dump client cannot stop the backend / dump completes before the watchdog deadline）を削除する。restic cache の使用を許可し、失敗時の stderr が診断可能であることを要求する

## Impact

- `scripts/backup/backup-fukurou`（watchdog 一式の削除、`--no-cache` 撤去、stderr 破棄撤去）
- `scripts/backup/backup-common`（watchdog 専用 helper が残れば削除）
- `WATCHDOG_TERMINATION_FAILED` の全参照: `scripts/backup/backup-result-codes-v1.txt` / `scripts/backup/publish-backup-monitoring` / `scripts/backup/backup-monitoring-projection.schema.json` / `scripts/backup/backup-status.schema.json` / `fukurou/src/main/kotlin/me/matsumo/fukurou/BackupMonitoringProjectionReader.kt`
- `scripts/backup/backup-selftest`（watchdog 関連 assertion の削除）
- `fukurou/src/test/kotlin/me/matsumo/fukurou/DatabaseBackupRestoreContractTest.kt`（watchdog 前提の assert があれば更新）
- `docs/deploy.md`（backup 運用記述の現在形更新）
- merge 後、runbook に従った root-owned backup artifact の更新と、acknowledge 済み deploy の再実行が必要（運用手順であり、この change のコード変更範囲外）

## 受け入れ条件との対応（issue #336）

| 受け入れ条件 | 対応 |
|---|---|
| standalone 実行が成功し integrity-checked snapshot が作成される | この change（merge 後の運用検証） |
| deploy が backup を通過し main が本番反映される | この change（merge 後の運用検証） |
| watchdog 関連コード・result code・テストの削除 | この change |
| selftest / contract test が通る | この change |
| make test / detekt が通る | この change |
| docs/deploy.md の現在形更新 | この change |
| root-owned artifact の更新 | merge 後の運用（scope 外） |
