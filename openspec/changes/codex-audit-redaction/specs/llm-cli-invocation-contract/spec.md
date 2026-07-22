## MODIFIED Requirements

### Requirement: Provider failures have stable typed categories
Issue #189 typed-failure DoD: The runtime MUST classify authentication, rate/session limit, quota exhaustion, output contract, timeout, non-zero process exit, and cleanup failure into stable provider-neutral categories while retaining provider-specific safe detail separately. Issue #291: Codex invocations retain redacted stdout/stderr for every non-`AUTHENTICATION` failure category, matching the diagnosability already available for Claude's successful invocations. Claude's failure-path behavior is unchanged by this requirement.

#### Scenario: Claude reports a session limit
- **WHEN** Claude returns a supported session-limit or 429 payload
- **THEN** audit records `RATE_OR_SESSION_LIMIT` rather than a generic authentication suspicion

#### Scenario: Codex authentication fails
- **WHEN** Codex returns a supported authentication failure payload or exit contract
- **THEN** audit records `AUTHENTICATION` without persisting raw output, token, path, or prompt

#### Scenario: Codex fails with a non-authentication category
- **WHEN** Codex returns a `RATE_OR_SESSION_LIMIT`, `QUOTA_EXHAUSTED`, `OUTPUT_CONTRACT`, `PROCESS_TIMEOUT`, `PROCESS_EXIT`, or `CLEANUP` category, or the failure cannot be mapped to a supported typed category
- **THEN** audit records the failure category and retains the completed process's stdout and stderr after `redactor.redactAndTruncate`, without a `rawOutputOmitted` marker

#### Scenario: Unknown provider failure occurs
- **WHEN** output cannot be mapped to a supported typed category
- **THEN** audit records `UNKNOWN_PROVIDER_FAILURE`, remains fail-closed, and retains only redacted safe diagnostics

## ADDED Requirements

### Requirement: Codex successful invocations record redacted process output
Issue #291: A Codex invocation that completes without a classified provider failure MUST record `redactor.redactAndTruncate(stdout)` and `redactor.redactAndTruncate(stderr)` in the audit payload, matching the diagnosability Claude invocations already have. The payload MUST NOT contain a `rawOutputOmitted` marker for a completed Codex process.

#### Scenario: Codex completes successfully
- **WHEN** a Codex invocation completes with no provider failure and a captured process result
- **THEN** audit records `stdout` and `stderr` fields containing the redacted and truncated process output

#### Scenario: Secret-shaped values are masked in Codex output
- **WHEN** a Codex process's stdout or stderr contains a value matching a known secret (environment-sourced credential or auth-file token)
- **THEN** the recorded audit payload replaces that value with the redaction placeholder and does not contain the raw secret value
