# llm-admission-blocker-recovery Specification

## Purpose
TBD - created by archiving change recover-admission-blocker-on-terminal. Update Purpose after archive.
## Requirements
### Requirement: Recovery scan resolves admission blockers from confirmed database terminals

**Trace:** Issue #350 受け入れ条件「UNKNOWN 終端で登録された blocker が、DB 終端確認後の recovery scan で自動解除される」

Every bounded recovery scan tick SHALL evaluate each process-local execution admission blocker against the current database state of its invocation. It SHALL resolve a blocker only when all of the following hold: the reservation is terminal (`FINISHED` or `FAILED`), the reservation records a non-null finish time, the blocker's claimant token matches the reservation's persisted execution claim token exactly, and the evaluation time has reached the finish time plus the configured hard timeout plus the process termination grace.

Resolution SHALL clear the recovery blocker and the heartbeat failure recorded under the same invocation and claimant token, because a terminal reservation makes past heartbeat outcomes irrelevant to admission.

The decision SHALL rest only on persisted reservation facts. The system MUST NOT resolve a blocker from process-local inference, elapsed wall-clock alone, absence of a database row, or the observation that no new blocker arrived.

#### Scenario: Reservation terminal predates the clearance window

- **WHEN** a blocker's reservation is terminal with a recorded finish time and the tick observes a time at or after finish time plus hard timeout plus process termination grace, and the blocker's claimant token equals the persisted execution claim token
- **THEN** the tick clears the recovery blocker and the same-key heartbeat failure, and admission health reports healthy once no other blocker, ambiguous claim, or unhealthy state remains

#### Scenario: Reservation is still running

- **WHEN** a blocker's reservation status is `RUNNING`
- **THEN** the blocker remains registered and admission stays fail-closed

#### Scenario: Clearance window has not elapsed

- **WHEN** a blocker's reservation is terminal but the tick observes a time before finish time plus hard timeout plus process termination grace
- **THEN** the blocker remains registered and admission stays fail-closed

#### Scenario: Reservation records no finish time

- **WHEN** a blocker's reservation is terminal but its persisted finish time is absent
- **THEN** the blocker remains registered because the clearance window has no anchor

#### Scenario: Claimant token does not match the reservation

- **WHEN** a blocker's claimant token differs from the persisted execution claim token for that invocation, including a blocker registered under a synthetic non-claim token
- **THEN** the tick leaves that blocker registered and resolves no state under the mismatched key

#### Scenario: Reservation row is absent

- **WHEN** no reservation row exists for a blocker's invocation
- **THEN** the blocker remains registered because absence is not a terminal fact

### Requirement: Blocker resolution leaves an audit trail

**Trace:** Issue #350 受け入れ条件「解除の監査イベントが command_event_log に残る」

Each resolved blocker SHALL append exactly one `command_event_log` event recording the invocation, claimant token, terminal reservation status, persisted finish time, resolution time, and the clearance window that was satisfied. The payload MUST NOT contain database passwords, exchange API keys, LLM credentials, or Cloudflare tokens.

Audit append failure SHALL fail the tick instead of resolving the blocker, so no blocker is cleared without a recorded reason.

#### Scenario: Blocker resolves successfully

- **WHEN** the tick resolves a blocker
- **THEN** exactly one audit event carries the invocation, claimant token, terminal status, finish time, resolution time, and clearance window

#### Scenario: Audit append fails

- **WHEN** the audit append for a resolution fails
- **THEN** the blocker remains registered, the tick reports failure, and recovery scan health becomes unhealthy until a later tick succeeds

### Requirement: Blocker evaluation respects the bounded recovery tick budget

The blocker evaluation pass SHALL execute inside the existing bounded recovery tick and SHALL check the deadline start reserve and coroutine cancellation before each blocker's database read. A database read failure SHALL mark recovery scan health unhealthy and fail the tick rather than silently skipping blockers.

The pass SHALL NOT alter the stale claim scan contract, its paging cursor, or the invariant that a recovery page completes with no unresolved recovery attempt.

#### Scenario: Deadline reserve is exhausted mid-pass

- **WHEN** the remaining tick budget falls below the start reserve before a blocker's database read
- **THEN** the tick fails with the deadline exception and recovery scan health becomes unhealthy, rather than reporting a completed pass

#### Scenario: Database read fails during the pass

- **WHEN** reading a blocker's reservation state fails
- **THEN** recovery scan health becomes unhealthy and the tick fails

#### Scenario: Stale claim recovery runs in the same tick

- **WHEN** a tick performs both blocker evaluation and stale claim recovery
- **THEN** the stale scan candidate set, cursor progression, and pending-recovery invariant remain unchanged by the blocker pass

### Requirement: Runtime gains no unconditional blocker reset

The blocker evaluation pass SHALL read blocker state through a read-only enumeration and SHALL resolve state only through the existing terminal-confirmation resolution path. The change MUST NOT add a runtime route, configuration switch, or public production capability that clears blockers without database terminal confirmation.

#### Scenario: Runtime surface is inspected

- **WHEN** the runtime routes, configuration, and public admission health API are inspected
- **THEN** no capability clears admission blockers without a confirmed database terminal
- **AND** the complete reset remains available to test fixtures only

