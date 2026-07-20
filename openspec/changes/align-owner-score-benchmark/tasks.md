## 1. Owner-score model and calculation

- [ ] 1.1 Add versioned owner-score input/result, daily point, coverage, population reason, winner, and fixed `OWNER_SCORE_V1` synthetic fee models
- [ ] 1.2 Implement bot liquidation, window-start buy-and-hold, cash, return, owner-score, and 90-day/81-valid-day calculations with explicit decimal scale
- [ ] 1.3 Add focused tests for unrealized loss, actual-fee cash preservation, synthetic entry/exit fee, no-fill carry-forward, missing boundaries, and unknown-day coverage

## 2. Existing-evidence snapshot

- [ ] 2.1 Add one bounded read-only repeatable-read owner-score query for the active CURRENT epoch, 90 daily candles/account states, existing gaps, execution semantics, and evaluation exclusions
- [ ] 2.2 Project `PROCESS_RESTART` from last transport activity and cap open market/infrastructure gaps at the frozen query/cutoff boundary
- [ ] 2.3 Add focused PostgreSQL tests for epoch isolation, ambiguous snapshots, young epochs, multi-day/open gaps, and existing legacy/exclusion reasons

## 3. Additive API contract

- [ ] 3.1 Add `GET /evaluation/owner-score` with `ROLLING` / `FIXED_CUTOFF` semantics and route-local Japanese OpenAPI
- [ ] 3.2 Add route/compatibility tests proving `/evaluation/benchmark` and immutable report JSON remain unchanged
- [ ] 3.3 Update `README.md` and `docs/design.md` with the formula, fixed synthetic fee assumption, coverage rule, cutoff semantics, and residual limits

## 4. Evaluation UI

- [ ] 4.1 Regenerate OpenAPI TypeScript types and add an independently queried owner-score panel with liquidation values, winner, cutoff, fee, coverage, and reasons
- [ ] 4.2 Render unknown points as chart gaps/reason rows and relabel the existing report chart as legacy realized equity
- [ ] 4.3 Add focused UI tests for available/inconclusive results and the legacy label

## 5. Validation

- [ ] 5.1 Grep README/docs for `benchmark`, `Bot realized`, `owner score`, and `buy & hold` and reconcile stale descriptions
- [ ] 5.2 Run focused PostgreSQL/trading/route/Web tests, OpenSpec strict validation, `make test`, `make detekt`, `make build`, and Web verify
- [ ] 5.3 After normal deploy, read owner-score rolling/fixed-cutoff results without mutation and confirm expected `INCONCLUSIVE` reasons remain visible
