## ADDED Requirements

### Requirement: Recovery scan resolves admission blockers from database terminal facts
**Trace:** Issue #350 受け入れ条件「UNKNOWN 終端で登録された blocker が、DB 終端確認後の recovery scan で自動解除される」

Each periodic recovery scan tick SHALL evaluate a bounded batch of registered execution admission recovery blockers against the durable reservation record identified by each blocker's invocation ID. The scan SHALL resolve a blocker only when both of the following hold: the reservation for that invocation ID is in a terminal status (`FINISHED` or `FAILED`), and the elapsed time since the blocker was registered is at least `hardTimeout + processTerminationGrace`. A blocker whose reservation is still `RUNNING`, whose reservation cannot be read, or whose quiet period has not elapsed SHALL remain registered and SHALL keep admission fail-closed.

Blocker resolution SHALL be derived only from the durable reservation record. The scan SHALL NOT infer termination from the absence of a record, from process-local heuristics, or from the passage of time alone.

The quiet period SHALL be measured from a monotonic clock reading captured when the blocker was registered. Wall-clock timestamps recorded for audit purposes SHALL NOT determine eligibility, so that a backward or forward system clock adjustment neither delays nor accelerates resolution.

#### Scenario: Blocker whose reservation reached a terminal status clears after the quiet period

- **GIVEN** a recovery blocker registered for an invocation whose child process termination was unconfirmed
- **WHEN** the reservation for that invocation is `FINISHED` or `FAILED` in the database and at least `hardTimeout + processTerminationGrace` has elapsed since the blocker was registered
- **THEN** the recovery scan tick removes the blocker
- **AND** execution admission health reports healthy once no other blocker, ambiguous claim, heartbeat failure, or scan failure remains

#### Scenario: Reservation is still running

- **GIVEN** a recovery blocker registered for an invocation
- **WHEN** the reservation for that invocation is still `RUNNING`
- **THEN** the recovery scan tick keeps the blocker registered
- **AND** execution admission health continues to report unhealthy

#### Scenario: Quiet period has not elapsed

- **GIVEN** a recovery blocker registered for an invocation whose reservation is already terminal
- **WHEN** less than `hardTimeout + processTerminationGrace` has elapsed since the blocker was registered
- **THEN** the recovery scan tick keeps the blocker registered

#### Scenario: Reservation lookup fails

- **GIVEN** a recovery blocker registered for an invocation
- **WHEN** the reservation lookup for that invocation returns a failure
- **THEN** the recovery scan tick keeps the blocker registered
- **AND** the tick does not treat the lookup failure as a terminal observation

#### Scenario: Reservation record is absent

- **GIVEN** a recovery blocker registered for an invocation
- **WHEN** no reservation record exists for that invocation ID
- **THEN** the recovery scan tick keeps the blocker registered

#### Scenario: System clock moves backward after registration

- **GIVEN** a recovery blocker registered for an invocation whose reservation is terminal
- **WHEN** the system wall clock is adjusted backward and at least `hardTimeout + processTerminationGrace` of real elapsed time has since passed
- **THEN** the recovery scan tick resolves the blocker

### Requirement: Blocker evaluation is bounded and starvation-free
**Trace:** Issue #350 受け入れ条件「回帰テスト 1 本」（自己回復が実際に完了すること）

Each tick SHALL stop evaluating blockers when either a fixed candidate count is reached or the remaining tick deadline falls below a reserve sufficient for the stale-claim scan to start, whichever comes first. Reaching either limit SHALL be a normal handoff to the stale-claim scan, not a tick failure. A count limit alone SHALL NOT be treated as sufficient, because slow per-candidate database responses would otherwise consume the scan's budget.

Evaluation SHALL advance through the registry in a stable order using a cursor that resumes from the previous tick's position. The cursor SHALL advance past each evaluated candidate, including candidates whose evaluation failed, so that a repeatedly failing candidate cannot prevent later candidates from ever being evaluated.

#### Scenario: Registry holds more blockers than one batch

- **GIVEN** more registered blockers than a single tick's batch limit, where the earlier ones are all retained and a later one is resolvable
- **WHEN** successive recovery ticks run
- **THEN** the resolvable blocker is evaluated and resolved within a bounded number of ticks

#### Scenario: Per-candidate lookups are slow

- **GIVEN** registered blockers whose individual database lookups each consume a large fraction of the tick budget
- **WHEN** the remaining deadline falls below the stale-claim scan reserve
- **THEN** the tick stops evaluating further blockers and proceeds to the stale-claim scan
- **AND** the stale-claim scan is able to start within its required reserve

#### Scenario: A candidate fails on every evaluation

