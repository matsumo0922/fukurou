## 1. Evaluation facts and catalog

- [x] 1.1 Project nullable `configuredModel` from persisted runner phase payloads into `LlmPhaseUsageFact`, with repository integration coverage
- [x] 1.2 Add the versioned exact-model API list-price catalog, conservative `<272K` applicability gate, token consistency checks, and BigDecimal calculation
- [x] 1.3 Add a list-price projection and dedicated coverage without changing provider-reported `knownCostUsd` or `unpricedPhaseCount` semantics

## 2. Public contract

- [x] 2.1 Extend `GET /evaluation/costs` and route tests with additive price-source, coverage, subscription-null, and catalog metadata fields
- [x] 2.2 Regenerate the committed OpenAPI snapshot and TypeScript API types, then verify the web contract

## 3. Documentation and validation

- [x] 3.1 Update current cost semantics in `docs/design.md` and verify README/docs references for changed response names
- [x] 3.2 Run targeted production-path tests, full test/detekt/build, OpenSpec strict validation, web verification, and diff checks within the 800-line hard stop
