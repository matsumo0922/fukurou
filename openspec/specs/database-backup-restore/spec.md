# database-backup-restore Specification

## Purpose

Production PostgreSQL の同一NAS暗号化logical backup、隔離restore drill、root-only rollout gate、および明示的な復旧境界を定義する。

## Requirements

### Requirement: Production PostgreSQL receives scheduled encrypted logical backup attempts
A root-owned timer MUST attempt a PostgreSQL 16 custom-format logical backup once per calendar day and store successful snapshots only in an encrypted same-NAS restic repository. It MUST reach the host-unpublished database through the fixed production container without placing the production database password in inspect output, dump arguments/environment, or host artifacts. It MUST bound the database-locking dump phase to 60 seconds with an independent exact-backend termination watchdog, and MUST NOT persist a plaintext dump, database password, or repository password in a filesystem artifact, process argument, log, or status document. The cadence is an attempt schedule and MUST NOT be represented as guaranteed daily success.

The Docker-backed integration contract MUST execute the production backup entrypoint itself against real PostgreSQL 16 and restic, and MUST cover its retention prune and redacted output rather than reproducing the orchestration as test-only commands.

#### Scenario: Daily backup succeeds
- **WHEN** production PostgreSQL is reachable and the encrypted repository is healthy
- **THEN** the job streams a custom-format dump into a restic snapshot and records its exact non-secret identity, source revision, integrity time, and successful attempt time

#### Scenario: Dump, repository, or publication fails
- **WHEN** dump creation, repository write, snapshot identification, integrity verification, or status publication fails
- **THEN** the job exits nonzero with a stable redacted result code, preserves the previous complete status and last-known-good evidence as applicable, and advances no unverified success freshness

#### Scenario: Production identity changes after the deploy-lock probe
- **WHEN** the PostgreSQL container, application container, database identity, or application revision differs between the pre-dump and post-dump observations
- **THEN** the attempt does not advance integrity-checked success evidence or attribute the snapshot to the earlier application revision

#### Scenario: Dump producer fails after restic accepted partial input
- **WHEN** `pg_dump` fails or times out after restic created a snapshot from partial stdin
- **THEN** the job detects the producer failure independently, forgets the attempt-tagged partial snapshot without prune when it can identify it, performs no integrity or retention operation, and never records it as successful evidence

#### Scenario: Host-side dump client cannot stop the database backend
- **WHEN** the dump client reaches its deadline while the container-side backend is waiting on a database lock and produces no stdout
- **THEN** the independent watchdog resolves exactly one validated application-named PID, atomically rechecks both PID and application name when terminating, preserves every non-target backend, verifies disappearance before return, and reports `WATCHDOG_TERMINATION_FAILED` without success evidence if exact termination cannot be proven

#### Scenario: Dump completes before the watchdog deadline
- **WHEN** the dump producer completes before its database-lock deadline
- **THEN** the job cancels and reaps the watchdog before the PID can be reused and terminates no database backend

#### Scenario: Backup capacity floor is not met
- **WHEN** the backup filesystem lacks the configured reserve in addition to the measured production database size
- **THEN** the job fails before dump or repository mutation and records only a stable capacity result

### Requirement: Destructive retention requires repository integrity
The backup job MUST retain the newest fourteen daily generations within the fixed production-backup AND integrity-checked tag predicate, host, and stdin path group. It MUST encode the tag conjunction with restic's single comma-separated AND filter rather than multiple OR filters. It MUST complete repository structure verification and a full `restic dump` read while independently validating the custom-archive list before destructive prune. A retention failure MUST remain visible without erasing the identity or freshness of an otherwise integrity-checked snapshot, and only an actual isolated restore drill SHALL establish recoverability.

#### Scenario: More than fourteen daily generations exist
- **WHEN** a new snapshot is identified and repository and custom-archive structure verification succeed
- **THEN** the job keeps the newest fourteen daily generations and prunes older data

#### Scenario: Repository integrity is uncertain
- **WHEN** integrity verification fails, times out, or is interrupted
- **THEN** the job does not invoke destructive retention, reports an integrity failure, and preserves last-known-good evidence

