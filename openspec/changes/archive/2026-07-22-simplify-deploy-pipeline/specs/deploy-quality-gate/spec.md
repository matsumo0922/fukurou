## REMOVED Requirements

### Requirement: Target commit quality gates image publication
**Reason**: JVM quality gating moves from deploy-time to PR-time. Stage 1（`add-pr-ci-workflow` change、`pr-quality-gate` capability）が `make test`/`make detekt` を pull_request で実行するため、deploy.yml が main マージ後に再度品質ゲートを行う必要がなくなった。owner の要求（main マージ = 承認）により、マージ済みコミットは無条件でデプロイされる
**Migration**: PR 時点の quality gate は `pr-quality-gate` capability（Stage 1）が担う。deploy 時点の quality gate は存在しなくなる

Issue #190 CI DoD stage 1: For automatic main pushes and a workflow_dispatch target equal to the resolved `origin/main` tip, the deploy workflow MUST run the repository JVM test suite and detekt against the exact resolved target commit. It MUST NOT authenticate to GHCR, build or push a production image, create a deploy bundle, or start production deployment until both quality commands and the clean-tree check succeed. Deploy-gated fixtures MUST exercise Linux supervisor semantics on Linux CI and MUST exercise platform-independent gateway path semantics on Linux CI and supported macOS development environments. A platform capability skip MUST NOT be reported as executed contract evidence.

#### Scenario: Push target passes quality
- **WHEN** a main push resolves its commit SHA and `make test`, `make detekt`, and the clean-tree check all succeed at that exact SHA
- **THEN** the workflow may authenticate to GHCR, build/push the immutable image, create the signed bundle, and deploy it

#### Scenario: Latest manual target passes quality
- **WHEN** workflow_dispatch resolves an `image_sha` equal to the current `origin/main` tip and every quality check succeeds at that exact SHA
- **THEN** the image build and deploy stages use the same resolved SHA rather than the workflow ref

#### Scenario: Test or detekt fails
- **WHEN** `make test`, `make detekt`, or the post-detekt clean-tree check fails
- **THEN** no GHCR authentication, production image publication, signed bundle, or NAS deploy occurs for that run

#### Scenario: Quality checkout differs from target
- **WHEN** the quality job checkout HEAD does not exactly equal the resolved target SHA
- **THEN** the quality job fails before running or authorizing image publication

#### Scenario: Build checkout differs from target
- **WHEN** the image build job checkout HEAD does not exactly equal the resolved target SHA
- **THEN** the build fails before GHCR authentication, image publication, or signed bundle creation

#### Scenario: Linux supervisor fixture contract is exercised
- **WHEN** the exact target test suite runs on GitHub-hosted Linux with executable `setsid`
- **THEN** supervisor completion と process-tree exit fixture は Linux process を実際に起動して production contract を検証する
- **AND** shell signal default を成功条件にしない

#### Scenario: macOS lacks the Linux supervisor facility
- **WHEN** the targeted recovery test runs on macOS without executable `/usr/bin/setsid`
- **THEN** the test is recorded as skipped rather than executed supervisor evidence

#### Scenario: Gateway socket branch is exercised on both platforms
- **WHEN** the targeted gateway-start fixture runs on Linux or macOS
- **THEN** production path selection に対する test-only oracle が選ぶ socket path を実際に妨害する
- **AND** standard phase process は起動せず、launch assertion は risk-reduction-only process だけを観測する
- **AND** platform-specific temp path length を成功条件にしない

#### Scenario: Population bound is exercised without transport timeout
- **WHEN** the targeted PostgreSQL fixture runs on Linux or macOS
- **THEN** 同じ period の1件 scoped + 20,001件 scope外に対する正常集計と global oversized rejection を別々に assertion する
- **AND** JDBC transport timeout を成功条件にしない

### Requirement: Historical manual rollback remains available until explicit intent exists
**Reason**: この Requirement は署名 intent（`AUTHORIZED_ROLLBACK`）導入前の一時的な移行措置だった。`deploy-revision-safety` の signed deploy intent 自体を撤去するため、この一時例外も意味を失う
**Migration**: rollback は `deploy-pipeline-baseline` capability の「過去 SHA を workflow_dispatch で指定して再デプロイする」という素朴な運用に置き換わる。historical/latest の区別や quality gate のスキップ判定は行わない（quality gate 自体が deploy.yml から撤去されるため）

Stage 1 MUST preserve the existing workflow_dispatch recovery path for a target SHA older than the resolved `origin/main` tip. Such a historical manual target SHALL skip this stage's quality job and MAY build/deploy only through an explicit closed dependency condition. This temporary exception MUST NOT apply to automatic pushes or the latest main target and SHALL be replaced by signed `AUTHORIZED_ROLLBACK` intent in stage 2.

#### Scenario: Historical main target is selected manually
- **WHEN** workflow_dispatch resolves a main-reachable target older than the current `origin/main` tip
- **THEN** resolution marks it as historical, quality is skipped, and build may proceed without treating a failed or cancelled quality job as a bypass

#### Scenario: Automatic push quality is cancelled or fails
- **WHEN** an automatic main push has quality status other than success
- **THEN** build remains blocked even if the target later becomes an ancestor of a newer main tip

#### Scenario: Latest manual target quality is cancelled or fails
- **WHEN** workflow_dispatch targets the current `origin/main` tip and quality status is not success
- **THEN** build remains blocked and cannot use the historical rollback exception

### Requirement: Quality jobs retain least privilege
**Reason**: deploy.yml から quality job 自体が撤去されるため、その権限境界を定義するこの Requirement は対象を失う
**Migration**: `pr-quality-gate`（Stage 1）が同等の least-privilege 制約（`contents: read` のみ、パッケージ公開・NAS executor 起動権限なし）を PR 時点の quality gate に対して持つ

The resolved-SHA and quality jobs MUST have no package-write or production-runner authority. The image build job SHALL retain package publication authority and MUST depend explicitly on successful resolved-SHA and quality jobs.

#### Scenario: Quality executes on GitHub-hosted runner
- **WHEN** the quality gate runs
- **THEN** it uses a GitHub-hosted runner with `contents: read` only and cannot publish packages or invoke the root deploy executor

#### Scenario: Image build dependency is inspected
- **WHEN** the production workflow contract is evaluated
- **THEN** the image build job has explicit dependencies on both target resolution and quality success

### Requirement: Existing deploy authority remains unchanged
**Reason**: この Requirement が保護していた「署名 bundle・immutable image identity・self-hosted deploy 直列化・installed root executor contract・post-deploy 検証/recovery」のうち、署名 bundle と root executor contract（v2）は本 change で撤去する。immutable image identity と self-hosted deploy 直列化は `deploy-pipeline-baseline` に引き継ぐが、post-deploy recovery は journal state machine ごと撤去するため同一の保証は持たない
**Migration**: `deploy-pipeline-baseline` capability が simple executor の post-deploy health 確認（`/health/live`・`/health/ready`・`/revision` の確認）を定義する。recovery は自動化せず、再デプロイという手動運用に委ねる

This stage MUST preserve the signed bundle fields, immutable image identity, self-hosted deploy serialization, installed root executor contract, and existing post-deploy verification/recovery behavior.

#### Scenario: Quality succeeds and deploy continues
- **WHEN** the quality gate authorizes image publication
- **THEN** the existing build bundle and NAS deploy steps execute with the same permissions, inputs, concurrency, timeout, and root executor invocation semantics
