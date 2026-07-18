## 1. Test Fixture Boundary

- [x] 1.1 Apply `java-test-fixtures` to `:trading` and publish the existing internal complete reset through a fixture.
- [x] 1.2 Consume it from `:fukurou` tests, compile both source sets, and prove runtime configurations exclude it.
- [x] 1.3 Inventory DB-backed module, direct-health, and indirect-admission tests in both modules, including scheduler/manual/standalone runner paths; record all affected classes and verify affected module tests keep daemon/obsidian disabled.

## 2. Recovery Worker Termination

- [x] 2.1 Make recovery close cancel and await termination for at most six seconds, throwing on timeout.
- [x] 2.2 Extract a result-bearing cleanup helper that attempts every close in order without self-suppression and preserves distinct failures; deliver the exact shutdown result independently from primary/fallback error reporting.
- [x] 2.3 Add direct tests for in-flight cancellation, timeout, cleanup continuation, repeated exception identity, exception preservation, reporter failure, and exact observer result delivery.

## 3. Deterministic Test Lifecycle

- [x] 3.1 Add method-level complete reset boundaries to application, direct-health, and every indirect-admission inventory group, including scheduler, manual launch, and standalone runner tests; replace partial restoration.
- [x] 3.2 Close DB-backed applications before PostgreSQL teardown.
- [x] 3.3 Add shared monotonic bounded observation for recovered readiness/direct health.
- [x] 3.4 Start the application explicitly, then prove admission healthy before and during intentional runtime-config 503 assertions.

## 4. Regression And Quality Validation

- [x] 4.1 Add module-local ordered regression tasks for the historical application sequence and unhealthy-predecessor trading paths, exclude duplicate default discovery, and wire both into `make test`.
- [x] 4.2 Run lifecycle tests, both ordered tasks, fresh deploy-gate tests with task evidence, detekt, build, runtime dependency inspection, and strict OpenSpec validation.
- [x] 4.3 Search README, docs, and KDoc for affected shutdown/admission/readiness descriptions, keep one current-state documentation authority, and record PR documentation impact.
