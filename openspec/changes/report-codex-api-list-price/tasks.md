## 1. Evaluation facts and catalog

- [ ] 1.1 Project nullable `configuredModel` from persisted runner phase payloads into `LlmPhaseUsageFact`, with repository integration coverage
- [ ] 1.2 Add the versioned exact-model API list-price catalog and BigDecimal calculation for uncached input, cached input, and non-duplicated output
- [ ] 1.3 Extend cost aggregation and model/provider coverage so provider-reported, catalog-estimated, equivalent, and unpriced phases remain distinct

## 2. Public contract

- [ ] 2.1 Extend `GET /evaluation/costs` and route tests with additive price-source, coverage, subscription-null, and catalog metadata fields
- [ ] 2.2 Regenerate the committed OpenAPI snapshot and TypeScript API types, then verify the web contract

## 3. Documentation and validation

- [ ] 3.1 Update current cost semantics in `docs/design.md` and verify README/docs references for changed response names
- [ ] 3.2 Run targeted production-path tests, full test/detekt/build, OpenSpec strict validation, web verification, and diff checks within the 800-line hard stop
