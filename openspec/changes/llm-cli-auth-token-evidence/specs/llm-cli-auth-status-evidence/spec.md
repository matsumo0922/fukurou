## ADDED Requirements

### Requirement: Codex credential lifecycle failures are tracked independently of raw output retention

**Trace:** Issue #305 受け入れ条件「Codex の token が失効している状態（または失効 evidence が直近 run に存在する状態）で、`/ops/llm-auth` が `logged_in` 以外の状態を返す」

The Codex output parser SHALL track, independently of both its first-match primary category resolution and its `authEvidenceObserved` tracking, whether the invocation's stdout or stderr contains a known credential lifecycle failure text. The tracked set SHALL consist of texts observed in a real production credential expiry (`refresh_token_reused`, `token_expired`, `Failed to refresh token`) and SHALL NOT include bare HTTP status text such as `401 Unauthorized`, because a non-authentication 401 from another source in the same output would otherwise be misread as credential expiry.

This signal SHALL NOT participate in the raw stdout/stderr retention decision defined by the provider-failure-category requirements. Adding a text to the credential lifecycle set SHALL NOT change which invocations retain redacted raw output, so that the diagnostic evidence that allows a human to identify an unknown failure mode remains available.

The LLM invocation auditor SHALL record this signal in the `RUNNER_PHASE_COMPLETED` audit payload as an allowlisted non-secret marker, present only when the signal is true.

This is detection of known failure shapes, not a proof of credential validity. A CLI log wording change SHALL be treated as a detection gap, not as evidence that credentials are valid.

#### Scenario: Codex stderr reports a reused refresh token

- **WHEN** a Codex invocation's stderr contains `refresh_token_reused` while its stdout carries no parseable terminal event
- **THEN** the parsed output records the credential lifecycle failure signal as true, and the audit payload for that phase carries the corresponding marker

#### Scenario: Codex output reports an expired token

- **WHEN** a Codex invocation's stdout or stderr contains `token_expired` or `Failed to refresh token`
- **THEN** the parsed output records the credential lifecycle failure signal as true

#### Scenario: Raw output retention is unchanged by the new signal

- **WHEN** a Codex invocation resolves to `OUTPUT_CONTRACT`, observes no known authentication-evidence text, and contains a credential lifecycle failure text
- **THEN** the audit payload still retains the redacted stdout and stderr, because the credential lifecycle signal is not a retention-suppressing condition

#### Scenario: A non-authentication 401 appears in output

- **WHEN** a Codex invocation's output contains `401 Unauthorized` without any tracked credential lifecycle text
- **THEN** the credential lifecycle failure signal remains false

#### Scenario: Successful invocation carries no signal

- **WHEN** a Codex invocation completes with a valid terminal event and no tracked text
- **THEN** the credential lifecycle failure signal is false and the audit payload omits the marker

### Requirement: CLI auth status reports token suspicion from recent invocation evidence

**Trace:** Issue #305 受け入れ条件「Codex の token が失効している状態で `/ops/llm-auth` が `logged_in` 以外の状態を返す」「正常時は従来どおり `logged_in` を返す」

`GET /ops/llm-auth` SHALL NOT report a provider as `logged_in` on the basis of credential marker file presence alone. When a credential marker is present, the service SHALL consult recent invocation audit evidence for that provider and SHALL report `token_suspect` instead of `logged_in` when that evidence indicates an authentication or credential lifecycle failure.

Authentication evidence SHALL consist of the audit payload's authentication-failure suspicion marker and the Codex credential lifecycle failure marker. The absence of any evidence SHALL NOT be reported as a failure: a provider whose marker is present and whose recent evidence shows no failure SHALL be reported as `logged_in`.

The status vocabulary SHALL remain a closed set of stable wire values, and `token_suspect` SHALL be documented in the route's own OpenAPI metadata.

#### Scenario: Recent Codex run shows credential expiry

- **WHEN** the Codex credential marker is present and the most recent in-window Codex phase audit carries the credential lifecycle failure marker
- **THEN** `/ops/llm-auth` reports `token_suspect` for Codex with a non-secret detail that names the evidence, rather than `logged_in`

#### Scenario: Recent run shows an authentication category failure

- **WHEN** the credential marker is present and the most recent in-window phase audit for that provider carries the authentication-failure suspicion marker
- **THEN** `/ops/llm-auth` reports `token_suspect` for that provider

#### Scenario: Recent run succeeded

- **WHEN** the credential marker is present and the most recent in-window phase audit for that provider carries neither marker
- **THEN** `/ops/llm-auth` reports `logged_in`, because a completed invocation is direct evidence that the credential was usable

#### Scenario: No in-window evidence exists

- **WHEN** the credential marker is present and no in-window phase audit exists for that provider
- **THEN** `/ops/llm-auth` reports `logged_in`, because the absence of failure evidence is not failure evidence

#### Scenario: Credential marker is absent

- **WHEN** no credential marker file is present for a provider
- **THEN** `/ops/llm-auth` reports `logged_out` without consulting invocation evidence

### Requirement: Evidence observation is bounded by the credential marker's last update

**Trace:** Issue #305 受け入れ条件「正常時は従来どおり `logged_in` を返す（回帰テスト 1 本）」

The service SHALL consider only invocation evidence that occurred at or after the credential marker file's last modification time. Evidence older than the current credential SHALL NOT downgrade the status, so that a completed re-login clears the downgrade without requiring a subsequent successful invocation.

The evidence read SHALL be bounded in rows and in statement time, and SHALL evaluate only the most recent in-window audit for each provider.

#### Scenario: Operator re-logs in after an expiry

- **WHEN** a provider's credential marker is rewritten by a login flow after a failing invocation, and no invocation has run since
- **THEN** `/ops/llm-auth` reports `logged_in` for that provider, because the failing evidence predates the current credential

#### Scenario: Failure follows the re-login

- **WHEN** a provider's credential marker is rewritten and a later invocation again carries a failure marker
- **THEN** `/ops/llm-auth` reports `token_suspect`

#### Scenario: An older failure is superseded by a newer success

- **WHEN** two in-window audits exist for a provider, the older carrying a failure marker and the newer carrying none
- **THEN** `/ops/llm-auth` reports `logged_in`

### Requirement: Unverifiable evidence is reported as unknown rather than logged in

**Trace:** Issue #305 背景「監視 API が『認証は正常』と報告し続けたため、原因の特定が遅れた」

When a credential marker is present but the service cannot evaluate invocation evidence — the evidence source is unreachable, the query fails, the query reaches its declared row bound before resolving the provider's most recent audit, or an audit payload cannot be interpreted — the service SHALL report `unknown` for that provider with a non-secret reason. It SHALL NOT fall back to `logged_in`.

When no evidence source is configured at all, the service SHALL retain the marker-presence behavior and report `logged_in`, so that deployments without a database keep the existing contract.

The status detail SHALL NOT contain credential file contents, tokens, provider output text, exception messages, or stack traces.

#### Scenario: Evidence source query fails

- **WHEN** the credential marker is present and the evidence query raises a failure
- **THEN** `/ops/llm-auth` reports `unknown` for that provider with a stable non-secret reason, and does not report `logged_in`

#### Scenario: Audit payload cannot be interpreted

- **WHEN** an in-window audit payload for the provider is not valid JSON, or carries a marker value outside the expected vocabulary
- **THEN** `/ops/llm-auth` reports `unknown` for that provider rather than skipping the row and reporting `logged_in`

#### Scenario: No evidence source is configured

- **WHEN** the service is constructed without an evidence source and the credential marker is present
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
