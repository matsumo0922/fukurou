## ADDED Requirements

### Requirement: Versioned rolling owner-score window
The system SHALL expose additive `GET /evaluation/owner-score` for the active CURRENT account epoch over 90 consecutive Asia/Tokyo calendar days ending at the last completed day before a cutoff. An omitted cutoff SHALL mean a rolling request-time cutoff, while an explicit ISO-8601 cutoff SHALL produce a fixed-cutoff result. The response SHALL expose cutoff mode, cutoff instant, window boundaries, account epoch, and benchmark semantics version. Existing `/evaluation/benchmark` behavior and immutable report payloads SHALL remain compatible and SHALL NOT be presented as the current owner score.

#### Scenario: Rolling display
- **WHEN** a client requests `/evaluation/owner-score` without a cutoff
- **THEN** the system fixes the request clock once, labels the result `ROLLING`, and evaluates the 90 completed JST days preceding that instant

#### Scenario: Fixed review cutoff
- **WHEN** a client supplies a valid cutoff instant
- **THEN** the system labels the result `FIXED_CUTOFF` and uses that instant without reading the current clock again for window selection

#### Scenario: Unsupported population
- **WHEN** the resolved scope is not the active CURRENT account epoch
- **THEN** the system returns `UNSUPPORTED_SCOPE` and does not publish an owner score or winner

#### Scenario: Existing benchmark consumer
- **WHEN** a consumer calls `/evaluation/benchmark` with existing range or scope parameters
- **THEN** the system preserves that endpoint's current wire contract and legacy realized-equity semantics

### Requirement: Fee-inclusive liquidation-equity comparison
The system SHALL use the bot liquidation equity at the window start as the common starting capital. Bot daily equity SHALL equal epoch-scoped cash plus BTC quantity multiplied by the daily close, less the `OWNER_SCORE_V1` synthetic taker fee for liquidating the BTC. Buy and hold SHALL buy BTC with the common starting capital at the window-start close after the same synthetic entry fee and value it after the same synthetic exit fee on each valid day. Cash SHALL remain at the common starting capital. `OWNER_SCORE_V1` SHALL fix the synthetic taker fee rate at `0.0005` and expose it as an assumption distinct from actual execution-fee provenance.

#### Scenario: Open BTC position loses value
- **WHEN** the bot holds BTC whose daily close falls below its acquisition value
- **THEN** bot equity includes the unrealized loss and synthetic exit fee instead of retaining realized-PnL-only equity

#### Scenario: Buy and hold pays both synthetic fees
- **WHEN** the window boundaries are available
- **THEN** buy-and-hold BTC quantity reflects the fixed synthetic entry fee and its daily liquidation equity reflects the fixed synthetic exit fee

#### Scenario: Actual realized fees differ from the assumption
- **WHEN** paper cash already contains actual fees sourced from exchange rules or a fallback rule
- **THEN** the system preserves that cash unchanged and labels `0.0005` only as the `OWNER_SCORE_V1` synthetic entry/exit assumption

### Requirement: Causal daily account state
The system SHALL derive each daily bot point from the latest epoch-scoped `EPOCH_START`, `BOOTSTRAP`, `FILL`, or `DAILY` account snapshot captured no later than that day's end. It SHALL use cash and BTC quantity from that snapshot and the day's close; it SHALL NOT use the snapshot's stored mark price or total equity as the daily market value. Account, gap, and existing population-integrity database reads SHALL be frozen in one read-only repeatable-read transaction bounded to the 90-day window and existing query limits. Daily candles SHALL be fetched once from the existing market-data source per request and fixed as one immutable calculation input without adding candle persistence.

#### Scenario: No fill occurs on a day
- **WHEN** an earlier valid epoch snapshot exists and no later fill changes the account before day end
- **THEN** the system carries forward cash and BTC quantity and re-marks the BTC with that day's close

#### Scenario: Snapshot belongs to another epoch
- **WHEN** the latest physical snapshot before day end has a different or null account epoch
- **THEN** the system ignores that snapshot and does not mix its account state into the active epoch

