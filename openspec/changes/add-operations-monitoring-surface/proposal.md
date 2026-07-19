## Why

Issue #190 の backup/restore と deploy safety は production に到達したが、daemon、LLM provider、reconciler、market-data/infrastructure gap、backup/restore freshness を外部 monitor が安全に評価できる単一の read-only contract がない。PR-5 の GitHub-hosted alert workflow を secret や root-only authority に触れさせず実装するため、先に fail-closed な monitoring surface を確立する。

## What Changes

- （ユーザー確認済み）Access 保護下の `GET /ops/monitoring` を追加し、revision、daemon cadence/terminal、30 分 provider outcome、reconciler freshness、未解決 gap、backup/restore freshness を versioned DTO で返す。
- （ユーザー確認済み）source 不在、query failure、schema mismatch、malformed file を正常扱いせず、component ごとの stable reason code を伴う `UNKNOWN` として返す。既存 `/health/ready` の意味は変更しない。
- （agent 仮決め）root-only `backup-status.json` は application へ直接 mount しない。systemd の `ExecStartPre` / `ExecStopPost` から root-owned publisher を呼び、allowlist 済み field だけを別の atomic read-only projection に公開する。
- （agent 仮決め）projection は service invocation の開始と terminal を authoritative status とは別に保持し、status publication 前の SIGKILL/OOM、stale success、service failure を application 側から区別可能にする。
- （agent 仮決め）DB event aggregation は固定 window と bounded query で実行し、結果が欠損・上限超過・parse 不能なら推測で補完せず `UNKNOWN` にする。
- route-local OpenAPI、production composition、compose mount、projection/route/aggregation/redaction/readiness non-regression の executable tests と運用 documentation を同じ PR に含める。

## Capabilities

### New Capabilities

- `operations-monitoring-surface`: PR-5 が alert 条件を評価するための redacted read-only monitoring response、component availability、bounded aggregation、secret-free projection contract。

### Modified Capabilities

- `database-backup-restore`: root-only authoritative status を維持しながら、systemd invocation と allowlist 済み backup/restore evidence だけを application-readable projection へ原子的に公開する要件を追加する。

## Impact

- `fukurou` の ops route DTO、aggregation service、application dependency composition、route/OpenAPI tests
- `trading` / PostgreSQL の command event と gap state を読む bounded monitoring query
- `scripts/backup/` の projection publisher、schema、installer、selftests、systemd service hooks
- `docker-compose.prod.yml` の fixed public-directory read-only mount。restic repository、password、root-only status は mount しない
- `docs/deploy.md`、`docs/design.md`、README の monitoring contract と PR-4 後の root artifact reinstall handoff
- wire contract は additive。既存 endpoint と readiness contract に breaking change はない
