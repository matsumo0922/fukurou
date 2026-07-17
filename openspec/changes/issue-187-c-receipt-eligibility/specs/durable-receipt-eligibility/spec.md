## ADDED Requirements

### Requirement: Committed receipt authority reaches the ledger unchanged

Issue #187 DoD (c): The system SHALL attach the exact durable receipt identity, admission ordinal, payload hash, and canonical persisted socket-observation timestamp returned by receipt commit to the realtime trade event published to the application consumer. The event `receivedAt` SHALL equal that canonical timestamp. It MUST NOT publish the event when receipt commit fails or conflicts.

#### Scenario: New receipt commits before dispatch

- **WHEN** the WebSocket listener decodes a trade and commits a new durable receipt
- **THEN** it publishes the trade only after commit with that receipt ID, admission ordinal, payload hash, and canonical persisted observation timestamp attached

#### Scenario: Duplicate source event reuses receipt authority

- **WHEN** an identical `(session, source sequence, normalized payload)` is received again
- **THEN** receipt commit returns the existing receipt and the published event carries the same receipt ID, admission ordinal, and original canonical `receivedAt` without creating another receipt

#### Scenario: Receipt persistence is not trustworthy

- **WHEN** receipt persistence fails or the same source identity has a conflicting payload
- **THEN** the listener publishes no trade event and terminates through the existing typed infrastructure-gap path

### Requirement: Resting entry creation captures a durable admission boundary transactionally

Issue #187 DoD (c): Every realtime-enabled risk-increasing resting BUY SHALL store a conservative global maximum committed receipt admission ordinal inside the order creation transaction. Receipt commit and boundary capture MUST serialize on the connected session's existing advisory-lock authority, and session rows MUST be locked before ledger rows on order and event paths.

#### Scenario: Receipt commits before order creation authority

- **WHEN** receipt N commits before the order creation transaction obtains the exclusive session authority
- **THEN** the order boundary includes receipt N even if its application event is still buffered or unprocessed

#### Scenario: Order creation authority precedes receipt commit

- **WHEN** the order transaction captures and commits boundary N before the next receipt obtains the session authority
- **THEN** the next receipt receives an admission ordinal greater than N and can be eligible for that order

#### Scenario: Session changes during order creation

- **WHEN** the captured session is disconnected, replaced, stale, or advances incompatibly before the order transaction validates it
- **THEN** order creation fails without inserting the resting entry or consuming its intent

### Requirement: Risk-increasing fill requires an exact newer receipt

Issue #187 DoD (c): A resting BUY fill SHALL require an exact persisted receipt whose identity, session, source sequence, admission ordinal, payload hash, canonical socket-observation timestamp, `receivedAt`, and normalized event payload match the consumed event, and whose admission ordinal is strictly greater than the order boundary. Wall-clock receive time MUST NOT be an ordering authority.

#### Scenario: Buffered pre-boundary event is processed after order commit

- **WHEN** a receipt commits, an order later captures a boundary including that receipt, and the buffered event is processed after the order commit
- **THEN** the event creates no BUY fill or execution

#### Scenario: Post-boundary receipt is processed

- **WHEN** an order commits boundary N and an exact matching receipt with ordinal greater than N commits afterward
- **THEN** the event may proceed to the existing queue, fill-invariant, cash, exposure, and status-CAS gates

#### Scenario: Wall clock disagrees with durable ordering

- **WHEN** `receivedAt` or processing time appears newer than order creation but the durable receipt ordinal is not newer than the order boundary
- **THEN** the event remains ineligible and creates no fill

#### Scenario: Exact receipt evidence is contradictory

- **WHEN** receipt ID, session, source sequence, admission ordinal, payload hash, canonical socket-observation timestamp, `receivedAt`, or recomputed normalized payload does not match the persisted receipt
- **THEN** the system performs no risk-increasing fill and cancels affected entries through the existing market-data-gap reason without requiring a SafetyViolation cancellation-detail row

### Requirement: Missing or legacy eligibility fails closed without blocking risk reduction

Issue #187 safety guardrails: Missing receipt authority, a null order boundary, session mismatch, or reconnect ambiguity MUST NOT be inferred or backfilled into an eligible entry fill. The same realtime event SHALL remain available for risk-reducing position protection.

#### Scenario: Legacy order has no admission boundary

- **WHEN** a risk-increasing resting BUY has a null durable boundary
- **THEN** the system creates no fill and moves the order through the existing market-data-gap cancellation path without retrospective backfill

#### Scenario: Event belongs to another session

- **WHEN** a resting BUY belongs to an earlier session and a new-session event arrives
- **THEN** the old BUY is not rebound or filled by that event

#### Scenario: Position protection shares an event with ineligible entries

- **WHEN** an event is ineligible for one or more risk-increasing resting BUY orders but is a causal realtime event for an open position
- **THEN** entry fills remain zero while STOP/TP and other risk-reducing position handling continue under their existing status predicates

### Requirement: Additive migration and operational rollback remain fail-closed

The system SHALL add the order receipt-boundary column without rewriting history or building a new large-table receipt index. Fresh installs and upgrades MUST initialize the schema, new readers MUST fail closed on null legacy boundaries, and operators MUST stop admission and cancel/drain all new-semantics resting BUY orders before starting a pre-change reader.

#### Scenario: Existing database upgrades

- **WHEN** bootstrap runs against an orders table without the receipt-boundary column
- **THEN** it adds the nullable column without changing existing order, receipt, execution, or account history and boundary lookup uses the existing global admission-ordinal index

#### Scenario: New reader observes a legacy row

- **WHEN** an upgraded reader evaluates an existing resting BUY whose durable boundary is null
- **THEN** it creates no fill and does not synthesize or backfill a boundary

#### Scenario: Operator rolls back to an old reader

- **WHEN** rollback to a pre-change reader is required
- **THEN** authenticated `POST /ops/halt` activates durable `HARD_HALT`, the new reader's `ALL_OPEN_RISK` sweep reaches `SAFE`, zero-open-risk readback and a zero-row resting-BUY query agree, and only then is the old reader started against the additive schema

#### Scenario: Commit-order barrier repeats one thousand times

- **WHEN** the deterministic receipt-first/order-second/buffer-consume race is repeated at least 1,000 times against PostgreSQL
- **THEN** pre-boundary BUY fills and executions remain zero in every iteration
