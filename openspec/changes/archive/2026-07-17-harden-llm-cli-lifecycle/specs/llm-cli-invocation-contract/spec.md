## ADDED Requirements

### Requirement: Pre-filter activation remains code-gated
Issue #189 non-regression invariant: The daemon MUST evaluate a code-owned release barrier before starting a pre-filter process, regardless of runtime configuration. The barrier MUST remain closed throughout Issue #189 and SHALL be opened only by the separate Issue #154 activation change.

#### Scenario: Runtime configuration enables pre-filter while barrier is closed
- **WHEN** `daemon.preFilterEnabled` is true and a heartbeat trigger is eligible for pre-filtering
- **THEN** the daemon starts no pre-filter child and continues with the full decision path

#### Scenario: Deployment candidate unexpectedly opens the barrier
- **WHEN** a deployment candidate reports the pre-filter release barrier as open before Issue #154 activation
- **THEN** deployment preflight fails before the candidate runtime is started or activated

### Requirement: No-tool Claude invocations preserve authentication without tools
Issue #189 auth-smoke prerequisite: Claude invocations without an MCP server MUST use the copied per-run authentication state, strict empty MCP configuration, and an explicit empty tool policy without passing an option that disables the supported authentication source.

#### Scenario: Pre-filter invokes Claude without MCP
- **WHEN** the daemon starts a no-MCP pre-filter with valid persisted Claude authentication
- **THEN** the rendered command authenticates from the per-run copy, exposes no tool, and does not include `--bare`

#### Scenario: No-MCP authentication is missing
- **WHEN** a no-MCP Claude invocation has no usable authentication source
- **THEN** the invocation fails with a typed authentication failure and does not silently retry through a broader environment

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
- **THEN** the fixed supervisor accepts exactly that phase policy without accepting an arbitrary tool set

### Requirement: Pinned CLI output is parsed as a versioned contract
Issue #189 deploy-smoke prerequisite: Claude and Codex output parsers MUST validate the pinned CLI event/result schema, preserve semantic response and token usage separately, and return a typed schema failure for an incompatible or incomplete terminal payload.

#### Scenario: Pinned Codex emits a complete JSON event stream
- **WHEN** Codex emits the supported thread, message, and turn-completed events
- **THEN** the parser returns the semantic response, configured or observed model identity, and token usage without persisting raw output

#### Scenario: CLI output schema drifts
- **WHEN** a pinned-provider smoke or runtime invocation omits a required terminal field or emits an unsupported schema
- **THEN** the invocation records a typed output-contract failure, cannot authorize `ENTER` or `ADD_LONG`, and does not invalidate an already persisted bounded `EXIT`, `REDUCE`, or `ADJUST_PROTECTION` decision

### Requirement: Provider failures have stable typed categories
Issue #189 typed-failure DoD: The runtime MUST classify authentication, rate/session limit, quota exhaustion, output contract, timeout, non-zero process exit, and cleanup failure into stable provider-neutral categories while retaining provider-specific safe detail separately.

#### Scenario: Claude reports a session limit
- **WHEN** Claude returns a supported session-limit or 429 payload
- **THEN** audit records `RATE_OR_SESSION_LIMIT` rather than a generic authentication suspicion

#### Scenario: Codex authentication fails
- **WHEN** Codex returns a supported authentication failure payload or exit contract
- **THEN** audit records `AUTHENTICATION` without persisting raw output, token, path, or prompt

#### Scenario: Unknown provider failure occurs
- **WHEN** output cannot be mapped to a supported typed category
- **THEN** audit records `UNKNOWN_PROVIDER_FAILURE`, remains fail-closed, and retains only redacted safe diagnostics

### Requirement: Model attribution does not depend on retained session files
Issue #189 session-retention and cost-attribution prerequisites: Every invocation MUST persist configured model identity as an effective model name or an explicit `CLI_DEFAULT` source without inventing a name, and SHALL store provider-observed model identity separately when supported output reports it. Runtime cost and audit attribution MUST NOT require scanning the CLI session directory.

#### Scenario: Provider output reports a model
- **WHEN** the supported output schema contains model identity
- **THEN** audit stores configured and observed model identities and marks the observation source

#### Scenario: Provider output omits model identity
- **WHEN** model identity is absent from otherwise valid provider output
- **THEN** audit retains the effective configured name or explicit `CLI_DEFAULT` source, marks observed identity unavailable, and does not scan session files
