## ADDED Requirements

### Requirement: Production PostgreSQL receives scheduled encrypted logical backup attempts
A root-owned timer MUST attempt a PostgreSQL 16 custom-format logical backup once per calendar day and store successful snapshots only in an encrypted same-NAS restic repository. It MUST reach the host-unpublished database through the fixed production container without placing the production database password in inspect output, dump arguments/environment, or host artifacts. It MUST bound the database-locking dump phase to 60 seconds with an independent exact-backend termination watchdog, and MUST NOT persist a plaintext dump, database password, or repository password in a filesystem artifact, process argument, log, or status document. The cadence is an attempt schedule and MUST NOT be represented as guaranteed daily success.

#### Scenario: Daily backup succeeds
- **WHEN** production PostgreSQL is reachable and the encrypted repository is healthy
- **THEN** the job streams a custom-format dump into a restic snapshot and records its exact non-secret identity, source revision, integrity time, and successful attempt time

#### Scenario: Dump, repository, or publication fails
- **WHEN** dump creation, repository write, snapshot identification, integrity verification, or status publication fails
- **THEN** the job exits nonzero with a stable redacted result code, preserves the previous complete status and last-known-good evidence as applicable, and advances no unverified success freshness

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

### Requirement: Weekly restore drills use an exact snapshot and disposable PostgreSQL
A weekly root-owned drill MUST restore the exact last integrity-checked snapshot into a disposable PostgreSQL 16 instance that shares no production container name, network, volume, host port, or database credential. It MUST restore data and schema with owner and ACL replay disabled, because code-owned deployment bootstrap remains the role/privilege authority. It MUST validate the versioned schema manifest, constraints, critical tables, and data invariants inside read-only transactions without starting application schema bootstrap, and MUST NOT claim to validate MCP role or ACL recovery.

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
Systemd services and timers MUST be root-owned, use fixed installed entrypoints without embedded secrets, remain disabled by installation, and be enabled only after a manual backup and restore drill succeed. The change MUST NOT expand `github-runner` sudo authority.

#### Scenario: Units are installed before the first drill
- **WHEN** the operator installs commands, profiles, services, and timers
- **THEN** no scheduled backup or restore begins until the operator explicitly enables the timers

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
