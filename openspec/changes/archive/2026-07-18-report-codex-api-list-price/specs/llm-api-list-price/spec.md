## ADDED Requirements

### Requirement: Exact Codex usage receives a static API list-price estimate
Issue #189 Codex cost DoD: The evaluation service MUST calculate an API list-price estimate only when a Codex phase has an exact catalog model identity and complete internally consistent token usage.

#### Scenario: Exact gpt-5.5 usage is priced
- **WHEN** a Codex phase records configured model `gpt-5.5`, internally consistent token counts, and total phase input below the catalog's 272,000-token applicability bound
- **THEN** the estimate charges uncached input, cached input, and output at the versioned standard API rates

#### Scenario: Reasoning output is reported separately
- **WHEN** Codex output tokens include a non-zero reasoning output token count
- **THEN** the estimate charges the total output token count once and does not add reasoning output tokens a second time

#### Scenario: Provider-reported cost already exists
- **WHEN** a phase contains provider-reported monetary cost and also matches a static catalog entry
- **THEN** provider-reported cost remains in `knownCostUsd` while the independent catalog conversion remains in `apiListPriceEquivalentUsd`, and no field combines the two bases

### Requirement: Unprovable estimates remain unpriced
The evaluation service MUST NOT invent a model, coerce incomplete usage to zero, or return a catalog estimate for inconsistent token facts.

#### Scenario: Configured model is unavailable
- **WHEN** a Codex phase records `CLI_DEFAULT` without an exact configured model name
- **THEN** the phase remains unpriced even if the current pinned CLI is known to default to a cataloged model

#### Scenario: Model is absent from the catalog
- **WHEN** a phase records a model name that has no exact catalog entry
- **THEN** the phase remains unpriced and the response preserves its token/model coverage facts

#### Scenario: Cached input exceeds total input
- **WHEN** cached input exceeds total input, reasoning output exceeds total output, or a required token count is negative or missing
- **THEN** the phase remains unpriced rather than producing a negative or partial estimate

#### Scenario: Phase aggregate cannot prove the standard price tier
- **WHEN** total phase input is 272,000 tokens or greater and per-request context lengths are not persisted
- **THEN** the phase remains API-list-price unpriced instead of assuming either the standard or long-context rate

### Requirement: Public cost sources remain distinct
`GET /evaluation/costs` MUST distinguish provider-reported cost, catalog-derived API list-price equivalent, and unavailable subscription actual cost while preserving existing response fields.

#### Scenario: Mixed Claude and Codex phases are aggregated
- **WHEN** a period contains a Claude phase with provider-reported cost and an exact catalog-priced Codex phase
- **THEN** `knownCostUsd` contains only provider-reported cost and `apiListPriceEquivalentUsd` contains only the Codex catalog conversion without combining the two bases

#### Scenario: Subscription actual cost is requested
- **WHEN** the cost response is serialized for CLI subscription-backed phases
- **THEN** `subscriptionActualCostUsd` is null and the response identifies the estimate catalog version, as-of date, standard API basis, and official source

#### Scenario: Legacy consumer reads the response
- **WHEN** an existing client reads `knownCostUsd`, provider groups, model token groups, or coverage counts
- **THEN** those fields remain present and retain their documented provider-reported/token meanings, including the existing `unpricedPhaseCount`

#### Scenario: Configured model exists without provider-observed model usage
- **WHEN** a phase has `configuredModel` and aggregate token usage but `usage.modelUsages` is empty
- **THEN** list-price projection may use `configuredModel`, while legacy `byModel` excludes the phase and `unattributedTokenPhaseCount` continues to count it as unattributed

#### Scenario: Model token groups are returned
- **WHEN** `byModel` contains provider-observed token groups
- **THEN** model entries contain only the legacy token fields and do not contain an API list-price equivalent

#### Scenario: API list-price coverage is read
- **WHEN** catalog-priced and proof-insufficient Codex phases coexist
- **THEN** dedicated covered and unpriced counts describe the list-price projection without changing provider-reported coverage

#### Scenario: Production route projects persisted Codex facts
- **WHEN** `GET /evaluation/costs` reads a persisted `RUNNER_PHASE_COMPLETED` payload with configured model and Codex usage
- **THEN** the public response contains the catalog estimate and updated pricing coverage without a database backfill
