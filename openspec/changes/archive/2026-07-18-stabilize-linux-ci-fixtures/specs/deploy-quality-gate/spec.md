## MODIFIED Requirements

### Requirement: Target commit quality gates image publication
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
