## 1. Process termination

- [x] 1.1 Make the launcher proxy request cancellation after TERM; establish child PGIDs before gate release; and make PID 1 empty job groups plus dedicated AI-UID inventory while exempting only authenticated PID/start-ticks proxy identities before acknowledgement
- [x] 1.2 Guarantee fallback proxy TERM/KILL after descendant-discovery failure and require the supervisor acknowledgement before claiming provider-tree proof
- [x] 1.3 Provision required process-group tools and add candidate-image 100-timeout plus 100-cancellation evidence covering gate races, root-first exit, session escape, proxy identity/reuse, and empty post-ack AI-UID inventory with no skip path

## 2. Independent terminal audit

- [x] 2.1 Track gateway semantic submission as committed, rejected, not attempted, or unknown without reporting false non-commit during repository races
- [x] 2.2 Project semantic commit, process exit, and cleanup states additively into `RUNNER_PHASE_COMPLETED`
- [x] 2.3 Cover committed-before-timeout, unknown repository race, no-submission, non-gateway, cleanup-failure, cancellation, and the two-producer compatibility inventory

## 3. Retention and documentation

- [x] 3.1 Verify current normal/failure/quarantine/tmpfs cleanup paths satisfy the bounded per-run artifact lifecycle without adding persistence
- [x] 3.2 Update MCP runtime, operations/design documentation, and affected KDoc to describe current terminal and retention semantics

## 4. Validation

- [x] 4.1 Run targeted production-path tests, candidate-image supervisor/canary proof, full `make test`, `make detekt`, `make build`, OpenSpec strict validation, and diff checks within the 1,400-line hard stop
