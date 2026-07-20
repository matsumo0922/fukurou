## 1. Design and adversarial audit

- [x] 1.1 Validate the OpenSpec change and run the clean-context five-vector falsification over inventory B1-B3, R1-R2, I1, M1, and O1.
- [x] 1.2 Reconcile every blocking falsification finding into the proposal, delta spec, design, and finite implementation inventory before code changes.

## 2. Production archive compatibility

- [x] 2.1 Route production identity reads, database control/watchdog queries, `pg_dump`, and full-stream `pg_restore --list` through the captured production container ID.
- [x] 2.2 Use the production container name only for initial ID capture and post-dump mapping verification, and remove host `pg_restore` as a runtime prerequisite.
- [x] 2.3 Preserve stable redacted failures, candidate isolation, tag identity, retention ordering, and producer/consumer stream status semantics.

## 3. Regression proof

- [x] 3.1 Extend shell selftests for captured-ID routing, A→B→A name replacement, early parser completion with EOF drain, reader failure, and producer failure before retention or success evidence.
- [x] 3.2 Extend the Docker-backed production entrypoint integration so a deliberately failing host `pg_restore` cannot affect real PostgreSQL 16 backup, retention, and restore coverage.
- [x] 3.3 Run the backup, restore, installer, publisher, systemd, monitoring, and OpenSpec focused validation inventory and record the tested HEAD.

## 4. Documentation and final validation

- [x] 4.1 Update `docs/deploy.md` and affected current-state documentation to describe the container-owned PostgreSQL 16 archive reader and actual NAS prerequisites.
- [x] 4.2 Grep README/docs/KDoc for stale `pg_restore`, backup, restore, and rollout statements and synchronize the delta spec.
- [x] 4.3 Run final OpenSpec validation, full repository test/detekt/build, diff/secret checks, and clean-context review on one final HEAD.
