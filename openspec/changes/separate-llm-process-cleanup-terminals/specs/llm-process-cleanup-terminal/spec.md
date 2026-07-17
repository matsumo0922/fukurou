## ADDED Requirements

### Requirement: Started LLM processes receive bounded termination
Issue #189 orphan-process DoD: The runtime MUST attempt bounded root-process termination on timeout and cancellation even when fallback descendant discovery fails, and MUST NOT report proven process-tree exit without evidence.

#### Scenario: Fallback descendant discovery fails
- **WHEN** descendant discovery throws after the root LLM process starts
- **THEN** the runtime still attempts root TERM/KILL, returns the discovery failure, and records process exit as unconfirmed

#### Scenario: Timeout stress uses the production process-group path
- **WHEN** 100 LLM process trees are independently timed out through the Linux process-group runner
- **THEN** every root and descendant exits within the bounded termination sequence and no orphan remains

#### Scenario: Cancellation stress uses the production process-group path
- **WHEN** 100 independently started LLM process trees are cancelled through the Linux process-group runner
- **THEN** every root and descendant exits within the bounded termination sequence and no orphan remains

### Requirement: Semantic process and cleanup terminals are independent
Issue #189 terminal-separation DoD: Every runner phase audit MUST independently report semantic commit, process exit, and cleanup terminal states from authoritative runtime facts.

#### Scenario: Decision commits before provider timeout
- **WHEN** the app-owned gateway commits a decision and the provider process subsequently times out
- **THEN** audit reports semantic commit as committed, process exit from the termination proof, and cleanup completion separately

#### Scenario: Gateway phase exits without submission
- **WHEN** a proposer or falsifier process exits without a successful repository submission
- **THEN** audit reports semantic commit as not committed without inferring success from provider output

#### Scenario: Phase has no semantic submission gateway
- **WHEN** a pre-filter, reflection, or evaluation-report phase completes
- **THEN** audit reports semantic commit as not applicable

#### Scenario: Cleanup fails after process exit
- **WHEN** the process exit is proven but process artifact, gateway, or manifest cleanup fails
- **THEN** audit preserves the proven process exit, reports cleanup failed, and activates the existing cleanup quarantine

### Requirement: Existing audit consumers remain compatible
The terminal projection MUST be additive. Existing process status, exit code, provider failure, usage, and cleanup-failure fields SHALL retain their current meaning.

#### Scenario: Existing runner phase consumer reads a new event
- **WHEN** a `RUNNER_PHASE_COMPLETED` event contains the terminal projection
- **THEN** the legacy fields remain present and evaluation and operations projections continue to parse the event

#### Scenario: Risk-reducing decision survives later infrastructure failure
- **WHEN** an accepted bounded risk-reducing decision commits before a provider or cleanup failure
- **THEN** the semantic commit remains recorded and the later infrastructure failure does not erase or reinterpret the decision

### Requirement: Per-run provider artifacts have a bounded retention lifecycle
Issue #189 session-retention DoD: Provider auth copies, configuration, and session artifacts MUST be deleted after every invocation path or retained only with an explicit cleanup quarantine until operator remediation or container restart destroys the tmpfs.

#### Scenario: Invocation completes without cleanup failure
- **WHEN** an invocation ends normally, with non-zero exit, timeout, cancellation, or pre-process failure
- **THEN** all generated per-run homes and manifests are deleted through non-cancellable cleanup

#### Scenario: Invocation cleanup cannot delete an artifact
- **WHEN** fixed cleanup cannot remove a per-run artifact
- **THEN** the artifact and quarantine marker remain together in tmpfs and later LLM invocations are rejected until remediation or container restart

#### Scenario: Container restarts with quarantined artifacts
- **WHEN** the application container restarts after operator inspection
- **THEN** tmpfs destroys both quarantined per-run artifacts and the marker without touching persistent authentication source
