## 1. Test Fixture Boundary

- [ ] 1.1 Apply `java-test-fixtures` to `:trading` and publish the existing internal complete reset through a fixture.
- [ ] 1.2 Consume it from `:fukurou` tests, compile both source sets, and prove runtime configurations exclude it.
- [ ] 1.3 Inventory DB-backed module, direct-health, and indirect-admission tests in both modules; record all affected classes and verify affected module tests keep daemon/obsidian disabled.

## 2. Recovery Worker Termination

- [ ] 2.1 Make recovery close cancel and await termination for at most six seconds, throwing on timeout.
- [ ] 2.2 Extract a result-bearing cleanup helper that attempts every close in order and preserves suppressed failures; add a module shutdown observer and error reporting to the Ktor subscriber.
- [ ] 2.3 Add direct tests for in-flight cancellation, timeout, cleanup continuation, exception preservation, and exact observer result delivery.

## 3. Deterministic Test Lifecycle

- [ ] 3.1 Add method-level complete reset boundaries to application, direct-health, and indirect-admission inventory groups and replace partial restoration.
- [ ] 3.2 Close DB-backed applications before PostgreSQL teardown.
- [ ] 3.3 Add shared monotonic bounded observation for recovered readiness/direct health.
- [ ] 3.4 Prove admission healthy before and during intentional runtime-config 503 assertions.

## 4. Regression And Quality Validation

- [ ] 4.1 Add `admissionHealthIsolationRegressionTest` for the historical JUnit 4 suite and exclude it from default discovery.
- [ ] 4.2 Run lifecycle tests, the ordered task, full tests, detekt, build, runtime dependency inspection, and strict OpenSpec validation.
- [ ] 4.3 Search README, docs, and KDoc for affected shutdown/admission/readiness descriptions and record PR documentation impact.
