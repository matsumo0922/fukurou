# deploy-quality-gate Specification

## Purpose

Production image publication is gated by JVM tests, static analysis, and exact target identity while preserving the explicit historical rollback path.

## Requirements

### Requirement: Target commit quality gates image publication
Issue #190 CI DoD stage 1: For automatic main pushes and a workflow_dispatch target equal to the resolved `origin/main` tip, the deploy workflow MUST run the repository JVM test suite and detekt against the exact resolved target commit. It MUST NOT authenticate to GHCR, build or push a production image, create a deploy bundle, or start production deployment until both quality commands and the clean-tree check succeed.

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

### Requirement: Historical manual rollback remains available until explicit intent exists
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
The resolved-SHA and quality jobs MUST have no package-write or production-runner authority. The image build job SHALL retain package publication authority and MUST depend explicitly on successful resolved-SHA and quality jobs.

#### Scenario: Quality executes on GitHub-hosted runner
- **WHEN** the quality gate runs
- **THEN** it uses a GitHub-hosted runner with `contents: read` only and cannot publish packages or invoke the root deploy executor

#### Scenario: Image build dependency is inspected
- **WHEN** the production workflow contract is evaluated
- **THEN** the image build job has explicit dependencies on both target resolution and quality success

### Requirement: Existing deploy authority remains unchanged
This stage MUST preserve the signed bundle fields, immutable image identity, self-hosted deploy serialization, installed root executor contract, and existing post-deploy verification/recovery behavior.

#### Scenario: Quality succeeds and deploy continues
- **WHEN** the quality gate authorizes image publication
- **THEN** the existing build bundle and NAS deploy steps execute with the same permissions, inputs, concurrency, timeout, and root executor invocation semantics
