## REMOVED Requirements

### Requirement: Signed deploy intent is closed by workflow event
**Reason**: owner の要求（main マージ = 承認、手動承認ゲート撤廃）により、署名つき deploy intent（FORWARD/AUTHORIZED_ROLLBACK 区分、bounded operator reason、migration rollback mode、schema-sensitive inventory hash）は不要になった。deploy.yml の `resolve` job は SHA 検証のみを行う
**Migration**: `deploy-pipeline-baseline` capability が「resolve は要求された SHA が main 上に存在することだけを検証する」という簡素な挙動を定義する

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
**Reason**: 署名つき FORWARD/AUTHORIZED_ROLLBACK 区分・reason 必須化・NAS 側 executor での ancestry 検証は撤去する。owner が求める「素朴な past-SHA 再デプロイ」運用とは両立しないため
**Migration**: `deploy-pipeline-baseline` capability の「Automatic push rejects a non-descendant target」Requirement が、automatic push（`push` イベント）限定で同等の逆行防止を引き継ぐ（falsifier 指摘 F-2 対応）。`workflow_dispatch`（past-SHA 再デプロイを含む手動操作）は ancestry を検証せず、指定 SHA を信頼して deploy する

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
- **WHEN** no application container exists, the read-only DB snapshot is `PRE_FOUNDATION`, and no published deployment directory exists
- **THEN** a signed `FORWARD` target reachable from main may continue without a current revision

#### Scenario: Existing installation lost its container
- **WHEN** no application container exists but DB foundation state or published deployment history shows a prior installation
- **THEN** the executor rejects the request as unknown current revision rather than treating it as fresh

### Requirement: Authorized rollback is explicit and ancestral
**Reason**: `AUTHORIZED_ROLLBACK` という特別な intent 区分と strict ancestor 検証を撤去する。過去 SHA への再デプロイは通常の deploy と同じ経路で扱う
**Migration**: `deploy-pipeline-baseline` capability の rollback 運用（workflow_dispatch で SHA を指定して再デプロイ）に置き換わる。operator が指定 SHA の正しさを自分で確認する運用とする

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
**Reason**: 7/21 以降このゲートが main の自動デプロイを全件拒否している（`SCHEMA_SENSITIVE_MODE_MISMATCH`）。owner はこの手動承認ゲートの撤廃を明示的に要求している。安全網は既存の restic backup に委ねる
**Migration**: `deploy-pipeline-baseline` capability が「migration 前に backup を取得し、無条件で migration を適用する」という挙動を定義する

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

#### Scenario: Workflow comparison base is unavailable
- **WHEN** push `before` is zero, malformed, unavailable, or not ancestral, or the event is manual dispatch
- **THEN** early classification is skipped without becoming an admission decision and root reclassification remains mandatory

#### Scenario: Inventory artifacts disagree
- **WHEN** the signed inventory hash, repository inventory, and root-installed inventory do not all agree
- **THEN** the executor rejects the bundle before candidate mutation

### Requirement: Deploy evidence and recovery remain durable
**Reason**: journal ベースの evidence/recovery state machine は、撤去する署名検証・revision monotonicity・schema-sensitive ゲートの状態を追跡するために存在していた。それらのゲートが無くなるため、journal が保護する状態遷移の大半が対象を失う
**Migration**: simple executor は compose up 失敗時に fail してログに残すのみとし、自動 recovery は行わない。中断からの復旧は再デプロイという手動運用に委ねる。ただし migration・compose cutover 中の launch disable/drain は `deploy-pipeline-baseline` capability の「Launches are paused during migration and compose cutover」Requirement に引き継ぐ（falsifier 指摘 F-5 対応、journal の状態遷移表そのものは持たない）。新 executor への切替は、旧 executor の unfinished journal が完全に drain され、maintenance/fence/gap state が正常であることを owner が確認してから行う（falsifier 指摘 F-4 対応、design.md の Migration Plan を参照）

For every accepted non-fresh deploy, the executor MUST persist validated intent, reason, current and target revisions, migration mode, inventory hash, schema-sensitive result, and the probed candidate image digest in the root-only rollback state and first current-format journal entry. V2 recovery MUST cross-check the state, first journal entry, and installed inventory, and MUST use only the interrupted deployment's persisted candidate digest for recovery operations. Existing digest, revision, liveness, readiness, journal CAS, and bounded recovery checks MUST remain authoritative.

