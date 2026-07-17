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

- Initial targeted validation at `12a4104`: validation lease acquired immediately (`0s` external wait). `./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process :trading:test` with the `InMemoryPaperLedgerRepositoryTest`, `RiskStateCommandServiceTest`, `OneShotLlmRunnerTest.exitDecision_*`, atomic-risk-exit / hard-halt-cleanup PostgreSQL barrier and failure patterns, and startup / periodic / connect-failure `ProtectionReconcilerTest` patterns passed with `BUILD SUCCESSFUL in 17s`. `./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process :fukurou:test --tests "me.matsumo.fukurou.OpsRouteTest"` passed with `BUILD SUCCESSFUL in 4s`.
- Full validation remains recorded at `b7b83ab`: `openspec validate issue-187-bf-atomic-exit` (`valid`), `make test` (`BUILD SUCCESSFUL in 7m 31s`), `make detekt` (`BUILD SUCCESSFUL in 6s`), and `make build` (`BUILD SUCCESSFUL in 4s`). No full validation has run at the later BF-01/BF-02 remediation HEADs. Final-HEAD full validation is deferred until after re-review and MUST NOT be treated as successful yet.
- BF-01 targeted validation at `e10a480`: the validation lease was acquired immediately. The retained command record identifies the `:trading:test` command family and filters as all of `InMemoryPaperLedgerRepositoryTest` plus the PostgreSQL HARD_HALT direct MARKET/resting barrier, intent-consuming rollback, and cleanup-race tests; the exact serialized `--tests` command is not retained. The reported result was 16 tests passed, 0 failures/errors, with `BUILD SUCCESSFUL in 17s`. Targeted `:trading:detekt` also passed with `BUILD SUCCESSFUL in 6s` and immediate lease acquisition.
- BF-02 initial targeted validation at `249a941`: `./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process :trading:test --tests "me.matsumo.fukurou.trading.market.FreshnessMetadataTest" --tests "me.matsumo.fukurou.trading.reconciler.TickStreamTest" --tests "me.matsumo.fukurou.trading.reconciler.TickSnapshotTickerTest" --tests "me.matsumo.fukurou.trading.reconciler.ProtectionReconcilerTest"`; validation lease acquired immediately and `BUILD SUCCESSFUL in 8s` (51 tests, 0 failures, 0 errors).
- BF-02 I/O-delay recheck at `b772650`: the `:trading:test` filter inventory was `PaperBrokerTest.sweep_hard_halt_revalidates_rest_source_after_delayed_orderbook_before_mutation` and `PaperBrokerTest.sweep_hard_halt_revalidates_rest_source_after_orderbook_failure_before_ticker_fallback`; the exact serialized command is not retained. Both tests passed with 0 failures/errors after `34s` external lease wait. Targeted `:trading:detekt` passed after `9s` external lease wait.

### Scenario ledger

The delta spec contains 22 Scenarios, and this ledger contains exactly one row per Scenario. When remediation added proving tests for an existing Scenario, the cell separates the tests by their actual targeted-validation SHA.

