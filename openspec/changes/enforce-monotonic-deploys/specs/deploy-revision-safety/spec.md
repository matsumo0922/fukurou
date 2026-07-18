## ADDED Requirements

### Requirement: Signed deploy intent is closed by workflow event
The deploy workflow MUST issue bundle schema v2 with a signed workflow event, deploy intent, bounded operator reason, migration rollback mode, and schema-sensitive inventory hash. Automatic push MUST issue only `FORWARD` with `AUTO_IMAGE_ROLLBACK`; a historical target MUST be issued only by manual dispatch as `AUTHORIZED_ROLLBACK` with a non-empty validated reason.

#### Scenario: Automatic main push
- **WHEN** a main push resolves a target commit
- **THEN** the signed bundle carries `push`, `FORWARD`, an empty reason, and `AUTO_IMAGE_ROLLBACK`

#### Scenario: Manual historical target
- **WHEN** manual dispatch resolves a main ancestor older than the current main tip and supplies a valid non-empty reason
- **THEN** the signed bundle carries `workflow_dispatch` and `AUTHORIZED_ROLLBACK`

#### Scenario: Invalid intent source or reason
- **WHEN** a push attempts authorized rollback, a historical manual target has an empty reason, or an explicit migration mode lacks a valid reason
- **THEN** the workflow or root executor rejects the request before production mutation

### Requirement: Forward deploys preserve revision monotonicity
The root deploy executor MUST verify revision ancestry under the production deploy lock after unfinished recovery and before candidate production mutation. A `FORWARD` deploy MUST accept only a fresh install, an identical revision, or a target descended from the currently running valid revision.

#### Scenario: Newer main revision is deployed
- **WHEN** the observed current revision is an ancestor of the signed target and intent is `FORWARD`
- **THEN** the executor records the revision pair and may continue

#### Scenario: Queued older revision reaches the lock
- **WHEN** a `FORWARD` target is older than the valid revision running when the executor obtains the lock
- **THEN** the executor rejects it before rollback capture, maintenance, fence, database, or compose mutation

#### Scenario: Revision authority is unavailable
- **WHEN** an existing container revision is missing or malformed, either commit is unavailable, or the revisions diverge
- **THEN** the executor fails closed with a stable non-secret reason before candidate mutation

#### Scenario: Fresh installation
- **WHEN** no application container exists and the read-only foundation/bootstrap evidence proves a fresh installation
- **THEN** a signed `FORWARD` target reachable from main may continue without a current revision

### Requirement: Authorized rollback is explicit and ancestral
The root deploy executor MUST accept `AUTHORIZED_ROLLBACK` only for a manual event with a valid non-empty reason and a target that is a strict ancestor of the observed current revision.

#### Scenario: Operator authorizes an older main revision
- **WHEN** a manual signed request targets a strict ancestor of current with `AUTHORIZED_ROLLBACK` and a valid reason
- **THEN** the executor records intent, reason, and revision pair and may continue

#### Scenario: Rollback target is not older
- **WHEN** an authorized rollback target equals current, descends from current, diverges, or is not on main
- **THEN** the executor rejects it before candidate mutation

#### Scenario: Bundle intent is tampered
- **WHEN** event, intent, reason, target, migration mode, or inventory hash differs from the signed canonical bundle
- **THEN** signature or schema validation rejects it before lock-protected mutation

### Requirement: Schema-sensitive diff requires explicit compatibility
The workflow and root executor MUST classify changes using the same hash-bound code-owned path inventory. The root executor MUST use the observed current-to-target diff as authority and MUST reject a schema-sensitive diff carrying `AUTO_IMAGE_ROLLBACK` or a non-sensitive diff carrying an explicit migration mode.

#### Scenario: No schema-sensitive path changes
- **WHEN** the actual current-to-target diff contains no inventory match and the signed mode is `AUTO_IMAGE_ROLLBACK`
- **THEN** existing automatic image rollback remains available

#### Scenario: Additive migration is reviewed as compatible
- **WHEN** the actual diff is schema-sensitive and manual dispatch signs `BACKWARD_COMPATIBLE` with a valid reason
- **THEN** candidate failure may use the existing durable previous-image rollback without reverting database history

#### Scenario: Migration is roll-forward only
- **WHEN** the actual diff is schema-sensitive and manual dispatch signs `ROLL_FORWARD_ONLY` with a valid reason
- **THEN** candidate failure MUST NOT start the previous image and MUST retain a durable manual-recovery-required safe boundary

#### Scenario: Workflow comparison misses a queued diff
- **WHEN** the workflow comparison base omits a schema-sensitive change that exists between observed production current and target
- **THEN** the root executor reclassification rejects `AUTO_IMAGE_ROLLBACK` before candidate mutation

#### Scenario: Inventory artifacts disagree
- **WHEN** the signed inventory hash, repository inventory, and root-installed inventory do not all agree
- **THEN** the executor rejects the bundle before candidate mutation

### Requirement: Deploy evidence and recovery remain durable
For every accepted non-fresh deploy, the executor MUST persist validated intent, reason, current and target revisions, migration mode, inventory hash, and schema-sensitive result in the root-only rollback state and first current-format journal entry. Existing digest, revision, liveness, readiness, journal CAS, and bounded recovery checks MUST remain authoritative.

#### Scenario: Candidate succeeds at exact identity
- **WHEN** intent admission succeeds and candidate digest, revision, liveness, and readiness agree before the deadline
- **THEN** the executor records the existing successful terminal with the accepted deploy evidence retained

#### Scenario: Compatible candidate fails
- **WHEN** a candidate with `AUTO_IMAGE_ROLLBACK` or `BACKWARD_COMPATIBLE` fails after rollback capture
- **THEN** the existing durable recovery may restore the captured previous image and records the terminal result

#### Scenario: Roll-forward-only candidate fails
- **WHEN** a candidate with `ROLL_FORWARD_ONLY` fails after rollback capture
- **THEN** the executor re-establishes the existing maintenance/fence safe boundary, records `MANUAL_RECOVERY_REQUIRED`, and does not restore the previous image or database

#### Scenario: Post-deploy identity disagrees
- **WHEN** running digest or `/revision` differs from the signed target, or readiness does not succeed within the deadline
- **THEN** the executor never records success and applies the signed recovery mode

### Requirement: Contract v2 rollout fails closed
The v2 workflow MUST require an installed contract v2 executor and matching root-owned schema-sensitive inventory. The executor MUST continue to validate existing v1 journal history for unfinished recovery, while no v2 bundle may execute through a v1 executor.

#### Scenario: Executor has not been pre-installed
- **WHEN** the merged v2 workflow reaches a NAS with contract v1 or mismatched root-owned artifacts
- **THEN** deploy stops before production mutation

#### Scenario: V2 artifacts are pre-installed
- **WHEN** contract v2 executor and inventory from the reviewed PR HEAD are installed with required root ownership and modes
- **THEN** the v2 workflow contract check and signed artifact hashes may pass

#### Scenario: Existing unfinished journal is found
- **WHEN** contract v2 starts with a valid unfinished v1 journal
- **THEN** existing version-aware recovery completes before evaluating the new candidate intent