#### Scenario: Candidate succeeds at exact identity
- **WHEN** intent admission succeeds and candidate digest, revision, liveness, and readiness agree before the deadline
- **THEN** the executor records the existing successful terminal with the accepted deploy evidence retained

#### Scenario: Compatible candidate fails
- **WHEN** a candidate with `AUTO_IMAGE_ROLLBACK` or `BACKWARD_COMPATIBLE` fails after rollback capture
- **THEN** the existing durable recovery may restore the captured previous image and records the terminal result

#### Scenario: Roll-forward-only candidate fails before safety mutation
- **WHEN** a candidate with `ROLL_FORWARD_ONLY` fails in `PREPARED` or `CAPTURED` before safety mutation begins
- **THEN** the executor leaves the running production, repository checkout, maintenance, and fence unchanged, records `CANDIDATE_ABORTED`, and does not start a replacement image

#### Scenario: Roll-forward-only candidate fails after safety mutation
- **WHEN** a candidate with `ROLL_FORWARD_ONLY` fails at or after `SAFETY_MUTATION_STARTED`
- **THEN** the executor records `MANUAL_RECOVERY_REQUIRED` only after re-establishing the maintenance/fence/OPEN-gap safe boundary and revoking the canary; otherwise it leaves retryable unfinished recovery and does not restore the previous image or database

#### Scenario: Roll-forward-only recovery resumes after restart
- **WHEN** startup finds a non-terminal `ROLL_FORWARD_ONLY` journal
- **THEN** it applies the same origin-state policy as live recovery and never falls through to previous image startup

#### Scenario: V2 recovery evidence is incomplete or inconsistent
- **WHEN** state version, accepted evidence, first journal details, installed inventory hash, or interrupted candidate digest is missing, invalid, or inconsistent
- **THEN** recovery remains non-terminal and fails closed without applying legacy automatic rollback or using the incoming bundle image

#### Scenario: Repository checkout differs from the running revision
- **WHEN** a previous roll-forward-only failure left repository HEAD at another revision
- **THEN** rollback compose is captured from the accepted running revision Git object, the signed target compose is snapshot and render-validated before mutation, and candidate preflight uses the unchanged production compose with the deny overlay

#### Scenario: Post-deploy identity disagrees
- **WHEN** running digest or `/revision` differs from the signed target, or readiness does not succeed within the deadline
- **THEN** the executor never records success and applies the signed recovery mode

### Requirement: Contract v2 rollout fails closed
**Reason**: contract v2 は bundle 署名・capability catalog・schema-sensitive inventory の整合性を保証する仕組みであり、それらの撤去に伴い contract version の概念自体が不要になる
**Migration**: simple executor は contract version を持たない。workflow と NAS 側 executor の整合は「同じ PR で両方を変更し、owner が配置手順に従って両方を更新する」という運用で担保する

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

#### Scenario: Application or workflow rolls back after v2 rollout
- **WHEN** application code or workflow behavior must return to a previous version after any v2 deployment state has been created
- **THEN** operations MUST retain the contract v2 executor and v2 audit history, complete recovery with v2, and MUST NOT invoke the v1 executor

#### Scenario: Root executor downgrade is requested
- **WHEN** an operator proposes replacing contract v2 with the v1 executor
- **THEN** the downgrade is unsupported by this change and requires a separate compatibility design that can parse retained v2 terminal and non-terminal history

### Requirement: Every target retains exact-SHA quality enforcement
**Reason**: deploy 時点の quality enforcement は撤去し、PR 時点（Stage 1 の `pr-quality-gate`）に一本化する
**Migration**: `pr-quality-gate` capability が PR マージ前に quality を担保する。マージ後の再検証は行わない

The workflow MUST require the exact-target JVM quality gate for forward and authorized rollback targets and MUST NOT provide a quality bypass.

#### Scenario: Historical target fails current quality
- **WHEN** an authorized historical target fails `make test` or `make detekt` in the current CI environment
- **THEN** no image or signed deploy bundle is published and recovery requires quality repair or a quality-passing forward fix