#### Scenario: Archive list validation finishes before the snapshot stream
- **WHEN** `pg_restore --list` reaches the archive table of contents before `restic dump` reaches end of stream
- **THEN** the consumer drains the remaining bytes, preserves the list result separately, and requires both the list result and full producer stream to succeed

#### Scenario: Retention fails after a verified snapshot
- **WHEN** retention or prune fails after the new snapshot passed integrity verification
- **THEN** the current attempt reports housekeeping failure while the integrity-checked snapshot remains recorded without claiming an actual restore succeeded

#### Scenario: Integrity tagging changes snapshot identity
- **WHEN** a candidate passes full-stream verification and restic adds the integrity-checked tag
- **THEN** the job obtains the tag operation's new authoritative snapshot ID and publishes only that ID for retention and restore selection

#### Scenario: A previous attempt left an unclassified candidate
- **WHEN** the repository contains an attempt-tagged snapshot without the integrity-checked tag from an earlier interrupted process
- **THEN** automation excludes it from retention and reports its aggregate count without deleting it, and the runbook requires full-stream evidence review before manual forget and checked prune

### Requirement: Jobs fail closed on start-time contention
Backup and restore jobs MUST share a non-blocking root lock and MUST probe the production deploy lock before database, repository, or Docker mutation. The contract SHALL cover start-time contention only and MUST NOT claim that a deploy starting after the probe is mutually excluded. A backup dump that overlaps a later deploy MUST use server-side termination to release its database locks within the fixed 60-second bound.

#### Scenario: Backup job is already running
- **WHEN** another backup or restore job owns the shared backup lock
- **THEN** the new job exits nonzero with a stable busy result and performs no database, repository, status, or Docker mutation

#### Scenario: Deploy is active at the safety probe
- **WHEN** the production deploy lock is owned when a backup or restore job performs its start-time probe
- **THEN** the job exits nonzero with `DEPLOY_IN_PROGRESS` before reading production data or mutating repository or Docker resources

#### Scenario: Deploy starts after the probe
- **WHEN** the deploy lock was free at the probe and a deploy starts later
- **THEN** the dump is terminated within its fixed bound, the backup contract does not report full mutual exclusion, and any resulting partial snapshot or child failure remains failed evidence without destructive retention

#### Scenario: A watchdog control query hangs
- **WHEN** Docker or a watchdog PostgreSQL control query does not return
- **THEN** every control query, termination check, cancel, wait, and reap uses the remaining absolute deadline and the database-locking phase cannot be configured beyond sixty seconds

### Requirement: Weekly restore drills use an exact snapshot and disposable PostgreSQL
A weekly root-owned drill MUST restore the exact last integrity-checked snapshot into a disposable PostgreSQL 16 instance that shares no production container name, network, volume, host port, or database credential. It MUST restore data and schema with owner and ACL replay disabled, because code-owned deployment bootstrap remains the role/privilege authority. It MUST validate the versioned schema manifest, constraints, critical tables, and data invariants inside read-only transactions without starting application schema bootstrap, and MUST NOT claim to validate MCP role or ACL recovery.

Repository contract tests MUST bind every column referenced by the invariant SQL and every critical-table primary-key assumption to the current code-owned schema authority, so a column rename or primary-key removal fails before a production restore drill.

#### Scenario: Scheduled restore drill succeeds
- **WHEN** the exact verified snapshot restores successfully, every profile invariant passes, and all owned disposable resources are removed
- **THEN** the drill records snapshot identity, verified time, measured duration, and non-secret verification counts as last-known-good restore evidence

#### Scenario: Restore or invariant validation fails
- **WHEN** decryption, archive restore, PostgreSQL startup, schema inventory, constraint validation, critical table validation, or a read-only invariant fails
- **THEN** the drill exits nonzero, does not alter production, preserves the previous last verified restore, and performs bounded cleanup of every owned resource

#### Scenario: Production roles do not exist in the disposable database
- **WHEN** the archive contains production OWNER or ACL entries for roles absent from the disposable instance
- **THEN** the drill suppresses owner and ACL replay, restores schema and data under the disposable owner, and leaves role and privilege reconstruction outside its recovery claim

