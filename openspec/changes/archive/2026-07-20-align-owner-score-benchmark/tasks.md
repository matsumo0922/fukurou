## 1. Benchmark calculation

- [x] 1.1 In a sibling worktree, read current production epoch age, first epoch-attributed snapshot date, and 90-day gap durations without mutation; record whether V1 initially returns `INCONCLUSIVE`
- [x] 1.2 Update benchmark models for nullable daily liquidation points, coverage, cutoff mode, semantics version, owner score, and winner
- [x] 1.3 Add owner-score math with active-epoch snapshot mark-to-market, fixed `0.0005` synthetic fee, window-start B&H, and cash comparison without changing legacy `EvaluationMath.benchmark`
- [x] 1.4 Add focused tests for open-position loss, constant-price B&H entry/exit fee with common-`S` returns, missing expected candle slots, carry-forward/06:00 JST boundary, 00:30 JST cutoff, and the 81/90 plus valid-boundary gate

## 2. Existing evidence reads

- [x] 2.1 Add `accountEpochId` and `EPOCH_START` support to the snapshot reader model
- [x] 2.2 Add bounded reads for the active epoch's snapshots and persisted market-data gaps over the 90-day window; do not reuse unbounded `findAll()`
- [x] 2.3 Sum persisted gaps by 06:00 JST GMO business day, mark days with at least one hour unknown, and test both short and material gaps

## 3. Existing API and UI

- [x] 3.1 Update `GET /evaluation/benchmark`, its Japanese `.describe {}`, and route tests for rolling/fixed cutoff, rejected `from` / `to`, active CURRENT scope, and owner-score response
- [x] 3.2 Regenerate OpenAPI TypeScript types and update the existing Evaluation benchmark card; render unknown days as gaps
- [x] 3.3 Keep legacy benchmark math and immutable report facts/hash unchanged, preserve the legacy-scope protection test, and label old report benchmark as legacy where displayed

## 4. Documentation and validation

- [x] 4.1 Update `README.md` and `docs/design.md` with current V1 formula, 0.05% fee assumption and bias, 90% coverage rule, one-hour gap threshold, cutoff, and known limits
- [x] 4.2 Grep README/docs for stale realized-only benchmark descriptions
- [x] 4.3 Run focused tests, OpenSpec strict validation, `make test`, `make detekt`, `make build`, and Web tests/build
