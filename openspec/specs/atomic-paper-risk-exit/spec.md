# atomic-paper-risk-exit Specification

## Purpose

Paper trading の full EXIT と HARD_HALT cleanup を、曖昧な状態を推測せず、同一 ledger authority 上で原子的かつ再試行可能に収束させる。

## Requirements

### Requirement: Full EXIT closes a position and invalidates same-thesis pending entries atomically

Issue #187 DoD (b): The system SHALL resolve the canonical thesis from the uniquely targeted position, close every open position currently attributed to that thesis, and cancel every open or pending-cancel risk-increasing BUY attributed to that thesis in one ledger transaction. It SHALL preserve exposure belonging to a different canonical thesis.

#### Scenario: Position and same-thesis resting entry coexist

- **WHEN** a full EXIT targets one open position and one or more risk-increasing pending BUY orders resolve to the same canonical thesis
- **THEN** all same-thesis position closes, protective-order cleanup, account mutation, and matching pending BUY cancellations commit together, leaving no same-thesis open exposure or late-entry candidate

#### Scenario: Unrelated thesis remains open

- **WHEN** a full EXIT targets one open position and another pending BUY resolves unambiguously to a different canonical thesis
- **THEN** the transaction closes the target position without canceling the unrelated pending BUY

#### Scenario: Resting entry is the only EXIT target

- **WHEN** no open position exists and exactly one risk-increasing pending BUY is the deterministic EXIT target
- **THEN** the system cancels that order without creating an execution

### Requirement: EXIT linkage ambiguity fails closed without partial mutation

Issue #187 DoD (b): The system MUST resolve canonical thesis linkage inside the transaction from persisted position, trade-group, order, and intent rows. Missing, null, multiple, or contradictory linkage SHALL abort the whole operation rather than infer a relationship.

#### Scenario: Target position thesis cannot be resolved uniquely

- **WHEN** the target position has missing, null, contradictory, or multiple canonical thesis candidates
- **THEN** the EXIT fails with a typed linkage failure and changes no position, order, execution, or account row

#### Scenario: Pending BUY thesis cannot be classified

- **WHEN** an open risk-increasing BUY that could survive the EXIT has missing, null, contradictory, or multiple canonical thesis linkage
- **THEN** the EXIT fails with a typed linkage failure and changes no ledger row

#### Scenario: Target changed before transaction lock

- **WHEN** the caller-selected position or resting order is no longer an eligible open target after the ledger locks are acquired
- **THEN** the operation fails with a typed stale-target result and performs no partial close or cancellation

### Requirement: EXIT and fill serialize at the ledger authority

Issue #187 DoD (b): Full EXIT and realtime market-event fill SHALL use the same lock order and conditional status transitions so that their commit order defines the only valid outcome.

#### Scenario: EXIT commits before an eligible fill

- **WHEN** EXIT and an eligible fill race and EXIT obtains the ledger authority first
- **THEN** the matching pending BUY is canceled in the EXIT transaction and the later fill creates no entry or execution

#### Scenario: Fill commits before EXIT

- **WHEN** an eligible fill obtains the ledger authority before EXIT
- **THEN** EXIT re-resolves the locked ledger state and latest full sizes, closes every resulting same-thesis position, and commits a state with no same-thesis open position or risk-increasing pending BUY

### Requirement: Sticky HARD_HALT cleanup converges through an idempotent atomic operation

Issue #187 DoD (f): While HARD_HALT is durable and sticky, the system SHALL atomically cancel all open risk-increasing orders and close all open positions using the same ledger close/cancel primitive as full EXIT. The durable cleanup state SHALL be UNKNOWN until the mutation transaction proves no open risk and stores SAFE. Startup and periodic reconciliation SHALL retry UNKNOWN cleanup.

#### Scenario: HARD_HALT cleanup succeeds

- **WHEN** HARD_HALT is set and executable market context is available
- **THEN** one ledger transaction cancels all risk-increasing open orders, closes all open positions at their locked latest full sizes, updates the account, proves no open risk, stores cleanup SAFE, and preserves HARD_HALT

#### Scenario: Failure occurs before cleanup commit

