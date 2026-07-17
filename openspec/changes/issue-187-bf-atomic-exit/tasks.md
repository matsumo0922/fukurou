## 1. Durable HARD_HALT cleanup state

- [x] 1.1 Add the minimal UNKNOWN/SAFE cleanup state to risk models and additive PostgreSQL bootstrap with fresh-upgrade and old-reader compatibility tests
- [x] 1.2 Set cleanup UNKNOWN on a new HARD_HALT, preserve SAFE on duplicate halt commands without trusting it as a skip, and require SAFE plus atomic zero-open-risk readback for manual resume in Exposed and in-memory services

## 2. Atomic paper risk-exit primitive

- [x] 2.1 Add typed SAME_THESIS/ALL_OPEN_RISK request and failure contracts to the paper ledger mutation interface
- [x] 2.2 Implement Exposed linkage resolution, latest locked full-size fill calculation, status CAS, atomic cancel/close/account mutation, and SAFE terminal write using the existing lock order, and confirm the transaction isolation assumed by fill-first re-resolution
- [x] 2.3 Project persisted intent identity into entry requests and store explicit intent-to-thesis linkage in the in-memory ledger, including missing/contradictory fixture states, without using trade-group equality as a substitute

## 3. Production call-path wiring

- [x] 3.1 Route full EXIT with an open position through SAME_THESIS atomic risk exit while preserving deterministic order-only EXIT and fail-closed target selection
- [x] 3.2 Route SafetyFloor, drawdown, reconciler, and kill-criterion HARD_HALT cleanup through ALL_OPEN_RISK and keep risk-reducing execution available
- [x] 3.3 Run a bounded HARD_HALT cleanup attempt under the existing trading lock regardless of cleanup evidence before the production WebSocket loop and in WebSocket connect-failure/backoff branches, beginning with atomic open-risk readback, without invoking full tick execution, and retain periodic/event retries
- [x] 3.4 Preserve REST exchange source timestamp separately from app observation time and reject missing, malformed, stale, or excessively future REST fallback immediately before it becomes HARD_HALT close execution authority

## 4. Contract and regression tests

- [x] 4.1 Cover same-thesis cancellation, unrelated-thesis preservation, order-only EXIT, and missing/null/multiple/stale linkage mutation-zero failures for Exposed and in-memory repositories
- [x] 4.2 Replace the existing EXIT-plus-resting-order expectation and add deterministic PostgreSQL barrier races for EXIT-first and fill-first convergence with duplicate execution zero
- [x] 4.3 Add HARD_HALT commit-before/commit-after-response-loss/retry tests, startup/connect-failure/periodic retry tests, flat-without-tick SAFE tests, SAFE evidence tests, old-writer stale-SAFE rollback tests, and UNKNOWN/open-risk resume rejection tests
- [x] 4.4 Run targeted production-entrypoint tests and record each OpenSpec Scenario's proving test at the validated HEAD

## 5. Documentation and validation

- [x] 5.1 Update the system prompt, README, `docs/design.md`, and `docs/mcp-runtime.md` in current tense and grep affected feature/class/command names for stale descriptions
- [x] 5.2 Run `openspec validate issue-187-bf-atomic-exit`, `make test`, `make detekt`, and `make build` under the validation lease and record command, scope, result, and HEAD

## Validation evidence

