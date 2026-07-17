## 1. Authority contract and canonical payload

- [x] 1.1 Add the server-owned decision submission authority model, typed conflict/UNKNOWN exceptions, and versioned canonical payload hashing.
- [x] 1.2 Add finite canonicalization tests covering every persisted decision/TradePlan/TradeIntent business field, JSON key order, decimal scale, and excluded metadata.
- [x] 1.3 Add phase-aware idempotent submission to the in-memory repository and prove same, changed, incomplete, and concurrent key behavior.

## 2. PostgreSQL authority and atomic persistence

- [x] 2.1 Add the empty additive `decision_submission_authorities` table to the schema bootstrap without legacy backfill or changes to `decisions`.
- [x] 2.2 Implement rows-affected winner arbitration, pre-side-effect short-circuit, decision-by-ID exact result reconstruction, conflict/UNKNOWN handling, and winner-only terminal evidence persistence in `ExposedDecisionRepository`.
- [x] 2.3 Add PostgreSQL tests for identical retry, changed payload, committed PENDING/inconsistent authority, transaction rollback, concurrent same-key submission, and winner rollback followed by contender promotion.
- [x] 2.4 Add a response-loss integration test proving retry returns the same decision/plan/intent IDs and does not duplicate evidence/link/coverage, opportunity episode, identity failure, or dedupe shadow rows.
- [x] 2.5 Add schema upgrade/old-reader compatibility tests proving legacy rows remain unchanged and do not seed strict authority.

## 3. Production gateway and MCP identity

- [x] 3.1 Pass gateway-bound invocation and phase into the authoritative repository call, assert manifest invocation equals decision-run identity, and preserve typed conflict/UNKNOWN codes across gateway responses and the MCP client.
- [x] 3.2 Make caller `invocation_id` an equality check only and always construct the saved submission from `DecisionRunContext`.
- [x] 3.3 Remove the decision-capable production direct repository fallback while preserving an explicit test-only/phase-less fixture boundary.
- [x] 3.4 Add MCP/gateway production-call-path tests for spoof rejection, manifest/decision-run mismatch, missing gateway rejection, response-loss retry, typed errors, one-terminal-decision-per-phase, and zero direct repository mutation while risk-reducing act tools remain independent.

## 4. Documentation and evidence

- [x] 4.1 Update `docs/design.md` and `docs/mcp-runtime.md` with current authority, retry, UNKNOWN, migration, and rollback semantics.
- [x] 4.2 Record the production read-only inventory evidence (nonNULL invocation duplicate count 0), no-backfill migration rule, NAS impact, and old-reader launch-gate requirement in the PR description.
- [x] 4.3 Grep README/docs for `submit_decision`, `invocation_id`, direct fallback, and idempotency descriptions and correct any stale current-spec text.

## 5. Validation and delivery

- [x] 5.1 Run targeted MCP, gateway, canonicalization, and PostgreSQL tests under the validation lease and record command, result, scope, wait time, and HEAD SHA.
- [x] 5.2 Run initial full `make test`, `make detekt`, and `make build` under one validation lease and record the evidence.
- [ ] 5.3 Validate the OpenSpec change, check `git diff --check` and untracked files, then publish the PR with Scenario-to-test evidence and documentation impact.
- [ ] 5.4 Complete independent review remediation, run one final full validation at final HEAD, synchronize the PR description/CI evidence, and post APPROVED or HANDOFF.
