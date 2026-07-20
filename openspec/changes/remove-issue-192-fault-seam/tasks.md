## 1. Contract and inventory

- [x] 1.1 Validate the cleanup proposal, delta spec, design, and finite PR #270 removal inventory.
- [x] 1.2 Run clean-context five-vector falsification and reconcile every blocking finding before implementation.

## 2. Runtime cleanup

- [x] 2.1 Remove the Issue #192 application controller, route, module flag, holder/factory wiring, and background-worker callback.
- [x] 2.2 Remove the injected WebSocket disconnect interface, PR #270 active-session/injection hunks, and command-event by-ID reader while preserving the pre-existing `afterTerminalClaim` behavior and Issue #192 decoding-only event types.
- [x] 2.3 Remove the production compose flag and current deploy documentation; retain the activity catalog mappings, golden fixture entries, and localization keys, and rewrite their descriptions as decoding-only historical vocabulary rather than a completed verification claim.

## 3. Test cleanup and proof

- [x] 3.1 Remove injection-only test hunks, not whole pre-existing test files, and restore the paper-ledger repository count and background-worker naming while retaining normal WebSocket, persistence, and paper-fidelity coverage.
- [x] 3.2 Add one application-level regression proving the retired POST route is `404`; run a bounded grep for the route path, activation key, controller, disconnector, writer, and fixed-PK reader while allowlisting only the retained enum/catalog/golden/i18n vocabulary and OpenSpec history.
- [x] 3.3 Run initial full validation under the shared lease and record command, scope, wait time, result, and HEAD.
  - HEAD: `6911ffc5b36a981b9b06006d59d21a1c461c1351` plus the tracked cleanup worktree diff; no untracked validation note.
  - Scope: worktree-only `GRADLE_USER_HOME=/private/tmp/fukurou-192-cleanup-gradle`; shared lease wait was 0 seconds for every invocation.
  - Initial command-shape attempt: combined `test`, both admission isolation tasks, `detekt`, and `build` in one Gradle invocation, followed by strict OpenSpec validation. Gradle rejected the globally placed `--auto-correct` before project validation because `:fukurou:build` does not accept that task option.
  - Full Gradle command: `./gradlew test :fukurou:admissionHealthIsolationRegressionTest :trading:admissionHealthIsolationRegressionTest build --continue`. Result: `992 tests completed, 2 failed, 2 skipped`; all non-PostgreSQL-flake work, including `retiredIssue192WebSocketDisconnectRouteReturnsNotFound`, completed. The two failures were `PostgresPersistenceIntegrationTest.auditCoverage_separatesTerminalPendingLegacyIncompleteNoDecisionAndBoundaryStraddles` and `PostgresPersistenceIntegrationTest.admissionAndRecoveryStayWithinStatementLockAndTimeoutBudgets`, both `HikariPool.PoolInitializationException` / `PSQLException` failures.
  - Follow-up command in the shared lease: `./gradlew detekt --auto-correct --continue`, then `openspec validate remove-issue-192-fault-seam --strict`. Result: both successful; the change is valid.
  - Flake confirmation command: `./gradlew :trading:test --tests me.matsumo.fukurou.trading.persistence.PostgresPersistenceIntegrationTest.auditCoverage_separatesTerminalPendingLegacyIncompleteNoDecisionAndBoundaryStraddles --tests me.matsumo.fukurou.trading.persistence.PostgresPersistenceIntegrationTest.admissionAndRecoveryStayWithinStatementLockAndTimeoutBudgets`. Result: both passed (`BUILD SUCCESSFUL`); no shared daemon or database recovery action was performed.

## 4. Delivery

- [x] 4.1 Rewrite the two retained event-type KDocs as decoding-only vocabulary, check docs/README/KDoc for stale current-state references, run `git diff --check`, commit, push, and create a draft PR with docs impact and explicit not-planned Issue disposition.
  - Worker scope completed the implementation, documentation/KDoc grep, diff check, and local commit preparation. Push and draft PR creation remain with the parent run because this worker is explicitly prohibited from external GitHub mutation.
- [ ] 4.2 Run clean-context Claude Opus review, adjudicate anchored findings, and converge accepted must-fix/should findings.
  - S-1 accepted: the retained decoding-only labels and descriptions could be read as evidence that injection rows existed or executed, although production injection was not performed.
  - Remediation: describe both values as compatibility vocabulary that decodes a matching row only if present, and state that the writer and route are removed. Re-review remains pending.
- [ ] 4.3 Run final full validation once at converged HEAD, synchronize PR/CI evidence, and post `APPROVED` or `HANDOFF` without merging.
