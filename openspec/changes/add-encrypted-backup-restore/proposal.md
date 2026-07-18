## Why

Issue #190 のうち exact-SHA quality gate と monotonic deploy intent は main の capability になった一方、production PostgreSQL には volume 以外の recoverable backup と実 restore 証跡がない。同一 NAS の個人運用という合意済み境界で、暗号化された日次 logical backup と週次 isolated restore drill を root-owned automation として追加する。

## What Changes

- production PostgreSQL 16 の bounded `pg_dump -Fc` stream を、平文 dump を filesystem に置かず同一 NAS の暗号化 restic repository へ日次timerで保存を試みる。timer cadenceは毎暦日の成功を保証しない。
- repositoryとcustom archiveの構造を確認できた場合だけ固定tag/host/pathのnewest 14 daily generationsを保持・pruneし、不確実な状態では破壊的retentionを拒否する。
- exact verified snapshot を production resource と分離した disposable PostgreSQL 16 へ週次 restoreし、schema、constraint、critical table、read-only invariant と resource cleanup を検証する。
- backup と restore の attempt および last-known-good evidence を、versioned・redacted・root-only な `backup-status.json` へ atomic publish する。
- root-owned systemd service/timer templates、deterministic selftests、現在形の backup/restore runbook を追加する。timer は初回の手動 backup と restore drill が成功するまで有効化しない。
- backup/restore は開始時の deploy lock 競合を fail closed にするが、開始後の deploy race まで完全相互排他であるとは主張しない。
- `/ops/monitoring`、GitHub/Cloudflare alert、PITR/WAL、off-site/NAS-loss protection、保証 RPO/RTO、自動 production DB restore、live trading は対象外とする。

## Capabilities

### New Capabilities

- `database-backup-restore`: 同一 NAS の encrypted daily logical backup、retention safety、isolated weekly restore drill、atomic status、明示的な root rollout と manual recovery boundary を定義する。

### Modified Capabilities

なし。

## Impact

- `scripts/backup/` に root command、status/profile contract、selftest、systemd templates を追加する。
- `docs/deploy.md`、`docs/design.md`、README に現在の backup scope、rollout、restore drill、manual recovery を記載する。
- NAS operator は merge 後に restic の導入、root-owned secret/directory/repository の初期化、reviewed artifact の install、初回 backup/restore drill、timer enable を行う。
- Ktor route、production compose、deploy workflow/executor、`github-runner` sudoers、DB schema は変更しない。