- **GIVEN** a blocker at the front of the stable order whose evaluation fails on every attempt, and a resolvable blocker later in the order
- **WHEN** successive recovery ticks run
- **THEN** the cursor advances past the failing candidate
- **AND** the resolvable blocker is evaluated and resolved within a bounded number of ticks

#### Scenario: Blocker evaluation shares the tick deadline

- **GIVEN** registered blockers to evaluate
- **WHEN** the database does not respond within the tick's remaining budget
- **THEN** the tick fails within its bounded deadline rather than blocking indefinitely
- **AND** a later tick retries with a fresh budget

### Requirement: Automatic blocker resolution is audited before it takes effect
**Trace:** Issue #350 受け入れ条件「解除の監査イベントが command_event_log に残る」

Every automatic blocker resolution SHALL append exactly one audit event to `command_event_log` recording the invocation ID, the claimant token, the observed terminal reservation status, and the elapsed quiet duration. The audit append SHALL be committed in the same durable transaction that observes the terminal reservation, and the in-memory blocker SHALL be removed only after that transaction commits. A failed or uncommitted append SHALL leave the blocker registered so that the next tick retries. Audit payloads SHALL NOT contain secrets.

Each blocker SHALL carry a stable resolution attempt identifier assigned at registration and reused across retries as the audit event identifier. Before appending, the resolving transaction SHALL read back whether an audit event with that identifier already exists. A matching existing event SHALL confirm resolution without appending again. A conflicting existing event SHALL fail rather than resolve. This guarantees that a lost commit acknowledgement neither duplicates the audit event nor leaves the blocker permanently unresolvable.

#### Scenario: Resolution appends an audit event

- **WHEN** the recovery scan resolves a blocker from a terminal reservation
- **THEN** exactly one `LLM_ADMISSION_BLOCKER_AUTO_RESOLVED` event is appended with the invocation ID, claimant token, observed reservation status, and elapsed quiet duration

#### Scenario: Audit append fails

- **WHEN** the audit append for a resolvable blocker fails
- **THEN** the blocker remains registered
- **AND** admission health continues to report unhealthy
- **AND** the recovery tick reports the failure rather than reporting a silent success

#### Scenario: Repeated ticks do not duplicate audit events

- **WHEN** a blocker is resolved on one tick and subsequent ticks run
- **THEN** no further `LLM_ADMISSION_BLOCKER_AUTO_RESOLVED` event is appended for that blocker

#### Scenario: Commit acknowledgement is lost after the audit event commits

- **GIVEN** a resolvable blocker whose resolving transaction committed its audit event
- **WHEN** the caller observes a failure instead of the committed result and a later tick re-evaluates the same blocker
- **THEN** the later tick observes the already-committed audit event and resolves the blocker
- **AND** no second audit event is appended

#### Scenario: An event with the attempt identifier exists but does not match

- **GIVEN** a blocker whose resolution attempt identifier collides with an unrelated audit event
- **WHEN** the resolving transaction reads it back
- **THEN** the tick fails and the blocker remains registered

### Requirement: Self-healing applies to production admission wiring
**Trace:** Issue #350 受け入れ条件「回帰テスト 1 本」（production call path での発動確認）

The production recovery worker SHALL drive automatic blocker resolution on its periodic schedule using the same durable reservation repository and audit log that the application wires at startup. Blocker registration performed by the one-shot runner on unconfirmed process-tree termination SHALL be resolvable by that worker without a process restart.

#### Scenario: Production worker restores readiness after an unconfirmed termination

- **GIVEN** the production recovery worker is running against the application's reservation repository and audit log
- **WHEN** a runner registers a recovery blocker for an invocation whose reservation subsequently becomes terminal, and the quiet period elapses
- **THEN** a later worker tick resolves the blocker without a process restart
- **AND** the readiness probe reports ready again

### Requirement: Fail-closed semantics survive the self-heal path
**Trace:** Issue #350 scope 外「fail-closed 機構自体の撤去・緩和」

Automatic resolution SHALL NOT introduce a runtime route, configuration flag, or public API that clears admission blockers without a durable terminal observation. Ambiguous claims, heartbeat failures, and recovery scan failures SHALL retain their existing effect on admission health and SHALL NOT be cleared by this mechanism.

#### Scenario: Non-blocker health inputs are untouched

- **WHEN** admission health is unhealthy because of an ambiguous claim, a heartbeat failure, or an unhealthy recovery scan
- **THEN** automatic blocker resolution does not clear that condition

#### Scenario: No operator override surface is added

- **WHEN** the application's runtime routes and configuration are inspected
- **THEN** no endpoint or setting clears an admission blocker without a durable terminal observation
