# test-admission-health-isolation Specification

## Purpose
TBD - created by archiving change stabilize-admission-health-test-isolation. Update Purpose after archive.
## Requirements
### Requirement: Admission tests isolate process-global health
Each test method that starts a DB-backed production module, directly references execution admission health, or invokes an admission-dependent path MUST begin from a clean baseline and MUST reset it after owned application/resource scopes close.

#### Scenario: Affected method follows an unhealthy test
- **WHEN** a preceding test leaves admission health fail-closed
- **THEN** the next affected method resets before starting its subject
- **AND** its result does not depend on the predecessor

#### Scenario: Method intentionally exercises fail-closed state
- **WHEN** a test makes admission health unhealthy
- **THEN** no reset occurs before its assertion
- **AND** teardown resets only after the subject completes

### Requirement: Test reset remains outside runtime authority
The complete reset MUST be available through a test-fixture variant only and MUST NOT add a runtime route, configuration, or public production reset capability.

#### Scenario: Application runtime dependencies are resolved
- **WHEN** the runtime classpath is inspected
- **THEN** the fixture is absent
- **AND** production admission remains fail-closed

### Requirement: Recovery shutdown is bounded and observable
Recovery-worker close MUST cancel and await its job for at most six seconds and MUST throw on timeout rather than claim successful termination.

#### Scenario: Tick is active during close
- **WHEN** close cancels an active recovery tick
- **THEN** it returns only after termination
- **OR** throws within the six-second bound

#### Scenario: Application resource close fails
- **WHEN** one or more owned closes fail
- **THEN** the cleanup helper attempts remaining closes in dependency-safe order
- **AND** returns the first failure with later distinct failures suppressed without self-suppression
- **AND** the Ktor subscriber forwards that exact result once to the observer independently of error reporting
- **AND** a primary reporter failure falls back to logging without replacing the cleanup failure

#### Scenario: Application test shutdown fails
- **WHEN** the Ktor subscriber cannot propagate a cleanup failure
- **THEN** the injected observer records the cleanup failure
- **AND** affected test teardown asserts that recorded result before reset
- **AND** the test run remains failed

### Requirement: Database outlives recovery work
DB-backed module tests MUST keep PostgreSQL available until application and recovery shutdown complete.

#### Scenario: DB-backed test exits
- **WHEN** the test leaves application scope
- **THEN** recovery and application resources stop before PostgreSQL

### Requirement: Asynchronous health assertions are bounded and observable
Tests expecting asynchronous recovery MUST poll real observable state to a monotonic deadline and MUST fail with the last observation on timeout.

#### Scenario: Route application recovers
- **WHEN** recovery establishes readiness before the deadline
- **THEN** the test observes `/health/ready` without a fixed delay

#### Scenario: Non-route service converges
- **WHEN** no application route exists
- **THEN** the test observes `isHealthy()` or equivalent state before the deadline

#### Scenario: Recovery does not converge
- **WHEN** state remains unavailable to the deadline
- **THEN** the test fails in bounded time with last evidence

### Requirement: Expected readiness rejection identifies its cause
A test asserting intentional 503 readiness MUST establish unrelated admission health is healthy before and at the assertion.

#### Scenario: Runtime configuration is unavailable
- **WHEN** a test expects runtime-config readiness rejection
- **THEN** it starts the lazy test application and proves execution admission is healthy
- **AND** the 503 has the intended unique cause

### Requirement: Regression validation covers shared-worker ordering
The change MUST provide module-local Gradle `Test` tasks for ordered JUnit 4 application and trading regressions, MUST execute both from `make test`, and MUST pass ordinary deployment quality commands in default discovery order.

#### Scenario: Deploy gate sequences full and ordered tests
- **WHEN** `make test` runs
- **THEN** ordinary tests complete before either ordered regression task starts
- **AND** both ordered tasks run without overlapping ordinary test container load

#### Scenario: Historical order runs
- **WHEN** both module-local `admissionHealthIsolationRegressionTest` tasks run
- **THEN** implicated application classes execute in declared order in one worker
- **AND** each inventoried trading path executes immediately after a predecessor that poisons every admission-health state category in one worker
- **AND** the poison helper skips before mutation unless the dedicated ordered task supplies its guard property
- **AND** all pass without residual global state

#### Scenario: Ordinary full suite runs
- **WHEN** deploy-gated full tests, static analysis, build, dependency inspection, and strict OpenSpec validation run
- **THEN** all succeed without duplicate ordered-suite discovery