- **WHEN** cleanup fails or the process stops before its transaction commits
- **THEN** none of that cleanup transaction's ledger mutations persist, HARD_HALT remains active with cleanup UNKNOWN, and a later startup or periodic pass retries the cleanup

#### Scenario: Result is lost after cleanup commit

- **WHEN** the cleanup transaction commits but its result is not observed by the caller
- **THEN** a later pass revalidates SAFE against an atomic zero-open-risk readback and completes idempotently without duplicate close executions or account mutations

#### Scenario: Cleanup cannot obtain trustworthy execution input

- **WHEN** market context or another required fact is missing or ambiguous, including a REST ticker source timestamp that is missing, malformed, more than 5 seconds old, or more than 5 seconds in the future
- **THEN** the system creates no inferred or retrospective execution, keeps HARD_HALT active, and reports cleanup as incomplete or unknown

#### Scenario: Flat halted account has no market input

- **WHEN** HARD_HALT cleanup is UNKNOWN, no open position or risk-increasing order exists, and no trustworthy market tick is available
- **THEN** the atomic readback stores SAFE without requiring a synthetic execution price

#### Scenario: Order-only halted account has no market input

- **WHEN** HARD_HALT cleanup is UNKNOWN, risk-increasing orders exist without an open position, and no trustworthy market tick is available
- **THEN** the atomic transaction cancels those orders, proves zero open risk, and stores SAFE without requesting a ticker or creating an execution

#### Scenario: WebSocket connection remains unavailable

- **WHEN** HARD_HALT cleanup is UNKNOWN and the market-event WebSocket repeatedly fails to connect
- **THEN** each bounded connection-loop retry may use a REST tick only when its parsed exchange source timestamp is no more than 5 seconds old and no more than 5 seconds in the future, and uses it only for cleanup rather than entry fill or protective execution

#### Scenario: REST ticker becomes stale while execution context is built

- **WHEN** a REST ticker passes the initial freshness check but an external orderbook lookup completes after its exchange source timestamp becomes more than 5 seconds old
- **THEN** the system revalidates the same source timestamp immediately before ledger mutation, creates no execution or other ledger mutation, and keeps HARD_HALT cleanup UNKNOWN with open risk intact

#### Scenario: Realtime event provides causal cleanup authority

- **WHEN** a realtime market trade event reaches the ledger while HARD_HALT cleanup still has an open position
- **THEN** cleanup uses that same event price and its exchange timestamp without applying the independent REST polling freshness gate

#### Scenario: Manual resume is requested before cleanup is safe

- **WHEN** an operator requests resume while HARD_HALT cleanup state is UNKNOWN
- **THEN** the system rejects the resume without changing risk state or cleanup evidence

#### Scenario: Stale SAFE survives an old-writer rollback epoch

- **WHEN** an old binary leaves HARD_HALT cleanup marked SAFE while open position or risk-increasing order rows exist
- **THEN** the next cleanup attempt or manual resume atomically detects the open risk, changes cleanup to UNKNOWN, rejects resume, and continues cleanup without trusting stale SAFE

### Requirement: HARD_HALT cleanup does not block risk reduction or broaden scope

Issue #187 safety guardrails: The system MUST reject new risk-increasing mutations under HARD_HALT while allowing the atomic cleanup primitive and HARD_HALT sweep to perform deterministic cancel and close operations. This change SHALL NOT introduce historical-price fills, unrelated strategy mutation, multi-replica coordination, or a general-purpose recovery engine.

#### Scenario: Entry races after HARD_HALT activation

- **WHEN** a new risk-increasing placement or fill races with cleanup after HARD_HALT has committed
- **THEN** the shared risk-state-first lock and write policy reject the risk increase while cleanup remains eligible to proceed

#### Scenario: Protective order belongs to a closing position

- **WHEN** cleanup closes a position that has a linked protective SELL order
- **THEN** the protective order is canceled as part of the position close transaction rather than treated as a risk-increasing order or independently executed

#### Scenario: Legacy thesis linkage blocks normal EXIT

- **WHEN** a normal full EXIT cannot resolve canonical thesis linkage without inference
- **THEN** it performs no mutation and leaves explicit position close/REDUCE and thesis-independent HARD_HALT cleanup available as risk-reducing paths
