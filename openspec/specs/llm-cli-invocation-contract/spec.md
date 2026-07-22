# LLM CLI Invocation Contract Specification

## Purpose

Claude と Codex の CLI invocation における認証、tool policy、出力 contract、failure classification、model attribution の要件を定義する。
## Requirements
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
Issue #189 typed-failure DoD: The runtime MUST classify authentication, rate/session limit, quota exhaustion, output contract, timeout, non-zero process exit, and cleanup failure into stable provider-neutral categories while retaining provider-specific safe detail separately. Issue #291: Codex invocations retain redacted stdout/stderr only when three conditions all hold: (1) the resolved category is `PROCESS_EXIT`, `PROCESS_TIMEOUT`, or `CLEANUP`; (2) the adapter itself reported no provider failure at all (`invocationResult.providerFailure == null`); and (3) the process's stderr does not contain any of the known Codex authentication-failure strings (`CODEX_STDERR_AUTH_FAILURES`). None of these conditions alone is a sufficient safety boundary: `primaryProviderFailure()` prioritizes a lifecycle category over an adapter-derived `UNKNOWN_PROVIDER_FAILURE` whenever the process also exited non-zero, timed out, or hit a cleanup failure, so a lifecycle category can coexist with output-text-derived adapter evidence that was never surfaced as the primary category (condition 2 closes this). Separately, `DefaultLlmOutputParser`'s own stderr authentication check only runs when no terminal event was parsed (`terminalCount == 0`); when stdout carries a complete, successful event stream, the adapter reports no failure at all even if stderr independently contains one of the known authentication-failure strings (condition 3 closes this). Every category resolved by interpreting Codex's own output text (`AUTHENTICATION`, `RATE_OR_SESSION_LIMIT`, `QUOTA_EXHAUSTED`, `OUTPUT_CONTRACT`, `UNKNOWN_PROVIDER_FAILURE`) continues to retain no raw output, whether or not it is the primary category, because Codex's first-match category resolution can let authentication evidence be superseded by an earlier-matched signal or fall through to `UNKNOWN_PROVIDER_FAILURE`, and `#282`'s confirmed production failure (`OUTPUT_CONTRACT`/`SCHEMA_DRIFT`) demonstrates this text-derived category set is not safe to expose without an independent authentication-evidence tracking mechanism (out of scope for this change; tracked as a follow-up). Claude's failure-path behavior is unchanged by this requirement.

#### Scenario: Claude reports a session limit
- **WHEN** Claude returns a supported session-limit or 429 payload
- **THEN** audit records `RATE_OR_SESSION_LIMIT` rather than a generic authentication suspicion

#### Scenario: Codex authentication fails
- **WHEN** Codex returns a supported authentication failure payload or exit contract
- **THEN** audit records `AUTHENTICATION` without persisting raw output, token, path, or prompt

#### Scenario: Codex output cannot be validated against the pinned schema
- **WHEN** Codex output cannot be validated against the pinned schema — whether from a genuine schema drift or from a terminal payload that also carries authentication-failure evidence which the parser's priority logic resolved to `OUTPUT_CONTRACT` instead of `AUTHENTICATION`
- **THEN** audit records `OUTPUT_CONTRACT` without persisting raw output, token, path, or prompt

#### Scenario: Codex reports a rate/session limit or quota category ahead of a later authentication signal
- **WHEN** Codex's output contains a `RATE_OR_SESSION_LIMIT` or `QUOTA_EXHAUSTED` match earlier in the stream than a later, otherwise-exact authentication failure signal, so the parser's first-match resolution assigns `RATE_OR_SESSION_LIMIT` or `QUOTA_EXHAUSTED` as the category
- **THEN** audit records that category without persisting raw output, token, path, or prompt

#### Scenario: Unknown provider failure occurs
- **WHEN** output cannot be mapped to a supported typed category
- **THEN** audit records `UNKNOWN_PROVIDER_FAILURE`, remains fail-closed, and retains only the category, provider code, and adapter schema version as redacted safe diagnostics — not raw stdout or stderr

