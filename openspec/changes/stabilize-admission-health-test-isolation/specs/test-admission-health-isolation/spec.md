## ADDED Requirements

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
- **AND** returns the first failure with later failures suppressed
- **AND** the Ktor subscriber forwards that exact result to the observer and error-logs the failure

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
- **THEN** it proves execution admission is healthy
- **AND** the 503 has the intended unique cause

### Requirement: Regression validation covers shared-worker ordering
The change MUST provide a dedicated Gradle `Test` task for the ordered JUnit 4 regression suite and MUST pass ordinary deployment quality commands in default discovery order.

#### Scenario: Historical order runs
- **WHEN** `admissionHealthIsolationRegressionTest` runs
- **THEN** implicated classes execute in declared order in one worker
- **AND** all pass without residual global state

#### Scenario: Ordinary full suite runs
- **WHEN** full tests, static analysis, build, dependency inspection, and strict OpenSpec validation run
- **THEN** all succeed without duplicate ordered-suite discovery
