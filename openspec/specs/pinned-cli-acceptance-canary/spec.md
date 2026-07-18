# pinned-cli-acceptance-canary Specification

## Purpose
固定した Claude Code / Codex CLI と production 実行経路の互換性を、production credential や取引権限から隔離した exact-image canary で安全に検証する。

## Requirements
### Requirement: Exact candidate image proves pinned provider compatibility
The qualification process MUST execute Claude Code 2.1.199 and Codex 0.142.5 from the exact candidate image through the production command renderer, process runner, and versioned output adapter before merge approval.

#### Scenario: Pinned phase matrix succeeds
- **WHEN** Claude `PRE_FILTER`, Claude `PROPOSER`, Codex `FALSIFIER`, and Claude `REFLECTION` each return a complete supported output envelope with the configured canary model and semantic probe marker
- **THEN** the canary records only a safe success result for each phase and the image may be considered provider-qualified

#### Scenario: Provider contract is incompatible
- **WHEN** authentication, process exit, output schema, configured model, supported observed model, semantic marker, timeout, or cleanup validation fails for any phase
- **THEN** the canary preserves that primary typed reason, reports cleanup independently as `COMPLETED` or `FAILED`, and the image is not provider-qualified

#### Scenario: Codex omits observed model identity
- **WHEN** pinned Codex returns its supported complete JSONL contract without a model field
- **THEN** the canary verifies configured `gpt-5.5` and the exact CLI/image pin, retains observed identity as unavailable, and does not infer a model from session files

### Requirement: No-tool and MCP phase policies are both exercised
The canary MUST render `PRE_FILTER` and `REFLECTION` with an empty canonical tool policy and no MCP server, and MUST render `PROPOSER` and `FALSIFIER` with their exact canonical tool policy and a data-free fixture MCP server.

#### Scenario: No-tool phase runs
- **WHEN** the canary executes `PRE_FILTER` or `REFLECTION`
- **THEN** the provider authenticates from the per-run copy, receives an explicit empty tool policy, and completes without an MCP server

#### Scenario: MCP phase resolves a canonical tool
- **WHEN** the canary executes `PROPOSER` or `FALSIFIER`
- **THEN** Claude Proposer invokes `submit_decision` and Codex Falsifier invokes auto-approved `submit_falsification` at least once with production-equivalent tool annotations, and the fixture records each phase and canonical tool name independently from the fixed response marker without receiving production DB or market data

#### Scenario: Tool policy or fixture call drifts
- **WHEN** enabled tools differ from the phase canonical policy, an unknown tool is requested, the required phase/tool call record is absent, or the response marker differs
- **THEN** the image is not provider-qualified

### Requirement: Canary credentials are isolated from production credentials
The acceptance harness MUST mount only a dedicated canary auth source as read-only persistent input, copy only required auth files into per-run tmpfs, and MUST NOT mount or expose the production auth source, database, vault, Docker socket, raw credential, or raw provider output.

#### Scenario: Dedicated auth source is valid and reusable
- **WHEN** all four phases run from supported canary credential files across the requested matrix repetitions
- **THEN** every phase authenticates from an independent per-run copy and the dedicated source remains reusable for the next phase

#### Scenario: Dedicated auth source is missing or expires
- **WHEN** a required credential is absent or remote refresh rotation makes the dedicated source unusable
- **THEN** qualification fails without affecting the production auth source and without printing credential path, content, digest, prompt, stdout, or stderr

#### Scenario: Container resources are inspected
- **WHEN** the acceptance container security contract is evaluated
- **THEN** no production auth, DB environment or mount, vault mount, Docker socket, production network, or trading mutation authority is present

### Requirement: Qualification repetition and image identity are explicit and bounded
The exact-image harness MUST support only a one-run operator smoke and a three-run merge qualification over the complete four-phase matrix. Merge qualification MUST accept an immutable repository digest, resolve it once, and execute foundation plus all acceptance repetitions in one harness invocation against that resolved image.

#### Scenario: Merge candidate is qualified
- **WHEN** a final immutable repository digest is proposed for merge
- **THEN** one harness invocation runs foundation once and every acceptance phase three consecutive times on the same resolved image within the matrix deadline

#### Scenario: One-run smoke is requested
- **WHEN** an operator invokes the harness with one repetition
- **THEN** every phase executes once with the same validation semantics and the result is not represented as three-run merge qualification

#### Scenario: Unsupported repetition is requested
- **WHEN** the harness is invoked with a repetition count other than one or three
- **THEN** it exits before mounting credentials or starting a provider process

#### Scenario: Mutable image reference is requested
- **WHEN** merge qualification receives a tag, bare image ID, unresolved digest, or a resolved identity different from the requested repository digest
- **THEN** it exits before foundation or provider execution and emits no qualification result

#### Scenario: Production qualification retains test overrides
- **WHEN** Docker or foundation harness override environment remains set without explicit selftest mode
- **THEN** qualification exits before image inspection, credential mount, foundation, or provider execution

