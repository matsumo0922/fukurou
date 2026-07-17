## Why

Issue #187 DoD (c) requires paper resting fills to respect durable event admission order rather than queue processing time. The current listener commits a durable receipt before enqueueing, but the receipt ordinal is discarded and later-created orders can still consume an already-committed buffered event, producing a non-causal paper fill.

## What Changes

- （ユーザー確認済み）Bind each risk-increasing resting order to the maximum committed receipt admission ordinal captured in its creation transaction.
- Carry the exact committed receipt identity and admission ordinal with each realtime trade event from the WebSocket listener to the ledger consumer.
- Require the exact event receipt to be newer than the order boundary before an entry fill can commit.
- Fail closed without a fill when the receipt, boundary, session, or durable linkage is missing or contradictory; do not backfill legacy orders.
- Remove wall-clock `market_eligible_from` from the active entry-fill predicate while retaining additive schema compatibility and an explicit fail-closed rollback procedure.
- Preserve risk-reducing STOP/TP handling and realtime position protection independently from risk-increasing resting BUY eligibility.
- Add deterministic commit-order barrier coverage, including the Issue #187 requirement of at least 1,000 pre-boundary race iterations.

## Capabilities

### New Capabilities

- `durable-receipt-eligibility`: Defines durable receipt propagation, transactional order-boundary capture, exact receipt eligibility, fail-closed legacy handling, and risk-reducing non-regression.

### Modified Capabilities

- None.

## Impact

- Realtime transport and event model: `GmoPublicWebSocketMarketEventStream`, `PaperMarketTradeEvent`, receipt repository contracts.
- Paper order creation and fill authority: `PaperBroker`, `PaperLedgerRepository`, `ExposedPaperLedgerWriter`, and in-memory parity where applicable.
- PostgreSQL schema/bootstrap: one nullable additive order boundary column and receipt lookup/capture queries that reuse the existing global admission-ordinal index; no destructive migration or history rewrite.
- Tests: WebSocket receipt propagation, deterministic PostgreSQL commit-order races, missing/legacy linkage, schema upgrade/rollback compatibility, and both production authority roots.
- Documentation: receipt authority changes from dark write to fill eligibility in `README.md`, `docs/design.md`, and `docs/mcp-runtime.md` where affected.
- Non-goals: C0 queue/journal/generation-token recovery infrastructure, receipt pruning, retrospective replay/backfill, multi-replica coordination, general recovery engines, root-installed artifacts, and NAS bootstrap.
