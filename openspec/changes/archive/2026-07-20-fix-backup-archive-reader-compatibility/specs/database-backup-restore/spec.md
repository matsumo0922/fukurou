## MODIFIED Requirements

### Requirement: Production PostgreSQL receives scheduled encrypted logical backup attempts
A root-owned timer MUST attempt a PostgreSQL 16 custom-format logical backup once per calendar day and store successful snapshots only in an encrypted same-NAS restic repository. It MUST reach the host-unpublished database through the fixed production container without placing the production database password in inspect output, dump arguments/environment, or host artifacts. After capturing the production PostgreSQL container ID, identity reads, database control, the dump producer, and the archive-list reader MUST execute in that captured container; a host-installed PostgreSQL client MUST NOT determine archive compatibility. It MUST bound the database-locking dump phase to 60 seconds with an independent exact-backend termination watchdog, and MUST NOT persist a plaintext dump, database password, or repository password in a filesystem artifact, process argument, log, or status document. The cadence is an attempt schedule and MUST NOT be represented as guaranteed daily success.

The Docker-backed integration contract MUST execute the production backup entrypoint itself against real PostgreSQL 16 and restic, MUST prove that host `pg_restore` absence or incompatibility cannot affect the entrypoint, and MUST cover its retention prune and redacted output rather than reproducing the orchestration as test-only commands.

#### Scenario: Daily backup succeeds
- **WHEN** production PostgreSQL 16 is reachable and the encrypted repository is healthy
- **THEN** the job streams a custom-format dump into a restic snapshot, validates the archive with the captured production container's PostgreSQL 16 reader, and records its exact non-secret identity, source revision, integrity time, and successful attempt time

#### Scenario: Host PostgreSQL client is absent or incompatible
- **WHEN** the NAS host has no `pg_restore` or has a client that cannot parse the PostgreSQL 16 custom archive
- **THEN** production archive validation remains independent of that host client and uses the captured production PostgreSQL 16 container

#### Scenario: Dump, repository, or publication fails
- **WHEN** dump creation, repository write, snapshot identification, integrity verification, or status publication fails
- **THEN** the job exits nonzero with a stable redacted result code, preserves the previous complete status and last-known-good evidence as applicable, and advances no unverified success freshness

#### Scenario: Production identity changes after the deploy-lock probe
- **WHEN** the PostgreSQL container, application container, database identity, or application revision differs between the pre-dump and post-dump observations
- **THEN** the attempt does not advance integrity-checked success evidence or attribute the snapshot to the earlier application revision

#### Scenario: Production container name is replaced and restored during an attempt
- **WHEN** the production container name points to another container and later returns to the originally captured container
- **THEN** identity reads, database control, dump production, and archive-list validation continue to use only the captured container ID and do not mix data paths

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
The backup job MUST retain the newest fourteen daily generations within the fixed production-backup AND integrity-checked tag predicate, host, and stdin path group. It MUST encode the tag conjunction with restic's single comma-separated AND filter rather than multiple OR filters. It MUST complete repository structure verification and a full `restic dump` read while independently validating the custom-archive list with `pg_restore` from the captured production PostgreSQL 16 container before destructive prune. A retention failure MUST remain visible without erasing the identity or freshness of an otherwise integrity-checked snapshot, and only an actual isolated restore drill SHALL establish recoverability.

#### Scenario: More than fourteen daily generations exist
- **WHEN** a new snapshot is identified and repository and custom-archive structure verification succeed
- **THEN** the job keeps the newest fourteen daily generations and prunes older data

#### Scenario: Repository integrity is uncertain
- **WHEN** integrity verification fails, times out, or is interrupted
- **THEN** the job does not invoke destructive retention, reports an integrity failure, and preserves last-known-good evidence

#### Scenario: Archive list validation finishes before the snapshot stream
- **WHEN** container-side `pg_restore --list` reaches the archive table of contents before `restic dump` reaches end of stream
- **THEN** the consumer drains the remaining bytes, preserves the list result separately, and requires both the list result and full producer stream to succeed

#### Scenario: Captured archive reader is unavailable after dump
- **WHEN** the captured production container stops or its `pg_restore --list` fails before the candidate is tagged
- **THEN** the job reports integrity failure, performs no destructive retention, and advances no success evidence

#### Scenario: Retention fails after a verified snapshot
- **WHEN** retention or prune fails after the new snapshot passed integrity verification
- **THEN** the current attempt reports housekeeping failure while the integrity-checked snapshot remains recorded without claiming an actual restore succeeded

#### Scenario: Integrity tagging changes snapshot identity
- **WHEN** a candidate passes full-stream verification and restic adds the integrity-checked tag
- **THEN** the job obtains the tag operation's new authoritative snapshot ID and publishes only that ID for retention and restore selection

#### Scenario: A previous attempt left an unclassified candidate
- **WHEN** the repository contains an attempt-tagged snapshot without the integrity-checked tag from an earlier interrupted process
- **THEN** automation excludes it from retention and reports its aggregate count without deleting it, and the runbook requires full-stream evidence review before manual forget and checked prune
