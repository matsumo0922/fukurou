## ADDED Requirements

### Requirement: The disconnect is bound to the owner-approved target order
The temporary Issue #192 controller MUST bind a request to one target order UUID, exact expiry, and bounded minimum remaining TTL. Immediately before requested audit, it MUST read the authoritative order snapshot and MUST require the same target to remain an `OPEN`, position-unlinked BUY entry with matching expiry and sufficient TTL. It MUST NOT substitute another resting order.

#### Scenario: The approved target remains eligible
- **WHEN** the target UUID matches one `OPEN`, position-unlinked BUY entry, its expiry equals the approved expiry, and its expiry is no earlier than the final observation plus the requested minimum TTL
- **THEN** target binding admits the request to the remaining one-shot and session gates

#### Scenario: The target is absent or replaced
- **WHEN** no authoritative order snapshot matches the approved target UUID
- **THEN** the controller returns a typed target-not-found conflict before requested audit and disconnect

#### Scenario: The target state, expiry, or TTL changed
- **WHEN** the target is not `OPEN`/BUY/position-unlinked, its expiry differs, or its remaining TTL is below the approved boundary
- **THEN** the controller returns the corresponding typed conflict before requested audit and disconnect

### Requirement: Pending cancellation is a no-mutation condition
The controller MUST require zero `PENDING_CANCEL`, position-unlinked BUY entries and zero open positions in its final preflight. A rejection MUST write neither requested nor executed audit and MUST NOT disconnect the WebSocket.

#### Scenario: A resting BUY enters pending cancellation
- **WHEN** the controller final preflight observes any position-unlinked BUY entry in `PENDING_CANCEL`
- **THEN** it returns `PREFLIGHT_INVENTORY_REJECTED`, appends no audit, and performs no disconnect

### Requirement: Fixed audit identity remains fail closed and single use
The fixed requested audit row MUST remain the global one-shot burn. Lookup failure or payload conflict MUST fail closed. The added target boundary MUST be written to new requested/executed payloads but MUST NOT allow an existing correctly identified fixed requested row to be bypassed.

#### Scenario: Requested audit is durable but the response is incomplete
- **WHEN** the fixed requested row exists and response, stream publication, abort, or executed audit is incomplete
- **THEN** no second injection is allowed and downstream verification uses durable audit state rather than the HTTP response alone

### Requirement: PR-2 is deployed before production verification
Production arm 1 MUST NOT begin until a reviewed main revision containing the PR-2 target-binding and pending-cancel checks is deployed and confirmed by revision evidence. PR-2 MUST NOT claim production-arm results or cleanup observations.

#### Scenario: Production still runs PR-1-only code
- **WHEN** `/revision` does not identify a main revision containing the PR-2 safety fix
- **THEN** the operator performs no fault request and does not treat operator-only queries as a substitute for controller enforcement

#### Scenario: PR-2 reaches a reviewed production revision
- **WHEN** PR-2 is merged and the signed deploy completes
- **THEN** PR-3 may begin mutation-free rehearsal and separately owner-gated production verification using the handed-off evidence template

### Requirement: Residual cross-system drift is not hidden
PR-2 MUST NOT claim continuous isolation across database, NAS, and GitHub. PR-3 MUST classify a condition that changes after its final operator or controller check as `INVALID` if requested audit has been written, MUST NOT retry the injection, and MUST preserve the exact observed difference.

#### Scenario: State drifts after the final check
- **WHEN** post-impact evidence shows an admitted application or operator-owned condition changed after its final check
- **THEN** the arm is `INVALID`, the one-shot is not retried, and the evidence records the check time and state difference
