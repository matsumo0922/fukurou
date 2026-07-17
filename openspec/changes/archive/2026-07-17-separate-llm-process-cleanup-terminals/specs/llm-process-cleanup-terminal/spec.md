## ADDED Requirements

### Requirement: Started LLM processes receive bounded termination
Issue #189 orphan-process DoD: The runtime MUST attempt bounded proxy termination and MUST make PID 1 terminate every active provider/MCP job process group before acknowledging launcher cancellation. It MUST NOT report proven provider-tree exit from proxy exit alone.

#### Scenario: Fallback descendant discovery fails
- **WHEN** descendant discovery throws after the root LLM process starts
- **THEN** the runtime still attempts root TERM/KILL, returns the discovery failure, and records process exit as unconfirmed

#### Scenario: Launcher proxy requests cancellation with supervised jobs active
- **WHEN** timeout or cancellation half-closes a launcher proxy request while provider or MCP jobs remain in the PID 1 job table
- **THEN** PID 1 terminates and reaps all provider/MCP job process groups and dedicated AI-UID processes within the shared deadline before acknowledging cancellation and without terminating the application JVM

#### Scenario: Cancellation arrives around start-gate release
- **WHEN** cancellation arrives immediately before or after a provider/MCP child is allowed to exec
- **THEN** the parent-established job process group is signalable and no child runs outside the tracked group due to setup ordering

#### Scenario: Provider root exits before a background child
- **WHEN** a provider root exits while its process group still contains a background child
- **THEN** PID 1 terminates the remaining group and does not reply or clear the job until the group is empty

#### Scenario: Provider descendant escapes its original process group
- **WHEN** a process owned by the dedicated LLM or MCP UID creates a new process group or session outside every active job group
- **THEN** PID 1 detects the rogue AI-UID process, performs global AI cleanup, reaps adopted descendants, and acknowledges success only after the non-exempt dedicated UID inventory is empty

#### Scenario: Authenticated launcher proxy waits for cleanup acknowledgement
- **WHEN** an exact launcher proxy identified by authenticated PID and process start ticks remains alive on its stored response socket
- **THEN** PID 1 excludes only that process identity from the pre-ack AI-UID inventory while every provider, MCP, descendant, and stale or reused PID remains subject to cleanup

#### Scenario: Supervisor acknowledges cancellation
- **WHEN** the proxy receives the fixed cancellation acknowledgement after PID 1 clears the AI job table
- **THEN** the process runner records proven provider-tree exit only after its acknowledged launcher proxy process group also exits

#### Scenario: Supervisor acknowledgement is absent
- **WHEN** the proxy is force-killed or exits without the fixed post-cleanup acknowledgement
- **THEN** audit records process exit as unconfirmed and execution admission remains fail-closed until operator verification or container restart

#### Scenario: Timeout stress uses the candidate runtime image
- **WHEN** 100 timeout-shaped proxy abandonments run through the candidate image supervisor job table
- **THEN** every provider/MCP job group and dedicated AI-UID process exits within the bounded termination sequence and no orphan remains

#### Scenario: Cancellation stress uses the candidate runtime image
- **WHEN** 100 cancellation-shaped proxy abandonments run through the candidate image supervisor job table
- **THEN** every provider/MCP job group and dedicated AI-UID process exits within the bounded termination sequence and no orphan remains

#### Scenario: Required process-group facility is missing
- **WHEN** the candidate runtime image lacks `setsid`, process-group signaling, `/proc` inspection, or any mandatory stress case
- **THEN** production-like validation fails instead of skipping the orphan proof

### Requirement: Semantic process and cleanup terminals are independent
Issue #189 terminal-separation DoD: Every LLM invocation phase audit produced by `LlmInvocationAuditor` MUST independently report semantic commit, process exit, and cleanup terminal states from authoritative runtime facts.

#### Scenario: Decision commits before provider timeout
- **WHEN** the app-owned gateway commits a decision and the provider process subsequently times out
- **THEN** audit reports semantic commit as committed, process exit from the termination proof, and cleanup completion separately

#### Scenario: Gateway phase exits without submission
- **WHEN** a proposer or falsifier process exits without a successful repository submission
- **THEN** audit reports semantic commit as not committed without inferring success from provider output

#### Scenario: Repository completion races gateway shutdown
- **WHEN** gateway shutdown cannot prove whether an in-flight blocking repository transaction committed
- **THEN** audit reports semantic commit as unknown rather than not committed

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

#### Scenario: Deterministic runner phase uses the same event type
- **WHEN** `OneShotLlmRunAuditRecorder` records a phase with no provider invocation
- **THEN** it keeps the current deterministic payload without pretending semantic, process, or cleanup terminals exist

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