#### Scenario: Cleanup is incomplete
- **WHEN** restore and invariant validation pass but any owned container, network, volume, or temporary resource remains
- **THEN** the drill reports cleanup failure and does not advance last verified restore evidence

#### Scenario: Additional signals arrive during cleanup
- **WHEN** HUP, INT, or TERM starts owned-resource cleanup and another HUP, INT, or TERM arrives before cleanup finishes
- **THEN** the cleanup continues within a service stop timeout longer than its worst-case bound and the process is not killed between container, network, and volume removal

#### Scenario: A previous drill left owned resources
- **WHEN** a previous force-killed or interrupted drill left any resource with the restore ownership label
- **THEN** the next drill reports cleanup failure before creating new resources and does not hide the leak behind a later successful attempt, and an unencrypted residual restore volume is handled as a data-at-rest incident

#### Scenario: Isolation contract is inspected
- **WHEN** fixture tests inspect the disposable restore resources and child arguments
- **THEN** they contain a unique non-production identity, bounded resource settings, and a separate credential, and do not contain production container, network, volume, password, or host-port bindings

### Requirement: Backup status is atomic, versioned, root-only, and redacted
Backup automation MUST publish a schema-versioned status document using same-directory atomic rename and durable file and directory publication. The document SHALL separate each job's last attempt from last-known-good evidence and MUST contain only allowlisted timestamps, snapshot identity, source revision, stable result codes, measured duration, and aggregate counts.

#### Scenario: Status is read during publication
- **WHEN** a reader opens status while a job publishes a new result
- **THEN** it observes either the complete previous schema-valid document or the complete new schema-valid document and never a partial document

#### Scenario: A later attempt fails
- **WHEN** a backup or restore attempt fails after an earlier success
- **THEN** the new attempt result is recorded while the corresponding last-known-good backup or restore evidence remains unchanged

#### Scenario: Interrupted candidate count cannot be read
- **WHEN** repository failure prevents an attempt from counting unclassified candidate snapshots
- **THEN** the status records the count as unknown rather than reporting zero candidates

#### Scenario: Unsafe child detail is produced
- **WHEN** a child error contains a credential, raw SQL, dump fragment, secret path, token, or private-key material
- **THEN** stdout, stderr, and status expose only a stable allowlisted result code and no unsafe detail

#### Scenario: Publication is interrupted before rename
- **WHEN** status publication stops before atomic replacement
- **THEN** the previous complete status remains authoritative

#### Scenario: Existing status is malformed or unsupported
- **WHEN** a job cannot safely merge the existing status document
- **THEN** automatic merge and restore fail closed, and the runbook requires root-only quarantine, repository evidence review, and explicit schema-v1 reinitialization before automation resumes

#### Scenario: Installed status ownership is inspected
- **WHEN** the root rollout verification checks the status directory and file
- **THEN** the directory is root-owned mode 0700 and the status file is root-owned mode 0600

### Requirement: Root automation requires an explicit rollout gate
Systemd services and timers MUST be root-owned, use fixed installed entrypoints without embedded secrets, remain disabled by installation, and be enabled only after a manual backup and restore drill succeed. Artifact installation MUST hold the shared backup lock and MUST fail while any backup or restore service or timer is active. After artifact placement and daemon reload, installation MUST atomically publish a root-only marker that binds the installed artifact aggregate hash and installation timestamp. Installation and rollout verification MUST reject a missing, malformed, incorrectly owned, or hash-mismatched marker. Rollout verification MUST require the latest backup attempt to be `SUCCESS` with successful retention, the latest restore attempt to be `SUCCESS`, both attempts to identify their matching last-success snapshot, and both last-success records to identify the same snapshot. It MUST also require both attempts to contain distinct systemd invocation IDs and the current kernel boot ID as durable status evidence, and MUST bind each attempt to the latest stable result for that unit in the current-boot journal. The journal result MUST be `SUCCESS` with the same invocation ID so a later publication failure invalidates older status evidence; garbage-collectable oneshot runtime properties MUST NOT be the authority. Both attempt timestamps, status `updatedAt`, and status file evidence MUST postdate the install marker and fall within the fixed 24-hour freshness age. A status publication failure, reinstall, earlier-boot evidence, expired evidence, or non-systemd invocation MUST NOT expose stale success evidence as current. It MUST confirm that exact snapshot exists uniquely in the current repository under the fixed host, stdin path, and production-plus-integrity AND tags. It MUST use bounded Docker inventory calls to confirm that no container, network, or volume with the restore ownership label remains. The change MUST NOT expand `github-runner` sudo authority.

