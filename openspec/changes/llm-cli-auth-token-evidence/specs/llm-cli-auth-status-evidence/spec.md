## ADDED Requirements

### Requirement: Codex credential lifecycle failures are tracked independently of raw output retention

**Trace:** Issue #305 受け入れ条件「Codex の token が失効している状態（または失効 evidence が直近 run に存在する状態）で、`/ops/llm-auth` が `logged_in` 以外の状態を返す」

The Codex output parser SHALL track, independently of both its first-match primary category resolution and its `authEvidenceObserved` tracking, whether the invocation reported a credential lifecycle failure. The tracked text set SHALL consist of texts observed in a real production credential expiry (`refresh_token_reused`, `token_expired`, `Failed to refresh token`) and SHALL NOT include bare HTTP status text such as `401 Unauthorized`, because a non-authentication 401 from another source in the same output would otherwise be misread as credential expiry.

The signal SHALL require a failure context in addition to the text match: the invocation SHALL have resolved to a provider failure, or SHALL have produced no parseable terminal event. An invocation that completed with exactly one successful terminal event and no provider failure SHALL NOT set the signal even when its response text mentions a tracked string, so that a valid-token invocation discussing a past incident is not reported as a credential failure.

This signal SHALL NOT participate in the raw stdout/stderr retention decision defined by the provider-failure-category requirements. Adding a text to the credential lifecycle set SHALL NOT change which invocations retain redacted raw output, so that the diagnostic evidence that allows a human to identify an unknown failure mode remains available.

The LLM invocation auditor SHALL record this signal in the `RUNNER_PHASE_COMPLETED` audit payload as an allowlisted non-secret marker, present only when the signal is true.

This is detection of known failure shapes, not a proof of credential validity. A CLI log wording change SHALL be treated as a detection gap, not as evidence that credentials are valid.

#### Scenario: Codex stderr reports a reused refresh token

- **WHEN** a Codex invocation's stderr contains `refresh_token_reused` while its stdout carries no parseable terminal event
- **THEN** the parsed output records the credential lifecycle failure signal as true, and the audit payload for that phase carries the corresponding marker

#### Scenario: Codex output reports an expired token during a failed invocation

- **WHEN** a Codex invocation resolves to a provider failure and its stdout or stderr contains `token_expired` or `Failed to refresh token`
- **THEN** the parsed output records the credential lifecycle failure signal as true

#### Scenario: A successful invocation mentions a tracked text in its response

- **WHEN** a Codex invocation completes with exactly one successful terminal event and no provider failure, and its agent message text contains `refresh_token_reused`
- **THEN** the credential lifecycle failure signal remains false, and the audit payload omits the marker

#### Scenario: Raw output retention is unchanged by the new signal

- **WHEN** a Codex invocation resolves to `OUTPUT_CONTRACT`, observes no known authentication-evidence text, and contains a credential lifecycle failure text
- **THEN** the audit payload still retains the redacted stdout and stderr, because the credential lifecycle signal is not a retention-suppressing condition

#### Scenario: A non-authentication 401 appears in output

- **WHEN** a Codex invocation's output contains `401 Unauthorized` without any tracked credential lifecycle text
- **THEN** the credential lifecycle failure signal remains false

### Requirement: Invocation evidence records the credential generation it ran against

**Trace:** Issue #305 受け入れ条件「正常時は従来どおり `logged_in` を返す（回帰テスト 1 本）」

Because an invocation runs against a per-run copy of the persistent credential source rather than the source itself, the recorded evidence SHALL identify the credential generation the invocation actually used, as the last-modified time of the credential source observed for that invocation. The observation SHALL be taken before the copy, so that a re-login racing the copy is recorded as an older generation rather than a newer one, and the resulting error is a missed downgrade rather than a downgrade that the operator cannot clear.

The observed time SHALL retain the precision the filesystem reports rather than being truncated to milliseconds.

The same value SHALL also be written to the phase audit payload as a non-secret diagnostic field, so that a human reading the audit log can tell which credential generation a failure belonged to.

#### Scenario: Codex invocation copies a credential source

- **WHEN** a Codex invocation is rendered with a readable persistent credential source
- **THEN** the observed source modification time is available to the evidence record and to the phase audit payload

#### Scenario: A re-login races the credential copy

- **WHEN** an operator re-logs in between the source observation and the copy
- **THEN** the recorded generation is the older one, so a subsequent failure is treated as belonging to a superseded generation

