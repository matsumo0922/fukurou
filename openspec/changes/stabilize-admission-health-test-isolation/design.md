## Context

`LlmExecutionAdmissionHealth` is process-global within a Gradle test worker. DB-backed application startup marks recovery scan health false synchronously and restores it only after an asynchronous successful tick. `LlmExecutionRecoveryWorker.close()` currently cancels without joining, so its JDBC tick can finish after application shutdown and rewrite the singleton for a later test.

The observed class order was `OpsRouteTest`, `DatabaseColdStartTest`, `DatabaseRecoveryPoolCompositionTest`, then `EvaluationReportPersistenceTest`; later classes failed before reaching their own subject. Production fail-closed behavior is correct and must remain intact.

Repository-local probes established:

- With Kotlin 2.4.0/JVM 21, `./gradlew :trading:compileTestFixturesKotlin :fukurou:compileTestKotlin` succeeds when a `:trading` test fixture calls the existing main-source `internal resetForTest()` and `:fukurou` consumes it. No public reset or friend-path customization is required.
- JUnit 4.13.2 `Suite` preserves declared class order; multiple Gradle `--tests` filters do not.
- Ktor 3.5.0 `safeRaiseEvent(ApplicationStopped, ...)` catches subscriber exceptions. An executable testApplication probe logged the exception and still returned successfully, so handler throw is not a test failure channel.
- Background workers start only when runtime config is valid. Daemon and reflection workers additionally require `daemon.enabled` and `obsidian.enabled`, both false in the affected DB-backed tests. They have broader cancel-only/independent-supervisor lifetime risks, but are not the observed cross-test writer. The unconditional recovery worker is the bounded prerequisite scope.

## Goals / Non-Goals

**Goals:**

- Start every admission-dependent test method from a clean health baseline.
- Make successful recovery-worker shutdown mean its job has terminated.
- Keep PostgreSQL alive through application/recovery shutdown.
- Make swallowed Ktor shutdown failures observable to tests and production logs.
- Make readiness assertions bounded and identify the intended 503 cause.
- Reproduce the historical class order explicitly and restore the full quality gate.

**Non-Goals:**

- Weakening production admission/readiness fail-closed semantics.
- Adding a runtime reset route, configuration, or public production API.
- Generalizing daemon, reflection, and their independent supervisors to one application-owned shutdown tree. They are disabled in the affected tests; that operational hardening is a separate change.
- Serializing the full suite or using a fixed delay as evidence.

## Decisions

### Publish the existing internal reset through a test fixture

`:trading` applies `java-test-fixtures` and publishes a small test-only adapter for `LlmExecutionAdmissionHealth.resetForTest()`. `:fukurou` consumes it only through `testImplementation(testFixtures(project(":trading")))`. Normal quality commands cover the fixture source set, and runtime dependency inspection proves the fixture is absent from application configurations.

### Select affected tests by behavior and reset at method boundaries

The inventory predicate is: a test starts `module` with non-null `databaseConfig`, directly references `LlmExecutionAdmissionHealth`, or invokes an admission path whose result depends on it. All matches in `:fukurou` and `:trading` are reviewed. Indirect paths include `EvaluationReportPersistenceTest`, `LlmDaemonSchedulerTest`, `ManualLlmLaunchServiceTest`, and `OneShotRunnerMainTest`. Method-level `@BeforeTest`/`@AfterTest` is used because `OpsRouteTest` owns multiple application lifecycles. Existing partial flag restoration is replaced with the complete reset.

Application tests inject a result observer into `module`, assert the recorded cleanup `Result` after `testApplication` returns, and then reset health in `finally`. The cleanup result—not `isHealthy()`—is the shutdown-success authority because cancelling a healthy in-flight tick can legitimately leave recovery health false. Direct and indirect admission tests without an application use reset-only teardown. If shutdown times out, its observed `Result.failure` makes the suite red before reset; no green isolation claim is made for that run.

### Bound and observe recovery-worker termination

`LlmExecutionRecoveryWorker.close()` cancels its job and uses `runBlocking` with `withTimeout` to await termination for 6 seconds, always cancelling its scope. Six seconds is an empirical margin over the existing five-second cooperative tick budget and is the total added bound for this one synchronous worker; a non-cooperative JDBC section falls into the explicit timeout path below. Injected test scopes must not share the caller's single-thread dispatcher. Timeout throws a dedicated exception and never reports successful termination.

