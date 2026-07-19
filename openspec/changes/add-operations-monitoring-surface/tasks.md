## 1. Monitoring domain and persistence

- [x] 1.1 Define versioned monitoring DTOs, stable component state/reason enums, daemon tick status provider, and redaction-safe serialization tests
- [x] 1.2 Implement bounded daemon terminal and 30-minute provider outcome queries with malformed/bound fail-closed handling
- [x] 1.3 Implement unresolved market-data/infrastructure gap aggregates and source-isolation tests
- [x] 1.4 Compose reconciler, DB, revision, and backup projection readers into a component-local monitoring snapshot service

## 2. HTTP contract

- [x] 2.1 Add `GET /ops/monitoring` with route-local Japanese OpenAPI metadata and dependency wiring
- [x] 2.2 Add route, OpenAPI, source-failure isolation, redaction, and readiness non-regression tests

## 3. Backup projection boundary

- [x] 3.1 Define strict public projection schema and application reader with regular-file/size/schema validation
- [x] 3.2 Implement the root-owned atomic projection publisher for start/terminal service lifecycle and authoritative status allowlisting
- [x] 3.3 Hook publisher into backup/restore systemd units and extend installer artifact/permission checks
- [x] 3.4 Add publisher selftests for normal success, pre-publication kill, stale invocation, malformed/secret input, atomicity, and failed terminal publication

## 4. Production composition and documentation

- [x] 4.1 Add the fixed public-directory read-only production mount with deploy-safe empty-directory creation and no host-path interpolation
- [x] 4.2 Update deploy/design/README documentation with the current endpoint contract, root artifact installation, activation, verification, and rollback procedure
- [x] 4.3 Grep README/docs for affected endpoint, backup status, systemd unit, and environment variable references and reconcile stale descriptions

## 5. Validation

- [x] 5.1 Run focused monitoring route/repository/projection tests and backup selftests
- [x] 5.2 Run OpenSpec validation, `make test`, `make detekt`, and `make build` through the validation lease
- [x] 5.3 Confirm production compose renders in both fallback and activated configurations without exposing authoritative paths or secret fields
