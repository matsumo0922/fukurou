## ADDED Requirements

### Requirement: Production Codex fallback login updates the persistent auth source as appuser
**Trace:** Issue #307 受け入れ条件「UID 10001 必須の再ログイン手順と root 実行の危険性が現在形で記載される」

The production operations runbook SHALL execute Codex device authentication explicitly as appuser UID `10001`, SHALL verify that `/tmp/fukurou-cli-home/.codex/auth.json` was updated, and SHALL warn operators not to run the login command as root. The procedure SHALL NOT rely on a successful CLI message alone as evidence that the credential was persisted.

#### Scenario: Operator performs Codex fallback login
- **WHEN** WebUI login is unavailable and an operator uses the production container fallback
- **THEN** the documented command uses `docker exec -it --user 10001 fukurou-ktor codex login --device-auth`

#### Scenario: Operator verifies credential persistence
- **WHEN** the device-auth flow reports successful login
- **THEN** the runbook requires checking the modification time of `/tmp/fukurou-cli-home/.codex/auth.json` and treats an updated value as the persistence check

#### Scenario: Root login is considered unsafe
- **WHEN** an operator considers running Codex login with UID 0
- **THEN** the runbook states that root can report successful login without persisting the appuser-owned auth source and can revoke the previously valid session, so the command must not be used

#### Scenario: Current container image already defaults to appuser
- **WHEN** the production image declares `USER appuser`
- **THEN** the runbook still pins `--user 10001` explicitly so the recovery procedure does not depend on an implicit image default and does not claim that omission currently selects root
