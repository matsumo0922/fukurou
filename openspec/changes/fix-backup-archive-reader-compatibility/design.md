## Context

Production rollout evidence on 2026-07-20 established the following facts: the production container runs PostgreSQL client 16.14, the NAS host runs `pg_restore` 15.16, `restic check` succeeds, `restic dump` reaches EOF, and only host-side archive-list parsing fails. The current implementation validates a container-produced archive with an unrelated host client, while the integration environment happens to have a sufficiently new host client and therefore does not exercise this compatibility boundary.

This is a rollout-blocking correctness defect in the already-merged database backup/restore capability. The hotfix is intentionally limited to the archive toolchain boundary and its proof. It does not add another package repository, copy PostgreSQL binaries onto the NAS, or introduce a host wrapper.

## Goals / Non-Goals

**Goals:**

- Bind daily archive production and list validation to one captured PostgreSQL 16 container identity.
- Reject missing or wrong-major container clients before repository mutation.
- Preserve full-stream drain, independent producer/consumer status, no-retention-on-failure, and redacted failure behavior.
- Make the production entrypoint test fail if it consults host `pg_restore`.
- Re-audit the adjacent backup, restore, installer, publisher, systemd, monitoring, and rollout paths before using the one allowed hotfix PR.

**Non-Goals:**

- Installing or managing PostgreSQL client packages on the NAS host.
- Changing restic repository format, retention, status schema, monitoring API, timer cadence, restore profiles, or production recovery scope.
- Automatically classifying or deleting the untagged candidate left by the failed production attempt.
- Unrelated hardening or refactoring discovered outside the backup/restore rollout invariant.

## Decisions

### 1. The captured production container owns both archive ends

（ユーザー確認済み）`pg_dump` already executes through the fixed production container. `verify_archive_stream` will execute `pg_restore --list` through `docker exec -i` against the previously captured `PRODUCTION_CONTAINER_ID`, not a name lookup and not the host `PATH`. Capturing the ID prevents a concurrent name replacement from silently selecting a different archive reader. If the captured container no longer exists or the reader fails, the current candidate remains unverified and retention does not run.

Installing PostgreSQL 16 from PGDG on the host was rejected because it adds an operational package-repository dependency solely to compensate for an implementation boundary error. A `/usr/local/bin/pg_restore` wrapper was rejected because it creates an unreviewed persistent root artifact outside the installer manifest.

### 2. PostgreSQL 16 client compatibility is checked before repository mutation

（agent 仮決め）After capturing the production container ID, the backup preflight reads the container-local `pg_dump --version` and `pg_restore --version` output and accepts only major 16 for both tools. Missing, extra, malformed, or wrong-major output returns the existing redacted `INVALID_CONFIGURATION` path before `restic backup`, tag, or retention. Minor versions remain flexible within major 16.

The preflight does not inspect host package versions and does not add a new result code or monitoring contract. The fixed production image remains the toolchain authority already used by dump and restore operations.

### 3. Stream semantics remain two-sided

（agent 仮決め）The existing pipeline shape remains: `restic dump` is the producer, a group runs container-side `pg_restore --list`, then drains the same stdin to EOF with `cat`. The producer exit status and grouped consumer status remain independent. A stopped container, parser error, drain error, or restic read error all fail integrity before tag or retention.

### 4. Tests make the production mismatch reproducible

（agent 仮決め）The shell selftest records the exact captured-ID `docker exec -i ... pg_restore --list` call and covers successful early parser completion, parser failure, producer failure, toolchain major mismatch, and no retention/success evidence after failure. The Docker-backed production-entrypoint integration prepends a deliberately failing host `pg_restore`; the test can pass only if the real production script uses the PostgreSQL 16 container reader. Existing real restic retention and isolated restore coverage remain active.

### 5. Audit scope is finite

（ユーザー確認済み）Before publish, the single hotfix PR checks the following inventory once: B1 dump/toolchain capture, B2 repository check/full-stream/tag/retention, B3 failed-candidate rerun behavior, R1 exact-snapshot restore and PostgreSQL 16 image, R2 validation/cleanup/signal bounds, I1 install marker and disabled timers, M1 authoritative/public monitoring projection, and O1 runbook prerequisites/rollout order. Findings enter this PR only when they break the same #190 backup/restore rollout invariant; unrelated pre-existing issues are reported without expanding the diff.

## Risks / Trade-offs

- [The production container stops after creating a snapshot] → captured-ID archive validation fails closed; the candidate is untagged, excluded from retention, and counted on a later successful attempt.
- [Container version output changes format] → exact preflight reports `INVALID_CONFIGURATION` before repository mutation; tests bind the supported PostgreSQL 16 image output.
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