#### Scenario: Units are installed before the first drill
- **WHEN** the operator installs commands, profiles, services, and timers
- **THEN** a root-only hash-bound install marker is published and no scheduled backup or restore begins until the operator explicitly enables the timers

#### Scenario: Installation overlaps a backup or restore job
- **WHEN** the shared backup lock is owned or any backup or restore service or timer is active
- **THEN** artifact installation fails before replacing commands, profiles, schemas, or units

#### Scenario: Status evidence does not belong to the current repository
- **WHEN** status names an exact snapshot that is absent, ambiguous, or outside the fixed host, path, and AND-tag group in the currently opened repository
- **THEN** rollout verification fails and the timers remain disabled

#### Scenario: A failed latest attempt preserves older success evidence
- **WHEN** backup retention or restore cleanup fails after an earlier successful backup and restore
- **THEN** rollout verification rejects the stale last-success evidence and the timers remain disabled

#### Scenario: Artifacts are reinstalled after a successful drill
- **WHEN** the install marker is newer than the service, attempt, status timestamp, or status file evidence
- **THEN** rollout verification rejects the pre-install evidence and requires a new manual backup and restore drill

#### Scenario: Installed artifacts drift or evidence expires
- **WHEN** the installed aggregate hash differs from the marker or any required evidence is older than 24 hours
- **THEN** installation or rollout verification fails and the timers remain disabled

#### Scenario: Restore-owned resources remain after the drill
- **WHEN** bounded Docker inventory finds any container, network, or volume with the restore ownership label
- **THEN** rollout verification fails without deleting or pruning any Docker resource

#### Scenario: First backup or restore has not succeeded
- **WHEN** either initial manual operation lacks valid last-known-good evidence or cleanup verification
- **THEN** the runbook forbids timer enablement and directs the operator to resolve the stable failure code

#### Scenario: NAS backup prerequisites are verified
- **WHEN** the operator evaluates timer enablement
- **THEN** restic and systemd are available on persistent paths, the repository uses format v2 with compression, capacity reserve is satisfied, and the measured manual dump finishes within the fixed database-lock bound

#### Scenario: Installed authority is inspected
- **WHEN** the rollout verifies installed commands, profiles, units, and sudoers
- **THEN** the artifacts are root-owned with restrictive modes, service entrypoints are fixed, no secret appears in a unit, and `github-runner` retains only its existing deploy authority

### Requirement: Recovery scope remains explicit and manual
The documented contract MUST state that backups provide scheduled daily same-NAS logical backup attempts and measured weekly recovery evidence only. It MUST NOT claim guaranteed daily success, PITR, off-site disaster recovery, NAS-loss protection, guaranteed RPO/RTO, role/ACL recovery by the drill, or automatic failure notification. Deploy failure MUST NOT trigger database restore, and production database replacement MUST remain outside the supplied commands.

#### Scenario: Application deploy rolls back
- **WHEN** a candidate image fails and a previous compatible image is restored
- **THEN** the database remains at its current state and no backup snapshot is replayed

#### Scenario: Operator reviews recovery evidence
- **WHEN** the runbook or status is read
- **THEN** it distinguishes actual snapshot age and measured restore duration from a guaranteed recovery objective

#### Scenario: Production database recovery is considered
- **WHEN** an operator suspects production corruption or data loss
- **THEN** the runbook requires exact snapshot selection, isolated restore and evidence review, risk-increasing execution stop, separate explicit authorization before any production replacement, and code-owned deploy foundation plus role/ACL bootstrap verification before application startup

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

#### Scenario: Start publisher cannot replace an older terminal
- **WHEN** the pre-execution publisher cannot write new running evidence and an older terminal projection remains
- **THEN** the projection age exceeds its fixed freshness bound and the application reports backup/restore monitoring as `UNKNOWN` instead of treating the old success as current

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