Application shutdown delegates to an internal cleanup helper returning `Result<Unit>`. It attempts every close in dependency-safe order, keeps the datasource alive until recovery close completes or times out, retains the first failure, attaches later distinct failures with `addSuppressed`, and skips self-suppression when multiple closes throw the same instance. `module` accepts a shutdown-result observer used by tests to capture this exact result. Observer delivery and error reporting use independent guarded boundaries, so a throwing primary reporter cannot prevent one observer delivery; a JDK logger records both the cleanup failure and reporter failure as fallback. The `ApplicationStopped` subscriber cannot propagate failure through Ktor. Worker/helper unit tests inspect exceptions/results directly; application tests assert their captured result before resetting admission health.

`docs/design.md` is the current-state documentation authority for this application shutdown contract; runtime docs do not duplicate the same paragraph.

On timeout, a non-cooperative JDBC call may still finish after the boundary. The design makes that residual path a visible red test and fail-closed runtime state; it does not claim the coroutine vanished.

### Own PostgreSQL outside application scope

DB-backed module tests start PostgreSQL before entering `testApplication`, close the application first, then stop PostgreSQL. This lets recovery cancellation/join complete against a live dependency. Only after both scopes close does teardown inspect/reset global health.

### Distinguish readiness causes with monotonic observation

Recovered-ready assertions use a shared monotonic bounded poll. Route tests poll `/health/ready`; non-route tests observe `isHealthy()` or equivalent state. Timeout includes the last observation.

Before intentional runtime-config 503 assertions, tests explicitly start the lazy Ktor test application, then establish and re-check healthy admission so the config gate is the unique cause. Cases invalid at module initialization do not start background workers, so that observation is immediate. `OpsRouteTest.moduleKeepsReadinessAndManualRecoveryAvailableWhenActiveFomcCalendarIsMissing` starts a valid runtime and polls ready because recovery startup is asynchronous.

### Use deploy-gated ordered-suite Gradle tasks

`:fukurou` `AdmissionHealthIsolationRegressionSuite` lists the implicated classes in observed order. `:trading` `TradingAdmissionHealthIsolationRegressionSuite` puts an unhealthy predecessor immediately before each newly inventoried admission-dependent class. Each module excludes its suite and nested predecessor classes from default `test` discovery, and exposes a module-local `admissionHealthIsolationRegressionTest` using one worker. `make test` completes ordinary tests first, then invokes both fully-qualified ordered tasks in a separate Gradle command. This makes the regression part of the deploy gate without duplicate suite execution or concurrent container load from the ordinary and ordered tasks.

## Risks / Trade-offs

- [Risk] Recovery close delays shutdown. → Normal cancellation is immediate; the single 6-second bound caps added delay.
- [Risk] One close failure skips later cleanup or loses evidence. → The helper attempts all closes, stores later distinct failures as suppressed, and ignores repeated identity for suppression only.
- [Risk] Ktor or the primary reporter swallows shutdown evidence. → The subscriber forwards the exact cleanup result once through an independent observer boundary and uses a JDK fallback logger when primary reporting fails.
- [Risk] A reset masks the state under test. → Reset occurs only at method boundaries after assertions/resources; direct unhealthy tests use reset-only teardown.
- [Risk] Optional daemon/reflection work has a similar broader lifetime risk when enabled. → Inventory records that affected module tests keep both flags false; enabling either in a future module test requires expanding the ownership design rather than silently reusing this guarantee.
- [Risk] New tests bypass inventory. → Keep the behavioral predicate beside the fixture and repeat the usage inventory during review.

## Migration Plan

1. Add and compile the test fixture and test-only consumer.
2. Implement bounded recovery termination and result-bearing cleanup.
3. Apply method isolation, resource ownership, and cause-specific readiness observation.
4. Run worker/helper tests, both ordered tasks, the deploy-gated full test command, detekt, build, dependency inspection, and strict OpenSpec validation.
5. Roll back fixture, shutdown, and tests together if validation regresses; no data/config migration is required.

## Open Questions

None.
