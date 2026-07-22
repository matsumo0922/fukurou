## REMOVED Requirements

### Requirement: Forward deploy requires one-run CLI acceptance before cutover
**Reason**: owner 確認済み。issue #292 の設計ステップ3（`deploy-fukurou` を pull→migration→compose up→health確認の素直なスクリプトに書き直す）を文字通り実装するため、FORWARD デプロイ前の CLI acceptance 必須化を撤去する。capability catalog・typed operation set・署名検証の撤去に伴い、この Requirement が依存する required-hook/operation 検証基盤自体も失われる
**Migration**: `scripts/mcp-credential-isolation-check` の qualification 機能自体（1-run smoke / 3-run merge qualification）は本 change の対象外で残る。ただし deploy パイプラインからの自動起動は行われなくなる。pinned CLI の互換性確認が再び必要になった場合は、別 change で deploy 経路への再統合を設計する

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
- **WHEN** the one-run acceptance does not complete within its 720-second container or 750-second host pre-mutation budget
- **THEN** the harness is given a bounded cleanup interval, the executor fails without changing production launch state, and fresh 1,200-second forward plus 1,500-second recovery budgets remain available after acceptance succeeds

#### Scenario: Forward provider gate is unavailable during an incident
- **WHEN** a new `FORWARD` deploy cannot pass the provider gate during a provider outage or credential incident
- **THEN** the executor provides no forward bypass, while existing launch-disable controls and a signed `AUTHORIZED_ROLLBACK` remain available as risk-reducing operations

#### Scenario: Fresh install has no rollback target
- **WHEN** fresh install or disaster recovery has no older running image eligible for `AUTHORIZED_ROLLBACK`
- **THEN** provisioned canary credentials are a bootstrap prerequisite and the system does not claim a forward bypass or unavailable rollback path
