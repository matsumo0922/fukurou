## MODIFIED Requirements

### Requirement: Target commit quality gates image publication
Issue #190 CI DoD stage 1: For automatic main pushes and a workflow_dispatch target equal to the resolved `origin/main` tip, the deploy workflow MUST run the repository JVM test suite and detekt against the exact resolved target commit. It MUST NOT authenticate to GHCR, build or push a production image, create a deploy bundle, or start production deployment until both quality commands and the clean-tree check succeed. Deploy-gated fixtures MUST exercise the same contract on Linux CI and supported local development platforms without platform-specific path or process assumptions.

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

#### Scenario: Platform-specific fixture contract is exercised
- **WHEN** the exact target test suite runs on GitHub-hosted Linux or a supported local development platform
- **THEN** process completion、socket path selection、population bound の fixture は production contract と同じ分岐を検証する
- **AND** platform-specific temp path length、shell signal default、transport timeout を成功条件にしない
