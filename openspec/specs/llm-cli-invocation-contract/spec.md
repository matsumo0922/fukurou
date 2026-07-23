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

Issue #189 typed-failure DoD: The runtime MUST classify authentication, rate/session limit, quota exhaustion, output contract, timeout, non-zero process exit, and cleanup failure into stable provider-neutral categories while retaining provider-specific safe detail separately.

Issue #291: Codex invocations retain redacted stdout/stderr when the resolved category is `PROCESS_EXIT`, `PROCESS_TIMEOUT`, or `CLEANUP` (a purely process-lifecycle fact) and the adapter itself reported no provider failure at all (`invocationResult.providerFailure == null`, i.e. `cliErrorReported == false`). `primaryProviderFailure()` prioritizes this lifecycle category over an adapter-derived `UNKNOWN_PROVIDER_FAILURE` whenever the process also exited non-zero, timed out, or hit a cleanup failure, so a lifecycle category can coexist with output-text-derived adapter evidence that was never surfaced as the primary category; the `cliErrorReported == false` condition closes this gap.

Issue #295: Codex invocations additionally retain redacted stdout/stderr when the resolved category is `OUTPUT_CONTRACT`, `RATE_OR_SESSION_LIMIT`, `QUOTA_EXHAUSTED`, or `UNKNOWN_PROVIDER_FAILURE` (categories derived by interpreting Codex's own output text), provided that no known authentication-evidence text was independently observed anywhere in the invocation's output. This is a known-text match, not a proof that no authentication evidence of any kind is present — see the incompleteness note below. This independent tracking (`authEvidenceObserved`) is computed by `DefaultLlmOutputParser.parseCodex()` separately from its first-match primary-category resolution: it is set whenever a `turn.failed` or `error` event's message maps to the `AUTHENTICATION` compatibility category (regardless of whether that category was superseded by an earlier first-match result), or whenever the raw stdout or stderr text contains one of the known Codex authentication-evidence strings (`CODEX_KNOWN_AUTH_EVIDENCE_TEXTS`, the union of `CODEX_STDERR_AUTH_FAILURES` and the two `AUTHENTICATION`-mapped compatibility strings "Not logged in"/"Invalid authentication credentials"), checked unconditionally against both stdout and stderr rather than only against stderr when no terminal event was parsed. When `authEvidenceObserved` is true, raw output is withheld regardless of which category — lifecycle or output-interpreted — the invocation resolved to. `AUTHENTICATION` itself continues to retain no raw output under any condition, because it is Codex's own most direct signal that credentials are in play. `authEvidenceObserved` has no default value at any construction site, so every code path that produces a `ParsedLlmOutput` or `LlmInvocationResult` must set it explicitly; there is no implicit "unscanned" state that resolves to safe.

This known-text matching is necessarily incomplete: content that does not match `CODEX_KNOWN_AUTH_EVIDENCE_TEXTS` (an unrotated or newly issued secret, an OAuth device code, a file path) is not detected, and `OUTPUT_CONTRACT` (when caused by non-JSON raw output) and `UNKNOWN_PROVIDER_FAILURE` (when caused by an unrecognized message) are the two categories most likely to carry such undetected content, since by definition their raw text did not match any known structure. This residual risk is accepted for all four newly-safe categories as a deliberate, user-confirmed scope decision.

#### Scenario: Claude reports a session limit

- **WHEN** Claude returns a supported session-limit or 429 payload
- **THEN** audit records `RATE_OR_SESSION_LIMIT` rather than a generic authentication suspicion

#### Scenario: Codex authentication fails

- **WHEN** Codex returns a supported authentication failure payload or exit contract
- **THEN** audit records `AUTHENTICATION` without persisting raw output, token, path, or prompt

#### Scenario: Codex output cannot be validated against the pinned schema and no known authentication-evidence text is observed

- **WHEN** Codex's stdout cannot be validated against the pinned schema (genuine schema drift, including a launch failure that produces empty or non-JSON stdout), and neither stdout nor stderr contains a known Codex authentication-evidence string, and no parsed `turn.failed`/`error` event message mapped to `AUTHENTICATION`
- **THEN** audit records `OUTPUT_CONTRACT` and retains the completed process's stdout and stderr after `redactor.redactAndTruncate`, without a `rawOutputOmitted` marker

#### Scenario: Codex output cannot be validated against the pinned schema

