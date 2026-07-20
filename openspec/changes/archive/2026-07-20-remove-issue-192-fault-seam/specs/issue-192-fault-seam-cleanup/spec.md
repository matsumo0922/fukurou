## ADDED Requirements

### Requirement: Issue #192 fault-injection surface is absent
The system MUST NOT expose or configure the discontinued Issue #192 WebSocket fault-injection surface. This requirement traces the user's explicit decision to stop the two production injections instead of claiming the original Issue #192 DoD was completed.

#### Scenario: Application starts after cleanup
- **WHEN** the production application is built and started from the cleanup revision
- **THEN** `POST /ops/issue-192/ws-disconnect`, its controller and application wiring are absent, and no Issue #192 activation environment key is read

#### Scenario: Production compose is rendered after cleanup
- **WHEN** the production compose configuration is rendered without any Issue #192-specific input
- **THEN** no `FUKUROU_ISSUE_192_WS_FAULT_ENABLED` environment entry is passed to the application

### Requirement: Normal market-data fidelity behavior remains intact
The cleanup MUST preserve normal WebSocket connection, natural terminal failure handling, process-restart gap recovery, durable market-event receipts, paper execution causality, and evaluation exclusion behavior. It MUST NOT delete or backfill production data.

#### Scenario: Existing market-data regression suite runs
- **WHEN** the repository's market-data, persistence, application routing, and paper-fidelity tests run against the cleanup revision
- **THEN** normal connection, disconnect recovery, receipt, execution, and exclusion behavior passes without an injected-disconnect API

#### Scenario: Historical state is preserved
- **WHEN** the cleanup revision is deployed
- **THEN** no schema migration, production row deletion, ledger rewrite, receipt backfill, gap rewrite, or evaluation-exclusion rewrite is performed

### Requirement: Historical audit decoding remains safe
The cleanup MUST retain the Issue #192 requested/executed command-event types and activity labels as decoding-only vocabulary so that any unknown existing row cannot break the activity feed. It MUST remove the writer and fixed-primary-key reader used by the retired runtime surface, and MUST NOT query-delete rows or add a data migration.

#### Scenario: Repository is searched after cleanup
- **WHEN** tracked runtime code, tests, compose, and current documentation are searched for Issue #192 injection symbols
- **THEN** no runtime activation key, route, controller, disconnector, writer, or fixed-primary-key reader remains outside the archived OpenSpec experiment record and this cleanup change

#### Scenario: A historical Issue #192 audit row is decoded
- **WHEN** the activity feed encounters a requested or executed Issue #192 command-event value after cleanup
- **THEN** the retained enum and activity catalog mapping decode it without exposing a callable injection surface
