## Why

Production の初回 backup gate で、PostgreSQL 16 container が生成した custom archive を NAS host の `pg_restore` 15.16 が読めず、restic snapshot と repository check が正常でも `INTEGRITY_CHECK_FAILED` になった。日次 archive verification が dump producer と同じ PostgreSQL major に束縛されておらず、実環境の client version 差を integration test が検出していないため、timer を安全に有効化できない。

## What Changes

- （ユーザー確認済み）日次 custom archive の dump、database control、`pg_restore --list` を同じ captured production PostgreSQL container ID で実行する。
- host `pg_restore` が存在しない、または archive と非互換でも production entrypoint が成功する production-call-path test を追加する。captured reader failure が retention・success evidence を進めないことも固定する。
- restore drill、retention predicate、status schema、monitoring wire contract、timer cadence、production database replacement boundaryは変更しない。
- NAS prerequisite と初回 rollout 手順を、実際の container-owned archive reader contract に同期する。

## Capabilities

### New Capabilities

- なし。

### Modified Capabilities

- `database-backup-restore`: custom archive の構造検証を dump producer と同じ PostgreSQL 16 container client に束縛し、host client version を backup 成否の前提から外す。

## Impact

- `scripts/backup/backup-fukurou` の production identity routing と archive verification。
- `scripts/backup/backup-selftest` / `scripts/backup/backup-postgres-selftest` の captured-ID routing・failure coverage。
- `docs/deploy.md` と `database-backup-restore` spec の NAS prerequisite / archive reader contract。
- DB schema、Ktor API、compose、sudoers、secret format、repository formatへの変更はない。
