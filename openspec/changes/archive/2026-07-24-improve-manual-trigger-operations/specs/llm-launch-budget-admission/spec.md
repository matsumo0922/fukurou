## ADDED Requirements

### Requirement: Hard invocation caps apply before trigger-specific reserve policy
**Trace:** Issue #307 受け入れ条件「hard cap 超過時は MANUAL も従来どおり拒否される」

The launch admission system SHALL reject every trigger kind, including `MANUAL`, when the rolling hourly or daily invocation total has reached the configured hard cap. A reserve exemption SHALL NOT increase or bypass `maxInvocationsPerHour` or `maxInvocationsPerDay`.

#### Scenario: Manual trigger reaches the hourly hard cap
- **WHEN** the rolling hourly usage has reached `maxInvocationsPerHour` and a `MANUAL` reservation is requested
- **THEN** admission rejects the request with `MAX_INVOCATIONS_PER_HOUR`

#### Scenario: Manual trigger reaches the daily hard cap
- **WHEN** the rolling daily usage has reached `maxInvocationsPerDay` and a `MANUAL` reservation is requested
- **THEN** admission rejects the request with `MAX_INVOCATIONS_PER_DAY`

### Requirement: Manual trigger bypasses unused critical-trigger reserves
**Trace:** Issue #307 受け入れ条件「reserve 保護域まで枠が消費された状態で MANUAL trigger が受理される」

The launch admission system SHALL exclude `MANUAL` requests from the protection of unused `ENTRY_FILL` and `STOP_PROXIMITY` hourly and daily reserves. The exemption SHALL apply only to reserve protection; all other admission gates, concurrency rules, and hard caps SHALL continue to apply.

#### Scenario: Automatic heartbeat reaches protected hourly headroom
- **WHEN** hourly usage has consumed all non-reserved headroom while the `ENTRY_FILL` and `STOP_PROXIMITY` hourly reserves remain unused
- **THEN** a `FLAT_HEARTBEAT` request is rejected with the applicable critical-trigger reserve reason

#### Scenario: Manual request uses protected hourly headroom
- **WHEN** the same hourly usage leaves only unused `ENTRY_FILL` or `STOP_PROXIMITY` reserve headroom and a `MANUAL` reservation is requested
- **THEN** the request is not rejected by either hourly reserve protection check

#### Scenario: Manual request uses protected daily headroom
- **WHEN** daily usage leaves only unused `ENTRY_FILL` or `STOP_PROXIMITY` reserve headroom and a `MANUAL` reservation is requested
- **THEN** the request is not rejected by either daily reserve protection check

#### Scenario: Critical triggers retain their existing reserve relationship
- **WHEN** an `ENTRY_FILL` or `STOP_PROXIMITY` request exceeds its own guaranteed usage while the other critical trigger still has unused reserve
- **THEN** the request remains subject to the other critical trigger's reserve protection
