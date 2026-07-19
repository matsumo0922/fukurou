## ADDED Requirements

### Requirement: Backup monitoring projection preserves root authority
The system SHALL keep the authoritative backup/restore status and repository credentials root-only and SHALL publish a separate atomic projection containing only allowlisted backup/restore timestamps, states, and service lifecycle evidence. The application SHALL never mount or read the authoritative status, repository, or password source.

#### Scenario: Valid authoritative status is projected
- **WHEN** the root publisher reads a schema-valid authoritative status for the current service invocation
- **THEN** it atomically publishes only allowlisted monitoring fields to a root-writable, application-readable projection

#### Scenario: Secret-bearing or unknown field is supplied
- **WHEN** an input or candidate projection contains a repository, password, token, command output, host path, unknown field, symlink, or oversized content
- **THEN** publication or application parsing fails closed and the public monitoring contract does not expose the value

### Requirement: Service lifecycle detects termination before status publication
The system SHALL publish service invocation start evidence before backup or restore execution and terminal evidence from systemd after execution, including executions terminated by signal, timeout, or OOM. A success from an older invocation SHALL NOT satisfy the current invocation.

#### Scenario: Backup publishes success normally
- **WHEN** a backup service invocation starts, publishes authoritative success, and exits successfully
- **THEN** the projection identifies the invocation as terminal success and exposes the matching last-attempt and last-success timestamps

#### Scenario: Process is killed before authoritative publication
- **WHEN** the backup or restore process is killed after invocation start but before it publishes authoritative status
- **THEN** the projection retains terminal failure or a stale running state and does not report an older success as the current invocation result

#### Scenario: Terminal publisher cannot run
- **WHEN** the post-execution publisher cannot update the projection
- **THEN** the prior running evidence becomes stale and the application reports backup/restore monitoring as `UNKNOWN`

### Requirement: Projection activation is fail-closed and deploy-safe
The production composition SHALL mount a fixed dedicated public monitoring directory read-only at a fixed container path. It SHALL permit Compose to create that empty host directory before root artifact installation, and the application SHALL read only a fixed projection filename from it. The composition SHALL NOT accept an arbitrary host source path.

#### Scenario: Change is deployed before root artifacts are installed
- **WHEN** the production environment has not installed or published root projection artifacts
- **THEN** compose starts successfully with an empty fixed public directory and `/ops/monitoring` reports backup/restore as `UNKNOWN` with a not-activated reason

#### Scenario: Public projection is activated
- **WHEN** root artifacts and their selftests succeed and a valid public projection exists in the fixed directory
- **THEN** atomic replacement in the mounted directory becomes visible to the application and it can report the allowlisted evidence

#### Scenario: Authoritative path cannot be configured
- **WHEN** production composition is rendered
- **THEN** its fixed bind source cannot be redirected to the root-only authoritative status, repository, or secret directory through environment interpolation
