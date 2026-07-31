## MODIFIED Requirements

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
- **THEN** audit records process exit as unconfirmed and execution admission remains fail-closed until the durable reservation for that invocation is observed terminal after the required quiet period, or until operator verification or container restart

#### Scenario: Timeout stress uses the candidate runtime image
- **WHEN** 100 timeout-shaped proxy abandonments run through the candidate image supervisor job table
- **THEN** every provider/MCP job group and dedicated AI-UID process exits within the bounded termination sequence and no orphan remains

#### Scenario: Cancellation stress uses the candidate runtime image
- **WHEN** 100 cancellation-shaped proxy abandonments run through the candidate image supervisor job table
- **THEN** every provider/MCP job group and dedicated AI-UID process exits within the bounded termination sequence and no orphan remains

#### Scenario: Required process-group facility is missing
- **WHEN** the candidate runtime image lacks `setsid`, process-group signaling, `/proc` inspection, or any mandatory stress case
- **THEN** production-like validation fails instead of skipping the orphan proof
