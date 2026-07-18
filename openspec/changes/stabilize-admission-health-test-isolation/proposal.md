## Why

production module tests share process-global `LlmExecutionAdmissionHealth`; asynchronous recovery startup/shutdown can leave it fail-closed for the next test class, causing order-dependent false failures in the newly required deploy quality gate.
Production must remain fail-closed, so the test harness needs explicit isolation and bounded readiness synchronization instead of weakening runtime safety.

## What Changes

- Add a compiled test-fixture reset boundary for process-global execution admission health without exposing a production reset API.
- Reset shared health at each affected test-method boundary, selected by whether the test starts the DB-backed production module or exercises admission health directly or indirectly.
- Await asynchronous readiness establishment with a bounded poll where a test asserts the recovered ready state.
- Own PostgreSQL outside `testApplication` so application shutdown completes before container teardown.
- Make recovery-worker shutdown await cancellation with a bounded failure instead of returning while a JDBC tick can still mutate global health.
- Preserve every application resource close attempt and expose Ktor's swallowed shutdown result to tests through an injected observer while production emits an error report.
- Make intentionally unavailable readiness assertions prove admission health is healthy first, so the tested failure cause remains unambiguous.
- Add an explicit JUnit 4 suite for the previously failing class sequence and run the full deploy quality commands.

## Capabilities

### New Capabilities

- `test-admission-health-isolation`: Defines deterministic isolation and lifecycle ordering for tests that share fail-closed execution admission health.

### Modified Capabilities

なし。

## Impact

- `trading` test-fixture Gradle variants and `:fukurou` test dependencies; the fixture remains absent from runtime configurations.
- DB-backed production-module tests and tests that directly or indirectly exercise execution admission.
- `LlmExecutionRecoveryWorker` shutdown waiting and its application resource-close path.
- Production admission/readiness decisions, DB schema, and wire APIs do not change; application bootstrap gains a shutdown-result observer and recovery completion becomes explicit and bounded.