- **WHEN** Codex's stdout cannot be validated against the pinned schema — whether from schema drift where stdout or stderr independently contains a known Codex authentication-evidence string, or from a terminal payload that also carries authentication-failure evidence which the parser's first-match priority logic resolved to a different category than `AUTHENTICATION`
- **THEN** audit records `OUTPUT_CONTRACT` without persisting raw output, token, path, or prompt

#### Scenario: Codex reports a rate/session limit or quota category with no known authentication-evidence text observed

- **WHEN** Codex's output contains a `RATE_OR_SESSION_LIMIT` or `QUOTA_EXHAUSTED` match, and neither stdout nor stderr contains a known Codex authentication-evidence string, and no parsed event message mapped to `AUTHENTICATION`
- **THEN** audit records that category and retains the completed process's stdout and stderr after `redactor.redactAndTruncate`, without a `rawOutputOmitted` marker

#### Scenario: Codex reports a rate/session limit or quota category ahead of a later authentication signal

- **WHEN** Codex's output contains a `RATE_OR_SESSION_LIMIT` or `QUOTA_EXHAUSTED` match earlier in the stream than a later, otherwise-exact authentication failure signal, so the parser's first-match resolution assigns `RATE_OR_SESSION_LIMIT` or `QUOTA_EXHAUSTED` as the category, but the later authentication signal is independently tracked
- **THEN** audit records that category without persisting raw output, token, path, or prompt, because the independently tracked authentication evidence withholds raw output regardless of the resolved category

#### Scenario: Unknown provider failure occurs

- **WHEN** output cannot be mapped to a supported typed category, and neither stdout nor stderr contains a known Codex authentication-evidence string, and no parsed event message mapped to `AUTHENTICATION`
- **THEN** audit records `UNKNOWN_PROVIDER_FAILURE`, the invocation remains fail-closed for trading-decision purposes (cannot authorize `ENTER` or `ADD_LONG`), and — because no known authentication-evidence text was observed — retains the completed process's stdout and stderr after `redactor.redactAndTruncate`

#### Scenario: Codex's output text does not map to a known compatibility failure

- **WHEN** Codex emits an error or turn-failure message that the parser cannot map to a known compatibility failure category, including when an earlier unrecognized message supersedes a later, otherwise-exact authentication failure signal under first-match resolution, and that later signal is independently tracked as authentication evidence
- **THEN** audit records `UNKNOWN_PROVIDER_FAILURE` without persisting raw output, token, path, or prompt

#### Scenario: Codex fails with a pure process-lifecycle category, no adapter failure, and no known authentication signature in stderr

- **WHEN** Codex's invocation resolves to `PROCESS_TIMEOUT` (from `processResult.status`), `PROCESS_EXIT` (from a non-zero `exitCode`), or `CLEANUP` (from a raised cleanup exception), the adapter reported no provider failure at all for this invocation, and no known authentication-evidence text was independently observed in stdout or stderr
- **THEN** audit records the failure category and retains the completed process's stdout and stderr after `redactor.redactAndTruncate`, without a `rawOutputOmitted` marker

#### Scenario: A lifecycle category coexists with an adapter-derived failure from Codex's output text

- **WHEN** Codex's invocation resolves to `PROCESS_TIMEOUT`, `PROCESS_EXIT`, or `CLEANUP` as the primary category, but the adapter also reported its own provider failure (for example an `UNKNOWN_PROVIDER_FAILURE` derived from an unrecognized error message, which may itself have suppressed a later authentication signal under first-match resolution)
- **THEN** audit does not record raw stdout or stderr for this invocation, because the presence of any adapter-derived failure means Codex's output text was interpreted and may carry evidence that must not be exposed, independent of whether authentication evidence was separately tracked

#### Scenario: A complete successful event stream coexists with a known authentication signature in stderr

- **WHEN** Codex's stdout contains a complete, successful event stream (`thread.started`/`item.completed`/`turn.completed`, exactly one terminal), so the adapter reports no provider failure at all, but the process's stderr independently contains one of the known Codex authentication-evidence strings, and the process also resolves to `PROCESS_TIMEOUT`, `PROCESS_EXIT`, or `CLEANUP`
- **THEN** audit does not record raw stdout or stderr for this invocation, because the independent authentication-evidence tracking scans stderr unconditionally (not only when no terminal event was parsed) and withholds raw output whenever it finds a match

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

