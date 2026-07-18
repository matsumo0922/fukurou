## ADDED Requirements

### Requirement: Forward deploy requires one-run CLI acceptance before cutover
Every `FORWARD` deploy MUST include the existing `CLI_AUTH_PREFLIGHT_V1` operation in its signed required-hook set and MUST run the installed, hash-verified acceptance harness once against the exact candidate repository digest after candidate admission and before production compose cutover. `AUTHORIZED_ROLLBACK` MUST retain the previous required-hook set so an older known-good image remains recoverable without claiming new provider qualification.

#### Scenario: Forward candidate passes deploy acceptance
- **WHEN** a signed `FORWARD` deploy targets an exact candidate digest and both candidate hook admission and `--cli-acceptance --runs 1` succeed
- **THEN** the executor records `CLI_AUTH_PREFLIGHT_V1` as dispatched and may proceed to production compose cutover

#### Scenario: Canary auth or provider compatibility fails
- **WHEN** the dedicated `llm-canary-auth` volume is missing or unusable, or any auth, output, model, tool-call, timeout, process, or cleanup validation fails
- **THEN** the executor fails before production compose cutover, preserves only safe failure output, and enters the existing rollback path without using production LLM credentials

#### Scenario: Historical rollback is authorized
- **WHEN** a signed `AUTHORIZED_ROLLBACK` targets an older main-reachable image
- **THEN** the bundle requires the existing foundation hook but does not require `CLI_AUTH_PREFLIGHT_V1`, and the rollback remains available without representing the old image as newly provider-qualified

#### Scenario: Required hook support drifts
- **WHEN** the signed hook set, typed operation set, candidate PID 1 probe, token allowlist, installed harness invocation, or dispatch ledger omits or disagrees on `CLI_AUTH_PREFLIGHT_V1` for a `FORWARD` deploy
- **THEN** validation or candidate preflight fails before production compose cutover

#### Scenario: Acceptance exceeds the deploy deadline
- **WHEN** the one-run acceptance does not complete within the executor's existing forward deadline
- **THEN** the executor terminates the harness through its bounded timeout path, cleans up the canary container, and enters the existing rollback path
