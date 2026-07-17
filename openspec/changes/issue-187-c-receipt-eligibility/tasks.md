## 1. Durable authority and schema

- [x] 1.1 Add the typed receipt authority to realtime paper events and preserve exact receipt ID, ordinal, payload hash, and canonical persisted observation timestamp across duplicate commits
- [x] 1.2 Add the nullable order admission-boundary column with fresh-install and upgrade coverage; reuse and query-plan verify the existing unique global admission-ordinal index

## 2. Receipt propagation and order-boundary capture

- [x] 2.1 Publish WebSocket trade events only after attaching the committed receipt authority, preserving typed persistence/conflict failure behavior
- [x] 2.2 Capture the global maximum committed receipt ordinal under the exclusive session advisory lock inside each realtime resting-order creation transaction
- [x] 2.3 Preserve existing queue snapshot/session validation and intent rollback when durable boundary capture cannot prove a connected stable session
- [x] 2.4 Enforce and cover the complete touched lock order: advisory-before-session-before-ledger for order admission, session-before-ledger for event application, and session-before-order for gap/disconnect

## 3. Exact fill eligibility

- [x] 3.1 Resolve and verify the exact persisted receipt in the event transaction, including identity, session, sequence, ordinal, payload hash, canonical timestamp, and normalized payload
- [x] 3.2 Replace wall-clock entry eligibility with strict receipt ordinal ordering and fail closed for null, missing, mismatched, or legacy evidence using market-data-gap cancellation without an invalid SafetyViolation detail insert
- [x] 3.3 Keep old-session BUY orders non-rebound and preserve realtime STOP/TP, mark handling, and cursor advancement

## 4. Contract and production-path tests

- [x] 4.1 Cover listener receipt propagation, duplicate identity reuse, and persistence/conflict publish-zero behavior
- [x] 4.2 Add deterministic PostgreSQL receipt-first/order-second and order-first/receipt-second barrier tests, including at least 1,000 pre-boundary iterations with constant-bounded open rows and zero fills/executions
- [x] 4.3 Cover missing/null/session/payload/timestamp evidence, legacy rows, wall-clock disagreement, authenticated HARD_HALT rollback with cleanup-SAFE plus zero-open-risk/zero-row agreement, and risk-reducing position protection
- [x] 4.4 Close both production inventories: the `TradingRuntimeFactory.connectedPostgres` order-authority root and the `ProtectionReconcilerWorker` durable event-consumer root; record each OpenSpec Scenario's proving test and SHA
  - Evidence: `implementation-evidence.md` at implementation SHA `93cee76d3156c2239a6df2431d9b523da44c095c`

## 5. Documentation and validation

- [x] 5.1 Update README and current receipt/eligibility descriptions in `docs/design.md` and `docs/mcp-runtime.md`; document the exact `/ops/halt` rollback request, cleanup-SAFE/readback checks, resting-BUY zero-row query, and resume prohibition; then grep affected names for stale dark-write claims
- [x] 5.2 Run targeted tests, `openspec validate issue-187-c-receipt-eligibility`, `make test`, `make detekt`, and `make build` under the validation lease and record command, scope, result, wait time, and HEAD

## 6. PR #250 review remediation

- [x] 6.1 Correct the rollback runbook to read `HARD_HALT` from `GET /ops/risk-state`, durable cleanup `SAFE` from read-only `risk_state.hard_halt_cleanup_state`, and require agreement with zero-open-risk and resting-BUY zero-row SQL without adding an API field
- [x] 6.2 Replace the sequential 1,000-iteration evidence with at least 500 receipt-first and 500 order-first PostgreSQL choreographies that deterministically observe the shared/exclusive session advisory-lock waiter before release and delayed event application
- [x] 6.3 Update scenario evidence and run the affected contract tests, OpenSpec validation, relevant detekt, and diff checks under the validation lease
