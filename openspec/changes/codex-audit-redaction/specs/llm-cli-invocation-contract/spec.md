## MODIFIED Requirements

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
