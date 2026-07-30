## MODIFIED Requirements

### Requirement: Production PostgreSQL receives scheduled encrypted logical backup attempts

A root-owned timer MUST attempt a PostgreSQL 16 custom-format logical backup once per calendar day and store successful snapshots only in an encrypted same-NAS restic repository. It MUST reach the host-unpublished database through the fixed production container without placing the production database password in inspect output, dump arguments/environment, or host artifacts. After capturing the production PostgreSQL container ID, identity reads, database control, the dump producer, and the archive-list reader MUST execute in that captured container; a host-installed PostgreSQL client MUST NOT determine archive compatibility. Time-bounding of the backup job is owned by its invokers (the deploy executor's `timeout` wrapper and the systemd unit's `TimeoutStartSec`); the job itself MUST NOT terminate database backends. It MUST NOT persist a plaintext dump, database password, or repository password in a filesystem artifact, process argument, log, or status document; the restic local cache holds only encrypted repository data and is permitted. The cadence is an attempt schedule and MUST NOT be represented as guaranteed daily success.

The Docker-backed integration contract MUST execute the production backup entrypoint itself against real PostgreSQL 16 and restic, MUST prove that host `pg_restore` absence or incompatibility cannot affect the entrypoint, and MUST cover its retention prune and redacted output rather than reproducing the orchestration as test-only commands.

#### Scenario: Daily backup succeeds

- **WHEN** production PostgreSQL 16 is reachable and the encrypted repository is healthy
- **THEN** the job streams a custom-format dump into a restic snapshot, validates the archive with the captured production container's PostgreSQL 16 reader, and records its exact non-secret identity, source revision, integrity time, and successful attempt time

#### Scenario: Host PostgreSQL client is absent or incompatible

- **WHEN** the NAS host has no `pg_restore` or has a client that cannot parse the PostgreSQL 16 custom archive
- **THEN** production archive validation remains independent of that host client and uses the captured production PostgreSQL 16 container

#### Scenario: Dump, repository, or publication fails

- **WHEN** dump creation, repository write, snapshot identification, integrity verification, or status publication fails
- **THEN** the job exits nonzero with a stable redacted result code, emits the underlying tool stderr for diagnosis without leaking the database or repository password, preserves the previous complete status and last-known-good evidence as applicable, and advances no unverified success freshness

#### Scenario: Production identity changes after the deploy-lock probe

- **WHEN** the PostgreSQL container, application container, database identity, or application revision differs between the pre-dump and post-dump observations
- **THEN** the attempt does not advance integrity-checked success evidence or attribute the snapshot to the earlier application revision

#### Scenario: Production container name is replaced and restored during an attempt

- **WHEN** the production container name points to another container and later returns to the originally captured container
- **THEN** identity reads, database control, dump production, and archive-list validation continue to use only the captured container ID and do not mix data paths

#### Scenario: Dump producer fails after restic accepted partial input

- **WHEN** `pg_dump` fails or is killed by the invoker's timeout after restic created a snapshot from partial stdin
- **THEN** the job detects the producer failure independently, forgets the attempt-tagged partial snapshot without prune when it can identify it, performs no integrity or retention operation, and never records it as successful evidence

#### Scenario: Backup runs against an accumulated repository

- **WHEN** the restic repository has accumulated snapshots from prior attempts
- **THEN** the backup pipeline uses the restic local cache so that repository index reads do not stall stdin consumption, and the dump producer is not terminated by any component of the backup job itself

#### Scenario: Backup capacity floor is not met

- **WHEN** the backup filesystem lacks the configured reserve in addition to the measured production database size
- **THEN** the job fails before dump or repository mutation and records only a stable capacity result

### Requirement: Jobs fail closed on start-time contention

Backup and restore jobs MUST share a non-blocking root lock and MUST probe the production deploy lock before database, repository, or Docker mutation. The contract SHALL cover start-time contention only and MUST NOT claim that a deploy starting after the probe is mutually excluded. A backup that overlaps a later deploy is bounded by its invoker's timeout; killing the host-side dump client closes its database connection, and the server terminates the corresponding backend on connection loss.

#### Scenario: Backup job is already running

- **WHEN** another backup or restore job owns the shared backup lock
- **THEN** the new job exits nonzero with a stable busy result and performs no database, repository, status, or Docker mutation

#### Scenario: Deploy is active at the safety probe

- **WHEN** the production deploy lock is owned when a backup or restore job performs its start-time probe
- **THEN** the job exits nonzero with `DEPLOY_IN_PROGRESS` before reading production data or mutating repository or Docker resources

#### Scenario: Deploy starts after the probe

- **WHEN** the deploy lock was free at the probe and a deploy starts later
- **THEN** the backup contract does not report full mutual exclusion, the backup job's lifetime remains bounded by its invoker's timeout, and any resulting partial snapshot or child failure remains failed evidence without destructive retention

## REMOVED Requirements

### Requirement: Watchdog-bounded dump phase（旧 Requirement 内の記述として削除）

理由: dump phase の 60 秒 bound と independent exact-backend termination watchdog は、deploy executor の `timeout 900` および systemd `TimeoutStartSec=20min` と二重の時間制限を構成し、2026-07-30 に restic `--no-cache` の index 読み込み背圧と複合して健全な pg_dump を誤終了させ、main の全デプロイを停止させた（issue #336）。single-owner 構成で backend を秒単位で強制終了すべき脅威は存在せず、Epic #286 の線引きに従い撤去する。これに伴い旧 Scenario「Host-side dump client cannot stop the database backend」「Dump completes before the watchdog deadline」「A watchdog control query hangs」を削除する。