#### Scenario: No credential source is available

- **WHEN** an invocation runs without a readable credential source
- **THEN** no generation is recorded, and the audit payload omits the field

### Requirement: CLI auth status reports token suspicion from in-process invocation evidence

**Trace:** Issue #305 受け入れ条件「Codex の token が失効している状態で `/ops/llm-auth` が `logged_in` 以外の状態を返す」「正常時は従来どおり `logged_in` を返す」

`GET /ops/llm-auth` SHALL NOT report a provider as `logged_in` on the basis of credential marker file presence alone. When a credential marker is present, the service SHALL consult in-process invocation evidence for that provider and SHALL report `token_suspect` instead of `logged_in` when that evidence records an authentication or credential lifecycle failure for the current credential generation.

The evidence SHALL be held as in-process live state, updated by the invocation path at the point where it observes a failure, and read by the status endpoint without querying the audit database. The state SHALL hold only the provider, the observation time, and the credential generation — not provider output, exception text, or credential content.

The state SHALL be updated before the phase audit is persisted, so that a database outage during an authentication failure does not leave the status reporting `logged_in`.

Updates SHALL be atomic per provider. An update SHALL NOT overwrite existing evidence that belongs to a newer credential generation, because an invocation running against an older credential may complete after one running against a newer credential; within the same generation, the later observation wins.

Every invocation path inside the serving process SHALL share one evidence state instance. This includes the decision-run one-shot path, the daemon pre-filter path, the reflection path, and the evaluation path, because more than one of these can invoke Codex.

**Scope of detection.** This requirement covers failures observed by invocations running inside the serving process. It does NOT cover a separate maintenance process that runs the one-shot runner directly, whose observations cannot reach the serving process's state, and it does NOT survive a restart of the serving process. Both are accepted limitations rather than defects: the direct runner is an isolated operator-supervised maintenance action, and a process that runs no invocations is also producing no invocation failures.

Authentication evidence SHALL consist of the authentication-failure suspicion signal and the Codex credential lifecycle failure signal.

A later invocation without a failure signal SHALL NOT clear an earlier failure of the same generation, because an invocation succeeds against a per-run copy of the credential and therefore does not prove that the persistent source is still valid, and because an invocation may fail for non-authentication reasons without carrying any authentication signal. The downgrade SHALL be cleared only by a credential marker update, which starts a new generation.

The absence of evidence SHALL NOT be reported as a failure: a provider whose marker is present and which holds no current-generation failure evidence SHALL be reported as `logged_in`.

The status vocabulary SHALL remain a closed set of stable wire values, and `token_suspect` SHALL be documented in the route's own OpenAPI metadata.

#### Scenario: A current-generation run shows credential expiry

- **WHEN** the Codex credential marker is present and the in-process evidence records a credential lifecycle failure for the current generation
- **THEN** `/ops/llm-auth` reports `token_suspect` for Codex with a non-secret detail that names the evidence, rather than `logged_in`

#### Scenario: A current-generation run shows an authentication category failure

- **WHEN** the credential marker is present and the evidence records an authentication-failure suspicion for the current generation
- **THEN** `/ops/llm-auth` reports `token_suspect` for that provider

#### Scenario: A later invocation succeeds

- **WHEN** a provider holds current-generation failure evidence and a subsequent invocation completes without any failure signal
- **THEN** `/ops/llm-auth` still reports `token_suspect`, because success against a per-run credential copy does not clear the evidence

#### Scenario: A later invocation fails for a non-authentication reason

- **WHEN** a provider holds current-generation failure evidence and a subsequent invocation fails with a timeout or cleanup failure carrying no authentication signal
- **THEN** `/ops/llm-auth` still reports `token_suspect`

#### Scenario: No evidence exists

- **WHEN** the credential marker is present and no failure evidence has been recorded for that provider
- **THEN** `/ops/llm-auth` reports `logged_in`, because the absence of failure evidence is not failure evidence

#### Scenario: Evidence belongs to another provider

- **WHEN** the only recorded failure evidence belongs to a different provider
- **THEN** the provider under evaluation is not downgraded

#### Scenario: Credential marker is absent

- **WHEN** no credential marker file is present for a provider
- **THEN** `/ops/llm-auth` reports `logged_out` without consulting invocation evidence

#### Scenario: The process restarts

- **WHEN** the application restarts and no invocation has run since
- **THEN** `/ops/llm-auth` reports `logged_in`, and a subsequent failing invocation restores `token_suspect`

