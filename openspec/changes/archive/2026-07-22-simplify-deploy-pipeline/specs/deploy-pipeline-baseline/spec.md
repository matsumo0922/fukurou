## ADDED Requirements

### Requirement: Main merge deploys without re-running quality gates
A push to `main` MUST resolve the pushed commit SHA and proceed directly to image build and NAS deploy without re-running JVM tests, static analysis, or any deploy-time approval gate. A `workflow_dispatch` invocation MUST accept an explicit target SHA that exists on `main`'s history and deploy it the same way.

#### Scenario: Automatic push deploys immediately
- **WHEN** a commit is pushed to `main`
- **THEN** the workflow resolves that commit SHA and proceeds to build and deploy without a quality job or approval gate

#### Scenario: Manual dispatch targets an explicit SHA
- **WHEN** an operator triggers `workflow_dispatch` with a SHA that exists on `main`'s history
- **THEN** the workflow resolves that SHA and deploys it through the same build/deploy path as an automatic push

#### Scenario: Manual dispatch targets a SHA absent from main
- **WHEN** an operator triggers `workflow_dispatch` with a SHA that is not reachable from `main`
- **THEN** the resolve step fails before image build

### Requirement: Automatic push rejects a non-descendant target
For a `push` event only, the target SHA MUST be a descendant of (or identical to) the revision currently running in production, verified authoritatively by the NAS executor under the production deploy lock immediately before production mutation begins. The `resolve` job MAY perform the same check early as a fast-fail optimization, but the workflow-side check alone is not authoritative (it runs before image build and cannot observe a revision change that happens concurrently). This check does not apply to `workflow_dispatch` (including intentional past-SHA redeploys).

#### Scenario: Push target descends from the running revision
- **WHEN** an automatic push resolves a target SHA that is a descendant of the currently running production revision
- **THEN** both the early `resolve`-job check and the executor's lock-protected check succeed, and deploy proceeds

