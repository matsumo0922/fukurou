## Context

Production rollout evidence on 2026-07-20 established the following facts: the production container runs PostgreSQL client 16.14, the NAS host runs `pg_restore` 15.16, `restic check` succeeds, `restic dump` reaches EOF, and only host-side archive-list parsing fails. The current implementation validates a container-produced archive with an unrelated host client, while the integration environment happens to have a sufficiently new host client and therefore does not exercise this compatibility boundary.

This is a rollout-blocking correctness defect in the already-merged database backup/restore capability. The hotfix is intentionally limited to the archive toolchain boundary and its proof. It does not add another package repository, copy PostgreSQL binaries onto the NAS, or introduce a host wrapper.

## Goals / Non-Goals

**Goals:**

- Bind daily archive production, database control, and list validation to one captured PostgreSQL 16 container identity.
- Preserve full-stream drain, independent producer/consumer status, no-retention-on-failure, and redacted failure behavior.
- Make the production entrypoint test fail if it consults host `pg_restore`.
- Re-audit the adjacent backup, restore, installer, publisher, systemd, monitoring, and rollout paths before using the one allowed hotfix PR.

**Non-Goals:**

- Installing or managing PostgreSQL client packages on the NAS host.
- Changing restic repository format, retention, status schema, monitoring API, timer cadence, restore profiles, or production recovery scope.
- Automatically classifying or deleting the untagged candidate left by the failed production attempt.
- Unrelated hardening or refactoring discovered outside the backup/restore rollout invariant.

## Decisions

### 1. The captured production container owns the complete backup data path

（ユーザー確認済み）After the initial name-to-ID lookup, production identity reads, database control/watchdog queries, `pg_dump`, and `pg_restore --list` execute through `docker exec` against the captured `PRODUCTION_CONTAINER_ID`, not another name lookup and not the host `PATH`. The production name is used again only for the post-dump mapping check. Capturing the complete data path prevents an A→B→A name replacement from mixing a dump producer and archive reader. If the captured container no longer exists or any captured-ID operation fails, the current candidate remains unverified and retention does not run.

Installing PostgreSQL 16 from PGDG on the host was rejected because it adds an operational package-repository dependency solely to compensate for an implementation boundary error. A `/usr/local/bin/pg_restore` wrapper was rejected because it creates an unreviewed persistent root artifact outside the installer manifest.

### 2. PostgreSQL 16 remains a static deployment contract

（ユーザー確認済み）The production compose image, isolated restore image, and contract tests continue to pin PostgreSQL 16. This hotfix does not add runtime parsing of `pg_dump --version` or `pg_restore --version`: the actual defect is cross-container/host routing, and a new output-format parser would add an unrelated availability gate.

### 3. Stream semantics remain two-sided

（agent 仮決め）The existing pipeline shape remains: `restic dump` is the producer, a group runs container-side `pg_restore --list`, then drains the same stdin to EOF with `cat`. The producer exit status and grouped consumer status remain independent. A stopped container, parser error, drain error, or restic read error all fail integrity before tag or retention.

### 4. Tests make the production mismatch reproducible

（agent 仮決め）The shell selftest gives the production name and captured ID different fixture behavior and records identity, control/watchdog, `pg_dump`, and `pg_restore --list` routing. It covers successful early parser completion, parser failure, producer failure, an A→B→A name-replacement attempt, and no retention/success evidence after failure. The Docker-backed production-entrypoint integration prepends a deliberately failing host `pg_restore`; the test can pass only if the real production script uses the PostgreSQL 16 container reader. Existing real restic retention and isolated restore coverage remain active.

### 5. Audit scope is finite

（ユーザー確認済み）Before publish, the single hotfix PR checks the following inventory once: B1 dump/toolchain capture, B2 repository check/full-stream/tag/retention, B3 failed-candidate rerun behavior, R1 exact-snapshot restore and PostgreSQL 16 image, R2 validation/cleanup/signal bounds, I1 install marker and disabled timers, M1 authoritative/public monitoring projection, and O1 runbook prerequisites/rollout order. Findings enter this PR only when they break the same #190 backup/restore rollout invariant; unrelated pre-existing issues are reported without expanding the diff.

## Risks / Trade-offs

- [The production container stops after creating a snapshot] → captured-ID archive validation fails closed; the candidate is untagged, excluded from retention, and counted on a later successful attempt.
- [Using the production container for parsing affects production] → `pg_restore --list` reads stdin and does not connect to or mutate the database; `docker exec` uses no database arguments or credentials.
- [A host client could have served as a fallback] → no fallback is used because cross-major acceptance would recreate the unbound compatibility defect.

## Migration Plan

1. Validate OpenSpec, shell selftests, Docker-backed PostgreSQL/restic integration, full repository quality, and clean-context review on one final SHA.
2. Merge and deploy the hotfix image; install the reviewed root artifacts while timers remain disabled.
3. Rerun the manual backup. The previous untagged candidate remains excluded and is reported by aggregate count; automation does not delete it.
4. Run the exact-snapshot restore drill, rollout verification, `/ops/monitoring` verification, then enable the daily and weekly timers.
5. If the hotfix fails, keep timers disabled and reinstall the previous reviewed artifacts. Do not delete the repository, password, status, or snapshots.

## Open Questions

なし。PostgreSQL major 16 and the single-hotfix-PR constraint are fixed by the existing capability and the user's instruction.
