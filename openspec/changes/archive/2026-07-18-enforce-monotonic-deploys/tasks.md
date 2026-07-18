## 1. Contract-first fixtures

- [x] 1.1 Add failing workflow contract tests for event-derived intent, historical quality enforcement, reason/mode validation, schema-sensitive early blocking, bundle v2 fields, and installed contract v2.
- [x] 1.2 Add failing deploy contract/runtime fixtures for bundle tamper rejection, inventory hash agreement, lock-time ancestry, fresh install, mode mismatch, evidence persistence, and roll-forward-only recovery.

## 2. Workflow and signed bundle v2

- [x] 2.1 Add manual migration mode and reason inputs, derive `FORWARD` or `AUTHORIZED_ROLLBACK` from event/target, and require quality for every target.
- [x] 2.2 Add the versioned schema-sensitive path inventory and block known schema-sensitive automatic pushes before image publication.
- [x] 2.3 Extend canonical signed bundle generation and JSON schema with closed v2 event, intent, reason, migration mode, and inventory hash fields.

## 3. Root executor admission

- [x] 3.1 Upgrade the root executor to contract v2 while retaining version-aware v1 journal recovery.
- [x] 3.2 Validate bundle v2 fields, reason safety, repository/installed inventory hashes, and exact signed target before candidate mutation.
- [x] 3.3 Observe current revision and fresh-install evidence (`PRE_FOUNDATION` plus zero published deployment directories) under the deploy lock, fetch main history, and enforce `FORWARD` / `AUTHORIZED_ROLLBACK` ancestry before rollback capture.
- [x] 3.4 Reclassify the actual current-to-target diff with the installed inventory and reject migration mode mismatches before rollback capture.

## 4. Durable recovery policy

- [x] 4.1 Persist accepted current/target, intent, reason, mode, inventory hash, and schema-sensitive result in rollback state and the first current-format journal entry.
- [x] 4.2 Preserve existing automatic image rollback for `AUTO_IMAGE_ROLLBACK` and `BACKWARD_COMPATIBLE`.
- [x] 4.3 Route pre-safety `ROLL_FORWARD_ONLY` failure to `CANDIDATE_ABORTED`, and post-safety live/startup failure to re-established maintenance/fence plus durable `MANUAL_RECOVERY_REQUIRED`, without starting the previous image or restoring the database.

## 5. Tests and documentation

- [x] 5.1 Complete workflow, contract, runtime, and production-like E2E scenarios for forward, queued-old, authorized rollback, mandatory historical quality, tamper, fresh/prior-history container loss, divergent, inventory mismatch, compatibility modes at pre/post-safety states, restart recovery, post-deploy mismatch, and v1 recovery.
- [x] 5.2 Update `docs/deploy.md` with current v2 semantics, manual inputs, controlled deploy freeze, exact root pre-install commands, verification, application/workflow rollback with v2 executor retained, unsupported v1 executor downgrade, and PR-2 merge gate.
- [x] 5.3 Grep README/docs for stale quality, rollback, executor contract, migration, and bundle descriptions and update affected current-specification text.

## 6. Validation

- [x] 6.1 Validate `enforce-monotonic-deploys` with OpenSpec strict validation and run shell syntax plus targeted contract/runtime/E2E tests.
- [x] 6.2 Run final `make test`, `make detekt`, and `make build` under the validation lease at one exact HEAD and record evidence for the PR.
- [x] 6.3 Obtain clean-context Opus approval with G1-G5 all PASS, leave PR-2 unmerged, and provide the exact NAS root pre-install handoff.