- Implementation HEAD: `12a4104` (`test: align fixtures with atomic halt contract`). This evidence-only task update does not change production or test code.
- Lease: `validation-lease.sh env GRADLE_USER_HOME=/tmp/fukurou-gradle-187-bf JAVA_TOOL_OPTIONS=-Duser.home=/tmp/fukurou-java-home-187 zsh -e -c '<commands below>'`; acquired immediately (`0s` external wait).
- Targeted trading command: `./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process :trading:test` with the `InMemoryPaperLedgerRepositoryTest`, `RiskStateCommandServiceTest`, `OneShotLlmRunnerTest.exitDecision_*`, atomic-risk-exit / hard-halt-cleanup PostgreSQL barrier and failure patterns, and the startup / periodic / connect-failure `ProtectionReconcilerTest` patterns; `BUILD SUCCESSFUL in 17s`.
- Targeted HTTP command: `./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process :fukurou:test --tests "me.matsumo.fukurou.OpsRouteTest"`; `BUILD SUCCESSFUL in 4s`.
- Full validation commands: `openspec validate issue-187-bf-atomic-exit` (`valid`), `make test` (`BUILD SUCCESSFUL in 7m 31s`), `make detekt` (`BUILD SUCCESSFUL in 6s`), and `make build` (`BUILD SUCCESSFUL in 4s`).
- Round 1 BF-02 targeted repair command: `./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process :trading:test --tests "me.matsumo.fukurou.trading.market.FreshnessMetadataTest" --tests "me.matsumo.fukurou.trading.reconciler.TickStreamTest" --tests "me.matsumo.fukurou.trading.reconciler.TickSnapshotTickerTest" --tests "me.matsumo.fukurou.trading.reconciler.ProtectionReconcilerTest"`; validation lease acquired immediately and `BUILD SUCCESSFUL in 8s` (51 tests, 0 failures, 0 errors). `openspec validate issue-187-bf-atomic-exit` remains valid.

### Scenario ledger

