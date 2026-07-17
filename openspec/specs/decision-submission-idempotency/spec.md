# Decision Submission Idempotency Specification

## Purpose

LLM decision submission の server-owned authority、冪等 retry、fail-closed conflict/UNKNOWN、および legacy compatibility を定義する。

## Requirements

### Requirement: Server-owned submission authority
The system SHALL derive the strict decision submission key from the server-owned invocation ID and invocation phase, SHALL reject a caller-provided invocation ID that differs from the server context, and SHALL require the app-owned gateway for a production invocation phase.

#### Scenario: Matching or omitted caller identity
- **WHEN** `submit_decision` omits `invocation_id` or supplies the exact server-owned invocation ID
- **THEN** the system submits with the server-owned invocation ID and gateway-bound phase

#### Scenario: Spoofed caller identity
- **WHEN** `submit_decision` supplies an invocation ID different from the server context
- **THEN** the system returns a typed invalid request before any decision repository mutation

#### Scenario: Production gateway is unavailable
- **WHEN** a decision-capable production invocation phase handles `submit_decision` without an app-owned gateway
- **THEN** the system rejects the submission and does not fall back to a direct repository write

#### Scenario: Manifest and decision-run identities diverge
- **WHEN** the gateway-bound manifest invocation ID differs from the server decision-run ID
- **THEN** startup or submission fails before authority creation rather than choosing either identity implicitly

### Requirement: Same-payload retry returns the committed result
The system SHALL store a durable authority for each server-owned `(invocationId, phase)` and SHALL return the originally committed decision, TradePlan, and TradeIntent IDs when the same canonical business payload is retried.

#### Scenario: Gateway response is lost after commit
- **WHEN** a decision submission commits but its gateway response is discarded and the same invocation, phase, and canonical payload are submitted again
- **THEN** the retry returns the original result IDs without adding decision, TradePlan, TradeIntent, evidence, link, coverage, opportunity episode, identity failure, or dedupe shadow rows

#### Scenario: Concurrent identical submissions
- **WHEN** multiple transactions concurrently submit the same invocation, phase, and canonical payload
- **THEN** one transaction creates the entities and every successful caller receives the same result IDs

#### Scenario: Concurrent winner rolls back
- **WHEN** the transaction that first inserts the authority rolls back while an identical contender is waiting on the same key
- **THEN** the contender becomes the sole winner, commits one result, and no orphan authority or entity remains

#### Scenario: Semantically equivalent representation
- **WHEN** a retry differs only in canonical JSON object key order or numerically equivalent decimal scale
- **THEN** the system treats the business payload as identical and returns the original result IDs

### Requirement: Changed or ambiguous retry fails closed
The system SHALL reject a changed canonical payload for an existing key as a typed conflict and SHALL report UNKNOWN without re-execution when the durable authority cannot reconstruct a completed result.

#### Scenario: Changed payload under the same key
- **WHEN** any canonical decision, TradePlan, or TradeIntent field changes for an existing invocation and phase
- **THEN** the system returns `decision_submission_conflict` and adds no decision protocol rows

#### Scenario: Authority is incomplete
- **WHEN** the authority row is not completed or lacks a required committed result reference
- **THEN** the system returns `decision_submission_unknown` and does not create or repair decision protocol rows automatically

#### Scenario: Authority result is inconsistent
- **WHEN** stored result references do not reconstruct the exact decision, TradePlan, and TradeIntent tuple declared by the authority
- **THEN** the system returns `decision_submission_unknown` and preserves the ambiguous state for read-only investigation

### Requirement: Authority and decision effects commit atomically
The system SHALL commit the authority, decision, optional TradePlan and TradeIntent, terminal evidence association, and completed result references in one database transaction.

#### Scenario: Failure before transaction commit
- **WHEN** persistence fails after authority claim but before transaction commit
- **THEN** the authority and all decision/evidence mutations roll back together so a later submission can make one fresh attempt

#### Scenario: Failure after transaction commit
- **WHEN** the database commit succeeds but the caller cannot observe the response
- **THEN** a retry reads the completed authority and does not repeat any decision or evidence mutation

### Requirement: Legacy history remains outside the strict authority cohort
The system SHALL add the strict authority schema without modifying, deduplicating, or assigning phases to legacy decision rows.

#### Scenario: Upgrade with legacy decisions
- **WHEN** the application upgrades a database containing nullable, unattributed, or repeated legacy invocation IDs
- **THEN** bootstrap creates the empty additive authority table without rewriting legacy decisions or deriving strict authority rows from them

#### Scenario: Old reader rollback
- **WHEN** the deployment rolls back to an image that does not understand the authority table
- **THEN** the table and rows remain intact and decision-capable LLM launch stays disabled until a gateway-aware image is restored

### Requirement: Canonical payload hashing is versioned and bounded
The system SHALL hash a versioned, typed projection of persisted decision semantics and SHALL exclude secrets, filesystem paths, raw logs, raw exceptions, and server-owned key metadata from that projection.

#### Scenario: Canonical field inventory
- **WHEN** each persisted decision, TradePlan, or TradeIntent business field is changed one at a time
- **THEN** the schema-v1 payload hash changes for every inventoried field

#### Scenario: Non-business metadata changes
- **WHEN** server-owned invocation metadata or an excluded audit transport value changes without changing persisted business semantics
- **THEN** that value is not copied into the business payload projection or exposed through a conflict response
