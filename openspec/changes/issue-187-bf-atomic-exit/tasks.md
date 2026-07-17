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

## 4. Contract and regression tests

- [x] 4.1 Cover same-thesis cancellation, unrelated-thesis preservation, order-only EXIT, and missing/null/multiple/stale linkage mutation-zero failures for Exposed and in-memory repositories
- [x] 4.2 Replace the existing EXIT-plus-resting-order expectation and add deterministic PostgreSQL barrier races for EXIT-first and fill-first convergence with duplicate execution zero
- [x] 4.3 Add HARD_HALT commit-before/commit-after-response-loss/retry tests, startup/connect-failure/periodic retry tests, flat-without-tick SAFE tests, SAFE evidence tests, old-writer stale-SAFE rollback tests, and UNKNOWN/open-risk resume rejection tests
- [ ] 4.4 Run targeted production-entrypoint tests and record each OpenSpec Scenario's proving test at the validated HEAD

## 5. Documentation and validation

- [x] 5.1 Update the system prompt, README, `docs/design.md`, and `docs/mcp-runtime.md` in current tense and grep affected feature/class/command names for stale descriptions
- [ ] 5.2 Run `openspec validate issue-187-bf-atomic-exit`, `make test`, `make detekt`, and `make build` under the validation lease and record command, scope, result, and HEAD