| OpenSpec Scenario | Proving test at `12a4104` |
| --- | --- |
| Position and same-thesis resting entry coexist | `InMemoryPaperLedgerRepositoryTest.sameThesisRiskExitClosesPositionAndCancelsOnlyMatchingPendingBuy`; `PostgresPersistenceIntegrationTest.atomicRiskExitCancelsSameThesisAndPreservesUnrelatedThesisInPostgres`; `OneShotLlmRunnerTest.exitDecision_closesSingleOpenPositionWhenRestingEntryOrderAlsoExists` |
| Unrelated thesis remains open | `InMemoryPaperLedgerRepositoryTest.sameThesisRiskExitClosesPositionAndCancelsOnlyMatchingPendingBuy`; `PostgresPersistenceIntegrationTest.atomicRiskExitCancelsSameThesisAndPreservesUnrelatedThesisInPostgres` |
| Resting entry is the only EXIT target | `OneShotLlmRunnerTest.exitDecision_cancelsSingleRestingEntryOrderDeterministically` |
| Target position thesis cannot be resolved uniquely | `InMemoryPaperLedgerRepositoryTest.sameThesisRiskExitFailsWithoutMutationForMissingNullOrMultipleLinkage`; `PostgresPersistenceIntegrationTest.atomicRiskExitLinkageFailuresAndStaleTargetLeavePostgresLedgerUnchanged` |
| Pending BUY thesis cannot be classified | `InMemoryPaperLedgerRepositoryTest.sameThesisRiskExitFailsWithoutMutationForMissingNullOrMultipleLinkage`; `PostgresPersistenceIntegrationTest.atomicRiskExitLinkageFailuresAndStaleTargetLeavePostgresLedgerUnchanged` |
| Target changed before transaction lock | `InMemoryPaperLedgerRepositoryTest.sameThesisRiskExitFailsWithoutMutationForContradictoryGroupOrStaleTarget`; `PostgresPersistenceIntegrationTest.atomicRiskExitLinkageFailuresAndStaleTargetLeavePostgresLedgerUnchanged` |
| EXIT commits before an eligible fill | `PostgresPersistenceIntegrationTest.atomicRiskExitWinsBarrierBeforeRestingFillWithoutDuplicateExecution` |
| Fill commits before EXIT | `PostgresPersistenceIntegrationTest.restingFillWinsBarrierBeforeAtomicRiskExitWithoutDuplicateExecution` |
| HARD_HALT cleanup succeeds | `ProtectionReconcilerTest.startup_hard_halt_cleanup_closesOpenPositionWithFreshRestSourceTimestamp`; `InMemoryPaperLedgerRepositoryTest.hardHaltCleanupKeepsUnknownWithoutTickThenConvergesAndRetriesIdempotently`; `PostgresPersistenceIntegrationTest.hardHaltCleanupPersistsUnknownRetriesToSafeAndResumesAtomicallyInPostgres` |
| Failure occurs before cleanup commit | `PostgresPersistenceIntegrationTest.hardHaltCleanupRollsBackBeforeCommitAndConvergesAfterCommitResponseLossInPostgres` |
| Result is lost after cleanup commit | `PostgresPersistenceIntegrationTest.hardHaltCleanupRollsBackBeforeCommitAndConvergesAfterCommitResponseLossInPostgres` |
| Cleanup cannot obtain trustworthy execution input | `ProtectionReconcilerTest.startup_hard_halt_cleanup_rejectsUntrustedRestTimestampsWithoutMutation`; `ProtectionReconcilerTest.websocket_connect_failure_keepsOpenPositionForUntrustedRestTimestamps`; `TickSnapshotTickerTest.requireExecutionTicker_rejectsStaleMissingAndExcessivelyFutureRestTimestamps`; `InMemoryPaperLedgerRepositoryTest.hardHaltCleanupKeepsUnknownWithoutTickThenConvergesAndRetriesIdempotently`; `PostgresPersistenceIntegrationTest.hardHaltCleanupPersistsUnknownRetriesToSafeAndResumesAtomicallyInPostgres` |
| Flat halted account has no market input | `ProtectionReconcilerTest.websocket_connect_failure_retries_hard_halt_cleanup_evenAfterSafeEvidence`; `InMemoryPaperLedgerRepositoryTest.flatHardHaltCleanupStoresSafeWithoutTickAndStaleSafeReturnsToUnknown`; `PostgresPersistenceIntegrationTest.flatHardHaltCleanupPersistsSafeWithoutTickInPostgres` |
| Order-only halted account has no market input | `ProtectionReconcilerTest.reconcile_pass_cancels_open_entry_before_fill_when_hard_halt` |
| WebSocket connection remains unavailable | `ProtectionReconcilerTest.websocket_connect_failure_retriesOpenPositionCleanupWithFreshRestTimestamp`; `ProtectionReconcilerTest.websocket_connect_failure_keepsOpenPositionForUntrustedRestTimestamps`; `ProtectionReconcilerTest.websocket_connect_failure_retries_hard_halt_cleanup_evenAfterSafeEvidence`; `ProtectionReconcilerTest.websocket_hard_halt_cleanup_retriesFromStartupDuringPeriodicMaintenance` |
| Realtime event provides causal cleanup authority | `ProtectionReconcilerTest.market_event_hard_halt_sweeps_with_same_event_snapshot`; `TickSnapshotTickerTest.requireExecutionTicker_doesNotApplyRestFreshnessGateToRealtimeCausalEvent` |
| Manual resume is requested before cleanup is safe | `RiskStateCommandServiceTest.resume_rejects_unknown_cleanup_without_changing_hard_halt`; `OpsRouteTest.opsRoutes_haltResumeAndReadRiskState` |
| Stale SAFE survives an old-writer rollback epoch | `InMemoryPaperLedgerRepositoryTest.flatHardHaltCleanupStoresSafeWithoutTickAndStaleSafeReturnsToUnknown`; `RiskStateCommandServiceTest.resume_rejects_stale_safe_and_downgrades_evidence_to_unknown`; `PostgresPersistenceIntegrationTest.hardHaltCleanupPersistsUnknownRetriesToSafeAndResumesAtomicallyInPostgres` |
| Entry races after HARD_HALT activation | `PostgresPersistenceIntegrationTest.hardHaltCleanupWinsBarrierBeforeRestingFillWithoutRiskIncrease`; `PostgresPersistenceIntegrationTest.restingFillWinsBarrierAfterHardHaltWithoutRiskIncrease` |
| Protective order belongs to a closing position | `InMemoryPaperLedgerRepositoryTest.sameThesisRiskExitClosesPositionAndCancelsOnlyMatchingPendingBuy`; `PostgresPersistenceIntegrationTest.atomicRiskExitCancelsSameThesisAndPreservesUnrelatedThesisInPostgres` |
| Legacy thesis linkage blocks normal EXIT | `InMemoryPaperLedgerRepositoryTest.sameThesisRiskExitFailsWithoutMutationForMissingNullOrMultipleLinkage`; `PostgresPersistenceIntegrationTest.atomicRiskExitLinkageFailuresAndStaleTargetLeavePostgresLedgerUnchanged`; `PostgresPersistenceIntegrationTest.hardHaltCleanupPersistsUnknownRetriesToSafeAndResumesAtomicallyInPostgres` |