#### Scenario: Concurrent pushes complete out of order
- **WHEN** two pushes to `main` (A older, B newer) are built concurrently, B is deployed first, and A's build later reaches the NAS executor
- **THEN** the executor observes the production revision is now B (not A's ancestor) after acquiring the deploy lock, and rejects A before any migration or compose mutation — even though A's early `resolve`-job check may have already passed before B was deployed

#### Scenario: Manual dispatch bypasses the descendant check
- **WHEN** an operator triggers `workflow_dispatch` with any SHA reachable from `main`, including one older than the currently running revision
- **THEN** neither the `resolve`-job nor the executor's lock-protected check applies; only SHA existence on `main`'s history is verified

### Requirement: NAS executor deploys via pull, migration, compose up, and health check
The NAS-side executor MUST perform, in order: pull the candidate image by immutable digest, run database migration (delegating to `fukurou-deploy-db`), bring up the production compose stack, and confirm application health before completing. The executor MUST NOT perform bundle signature verification, capability catalog validation, schema-sensitive diff classification, or CLI acceptance preflight.

#### Scenario: Deploy completes successfully
- **WHEN** the executor pulls the candidate image, runs migration, and `docker compose up -d` succeeds
- **THEN** the executor polls `/health/live` and `/health/ready` until they succeed or the deploy deadline elapses, then confirms `/revision` matches the deployed target before exiting successfully

#### Scenario: Health check fails
- **WHEN** `/health/live` or `/health/ready` does not succeed before the deploy deadline
- **THEN** the executor exits with failure and does not attempt automatic recovery; the running production state is left as-is for operator inspection

#### Scenario: Compose up fails
- **WHEN** `docker compose up -d` fails
- **THEN** the executor exits with failure without attempting automatic rollback

### Requirement: Image is pulled and verified by immutable digest
The build job MUST output the pushed image's digest, and the deploy job MUST pass that digest (not a mutable tag) to the executor. The executor MUST pull `<image>@<digest>` and MUST verify the running container's digest matches after `docker compose up -d`.

#### Scenario: Digest matches after cutover
- **WHEN** the executor pulls the image by digest and brings the compose stack up
- **THEN** it confirms the running container's image digest equals the digest passed from the build job before reporting success

#### Scenario: Tag is overwritten between build and pull
- **WHEN** a mutable tag associated with the build is overwritten by another process before the executor pulls
- **THEN** pulling by digest is unaffected because the executor never resolves the tag itself

### Requirement: Launches are paused during migration and compose cutover
The executor MUST perform, in strict order: disable new launches, wait for in-flight launches to drain, record an OPEN infrastructure gap event, then run migration and compose cutover. Only after `docker compose up -d` succeeds and health checks pass MUST the executor resume launches and record a CLOSE gap event referencing that same gap ID. Before disabling launches, the executor MUST write a durable paused-state marker recording: a deployment ID, the target SHA, the expected image digest, the current maintenance generation counter, the gap ID, the target SHA/digest for which migration last completed (if any), and a phase field (`PAUSED_BEFORE_MIGRATION`, `MIGRATION_DONE`, `CUTOVER_STARTED`, `CUTOVER_HEALTHY_PENDING_CLOSE`, or `ACKNOWLEDGED_FOR_REDEPLOY`). The gap ID and maintenance generation identify a single continuous incident (launches have been disabled without interruption since the gap opened) and MUST be preserved across every phase transition until the gap is actually CLOSEd — no code path may clear the marker or drop the gap ID while the gap is still OPEN.

#### Scenario: Launches pause before migration
- **WHEN** the executor reaches the migration step
- **THEN** it writes the paused-state marker with phase `PAUSED_BEFORE_MIGRATION` and a fresh gap ID, disables new launches, waits for in-flight launches to drain, and records an OPEN gap event before invoking `fukurou-deploy-db`

#### Scenario: Launches resume after successful cutover
- **WHEN** compose up succeeds and health checks pass
- **THEN** the executor resumes launches, records a CLOSE gap event referencing the marker's gap ID, and only then clears the paused-state marker

#### Scenario: Deploy fails after launches are paused
- **WHEN** migration or compose up fails after launches were disabled
- **THEN** launches remain disabled, the gap event remains OPEN, the paused-state marker is left in place with its current phase and gap ID, and the executor reports failure for operator inspection rather than silently resuming against a possibly inconsistent state

#### Scenario: Executor process is killed or the NAS restarts before cutover is healthy
- **WHEN** the executor process is killed (e.g. SIGKILL, NAS reboot) while the paused-state marker's phase is `PAUSED_BEFORE_MIGRATION`, `MIGRATION_DONE`, or `CUTOVER_STARTED`
- **THEN** the next executor invocation reads the marker, refuses to start any new deploy attempt, and requires an explicit `--acknowledge-paused-state <deployment-id>` operator action naming the exact deployment ID before proceeding; acknowledging transitions the marker's phase to `ACKNOWLEDGED_FOR_REDEPLOY` — it MUST NOT clear the marker, delete the gap ID, or resume launches. The gap remains OPEN and launches remain disabled

#### Scenario: A fresh deploy attempt follows an acknowledged pause for the same target
- **WHEN** an operator triggers a new deploy for the SAME target SHA/digest recorded as "migration last completed for" in the marker, while the marker's phase is `ACKNOWLEDGED_FOR_REDEPLOY`
- **THEN** the executor adopts the existing gap ID and maintenance generation, updates the deployment ID, sets phase to `MIGRATION_DONE`, skips re-running migration, and proceeds directly to compose cutover — skipping a redundant launch-disable/drain since launches are already disabled

#### Scenario: A fresh deploy attempt follows an acknowledged pause for a different target
- **WHEN** an operator triggers a new deploy for a target SHA/digest that does NOT match the marker's "migration last completed for" value (including when no migration had completed yet), while the marker's phase is `ACKNOWLEDGED_FOR_REDEPLOY`
- **THEN** the executor adopts the existing gap ID and maintenance generation (it does NOT open a new gap, since launches have remained continuously disabled since the original incident), updates the marker's deployment ID and target SHA/expected digest to the new attempt, sets phase to `PAUSED_BEFORE_MIGRATION`, and re-runs migration for the new target before cutover — it MUST NOT skip migration on the assumption that a prior, different target's completed migration still applies

#### Scenario: A fresh deploy attempt after acknowledgment eventually succeeds
- **WHEN** a deploy that adopted an existing gap ID (per the previous scenario) completes successfully
- **THEN** the executor resumes launches and records a CLOSE gap event referencing the ORIGINAL gap ID (not a new one), so the infrastructure gap's duration spans the full incident from the first pause to the eventual successful cutover, then clears the marker

#### Scenario: Health succeeds but resume is interrupted before CLOSE is recorded
- **WHEN** the executor restarts and finds the paused-state marker's phase is `CUTOVER_HEALTHY_PENDING_CLOSE`
- **THEN** it re-verifies that the running container's digest equals the marker's expected image digest and that health checks currently pass; if both hold, it treats resume as idempotent (does not re-run migration or compose up), resumes launches, records the CLOSE gap event referencing the marker's gap ID, and clears the marker without requiring operator acknowledgement; if either check fails, it falls back to the "killed before cutover is healthy" scenario (refuses to start a new deploy, requires `--acknowledge-paused-state`)

### Requirement: DB helper version is verified before migration
The DB helper marker MUST be computed deterministically as follows: list `fukurou-deploy-db` and every SQL file it can execute (`scripts/deploy/sql/**/*.sql`, covering foundation and index migrations) by relative path, sort the paths byte-wise under `LC_ALL=C`, then build a manifest by concatenating, for each file in that order, `path + NUL + sha256(content) + NUL`; the marker is the SHA-256 of the resulting manifest. The candidate image MUST embed the expected marker at build time, computed by this same algorithm from the exact file set at the build commit. Before invoking `fukurou-deploy-db`, the executor MUST **recompute the marker from the actual root-installed files on the NAS at deploy time** (not merely reuse a value stored at a previous install) and MUST fail closed if that recomputed value disagrees with either the value recorded at root-install time or the value embedded in the candidate image.

#### Scenario: Versions match
- **WHEN** the executor recomputes the marker from the actual root-installed `fukurou-deploy-db`/SQL files and it equals both the value recorded at install time and the marker embedded in the candidate image
- **THEN** the executor proceeds to invoke `fukurou-deploy-db`

#### Scenario: Versions disagree
- **WHEN** the recomputed marker does not equal the marker embedded in the candidate image
- **THEN** the executor fails before invoking `fukurou-deploy-db` or pausing launches

#### Scenario: Root installation was partially applied or altered after install
- **WHEN** the recomputed marker does not equal the value recorded at root-install time (indicating a partial install or a post-install file change), even if it happens to match the candidate's expected value
- **THEN** the executor fails before invoking `fukurou-deploy-db`, because the on-disk state cannot be trusted to match what was atomically installed

#### Scenario: DB helper or SQL changes without a root install
- **WHEN** a main commit changes `fukurou-deploy-db` or any covered SQL file but the NAS root installation has not been updated to match
- **THEN** the recomputed marker mismatch is detected and migration is blocked, rather than silently running against stale root-installed artifacts

### Requirement: Schema migration applies automatically after a pre-migration backup
Before invoking `fukurou-deploy-db` to install foundation and index migrations, the executor MUST take a PostgreSQL backup (via the existing restic-based `scripts/backup/` mechanism, invoked in a mode that is safe to call while the executor holds the production deploy lock) as the only safety step, then apply the migration unconditionally. The executor MUST NOT attempt to classify the diff as schema-sensitive or select a migration compatibility mode.

#### Scenario: Migration runs after backup
- **WHEN** a deploy reaches the migration step
- **THEN** the executor first triggers a PostgreSQL backup and then runs `fukurou-deploy-db install-foundation`/`install-indexes` regardless of whether the diff touches schema-sensitive paths

#### Scenario: Backup fails
- **WHEN** the pre-migration backup step fails
- **THEN** the executor does not proceed to migration or compose cutover

#### Scenario: Backup is invoked while the deploy lock is held
- **WHEN** the executor invokes backup from within its own deploy-lock-holding process
- **THEN** the backup entrypoint skips its own deploy-lock acquisition check instead of failing with a lock-contention error

### Requirement: Past-SHA redeploy does not restore the database
A `workflow_dispatch` redeploy of a past SHA changes only the running application image; it MUST NOT attempt to restore database state. If a schema-sensitive migration was applied by a later revision, redeploying an older SHA runs older application code against the newer schema.

#### Scenario: Redeploy after an incompatible migration
- **WHEN** an operator redeploys a past SHA after a later revision applied a schema-sensitive migration that is incompatible with the older code
- **THEN** the executor does not restore the database, and recovery requires either a forward fix or a manual restic restore performed outside the deploy pipeline

### Requirement: Rollback is a manual redeploy of a past SHA's application image
An operator MUST be able to respond to a bad deploy by invoking `workflow_dispatch` with a known-good past SHA on `main`'s history, which redeploys that SHA's application image through the same path as a forward deploy — no distinct rollback intent, ancestry validation, or operator-reason field is required. The workflow and executor MUST redeploy the application image ONLY and MUST NOT represent this as a database rollback: per "Past-SHA redeploy does not restore the database", if an incompatible schema-sensitive migration was applied since that SHA last ran, redeploying its image does not undo that migration and does not by itself guarantee recovery.

#### Scenario: Operator redeploys a past SHA after a compatible change
- **WHEN** an operator selects a past known-good SHA where no incompatible schema-sensitive migration occurred since, and triggers `workflow_dispatch`
- **THEN** the workflow builds and deploys that SHA's image through the same path as a forward deploy, without ancestry checks against the currently running revision, and the redeployed application runs correctly against the current schema

#### Scenario: Operator redeploys a past SHA after an incompatible migration
- **WHEN** an operator selects a past SHA whose code is incompatible with a schema-sensitive migration applied since
- **THEN** the image redeploy itself still proceeds (no automated block), but per "Past-SHA redeploy does not restore the database" this does not restore correct behavior, and the operator must follow the forward-fix or manual restic-restore recovery path documented in `docs/deploy.md`

### Requirement: Executor refuses to start unless legacy drain is confirmed by sentinel
On startup, the executor MUST require both: (a) a drain-complete sentinel file at a known path, created by the operator only after confirming the previous (contract v2) executor's journal fully drained to terminal state via that executor's normal recovery path, and (b) that the previous executor's known "active" journal/rollback-state path is empty. Terminal journal/rollback-state history that the operator has archived to a separate audit path (outside the "active" path) MUST NOT cause this check to fail — the check targets unconfirmed/unfinished state, not the presence of any historical record. If either condition is unmet, the executor MUST refuse to start a new deploy and MUST fail closed with a message directing the operator to complete the drain-confirmation procedure documented in `docs/deploy.md`.

#### Scenario: Sentinel present and active path empty
- **WHEN** the executor starts, finds the drain-complete sentinel, and finds the legacy "active" journal/rollback-state path empty
- **THEN** it proceeds with the deploy, regardless of any archived terminal history that may exist elsewhere

#### Scenario: Sentinel missing
- **WHEN** the executor starts and the drain-complete sentinel does not exist
- **THEN** it refuses to start the deploy and fails closed, even if the active path happens to be empty

#### Scenario: Active path is not empty despite the sentinel existing
- **WHEN** the executor starts, finds the sentinel, but the legacy "active" journal/rollback-state path still contains a file
- **THEN** it refuses to start the deploy and fails closed, requiring explicit operator investigation before any future deploy can proceed

### Requirement: Sudoers boundary remains restricted to the deploy executor
The `github-runner` account MUST retain sudo access only to `/usr/local/sbin/deploy-fukurou` and MUST NOT receive direct Docker or root privileges.

#### Scenario: Sudoers configuration is inspected
- **WHEN** the NAS sudoers configuration for `github-runner` is reviewed
- **THEN** it permits `sudo` execution of `/usr/local/sbin/deploy-fukurou` only, with no other command or NOPASSWD Docker/root grant
