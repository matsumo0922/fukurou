## 1. backup-fukurou の watchdog 撤去

- [ ] 1.1 `watch_dump_backend` / `terminate_or_observe_dump_backend` / `dump_watchdog_is_running` / `watchdog_has_time_remaining` / `cancel_and_reap_dump_watchdog` / `wait_for_dump_watchdog` / `settle_watchdog_after_pipeline_failure` と、`WATCHDOG_TERMINATION_GRACE_SECONDS` / `WATCHDOG_PID` / `WATCHDOG_DEADLINE_EPOCH` / `POSTGRES_CONTROL_TIMEOUT_SECONDS`（watchdog 専用なら）を削除する
- [ ] 1.2 `run_backup()` から watchdog の起動・回収・result file 判定と、`DUMP_DEADLINE_SECONDS` の範囲検証・`timeout --signal=TERM ...` の内側 timeout を削除する。パイプラインは `PIPESTATUS` による producer / restic の exit 判定を維持する
- [ ] 1.3 cleanup trap（47 行付近）の watchdog 回収参照を削除する
- [ ] 1.4 `backup-common` に watchdog 専用 helper（`postgres_scalar_bounded` 等）が残る場合、他に参照がなければ削除する

## 2. --no-cache と stderr 破棄の撤去

- [ ] 2.1 `restic_command()` と partial snapshot cleanup（62 行付近）の `--no-cache` を削除する
- [ ] 2.2 pg_dump（399 行付近）と restic backup（403 行付近）の `2>/dev/null` を削除する。他の restic 呼び出し（check / tag / snapshots 等）の出力抑制は現状維持とする（診断対象は dump パイプラインに限る）

## 3. result code と selftest / テストの追随

- [ ] 3.1 `backup-result-codes-v1.txt` から `WATCHDOG_TERMINATION_FAILED` を削除する
- [ ] 3.2 `backup-selftest` の watchdog / DUMP_DEADLINE 関連 assertion（207〜217 行付近ほか、約 39 箇所）と watchdog 動作テスト（236 行付近〜）を削除する
- [ ] 3.3 `backup-postgres-selftest` の `--no-cache` 前提の箇所を確認し、実行に影響する場合のみ更新する（fixture repo への直接 restic 呼び出しは cache 有無で結果が変わらないため、原則現状維持）
- [ ] 3.4 `DatabaseBackupRestoreContractTest` に watchdog / no-cache 前提の assert がないことを確認し、あれば更新する

## 4. ドキュメント

- [ ] 4.1 `docs/deploy.md` の backup 記述（546 行付近「60秒」等）を現在形で更新し、`--no-cache` 廃止と watchdog 撤去の根拠として issue #336 を参照する。698 行付近の運用スニペットの `--no-cache` も確認する

## 5. 検証

- [ ] 5.1 `scripts/backup/backup-selftest` を実行して通す
- [ ] 5.2 `scripts/backup/backup-postgres-selftest` を実行して通す（Docker 必要。実行不能環境なら CI に委ね、その旨を検証記録に残す）
- [ ] 5.3 `make test` / `make detekt` を実行して通す
- [ ] 5.4 検証記録（コマンド、結果、HEAD SHA、scope）を PR description に転記する
