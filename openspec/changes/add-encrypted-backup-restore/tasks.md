## 1. Contracts and failing fixtures

- [x] 1.1 Add the versioned status JSON schema, stable result-code allowlist, and versioned restore inventory/profile contracts.
- [x] 1.2 Add deterministic backup fixtures for success, producer/consumer split pipeline failure, full archive drain with early list completion, PID-and-application rechecked backend termination, watchdog cancel/reap, non-target preservation/disappearance, tag-induced snapshot-ID replacement, interrupted candidate preservation, current-attempt partial forget without prune, dump timeout, capacity floor, repository/status failure, secret redaction, atomic publication, lock contention, integrity no-prune, and comma-separated AND retention semantics.
- [x] 1.3 Add deterministic restore fixtures for exact snapshot selection, PostgreSQL 16 isolation, invariant failure, timeout/signal cleanup, cleanup failure reporting, and zero leaked owned resources.
- [x] 1.4 Add repository contract tests for script syntax, installed ownership/unit hardening, unchanged sudo authority, production container identity parity, and manifest drift across Exposed tables, deploy-foundation SQL, bootstrap SQL, evaluation-report SQL, and documentation wiring.

## 2. Backup producer

- [x] 2.1 Implement root preflight, secret-file mode validation, measured database-size/free-space safety floor, shared non-blocking backup lock, and start-time deploy-lock fail-closed probe.
- [x] 2.2 Implement password-free production-container `pg_dump -Fc -Z0` to restic stdin streaming with application-named exact-PID watchdog termination, separate producer/consumer status, attempt tags, exact snapshot identification, and current-attempt partial-snapshot forget without prune.
- [x] 2.3 Implement repository structure and full-stream custom-archive list verification, tag-induced authoritative-ID capture, interrupted candidate preservation/reporting, and fixed-tag/host/path newest-14-daily retention that never prunes on uncertain integrity.
- [x] 2.4 Implement schema-valid atomic status publication that separates last attempt, last successful integrity evidence, and retention outcome without exposing child detail.

## 3. Isolated restore drill

- [x] 3.1 Implement exact integrity-checked snapshot selection and bounded restic-to-`pg_restore --no-owner --no-acl` streaming into a uniquely labelled disposable PostgreSQL 16 environment.
- [x] 3.2 Implement versioned table/view/sequence/constraint, critical-table primary-key, and read-only paper-account/runtime-config/ledger-lineage invariant validation without application bootstrap.
- [x] 3.3 Implement HUP/INT/TERM re-entry-safe owned-resource cleanup, cleanup postcondition checks, and restore status publication that advances last verified evidence only after cleanup succeeds.
- [x] 3.4 Add a Docker-backed PostgreSQL integration selftest that executes the production backup entrypoint with real custom-format backup, retention prune, redaction, restore, profile validation, constraint preservation, and production-resource non-interference.

## 4. Root rollout artifacts

- [x] 4.1 Add root-only install and verification commands for fixed backup/restore entrypoints, validation profiles, status/secret/repository directories, and disabled-by-default unit installation.
- [x] 4.2 Add hardened root oneshot services whose stop timeout exceeds worst-case cleanup, plus persistent randomized daily/weekly timers with bounded execution and no embedded secret.
- [x] 4.3 Verify that the change does not modify `github-runner` sudoers, production deploy executor/workflow, Ktor routes, production compose, or database schema; the existing deploy-validation CI gate may receive backup contract validation.
- [x] 4.4 Require successful latest backup retention and restore attempts, current-repository exact snapshot evidence, and bounded zero-resource cleanup inventory before rollout enablement.

## 5. Documentation

- [x] 5.1 Update `docs/deploy.md` with root prerequisites, format-v2 compression and recovery-copy handling, capacity/dump-duration measurement, repository/status initialization and repair, orphan-pack check/prune, install/verify commands, first manual backup/restore gate, timer enable/disable, active systemd/status inspection before alerts exist, stable failure triage, and production replacement role/ACL bootstrap boundary.
- [x] 5.2 Update `docs/design.md` and README with the current same-NAS daily logical scope, newest-14 retention, weekly measured restore evidence, and explicit no-PITR/off-site/NAS-loss/RPO/RTO guarantees.
- [x] 5.3 Grep README/docs/KDoc for backup, restore, restic, PITR, RPO/RTO, systemd, and deploy-lock references and correct stale current-specification text in the same change.

## 6. Validation and handoff

- [x] 6.1 Pass strict OpenSpec validation, `bash -n`, deterministic backup/restore selftests, JSON schema/profile validation, and Docker integration selftest.
- [x] 6.2 Through the shared validation lease, pass `make test`, `make detekt`, and `make build` at one exact HEAD and confirm a clean worktree apart from intended changes.
- [ ] 6.3 Pass clean-context design falsification and final G1-G5 implementation review, resolving every blocking finding before PR approval.
- [x] 6.4 Record the NAS root rollout as HANDOFF: install restic/artifacts, create and recoverably store the password, initialize repository, run first backup and exact-snapshot restore drill, verify modes/status/cleanup, then enable timers.
