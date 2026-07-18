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

### Requirement: Issue closure remains evidence-based
The PR MUST distinguish completed Issue #189 DoD evidence from the deploy required hook that remains outside this change and MUST NOT close Issue #189 while that hook is absent.

#### Scenario: Acceptance harness is merged without deploy wiring
- **WHEN** this change is otherwise complete and merge-qualified
- **THEN** Issue #189 remains open with the deploy-time smoke DoD marked incomplete
