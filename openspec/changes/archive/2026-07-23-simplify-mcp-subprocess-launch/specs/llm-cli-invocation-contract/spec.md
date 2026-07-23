## ADDED Requirements

### Requirement: MCP subprocess launches from argv manifest id, literal env, and manifest socket path without privileged descriptor passing
Issue #288 launch-simplification invariant: The production MCP subprocess MUST be launched with only an argv manifest id, a literal environment DB password, and ordinary owner-readable file reads — without inherited privileged file descriptors, a setuid launcher, or a runtime supervisor. The MCP subprocess reads its manifest from the manifest directory by the argv id, reads the DB password from its own process environment, and connects to the submission gateway using the socket path recorded in that manifest. The DB password reaches the MCP subprocess only, and MUST NOT appear in the LLM CLI body process environment. This holds for both provider paths (Codex and Claude), because the production proposer default provider is not fixed to Codex. The manifest validation performed at bootstrap is preserved unchanged (its simplification is out of scope). The runtime entrypoint runs the server directly under an explicit non-root service user; launch quiescing and timeout termination proof no longer depend on an external supervisor. This requirement scopes to the production decision-run launch path built by `OneShotLlmRunner` and the `:mcp` bootstrap; the CLI acceptance canary fixture is out of scope.

#### Scenario: MCP subprocess bootstraps from the argv manifest id
- **WHEN** the MCP subprocess is started with a manifest id as its argument and a manifest directory in its environment
- **THEN** it reads `<manifest-directory>/<manifest-id>.json`, applies the same manifest validation as before, and starts without requiring any inherited file descriptor for the manifest, password, or submission gateway

#### Scenario: DB password is delivered as literal MCP environment only, for either provider
- **WHEN** `OneShotLlmRunner` builds the MCP config for a decision-run invocation, whether the provider is Codex or Claude
- **THEN** the rendered config declares the DB password as a literal value scoped to the MCP server (Codex `[mcp_servers.<name>.env]` table or Claude MCP config `env` object), the MCP subprocess reads the password from its process environment, and the DB password is absent from the LLM CLI body process environment

#### Scenario: Submission gateway is reached by the manifest socket path
- **WHEN** the MCP subprocess submits a decision or falsification
- **THEN** it connects to the app-owned submission gateway using the socket path recorded in its manifest, and the submission is persisted through the gateway rather than by a direct database write

#### Scenario: Runtime entrypoint launches the server as a non-root service user without a setuid launcher or supervisor
- **WHEN** the production container starts
- **THEN** the entrypoint runs the Ktor server directly under an explicit non-root service user, no setuid launcher or runtime supervisor mediates MCP or LLM CLI process creation, and the LLM CLI, MCP subprocess, and application run under a single service user

#### Scenario: New launches are gated inside reservation admission when maintenance is active
- **WHEN** the durable launch-maintenance flag indicates maintenance is active while any launch path (scheduler or manual) attempts a launch reservation
- **THEN** the reservation admission transaction reads the maintenance row within the same lock boundary that admits the reservation, rejects the reservation with a maintenance skip reason, and starts no decision run — so both the scheduler and manual paths are gated without relying on an external supervisor to refuse the process spawn

#### Scenario: Timeout termination is proven by the application's own process group
- **WHEN** a provider invocation exceeds its timeout or is cancelled
- **THEN** the application terminates the invocation's Linux process group and proves exit by its own process-group and `/proc` verification, without requiring an external supervisor acknowledgement

## MODIFIED Requirements

### Requirement: Tool policy is explicit and phase-bounded
Issue #189 sandbox/tool-inventory DoD: Every LLM invocation request MUST carry an explicit tool policy. An empty policy MUST expose no tools, and a non-empty policy MUST expose exactly the required and enabled tools for that phase; no default-all fallback is allowed.

#### Scenario: Empty allowlist is rendered
- **WHEN** an invocation declares an empty tool policy
- **THEN** rendering either produces a provider-supported no-tool command or fails before process start

#### Scenario: Required tool is absent from enabled tools
- **WHEN** a phase requires a tool that is not enabled by its policy
- **THEN** rendering fails before credentials, MCP runtime, or provider process are exposed

#### Scenario: Risk-reduction policy reaches the fixed launcher
- **WHEN** Claude runs the canonical `RISK_REDUCTION_ONLY` phase after standard material capture fails
- **THEN** the MCP subprocess bootstrap accepts exactly that phase's canonical tool allowlist from the manifest without accepting an arbitrary tool set
