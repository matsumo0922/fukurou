## 1. Durable authority and schema

- [ ] 1.1 Add the typed receipt authority to realtime paper events and preserve exact receipt ID, ordinal, and payload hash across duplicate commits
- [ ] 1.2 Add the nullable order admission-boundary column and bounded session/ordinal lookup index with fresh-install, upgrade, and old-reader compatibility coverage

## 2. Receipt propagation and order-boundary capture

- [ ] 2.1 Publish WebSocket trade events only after attaching the committed receipt authority, preserving typed persistence/conflict failure behavior
- [ ] 2.2 Capture the maximum committed session receipt ordinal under the exclusive session advisory lock inside each realtime resting-order creation transaction
- [ ] 2.3 Preserve existing queue snapshot/session validation and intent rollback when durable boundary capture cannot prove a connected stable session

## 3. Exact fill eligibility

- [ ] 3.1 Resolve and verify the exact persisted receipt in the event transaction, including identity, session, sequence, ordinal, payload hash, and normalized payload
- [ ] 3.2 Replace wall-clock entry eligibility with strict receipt ordinal ordering and fail closed for null, missing, mismatched, or legacy evidence using the existing market-data-gap cancellation surface
- [ ] 3.3 Keep old-session BUY orders non-rebound, preserve realtime STOP/TP and mark handling, and fence new orders against pre-change reader fills

## 4. Contract and production-path tests

- [ ] 4.1 Cover listener receipt propagation, duplicate identity reuse, and persistence/conflict publish-zero behavior
- [ ] 4.2 Add deterministic PostgreSQL receipt-first/order-second and order-first/receipt-second barrier tests, including at least 1,000 pre-boundary iterations with zero fills/executions
- [ ] 4.3 Cover missing/null/session/payload evidence, legacy rows, wall-clock disagreement, rollback reader fencing, and risk-reducing position protection
- [ ] 4.4 Close the production wiring inventory from `ProtectionReconcilerWorker` through the durable receipt stream and Exposed ledger, and record each OpenSpec Scenario's proving test and SHA

## 5. Documentation and validation

- [ ] 5.1 Update README and current receipt/eligibility descriptions in `docs/design.md` and `docs/mcp-runtime.md`, then grep affected names for stale dark-write claims
- [ ] 5.2 Run targeted tests, `openspec validate issue-187-c-receipt-eligibility`, `make test`, `make detekt`, and `make build` under the validation lease and record command, scope, result, wait time, and HEAD
