## 1. Durable HARD_HALT cleanup state

- [ ] 1.1 Add the minimal UNKNOWN/SAFE cleanup state to risk models and additive PostgreSQL bootstrap with fresh-upgrade and old-reader compatibility tests
- [ ] 1.2 Set cleanup UNKNOWN on a new HARD_HALT, preserve SAFE on duplicate halt commands, and reject manual resume until SAFE in Exposed and in-memory services

## 2. Atomic paper risk-exit primitive

- [ ] 2.1 Add typed SAME_THESIS/ALL_OPEN_RISK request and failure contracts to the paper ledger mutation interface
- [ ] 2.2 Implement Exposed linkage resolution, latest locked full-size fill calculation, status CAS, atomic cancel/close/account mutation, and SAFE terminal write using the existing lock order
- [ ] 2.3 Implement equivalent in-memory atomic semantics and canonical thesis linkage state without using trade-group equality as a substitute

## 3. Production call-path wiring

- [ ] 3.1 Route full EXIT with an open position through SAME_THESIS atomic risk exit while preserving deterministic order-only EXIT and fail-closed target selection
- [ ] 3.2 Route SafetyFloor, drawdown, reconciler, and kill-criterion HARD_HALT cleanup through ALL_OPEN_RISK and keep risk-reducing execution available
- [ ] 3.3 Run a bounded startup HARD_HALT/UNKNOWN cleanup attempt before the production WebSocket loop and retain periodic/event retries

## 4. Contract and regression tests

- [ ] 4.1 Cover same-thesis cancellation, unrelated-thesis preservation, order-only EXIT, and missing/null/multiple/stale linkage mutation-zero failures for Exposed and in-memory repositories
- [ ] 4.2 Replace the existing EXIT-plus-resting-order expectation and add deterministic PostgreSQL barrier races for EXIT-first and fill-first convergence with duplicate execution zero
- [ ] 4.3 Add HARD_HALT commit-before/commit-after-response-loss/retry tests, startup and periodic retry tests, SAFE evidence tests, and UNKNOWN resume rejection tests
- [ ] 4.4 Run targeted production-entrypoint tests and record each OpenSpec Scenario's proving test at the validated HEAD

## 5. Documentation and validation

- [ ] 5.1 Update the system prompt, README, `docs/design.md`, and `docs/mcp-runtime.md` in current tense and grep affected feature/class/command names for stale descriptions
- [ ] 5.2 Run `openspec validate issue-187-bf-atomic-exit`, `make test`, `make detekt`, and `make build` under the validation lease and record command, scope, result, and HEAD
