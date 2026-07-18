## ADDED Requirements

### Requirement: Exact candidate image proves pinned provider compatibility
The release process MUST execute Claude Code 2.1.199 and Codex 0.142.5 from the exact candidate image through the production command renderer, fixed launcher, process runner, and versioned output adapter before production activation.

#### Scenario: Pinned phase matrix succeeds
- **WHEN** Claude `PRE_FILTER`, Claude `PROPOSER`, Codex `FALSIFIER`, and Claude `REFLECTION` each return a complete supported output envelope with the configured canary model and semantic probe marker
- **THEN** the canary records only a safe success result for each phase and permits the deploy gate to continue

#### Scenario: Provider contract is incompatible
- **WHEN** authentication, process exit, output schema, configured or observed model, semantic marker, timeout, or cleanup validation fails for any phase
- **THEN** the canary fails closed with a typed safe reason and production activation does not begin

### Requirement: No-tool and MCP phase policies are both exercised
The canary MUST render `PRE_FILTER` and `REFLECTION` with an empty canonical tool policy and no MCP server, and MUST render `PROPOSER` and `FALSIFIER` with their exact canonical tool policy and a data-free fixture MCP server.

#### Scenario: No-tool phase runs
- **WHEN** the canary executes `PRE_FILTER` or `REFLECTION`
- **THEN** the provider authenticates from the per-run copy, receives an explicit empty tool policy, and completes without resolving or invoking an MCP tool

#### Scenario: MCP phase resolves a canonical tool
- **WHEN** the canary executes `PROPOSER` or `FALSIFIER`
- **THEN** the provider resolves the canonical fixture tool, invokes it exactly once, and returns the run-specific nonce without receiving production DB or market data

#### Scenario: Tool policy or fixture call drifts
- **WHEN** enabled tools differ from the phase canonical policy, an unknown tool is requested, the required probe call is absent, or the returned nonce differs
- **THEN** the invocation fails before the deploy gate can authorize production activation

### Requirement: Provider credentials remain isolated from deploy artifacts
The deploy canary MUST mount only the existing provider auth source as read-only persistent input, copy only required auth files into per-run tmpfs, and MUST NOT mount or expose the production database, vault, Docker socket, raw credential, or raw provider output.

#### Scenario: Auth source is valid
- **WHEN** a phase starts with a readable supported provider credential file
- **THEN** the renderer copies it into the private per-run home, the provider authenticates, and the source content remains unchanged after cleanup

#### Scenario: Auth source is missing or mutable
- **WHEN** a required credential is absent, the auth mount is not read-only, or the source changes during the run
- **THEN** the canary fails closed without printing the credential path, content, digest, prompt, stdout, or stderr

#### Scenario: Production resources are inspected
- **WHEN** the acceptance container security contract is evaluated
- **THEN** no production DB environment or mount, vault mount, Docker socket, production network, or trading mutation authority is present

### Requirement: Qualification and deployment use bounded repetition
The same acceptance harness MUST support only a three-run merge qualification and a one-run production deploy gate over the complete four-phase matrix.

#### Scenario: Merge candidate is qualified
- **WHEN** a final exact image is proposed for merge
- **THEN** every phase succeeds three consecutive times on that image before the PR is considered qualified

#### Scenario: Production deploy is evaluated
- **WHEN** a signed deploy bundle reaches the candidate preflight stage
- **THEN** every phase succeeds once after foundation preflight and before any production mutation

#### Scenario: Unsupported repetition is requested
- **WHEN** the harness is invoked with a repetition count other than one or three
- **THEN** it exits before mounting credentials or starting a provider process

### Requirement: CLI auth preflight is a signed required deploy hook
The signed deploy bundle, candidate capability catalog, installed executor contract, and dispatch receipt MUST require `CLI_AUTH_PREFLIGHT_V1` after `FOUNDATION_PREFLIGHT_V1` and before production compose mutation.

#### Scenario: Both required hooks succeed
- **WHEN** foundation isolation and the one-run CLI acceptance matrix both succeed for the signed candidate SHA and digest
- **THEN** the executor may continue to the production deployment operations and records both hook dispatches

#### Scenario: CLI auth hook fails
- **WHEN** the candidate rejects the signed hook or any acceptance phase fails
- **THEN** the executor preserves the current runtime, does not activate the candidate, and records no successful CLI auth dispatch

#### Scenario: Bundle omits the CLI auth hook
- **WHEN** a deploy bundle or catalog does not bind `CLI_AUTH_PREFLIGHT_V1` with the supported candidate hook profile and harness hash
- **THEN** validation fails before the candidate canary or production mutation begins
