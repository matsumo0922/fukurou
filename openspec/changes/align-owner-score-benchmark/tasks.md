## 1. Immutable benchmark policy

- [ ] 1.1 Add append-only `paper_account_epoch_benchmark_policies`, immutability/schema checks, backup inventory/critical-table PK verification, and bounded snapshot lookup indexes
- [ ] 1.2 Insert `OWNER_SCORE_V1` policy atomically during future epoch activation and prove an insert failure rolls back the config/epoch/account transaction
- [ ] 1.3 Provision the active epoch policy only on exact retained-config hash verification and test retention independence / fail-closed absence

## 2. Frozen benchmark evidence

- [ ] 2.1 Define versioned owner-score input/result, point, coverage, fee-assumption, population-integrity, winner, and reason models; extend snapshot reader models for `accountEpochId` and `EPOCH_START`
- [ ] 2.2 Implement one explicitly read-only repeatable-read bounded query for active epoch, policy, 90 daily account states, gaps, and population integrity, with an activation-race/isolation regression test
- [ ] 2.3 Derive `PROCESS_RESTART` downtime from last transport activity, cap every open market/infrastructure gap at frozen `queryNow/cutoff`, and test multi-day crash, never-recovered gaps, missing session evidence, and normal disconnect intervals
- [ ] 2.4 Reuse execution-lineage/exclusion rules for positions/executions intersecting the window, including positions spanning its start

## 3. Shared liquidation calculation

- [ ] 3.1 Implement synthetic-fee bot liquidation, window-start buy-and-hold, cash, returns, and owner-score math with explicit decimal scale
- [ ] 3.2 Implement the 90-day/81-valid-day gate, mandatory boundaries, gap union, young-epoch reasons, and nullable unknown points
- [ ] 3.3 Add regression tests for unrealized loss, actual-fee cash preservation, synthetic entry/exit fee, no-fill carry-forward, ambiguous snapshots, population failure, missing boundaries, and gap overlap

## 4. Additive API and Evaluation UI

- [ ] 4.1 Add `GET /evaluation/owner-score` with `ROLLING` / `FIXED_CUTOFF` semantics and route-local Japanese OpenAPI
- [ ] 4.2 Add compatibility tests proving `/evaluation/benchmark` and immutable report JSON remain unchanged
- [ ] 4.3 Regenerate OpenAPI TypeScript types and add the Evaluation owner-score panel with liquidation labels, cutoff, fee assumption, coverage, and integrity
- [ ] 4.4 Render unknown points as chart gaps/reason rows and relabel the existing report chart as legacy realized equity

## 5. Documentation and validation

- [ ] 5.1 Update `README.md` and `docs/design.md` with the formula, 90% rule, crash projection, fee assumption (including fee-only config activation), cutoff semantics, backup-profile transition, and residual risks
- [ ] 5.2 Grep README/docs for `benchmark`, `Bot realized`, `owner score`, and `buy & hold` and reconcile stale descriptions
- [ ] 5.3 Run focused PostgreSQL/trading/route/Web tests, OpenSpec validation, `make test`, `make detekt`, `make build`, and Web test/build
- [ ] 5.4 Read current epoch age and window lineage/gap reason counts without mutation, verify fixed-cutoff determinism after runtime-config pruning, and verify crash/open-gap/mixed-lineage fixtures remain `INCONCLUSIVE`