### Requirement: Acceptance evidence composes with foundation evidence
Merge qualification MUST bind the offline foundation result and the real-provider acceptance result to the single immutable image digest resolved by the same harness invocation, while keeping their security responsibilities distinct.

#### Scenario: Both evidence sets succeed
- **WHEN** the foundation canary proves fixed launcher, process cleanup, MCP/DB/tool isolation and the acceptance harness proves real auth, output schema, and provider MCP resolution for the same digest
- **THEN** the harness emits one safe qualification record containing that digest and the PR may report the candidate image as merge-qualified

#### Scenario: Only one evidence set succeeds
- **WHEN** foundation or real-provider evidence is missing, failed, or refers to a different image digest
- **THEN** the PR MUST report qualification as incomplete

### Requirement: Implementation remains within the reviewed PR boundary
The code, test, script, and documentation implementation for this change MUST remain at or below 1,100 added/deleted lines, excluding the OpenSpec planning artifacts and recovered archive committed before implementation.

#### Scenario: Implementation reaches the hard stop
- **WHEN** the scoped implementation diff exceeds 1,100 added/deleted lines or cannot meet the limit without weakening a normative requirement
- **THEN** implementation stops and the remaining responsibility is moved to a separate change before review continues

### Requirement: Forward deploy requires one-run CLI acceptance before cutover
Every `FORWARD` deploy MUST include the existing `CLI_AUTH_PREFLIGHT_V1` operation in an exact signed required-hook/operation set and MUST run the installed, hash-verified acceptance harness once against the exact candidate repository digest before rollback-state capture, launch disable, or production compose cutover. `AUTHORIZED_ROLLBACK` MUST retain the previous required-hook set so an older known-good image remains recoverable without claiming new provider qualification.

#### Scenario: Forward candidate passes deploy acceptance
- **WHEN** a signed `FORWARD` deploy targets an exact candidate digest and both the pre-mutation `--cli-acceptance --runs 1` gate and later candidate hook admission succeed
- **THEN** the executor records `CLI_AUTH_PREFLIGHT_V1` as dispatched and may proceed to production compose cutover

#### Scenario: Canary auth or provider compatibility fails
- **WHEN** the dedicated `llm-canary-auth` volume is missing or unusable, or any auth, output, model, tool-call, timeout, process, or cleanup validation fails
- **THEN** the executor fails before creating a new rollback journal or changing production launch state, preserves only safe failure output, and does not use production LLM credentials

#### Scenario: Historical rollback is authorized
- **WHEN** a signed `AUTHORIZED_ROLLBACK` targets an older main-reachable image
- **THEN** the bundle requires the existing foundation hook but does not require `CLI_AUTH_PREFLIGHT_V1`, and the rollback remains available without representing the old image as newly provider-qualified

#### Scenario: Required hook support drifts
- **WHEN** the intent-specific signed hook set and `SMOKE_HOOK_V1` operations are not exactly equivalent, or candidate PID 1 probe, token allowlist, safe marker, installed harness invocation, or dispatch ledger omits or disagrees on `CLI_AUTH_PREFLIGHT_V1` for a `FORWARD` deploy
- **THEN** validation or candidate preflight fails before production compose cutover

#### Scenario: Acceptance exceeds the deploy deadline
- **WHEN** the one-run acceptance does not complete within its 720-second container or 750-second host pre-mutation budget
- **THEN** the harness is given a bounded cleanup interval, the executor fails without changing production launch state, and fresh 1,200-second forward plus 1,500-second recovery budgets remain available after acceptance succeeds

#### Scenario: Forward provider gate is unavailable during an incident
- **WHEN** a new `FORWARD` deploy cannot pass the provider gate during a provider outage or credential incident
- **THEN** the executor provides no forward bypass, while existing launch-disable controls and a signed `AUTHORIZED_ROLLBACK` remain available as risk-reducing operations

#### Scenario: Fresh install has no rollback target
- **WHEN** fresh install or disaster recovery has no older running image eligible for `AUTHORIZED_ROLLBACK`
- **THEN** provisioned canary credentials are a bootstrap prerequisite and the system does not claim a forward bypass or unavailable rollback path

### Requirement: Issue closure remains evidence-based
The final Issue #189 change MUST distinguish implemented deploy wiring from operator evidence and MUST NOT close Issue #189 until the three-run exact-digest qualification and one real forward-deploy acceptance result are recorded without raw provider output.

#### Scenario: Deploy hook is merged without operator evidence
- **WHEN** `CLI_AUTH_PREFLIGHT_V1` wiring is merged but exact-digest qualification or first forward-deploy evidence is absent
- **THEN** Issue #189 remains open with the corresponding evidence item marked incomplete

#### Scenario: Final evidence is complete
- **WHEN** one exact digest has passed the three-run qualification and a forward deploy has recorded both required hooks before cutover
- **THEN** Issue #189 may be closed with the safe digest-bound results
