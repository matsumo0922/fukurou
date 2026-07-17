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

（agent 仮決め）Add a nullable `PaperMarketEventReceiptAuthority` to `PaperMarketTradeEvent`, containing receipt ID, admission ordinal, and payload hash. The WebSocket listener copies the committed authority onto the event before publishing. Nullable construction preserves unit fixtures and risk-reducing callers, but the PostgreSQL risk-increasing entry-fill gate requires a non-null authority.

The persisted receipt remains the authority. The event projection is only a lookup key and claim: the ledger transaction re-reads the row and compares receipt ID, session, source sequence, ordinal, persisted payload hash, and a recomputed normalized event-payload hash. Carrying only an ordinal would allow mismatched or fabricated event data to select another receipt.

Alternative rejected: querying only `(session, sequence)` from the consumer without carrying commit identity. That hides whether the exact listener commit was propagated and weakens duplicate/conflict evidence.

### 2. Capture the order boundary inside the existing order transaction

（ユーザー確認済み）The broker continues to collect queue/orderbook evidence and a candidate session outside the transaction. The Exposed writer then:

1. acquires the existing ledger locks in their current order;
2. obtains an exclusive transaction-scoped advisory lock using `paperMarketSessionAdvisoryLockKey(sessionId)`;
3. locks and validates the connected `market_data_sessions` row and expected processed sequence;
4. reads `MAX(admission_ordinal)` for that session;
5. inserts the order with that durable boundary.

Receipt commit already takes the same advisory key in shared mode before inserting a receipt. If receipt commit wins, its ordinal is visible in the boundary. If order creation wins, the later receipt waits and receives a larger ordinal. Receipt commit never waits for ledger rows, so placing the advisory lock after the established ledger locks does not introduce a lock cycle.

Add an index on `(session_id, admission_ordinal DESC)` so boundary capture does not scan the session's full retained receipt history.

Alternative rejected: capturing the ordinal in `PaperBroker` before order creation. That recreates the same transaction gap as the current status snapshot.

### 3. Gate only risk-increasing resting entries on exact receipt order

（ユーザー確認済み）The event transaction resolves and verifies its receipt before evaluating resting BUY entries. A candidate order is eligible only when:

- it belongs to the same session;
- its durable boundary is non-null;
- exact receipt verification succeeds; and
- `receipt.admissionOrdinal > order.marketEligibleAfterAdmissionOrdinal`.

`market_eligible_from` is removed from the new active entry predicate. Existing queue depletion, fill invariant, cash/exposure checks, risk-state gate, order status CAS, position protection, and cursor advance retain their responsibilities.

If the event receipt is missing or contradictory, or a current-session candidate has a null boundary, the transaction creates no entry fill. Affected risk-increasing orders use the existing `market_data_gap` outer cancellation reason plus an additive `MARKET_ELIGIBILITY_UNKNOWN` detail in `paper_order_cancellation_details`. The event may still update marks and execute causal risk-reducing STOP/TP logic. Old-session resting BUY orders are never rebound.

Alternative rejected: failing the entire event transaction before protection. That would make a risk-increasing evidence defect block risk reduction.

### 4. Fence rollback readers without keeping wall clock as new authority

（agent 仮決め）Add nullable `orders.market_eligible_after_admission_ordinal`. Existing rows remain null and are not backfilled. New realtime-enabled resting orders also retain legacy session/sequence fields for old-reader shape compatibility, but store `market_eligible_from = Long.MAX_VALUE` as a rollback fence. New code ignores this wall-clock field for entry eligibility; a pre-change reader therefore cannot fill a new-semantics order, while TTL/manual/HARD_HALT cancellation remains available.

This is safer than storing the current creation time: an old image would otherwise reopen the pre-boundary fill hole during rollback. It also avoids schema or enum changes that old code cannot parse.

### 5. Keep the guarantee on the durable PostgreSQL production path

（agent 仮決め）Both production roots that set `requireRealtimeIntegrityForRestingOrders=true` use `ExposedPaperLedgerRepository` and the durable WebSocket receipt repository. The in-memory runtime keeps its legacy session/sequence model and does not claim durable receipt eligibility. Static production-wiring tests will close the enabled call-site inventory so a future durable production root cannot silently use an in-memory ledger or unavailable receipt repository.

### 6. Prove commit ordering, not scheduler timing

（ユーザー確認済み）A deterministic PostgreSQL barrier test coordinates the shared receipt lock and exclusive order-boundary lock. The required receipt-first/order-second/buffer-consume outcome runs at least 1,000 iterations and asserts zero BUY executions/fills. Separate tests cover order-first/receipt-second eligibility, duplicate receipt identity, missing/null/mismatch evidence, risk-reducing protection, and fresh/upgrade/old-reader compatibility.

The 1,000-iteration test reuses one Testcontainers database and bounded fixtures. It must not introduce sleeps as the correctness oracle.

## Risks / Trade-offs

- [Risk] Advisory-lock or row-lock order creates a deadlock with receipt, disconnect, or ledger mutation paths. → Record the complete lock graph in tests; receipt commit holds only the shared session advisory and receipt rows, while order/event transactions retain ledger-first ordering.
- [Risk] Receipt verification failure cancels entries but accidentally skips STOP/TP or cursor progress. → Split entry eligibility result from risk-reducing event handling and prove production `ProtectionReconciler` behavior.
- [Risk] `MAX(admission_ordinal)` becomes a hot-path scan under 365-day retention. → Add and verify the session/ordinal index and inspect the query plan in PostgreSQL tests.
- [Risk] Nullable event authority lets a production event bypass the gate. → Exact-receipt verification is mandatory only at the Exposed risk-increasing entry boundary; static wiring and missing-authority tests prove no production bypass.
- [Risk] Old binaries ignore the new column. → Set the retained legacy time boundary to a non-fillable sentinel for new orders; keep cancellation readable and test an old-reader projection/predicate.
- [Trade-off] Legacy null-boundary orders are canceled rather than migrated. This intentionally sacrifices possible entries to preserve causal fidelity.

## Migration Plan

1. Bootstrap adds nullable `market_eligible_after_admission_ordinal` and the session/ordinal receipt index. No existing row is updated.
2. New listener publishes committed receipt authority; new writer captures boundaries for newly created realtime resting orders.
3. New fill logic fails closed on null/missing/mismatched evidence and ignores wall-clock eligibility.
4. Deploy uses the normal single production image path. No NAS bootstrap is required.
5. Rollback leaves schema and rows intact. New orders remain non-fillable to the old reader through the legacy time sentinel; risk-reducing cancellation remains available.

## Open Questions

None requiring user value judgment. The agent decisions above are reversible, additive implementation choices and must be included in the PR's human-confirmation section and independent review.
