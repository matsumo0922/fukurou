## Context

PR #240 already persists every decoded WebSocket trade in `paper_market_event_receipts` before publishing it to the application queue. The commit returns a globally monotonic admission ordinal, but `GmoPublicWebSocketMarketEventStream` currently discards that result and publishes the original `PaperMarketTradeEvent`. Resting-order creation uses `ReconcilerStatusProvider` snapshots outside the ledger transaction, and entry fill eligibility uses session, local sequence, and `market_eligible_from`. Therefore a receipt that committed before an order can remain buffered and be consumed after the order commits.

This change is the pragmatic PR C defined by the Issue #187 handoff. It supersedes the older C0/C1/C2 queue, journal, generation-token, and general recovery design left in historical issue comments.

## Goals / Non-Goals

**Goals:**

- Make durable receipt commit order the sole causal boundary for risk-increasing resting BUY fills.
- Serialize receipt commit and order-boundary capture without adding a general coordinator.
- Verify the exact persisted receipt in the same transaction that evaluates and commits a fill.
- Fail closed for legacy or contradictory evidence without blocking realtime STOP/TP handling.
- Preserve additive schema and old-reader operability without destructive backfill.

**Non-Goals:**

- C0 queue/journal/member/generation-token recovery infrastructure.
- Receipt retention pruning, retrospective event replay, or historical fill creation.
- Multi-replica coordination, owner leases, general recovery engines, or population paging.
- Changing risk-reducing position session/rebind semantics.
- Root-installed artifacts, `mcp-role.sql`, NAS bootstrap, or daemon downtime.
- Applying durable receipt semantics to the in-memory runtime, where `requireRealtimeIntegrityForRestingOrders` remains false and the durable PostgreSQL receipt authority is not enabled.

## Decisions

### 1. Publish a typed receipt authority with the event

（agent 仮決め）Add a nullable `PaperMarketEventReceiptAuthority` to `PaperMarketTradeEvent`, containing receipt ID, admission ordinal, payload hash, and the persisted socket-observation timestamp. The receipt commit result returns the canonical persisted timestamp for both new and duplicate receipts. The WebSocket listener copies the committed authority onto the event and replaces `receivedAt` with that canonical timestamp before publishing. Nullable construction preserves unit fixtures and risk-reducing callers, but the PostgreSQL risk-increasing entry-fill gate requires a non-null authority.

The persisted receipt remains the authority. The event projection is only a lookup key and claim: the ledger transaction re-reads the row and compares receipt ID, session, source sequence, ordinal, persisted payload hash, persisted socket-observation timestamp, canonical `receivedAt`, and a recomputed normalized event-payload hash. Carrying only an ordinal would allow mismatched or fabricated event data to select another receipt. A duplicate transport observation therefore cannot change the execution or cursor timestamp attached to the original durable receipt.

Alternative rejected: querying only `(session, sequence)` from the consumer without carrying commit identity. That hides whether the exact listener commit was propagated and weakens duplicate/conflict evidence.

### 2. Capture the order boundary inside the existing order transaction

（ユーザー確認済み）The broker continues to collect queue/orderbook evidence and a candidate session outside the transaction. The Exposed writer then:

1. obtains an exclusive transaction-scoped advisory lock using `paperMarketSessionAdvisoryLockKey(sessionId)`;
2. locks and validates the connected `market_data_sessions` row and expected processed sequence;
3. acquires the existing ledger locks in their current order;
4. reads the global `MAX(admission_ordinal)` through the existing unique admission-ordinal index;
5. inserts the order with that durable boundary.

Receipt commit already takes the same advisory key in shared mode before inserting a receipt. If receipt commit wins, its ordinal is visible in the boundary. If order creation wins, the later receipt waits and receives a larger ordinal. A global maximum is conservative across old sessions and gives the required ordering because admission ordinals are globally monotonic; it also avoids a new large-table index migration.

The lock graph for all touched paths is fixed as `session advisory (when needed) -> market_data_sessions row -> risk_state -> account -> positions -> orders -> receipt/read-only lookup`. Gap and disconnect paths already begin with the session row before mutating orders. Event application is reordered to lock and validate its session row before ledger rows. Receipt commit takes only `session advisory -> receipt identity/insert` and never waits for the session row or ledger rows. No C path may acquire the session row after ledger rows.

Alternative rejected: capturing the ordinal in `PaperBroker` before order creation. That recreates the same transaction gap as the current status snapshot.

### 3. Gate only risk-increasing resting entries on exact receipt order

（ユーザー確認済み）The event transaction resolves and verifies its receipt before evaluating resting BUY entries. A candidate order is eligible only when:

- it belongs to the same session;
- its durable boundary is non-null;
- exact receipt verification succeeds; and
- `receipt.admissionOrdinal > order.marketEligibleAfterAdmissionOrdinal`.

`market_eligible_from` is removed from the new active entry predicate. Existing queue depletion, fill invariant, cash/exposure checks, risk-state gate, order status CAS, position protection, and cursor advance retain their responsibilities.