#### Scenario: The audit database is unavailable when the failure is observed

- **WHEN** an invocation observes an authentication failure and persisting its phase audit fails
- **THEN** the evidence is still recorded and `/ops/llm-auth` reports `token_suspect`

#### Scenario: An older-generation invocation completes last

- **WHEN** evidence for a newer credential generation is already recorded and an invocation running against an older generation then records its own failure
- **THEN** the newer-generation evidence is retained

#### Scenario: A non-decision-run path observes the failure

- **WHEN** the reflection path invokes Codex and observes an authentication failure
- **THEN** `/ops/llm-auth` reports `token_suspect`, because that path shares the same evidence state

### Requirement: Evidence is scoped to the current credential generation

**Trace:** Issue #305 受け入れ条件「正常時は従来どおり `logged_in` を返す（回帰テスト 1 本）」

The service SHALL treat evidence whose recorded credential generation is strictly older than the credential marker file's current last-modified time as superseded, so that a completed re-login clears the downgrade without requiring a subsequent successful invocation.

Evidence whose recorded generation equals the marker's time SHALL be treated as current, so that an unresolvable same-timestamp collision preserves the downgrade rather than hiding an expiry.

#### Scenario: Operator re-logs in after an expiry

- **WHEN** a provider's credential marker is rewritten by a login flow after a failing invocation, and no invocation has run since
- **THEN** `/ops/llm-auth` reports `logged_in` for that provider, because the failing evidence belongs to a superseded credential generation

#### Scenario: Failure follows the re-login

- **WHEN** a provider's credential marker is rewritten and a later invocation running against the new credential again records a failure
- **THEN** `/ops/llm-auth` reports `token_suspect`

#### Scenario: Evidence generation equals the marker time

- **WHEN** recorded evidence carries a generation exactly equal to the credential marker's current modification time
- **THEN** the evidence is treated as current and `/ops/llm-auth` reports `token_suspect`

### Requirement: Unverifiable status is reported as unknown rather than logged in

**Trace:** Issue #305 背景「監視 API が『認証は正常』と報告し続けたため、原因の特定が遅れた」

When a credential marker is present but the service cannot determine its modification time, the service SHALL report `unknown` for that provider with a stable non-secret reason. It SHALL NOT fall back to `logged_in`.

When no evidence state is configured at all, the service SHALL retain the marker-presence behavior and report `logged_in`, so that existing constructions keep the current contract.

The status evaluation SHALL NOT perform a database query, so that database availability, query bounds, and audit payload interpretation cannot make a valid credential appear unknown.

The status detail SHALL NOT contain credential file contents, tokens, provider output text, exception messages, or stack traces.

#### Scenario: Marker modification time cannot be read

- **WHEN** the credential marker is present but its modification time cannot be read
- **THEN** `/ops/llm-auth` reports `unknown` for that provider with a stable non-secret reason, and does not report `logged_in`

#### Scenario: The audit database is unavailable

- **WHEN** the audit database is unreachable
- **THEN** `/ops/llm-auth` still reports each provider from marker presence and in-process evidence, because the status evaluation does not query the database

#### Scenario: No evidence state is configured

- **WHEN** the service is constructed without evidence state and the credential marker is present
- **THEN** `/ops/llm-auth` reports `logged_in` as before

#### Scenario: Status detail is redacted

- **WHEN** any provider status is produced, including the failure paths
- **THEN** the detail contains only fixed non-secret text and no credential content, token, provider output, exception message, or path beyond the already-exposed auth home path

### Requirement: CLI auth status remains outside readiness and trading behavior

**Trace:** Issue #305 やらないこと「通知機構の新設」および既存の CLI auth 境界

The CLI auth status evaluation SHALL NOT participate in `/health`, `/health/ready`, scheduler admission, SafetyFloor, order lifecycle, or trade execution. A `token_suspect` or `unknown` status SHALL NOT block, gate, or alter any of them, and SHALL NOT trigger an automatic re-login or token refresh.

#### Scenario: A provider is token suspect

- **WHEN** `/ops/llm-auth` reports `token_suspect` for a provider
- **THEN** readiness, scheduler admission, and trading semantics are unchanged, and no re-login is started automatically

#### Scenario: Evidence source is unavailable

- **WHEN** the evidence source cannot be reached
- **THEN** `/ops/llm-auth` reports `unknown` while `/health/ready` and trading semantics remain unchanged
