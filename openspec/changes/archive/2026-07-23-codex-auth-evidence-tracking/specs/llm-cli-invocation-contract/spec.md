## MODIFIED Requirements

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
