## 1. Process termination

- [ ] 1.1 Guarantee fallback root TERM/KILL attempt after descendant-discovery failure without claiming an unproven tree exit
- [ ] 1.2 Add deterministic fallback regression coverage and 100-timeout plus 100-cancellation Linux process-group stress evidence

## 2. Independent terminal audit

- [ ] 2.1 Track successful decision and falsification repository commits in the app-owned submission gateway
- [ ] 2.2 Project semantic commit, process exit, and cleanup states additively into `RUNNER_PHASE_COMPLETED`
- [ ] 2.3 Cover committed-before-timeout, no-submission, non-gateway, cleanup-failure, cancellation, and legacy-consumer compatibility paths

## 3. Retention and documentation

- [ ] 3.1 Verify current normal/failure/quarantine/tmpfs cleanup paths satisfy the bounded per-run artifact lifecycle without adding persistence
- [ ] 3.2 Update MCP runtime, operations/design documentation, and affected KDoc to describe current terminal and retention semantics

## 4. Validation

- [ ] 4.1 Run targeted production-path tests, full `make test`, `make detekt`, `make build`, OpenSpec strict validation, and diff checks within the 900-line hard stop