| OpenSpec Scenario | Targeted-validation SHA and proving tests |
| --- | --- |
| Position and same-thesis resting entry coexist | `12a4104`: `InMemoryPaperLedgerRepositoryTest.sameThesisRiskExitClosesPositionAndCancelsOnlyMatchingPendingBuy`; `PostgresPersistenceIntegrationTest.atomicRiskExitCancelsSameThesisAndPreservesUnrelatedThesisInPostgres`; `OneShotLlmRunnerTest.exitDecision_closesSingleOpenPositionWhenRestingEntryOrderAlsoExists` |
| Unrelated thesis remains open | `12a4104`: `InMemoryPaperLedgerRepositoryTest.sameThesisRiskExitClosesPositionAndCancelsOnlyMatchingPendingBuy`; `PostgresPersistenceIntegrationTest.atomicRiskExitCancelsSameThesisAndPreservesUnrelatedThesisInPostgres` |
| Resting entry is the only EXIT target | `12a4104`: `OneShotLlmRunnerTest.exitDecision_cancelsSingleRestingEntryOrderDeterministically` |
| Target position thesis cannot be resolved uniquely | `12a4104`: `InMemoryPaperLedgerRepositoryTest.sameThesisRiskExitFailsWithoutMutationForMissingNullOrMultipleLinkage`; `PostgresPersistenceIntegrationTest.atomicRiskExitLinkageFailuresAndStaleTargetLeavePostgresLedgerUnchanged` |
| Pending BUY thesis cannot be classified | `12a4104`: `InMemoryPaperLedgerRepositoryTest.sameThesisRiskExitFailsWithoutMutationForMissingNullOrMultipleLinkage`; `PostgresPersistenceIntegrationTest.atomicRiskExitLinkageFailuresAndStaleTargetLeavePostgresLedgerUnchanged` |
| Target changed before transaction lock | `12a4104`: `InMemoryPaperLedgerRepositoryTest.sameThesisRiskExitFailsWithoutMutationForContradictoryGroupOrStaleTarget`; `PostgresPersistenceIntegrationTest.atomicRiskExitLinkageFailuresAndStaleTargetLeavePostgresLedgerUnchanged` |
| EXIT commits before an eligible fill | `12a4104`: `PostgresPersistenceIntegrationTest.atomicRiskExitWinsBarrierBeforeRestingFillWithoutDuplicateExecution` |
| Fill commits before EXIT | `12a4104`: `PostgresPersistenceIntegrationTest.restingFillWinsBarrierBeforeAtomicRiskExitWithoutDuplicateExecution` |
| HARD_HALT cleanup succeeds | `12a4104`: `InMemoryPaperLedgerRepositoryTest.hardHaltCleanupKeepsUnknownWithoutTickThenConvergesAndRetriesIdempotently`; `PostgresPersistenceIntegrationTest.hardHaltCleanupPersistsUnknownRetriesToSafeAndResumesAtomicallyInPostgres`.<br>`249a941`: `ProtectionReconcilerTest.startup_hard_halt_cleanup_closesOpenPositionWithFreshRestSourceTimestamp` |
| Failure occurs before cleanup commit | `12a4104`: `PostgresPersistenceIntegrationTest.hardHaltCleanupRollsBackBeforeCommitAndConvergesAfterCommitResponseLossInPostgres` |
| Result is lost after cleanup commit | `12a4104`: `PostgresPersistenceIntegrationTest.hardHaltCleanupRollsBackBeforeCommitAndConvergesAfterCommitResponseLossInPostgres` |
| Cleanup cannot obtain trustworthy execution input | `12a4104`: `InMemoryPaperLedgerRepositoryTest.hardHaltCleanupKeepsUnknownWithoutTickThenConvergesAndRetriesIdempotently`; `PostgresPersistenceIntegrationTest.hardHaltCleanupPersistsUnknownRetriesToSafeAndResumesAtomicallyInPostgres`.<br>`249a941`: `ProtectionReconcilerTest.startup_hard_halt_cleanup_rejectsUntrustedRestTimestampsWithoutMutation`; `ProtectionReconcilerTest.websocket_connect_failure_keepsOpenPositionForUntrustedRestTimestamps`; `TickSnapshotTickerTest.requireExecutionTicker_rejectsStaleMissingAndExcessivelyFutureRestTimestamps` |
| Flat halted account has no market input | `12a4104`: `ProtectionReconcilerTest.websocket_connect_failure_retries_hard_halt_cleanup_evenAfterSafeEvidence`; `InMemoryPaperLedgerRepositoryTest.flatHardHaltCleanupStoresSafeWithoutTickAndStaleSafeReturnsToUnknown`; `PostgresPersistenceIntegrationTest.flatHardHaltCleanupPersistsSafeWithoutTickInPostgres` |
| Order-only halted account has no market input | `249a941`: `ProtectionReconcilerTest.reconcile_pass_cancels_open_entry_before_fill_when_hard_halt` |
| WebSocket connection remains unavailable | `12a4104`: `ProtectionReconcilerTest.websocket_connect_failure_retries_hard_halt_cleanup_evenAfterSafeEvidence`; `ProtectionReconcilerTest.websocket_hard_halt_cleanup_retriesFromStartupDuringPeriodicMaintenance`.<br>`249a941`: `ProtectionReconcilerTest.websocket_connect_failure_retriesOpenPositionCleanupWithFreshRestTimestamp`; `ProtectionReconcilerTest.websocket_connect_failure_keepsOpenPositionForUntrustedRestTimestamps` |
| REST ticker becomes stale while execution context is built | `b772650`: `PaperBrokerTest.sweep_hard_halt_revalidates_rest_source_after_delayed_orderbook_before_mutation`; `PaperBrokerTest.sweep_hard_halt_revalidates_rest_source_after_orderbook_failure_before_ticker_fallback` |
| Realtime event provides causal cleanup authority | `249a941`: `ProtectionReconcilerTest.market_event_hard_halt_sweeps_with_same_event_snapshot`; `TickSnapshotTickerTest.requireExecutionTicker_doesNotApplyRestFreshnessGateToRealtimeCausalEvent` |
| Manual resume is requested before cleanup is safe | `12a4104`: `RiskStateCommandServiceTest.resume_rejects_unknown_cleanup_without_changing_hard_halt`; `OpsRouteTest.opsRoutes_haltResumeAndReadRiskState` |
| Stale SAFE survives an old-writer rollback epoch | `12a4104`: `InMemoryPaperLedgerRepositoryTest.flatHardHaltCleanupStoresSafeWithoutTickAndStaleSafeReturnsToUnknown`; `RiskStateCommandServiceTest.resume_rejects_stale_safe_and_downgrades_evidence_to_unknown`; `PostgresPersistenceIntegrationTest.hardHaltCleanupPersistsUnknownRetriesToSafeAndResumesAtomicallyInPostgres` |
| Entry races after HARD_HALT activation | `12a4104`: `PostgresPersistenceIntegrationTest.hardHaltCleanupWinsBarrierBeforeRestingFillWithoutRiskIncrease`; `PostgresPersistenceIntegrationTest.restingFillWinsBarrierAfterHardHaltWithoutRiskIncrease`.<br>`e10a480`: `PostgresPersistenceIntegrationTest.hardHaltWinsBarrierBeforeDirectMarketEntryWithoutRiskIncrease`; `PostgresPersistenceIntegrationTest.directMarketEntryWinsBarrierBeforeHardHalt`; `PostgresPersistenceIntegrationTest.hardHaltWinsBarrierBeforeDirectRestingEntryWithoutRiskIncrease`; `PostgresPersistenceIntegrationTest.directRestingEntryWinsBarrierBeforeHardHalt`; `PostgresPersistenceIntegrationTest.hardHaltRejectsIntentConsumingDirectEntriesWithoutConsumingIntent`; `InMemoryPaperLedgerRepositoryTest.directMarketAndRestingEntriesHonorInMemoryHardHaltMutationBoundary`; `InMemoryPaperLedgerRepositoryTest.hardHaltPreventsInMemoryTickAndMarketEventEntryFills` |
| Protective order belongs to a closing position | `12a4104`: `InMemoryPaperLedgerRepositoryTest.sameThesisRiskExitClosesPositionAndCancelsOnlyMatchingPendingBuy`; `PostgresPersistenceIntegrationTest.atomicRiskExitCancelsSameThesisAndPreservesUnrelatedThesisInPostgres` |
| Legacy thesis linkage blocks normal EXIT | `12a4104`: `InMemoryPaperLedgerRepositoryTest.sameThesisRiskExitFailsWithoutMutationForMissingNullOrMultipleLinkage`; `PostgresPersistenceIntegrationTest.atomicRiskExitLinkageFailuresAndStaleTargetLeavePostgresLedgerUnchanged`; `PostgresPersistenceIntegrationTest.hardHaltCleanupPersistsUnknownRetriesToSafeAndResumesAtomicallyInPostgres` |
