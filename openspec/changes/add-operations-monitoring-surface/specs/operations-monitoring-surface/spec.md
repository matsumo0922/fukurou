## ADDED Requirements

### Requirement: Operations monitoring endpoint exposes a versioned redacted snapshot
The system SHALL expose `GET /ops/monitoring` as a versioned read-only contract containing the deployed revision and component snapshots for daemon worker tick/invocation terminal, provider outcomes, reconciler, unresolved gaps, and backup/restore freshness. The response SHALL contain only explicitly allowlisted fields and SHALL NOT contain raw event payloads, command output, exception messages, filesystem paths, repository coordinates, passwords, tokens, or invocation identifiers.

#### Scenario: All sources are valid
- **WHEN** every monitoring source returns a schema-valid snapshot
- **THEN** the endpoint returns HTTP 200 with `schemaVersion`, `observedAt`, `revision`, and `AVAILABLE` component values sufficient to evaluate PR-5 alert rules

#### Scenario: A source fails independently
- **WHEN** one monitoring source is absent, malformed, unavailable, over its bound, or raises a query failure
- **THEN** the endpoint returns HTTP 200, marks only the affected component `UNKNOWN` with a stable reason code, and does not synthesize a healthy value from another source

#### Scenario: Response redaction is enforced
- **WHEN** source data contains raw payload, provider output, host paths, repository fields, credentials, or internal exception text
- **THEN** none of those values or field names appear in the response or route log

### Requirement: Database monitoring reads are finite and fail closed
The system SHALL derive daemon terminal, 30-minute provider outcome, and unresolved gap facts through fixed-window bounded queries. A truncated, malformed, unknown-enum, or partially parsed result SHALL be reported as `UNKNOWN`, not as a partial aggregate.

Daemon terminal timestamps SHALL be compared at the millisecond precision preserved by `command_event_log.ts`. Resolved gap history SHALL NOT consume the unresolved-gap row bound.

#### Scenario: Provider outcomes are aggregated
- **WHEN** valid runner terminal events exist within the fixed 30-minute window
- **THEN** the endpoint returns per-provider total, failure, and authentication-failure counts without returning individual event payloads

#### Scenario: Deterministic runner phase has no provider
- **WHEN** an in-window `RUNNER_PHASE_COMPLETED` event represents a deterministic non-provider phase
- **THEN** it is excluded from provider aggregation and does not make the provider component malformed

#### Scenario: Authentication signal is absent
- **WHEN** a valid provider phase event omits the optional `authFailureSuspected` field
- **THEN** it contributes zero authentication failures rather than making the provider component unknown

#### Scenario: Malformed provider event is present
- **WHEN** any in-window runner terminal event lacks a valid provider, terminal status, or authentication-failure value
- **THEN** the provider component is `UNKNOWN` and no partial provider counts are returned

#### Scenario: Query bound is reached
- **WHEN** a monitoring query reaches its declared maximum row or result bound
- **THEN** its component is `UNKNOWN` with a stable bound-exceeded reason instead of silently truncating evidence

### Requirement: Reconciler and gap evidence retain source boundaries
The system SHALL read reconciler freshness/current market-data state from the reconciler status provider and unresolved gap aggregates from persistent gap state. It SHALL preserve independent availability for these sources.

#### Scenario: Reconciler is available and gap query fails
- **WHEN** reconciler status is valid but the unresolved gap query fails
- **THEN** reconciler remains `AVAILABLE` and gaps are `UNKNOWN`

#### Scenario: Unresolved gaps exist
- **WHEN** persistent market-data or infrastructure gaps remain unresolved
- **THEN** the gap component returns their count and oldest opened-at timestamp without exposing row payloads

### Requirement: Daemon worker liveness is independently observable
The system SHALL expose the last completed scheduler tick timestamp/outcome from a live in-process status provider separately from the last LLM invocation terminal event. A lack of trigger SHALL still complete and refresh a tick.

#### Scenario: Scheduler runs without selecting a trigger
- **WHEN** the enabled scheduler completes a poll with no due trigger
- **THEN** the daemon component advances `lastTickAt` while leaving the last invocation terminal unchanged

#### Scenario: Scheduler worker stalls or exits
- **WHEN** no scheduler tick completes for longer than the external policy permits
- **THEN** the unchanged `lastTickAt` allows PR-5 to detect the stale worker without inferring liveness from invocation events

### Requirement: Monitoring does not alter readiness or trading behavior
The operations monitoring aggregation SHALL NOT participate in `/health/ready`, scheduler admission, SafetyFloor, order lifecycle, or trade execution.

#### Scenario: Monitoring source is unavailable
- **WHEN** the backup projection or a monitoring query is unavailable
- **THEN** `/ops/monitoring` reports `UNKNOWN` while the existing readiness and trading semantics remain unchanged

### Requirement: Route-local OpenAPI defines the monitoring wire contract
The route SHALL define its summary, description, response schema, component states, and stable reason values in route-local `.describe {}` metadata.

#### Scenario: OpenAPI is generated
- **WHEN** `/openapi.json` is requested
- **THEN** it documents `GET /ops/monitoring` and its versioned redacted response without documenting secret-bearing source structures