If the event receipt is missing or contradictory, or a current-session candidate has a null boundary, the transaction creates no entry fill. Affected risk-increasing orders use the existing `market_data_gap` cancellation reason without inserting `paper_order_cancellation_details`: that table is reserved for a non-null `SafetyViolation` FK and cannot represent receipt eligibility. Eligibility failure is an entry-only typed internal result, not an exception, so the same transaction can still update marks, execute causal risk-reducing STOP/TP logic, and advance the cursor. Old-session resting BUY orders are never rebound.

Alternative rejected: failing the entire event transaction before protection. That would make a risk-increasing evidence defect block risk reduction.

### 4. Keep additive schema rollback-compatible without claiming unsafe hot rollback

（agent 仮決め）Add nullable `orders.market_eligible_after_admission_ordinal`. Existing rows remain null and are not backfilled. New realtime-enabled resting orders retain legacy session/sequence/time fields for old-reader shape compatibility, while new code ignores the wall-clock field for entry eligibility.

An old binary has a `FULL_TICK_EXECUTION` path that ignores `market_eligible_from`; no column sentinel can make a hot rollback safe. Rollback is therefore operationally fail-closed: issue the existing authenticated `POST /ops/halt` with `level=HARD`, keep the new image running until the existing `ALL_OPEN_RISK` sweep reports durable cleanup `SAFE`, and verify both the risk-state zero-open-risk readback and zero `orders` rows matching resting BUY lifecycle predicates before stopping it. Only then may a pre-change image start. The additive nullable column remains readable/ignorable by the old binary. This change does not claim that an old binary can safely consume new-semantics open orders.

### 5. Keep the guarantee on the durable PostgreSQL production path

（agent 仮決め）The two production roots have distinct authority roles. `TradingRuntimeFactory.connectedPostgres` creates realtime resting orders through `ExposedPaperLedgerRepository` with integrity required; `ProtectionReconcilerWorker` owns the durable WebSocket receipt repository and consumes events through the Exposed ledger. The in-memory runtime keeps its legacy session/sequence model and does not claim durable receipt eligibility. Static production-wiring tests cover both the order-authority root and event-consumer root so neither side can silently lose the PostgreSQL authority required by the contract.

### 6. Prove commit ordering, not scheduler timing

（ユーザー確認済み）A deterministic PostgreSQL barrier test coordinates the shared receipt lock and exclusive order-boundary lock. The required receipt-first/order-second/buffer-consume outcome runs at least 1,000 iterations and asserts zero BUY executions/fills. Separate tests cover order-first/receipt-second eligibility, duplicate receipt identity, missing/null/mismatch evidence, risk-reducing protection, and fresh/upgrade/old-reader compatibility.

The 1,000-iteration test reuses one Testcontainers database and keeps open positions and orders at a constant bound by canceling/closing each iteration's fixture before the next. It asserts both the receipt-first commit barrier and delayed consumer release explicitly. It must not introduce sleeps as the correctness oracle.

## Risks / Trade-offs

- [Risk] Advisory-lock or row-lock order creates a deadlock with receipt, disconnect, or ledger mutation paths. → Enforce and test session-before-ledger ordering for order/event paths, session-before-order for gap/disconnect, and advisory-before-session for boundary capture.
- [Risk] Receipt verification failure cancels entries but accidentally skips STOP/TP or cursor progress. → Split entry eligibility result from risk-reducing event handling and prove production `ProtectionReconciler` behavior.
- [Risk] `MAX(admission_ordinal)` becomes a hot-path scan under 365-day retention. → Query the global maximum through the already-required unique admission-ordinal index and verify the PostgreSQL query plan; do not add a timeout-sensitive large-table index migration.
- [Risk] Nullable event authority lets a production event bypass the gate. → Exact-receipt verification is mandatory only at the Exposed risk-increasing entry boundary; static wiring and missing-authority tests prove no production bypass.
- [Risk] Old binaries ignore the new boundary and their full-tick path can fill open orders. → Require an admission stop plus cancel/drain and zero-open-order verification before rollback; test old schema readability separately from semantic rollback safety.
- [Trade-off] Legacy null-boundary orders are canceled rather than migrated. This intentionally sacrifices possible entries to preserve causal fidelity.

## Migration Plan

1. Bootstrap adds nullable `market_eligible_after_admission_ordinal`. No existing row is updated and no large-table receipt index is built; boundary capture reuses the existing unique admission-ordinal index.
2. New listener publishes committed receipt authority; new writer captures boundaries for newly created realtime resting orders.
3. New fill logic fails closed on null/missing/mismatched evidence and ignores wall-clock eligibility.
4. Deploy uses the normal single production image path. No NAS bootstrap is required.
5. Rollback leaves schema and rows intact, but is allowed only after authenticated `POST /ops/halt` activates durable `HARD_HALT`, the new image's `ALL_OPEN_RISK` sweep reaches `SAFE`, and both its zero-open-risk readback and a zero-row resting-BUY query agree. The old binary may then ignore the nullable column safely. The operator runbook records the exact request, readback, query, and prohibition on `/ops/resume` until a receipt-aware image is active.

## Open Questions

None requiring user value judgment. The agent decisions above are reversible, additive implementation choices and must be included in the PR's human-confirmation section and independent review.