#### Scenario: Codex's output text does not map to a known compatibility failure
- **WHEN** Codex emits an error or turn-failure message that the parser cannot map to a known compatibility failure category, including when an earlier unrecognized message supersedes a later, otherwise-exact authentication failure signal under first-match resolution
- **THEN** audit records `UNKNOWN_PROVIDER_FAILURE` without persisting raw output, token, path, or prompt, because this category is derived from Codex's own output text and is not a pure process-lifecycle fact

#### Scenario: Codex fails with a pure process-lifecycle category, no adapter failure, and no known authentication signature in stderr
- **WHEN** Codex's invocation resolves to `PROCESS_TIMEOUT` (from `processResult.status`), `PROCESS_EXIT` (from a non-zero `exitCode`), or `CLEANUP` (from a raised cleanup exception), the adapter reported no provider failure at all for this invocation, and stderr does not contain any of the known Codex authentication-failure strings
- **THEN** audit records the failure category and retains the completed process's stdout and stderr after `redactor.redactAndTruncate`, without a `rawOutputOmitted` marker

#### Scenario: A lifecycle category coexists with an adapter-derived failure from Codex's output text
- **WHEN** Codex's invocation resolves to `PROCESS_TIMEOUT`, `PROCESS_EXIT`, or `CLEANUP` as the primary category, but the adapter also reported its own provider failure (for example an `UNKNOWN_PROVIDER_FAILURE` derived from an unrecognized error message, which may itself have suppressed a later authentication signal under first-match resolution)
- **THEN** audit does not record raw stdout or stderr for this invocation, because the presence of any adapter-derived failure means Codex's output text was interpreted and may carry evidence that must not be exposed

#### Scenario: A complete successful event stream coexists with a known authentication signature in stderr
- **WHEN** Codex's stdout contains a complete, successful event stream (`thread.started`/`item.completed`/`turn.completed`, exactly one terminal), so the adapter reports no provider failure at all, but the process's stderr independently contains one of the known Codex authentication-failure strings, and the process also resolves to `PROCESS_TIMEOUT`, `PROCESS_EXIT`, or `CLEANUP`
- **THEN** audit does not record raw stdout or stderr for this invocation, because `DefaultLlmOutputParser`'s own authentication check only runs when no terminal event was parsed and therefore would not have caught this case

### Requirement: Model attribution does not depend on retained session files
Issue #189 session-retention and cost-attribution prerequisites: Every invocation MUST persist configured model identity as an effective model name or an explicit `CLI_DEFAULT` source without inventing a name, and SHALL store provider-observed model identity separately when supported output reports it. Runtime cost and audit attribution MUST NOT require scanning the CLI session directory.

#### Scenario: Provider output reports a model
- **WHEN** the supported output schema contains model identity
- **THEN** audit stores configured and observed model identities and marks the observation source

#### Scenario: Provider output omits model identity
- **WHEN** model identity is absent from otherwise valid provider output
- **THEN** audit retains the effective configured name or explicit `CLI_DEFAULT` source, marks observed identity unavailable, and does not scan session files

### Requirement: OneShotLlmRunner's Codex MCP invocation forwards the decision run invocation id
Issue #287 proposer-restoration invariant: The `LlmMcpServerConfig` that `OneShotLlmRunner` builds for a decision run's Codex-provider invocation MUST forward `FUKUROU_INVOCATION_ID` to the MCP subprocess via the rendered config's `env_vars`, so the MCP launcher's required-environment check does not fail closed. This requirement scopes to `OneShotLlmRunner.mcpServerConfig()` only; other Codex MCP config builders (e.g. CLI acceptance canary, MCP isolation canary) are out of scope for this change.

#### Scenario: Production MCP config is rendered for a Codex invocation
- **WHEN** `OneShotLlmRunner` builds an `LlmMcpServerConfig` for a decision run's Codex-provider invocation
- **THEN** the rendered Codex config TOML's `env_vars` array contains `FUKUROU_INVOCATION_ID`, and the decision run's invocation id is present in the invocation's process environment

