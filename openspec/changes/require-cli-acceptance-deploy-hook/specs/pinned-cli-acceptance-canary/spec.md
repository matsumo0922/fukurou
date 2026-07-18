## ADDED Requirements

### Requirement: Forward deploy requires one-run CLI acceptance before cutover
Every `FORWARD` deploy MUST include the existing `CLI_AUTH_PREFLIGHT_V1` operation in an exact signed required-hook/operation set and MUST run the installed, hash-verified acceptance harness once against the exact candidate repository digest before rollback-state capture, launch disable, or production compose cutover. `AUTHORIZED_ROLLBACK` MUST retain the previous required-hook set so an older known-good image remains recoverable without claiming new provider qualification.

#### Scenario: Forward candidate passes deploy acceptance
- **WHEN** a signed `FORWARD` deploy targets an exact candidate digest and both the pre-mutation `--cli-acceptance --runs 1` gate and later candidate hook admission succeed
- **THEN** the executor records `CLI_AUTH_PREFLIGHT_V1` as dispatched and may proceed to production compose cutover

#### Scenario: Canary auth or provider compatibility fails
- **WHEN** the dedicated `llm-canary-auth` volume is missing or unusable, or any auth, output, model, tool-call, timeout, process, or cleanup validation fails
- **THEN** the executor fails before creating a new rollback journal or changing production launch state, preserves only safe failure output, and does not use production LLM credentials

#### Scenario: Historical rollback is authorized
- **WHEN** a signed `AUTHORIZED_ROLLBACK` targets an older main-reachable image
- **THEN** the bundle requires the existing foundation hook but does not require `CLI_AUTH_PREFLIGHT_V1`, and the rollback remains available without representing the old image as newly provider-qualified

#### Scenario: Required hook support drifts
- **WHEN** the intent-specific signed hook set and `SMOKE_HOOK_V1` operations are not exactly equivalent, or candidate PID 1 probe, token allowlist, safe marker, installed harness invocation, or dispatch ledger omits or disagrees on `CLI_AUTH_PREFLIGHT_V1` for a `FORWARD` deploy
- **THEN** validation or candidate preflight fails before production compose cutover

#### Scenario: Acceptance exceeds the deploy deadline
- **WHEN** the one-run acceptance does not complete within its 600-second pre-mutation budget
- **THEN** the harness terminates its exact canary container, the executor fails without changing production launch state, and the existing 1,200-second mutation/recovery deadline remains available after acceptance

#### Scenario: Forward provider gate is unavailable during an incident
- **WHEN** a new `FORWARD` deploy cannot pass the provider gate during a provider outage or credential incident
- **THEN** the executor provides no forward bypass, while existing launch-disable controls and a signed `AUTHORIZED_ROLLBACK` remain available as risk-reducing operations

## MODIFIED Requirements

### Requirement: Issue closure remains evidence-based
The final Issue #189 change MUST distinguish implemented deploy wiring from operator evidence and MUST NOT close Issue #189 until the three-run exact-digest qualification and one real forward-deploy acceptance result are recorded without raw provider output.

#### Scenario: Deploy hook is merged without operator evidence
- **WHEN** `CLI_AUTH_PREFLIGHT_V1` wiring is merged but exact-digest qualification or first forward-deploy evidence is absent
- **THEN** Issue #189 remains open with the corresponding evidence item marked incomplete

#### Scenario: Final evidence is complete
- **WHEN** one exact digest has passed the three-run qualification and a forward deploy has recorded both required hooks before cutover
- **THEN** Issue #189 may be closed with the safe digest-bound results