#### Scenario: Same timestamp has conflicting states
- **WHEN** multiple latest snapshots share `captured_at` but have different cash or BTC quantity
- **THEN** the affected day is `UNKNOWN` with `ACCOUNT_STATE_AMBIGUOUS`

### Requirement: Strategy-population integrity
The system SHALL evaluate only the active CURRENT account epoch and SHALL reuse existing execution-semantics, cohort, attribution, and evaluation-exclusion evidence for the owner-score window. If that evidence reports legacy, unsupported, attribution-missing, cross-epoch, or excluded population, the system SHALL expose reason counts and return `INCONCLUSIVE` rather than publishing a current strategy winner. The system SHALL NOT infer conclusive lineage where existing durable evidence is insufficient.

#### Scenario: Existing evidence reports mixed semantics
- **WHEN** legacy or unsupported execution semantics are reported for the window
- **THEN** the system returns `INCONCLUSIVE` with the relevant population reason

#### Scenario: Excluded population affects the window
- **WHEN** existing evaluation evidence reports an excluded order, position, or execution in the window
- **THEN** the system withholds owner score and winner while retaining the account state as evidence

### Requirement: Truth coverage and crash-aware downtime
The system SHALL classify each of the 90 expected days as valid or unknown. A day SHALL be unknown when it intersects a persisted market-data gap, a process-restart downtime, an infrastructure gap, lacks a complete daily candle, lacks scoped account state, or depends on invalid existing population evidence. Every open gap SHALL end at the earlier of the request's frozen query instant and cutoff. For a `PROCESS_RESTART` gap, evaluation SHALL conservatively extend its effective start to the stale session's last transport activity, or connection start if absent. The system SHALL expose expected, valid, gap, unknown, and reason counts without interpolating missing evidence.

#### Scenario: Process is down across several days
- **WHEN** a stale connected session's last transport activity precedes restart recovery by several JST days
- **THEN** every intersected day from the last observed activity through recovery is `UNKNOWN`

#### Scenario: Gap remains open at query time
- **WHEN** a market-data or infrastructure gap has no recovery boundary
- **THEN** the system ends the interval at the frozen query boundary and does not classify an intersected completed day as valid

#### Scenario: Coverage meets the threshold
- **WHEN** at least 81 expected days are valid, both boundary days are valid, and population integrity is valid
- **THEN** the system returns bot and buy-and-hold liquidation returns, owner score, and `BOT`, `BUY_AND_HOLD`, or `TIE`

#### Scenario: Coverage is below threshold
- **WHEN** fewer than 81 expected days are valid
- **THEN** the system returns coverage evidence but no owner score, winner, or conclusive returns

#### Scenario: Boundary day is unknown
- **WHEN** the window-start or window-end day is unknown even though at least 81 other days are valid
- **THEN** the system returns `INCONCLUSIVE`

#### Scenario: Account epoch is too young
- **WHEN** days before epoch creation prevent a valid window-start boundary or the 81-day threshold
- **THEN** the system reports `OUTSIDE_ACCOUNT_EPOCH` separately from operational gap counts

### Requirement: Evaluation UI truthfulness
The Evaluation screen SHALL obtain the current owner score from `/evaluation/owner-score`, display liquidation returns, winner, fixed synthetic fee assumption, cutoff mode, coverage, and reasons, and render unknown points as gaps. Existing immutable report benchmark charts without `OWNER_SCORE_V1` SHALL be labeled as legacy realized-equity comparison and SHALL NOT be used as the owner score.

#### Scenario: Owner score is available
- **WHEN** the API returns an available result
- **THEN** the UI displays bot, buy-and-hold, and cash liquidation series plus owner score and winner

#### Scenario: Owner score is inconclusive
- **WHEN** the API returns `INCONCLUSIVE`
- **THEN** the UI displays coverage and reasons without a winner

#### Scenario: Legacy immutable report is displayed
- **WHEN** the Evaluation screen renders an existing report benchmark
- **THEN** it labels the chart as legacy realized equity and does not visually merge it with the owner-score panel
