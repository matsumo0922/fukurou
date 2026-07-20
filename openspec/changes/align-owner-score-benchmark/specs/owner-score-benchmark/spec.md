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
The system SHALL use the bot liquidation equity at the window start as the common starting capital. Bot daily equity SHALL equal epoch-scoped cash plus BTC quantity multiplied by the daily close, less the synthetic taker fee for liquidating the BTC. Buy and hold SHALL buy BTC with the common starting capital at the window-start close after entry fee and value it after exit fee on each valid day. Cash SHALL remain at the common starting capital. The synthetic fee assumption SHALL be stored in an immutable epoch benchmark policy and exposed as an assumption distinct from actual execution-fee provenance.

#### Scenario: Open BTC position loses value
- **WHEN** the bot holds BTC whose daily close falls below its acquisition value
- **THEN** bot equity includes the unrealized loss and synthetic exit fee instead of retaining realized-PnL-only equity

#### Scenario: Buy and hold pays both synthetic fees
- **WHEN** the window boundaries and epoch benchmark policy are available
- **THEN** buy-and-hold BTC quantity reflects the synthetic entry fee and its daily liquidation equity reflects the synthetic exit fee

#### Scenario: Actual realized fees differ from the assumption
- **WHEN** paper cash already contains actual fees sourced from exchange rules or a fallback rule
- **THEN** the system preserves that cash unchanged and labels the benchmark rate only as the synthetic entry/exit fee assumption

#### Scenario: Fee assumption cannot be proven
- **WHEN** no immutable `OWNER_SCORE_V1` fee policy exists for the epoch
- **THEN** the system returns `INCONCLUSIVE` with `FEE_POLICY_UNAVAILABLE` and does not substitute the current config or a network value

### Requirement: Immutable epoch benchmark policy
The system SHALL persist one append-only benchmark policy per account epoch and semantics version. Future epoch activation SHALL insert the policy in the same transaction as the epoch and `EPOCH_START` snapshot. Provisioning MAY insert a policy for the existing active epoch only when its retained runtime-config snapshot hash is exactly verified; it SHALL NOT rewrite epoch or account history when verification fails.

#### Scenario: New account epoch is activated
- **WHEN** runtime config activation creates an account epoch
- **THEN** the same transaction inserts its `OWNER_SCORE_V1` synthetic taker fee assumption and source config hash before activation succeeds

#### Scenario: Fee config changes without an epoch switch
- **WHEN** runtime config activation changes the fallback taker fee but leaves `paper.initialCashJpy` and the active account epoch unchanged
- **THEN** the existing epoch policy remains unchanged and the response may intentionally differ from the current config because the assumption is fixed per epoch

#### Scenario: Runtime config versions are pruned
- **WHEN** a runtime-config version used to create an epoch is later removed by retention
- **THEN** the epoch benchmark policy remains available and the fixed-cutoff calculation does not change

#### Scenario: Existing epoch cannot be bootstrapped safely
- **WHEN** provisioning cannot match the active epoch hash to exactly one retained config snapshot
- **THEN** it leaves the policy absent and the API reports `FEE_POLICY_UNAVAILABLE` until an operator activates a new auditable epoch

### Requirement: Causal daily account state
The system SHALL derive each daily bot point from the latest epoch-scoped `EPOCH_START`, `BOOTSTRAP`, `FILL`, or `DAILY` account snapshot captured no later than that day's end. It SHALL use cash and BTC quantity from that snapshot and the day's close; it SHALL NOT use the snapshot's stored mark price or total equity as the daily market value. Snapshot, gap, fee-policy, population-integrity, and active-epoch reads SHALL be frozen in one repeatable-read transaction with fixed bounds.

#### Scenario: No fill occurs on a day
- **WHEN** an earlier valid epoch snapshot exists and no later fill changes the account before day end
- **THEN** the system carries forward cash and BTC quantity and re-marks the BTC with that day's close

#### Scenario: Snapshot belongs to another epoch
- **WHEN** the latest physical snapshot before day end has a different or null account epoch
- **THEN** the system ignores that snapshot and does not mix its account state into the active epoch

#### Scenario: Same timestamp has conflicting states
- **WHEN** multiple latest snapshots share `captured_at` but have different cash or BTC quantity
- **THEN** the affected day is `UNKNOWN` with `ACCOUNT_STATE_AMBIGUOUS` rather than selecting a random UUID order

#### Scenario: Epoch changes during request
- **WHEN** account epoch activation races with an owner-score request
- **THEN** the request returns a snapshot wholly before or wholly after activation, never mixed inputs from both epochs

### Requirement: Strategy-population integrity
The system SHALL evaluate all positions and executions whose exposure or account mutation intersects the owner-score window. If any such lineage is legacy, unsupported, attribution-missing, cross-epoch, or covered by an evaluation exclusion, the system SHALL expose integrity counts and return `INCONCLUSIVE` rather than including the affected cash or BTC movement in a current strategy KPI.

#### Scenario: Mixed execution semantics affect cash
- **WHEN** a legacy or unsupported position changes account cash within the window
- **THEN** the system returns `INCONCLUSIVE` with the relevant integrity reason instead of publishing a winner

#### Scenario: Position spans the window boundary
- **WHEN** a position opened before the window remains exposed or closes inside the window
- **THEN** its full entry/execution lineage is included in the integrity check

#### Scenario: Excluded position affects the account
- **WHEN** an order or position intersecting the window has a persisted evaluation exclusion
- **THEN** the system retains the real account state as evidence but withholds owner score and winner

### Requirement: Truth coverage and crash-aware downtime
The system SHALL classify each of the 90 expected days as valid or unknown. A day SHALL be unknown when it intersects a persisted market-data gap, a process-restart downtime, an infrastructure gap, lacks a complete daily candle, lacks scoped account state, or depends on an invalid or truncated source. Every open market-data or infrastructure gap with no recovered/close timestamp SHALL use the request's frozen query instant as its exclusive effective end. For a `PROCESS_RESTART` gap, evaluation SHALL conservatively extend its effective start to the stale session's last transport activity (or connection start if absent) and use recovery or the frozen query instant as its end. The system SHALL expose expected, epoch-effective, valid, gap, unknown, and reason counts.

#### Scenario: Process is down across several days
- **WHEN** a stale connected session's last transport activity precedes restart recovery by several JST days
- **THEN** every intersected day from the last observed activity through recovery is `UNKNOWN` even if the stored gap `started_at` equals restart time

#### Scenario: Gap remains open at query time
- **WHEN** a market-data gap has no `recovered_at` or an infrastructure gap has no matching CLOSE event
- **THEN** the system treats it as a half-open interval ending at the request's frozen query instant and does not classify any intersected completed day as valid

#### Scenario: Coverage meets the user-selected threshold
- **WHEN** at least 81 expected days are valid, both boundary days are valid, and population integrity is valid
- **THEN** the system returns bot and buy-and-hold liquidation returns, their difference as owner score, and `BOT`, `BUY_AND_HOLD`, or `TIE`

#### Scenario: Coverage is below threshold
- **WHEN** fewer than 81 expected days are valid
- **THEN** the system returns coverage evidence but no owner score, winner, or conclusive returns

#### Scenario: Boundary day is unknown
- **WHEN** the window-start or window-end day is unknown even though 81 other days are valid
- **THEN** the system returns `INCONCLUSIVE` because a period return cannot be grounded at both boundaries

#### Scenario: Account epoch is too young
- **WHEN** days before epoch creation prevent the 81-day threshold or a valid window-start boundary
- **THEN** the system returns `INCONCLUSIVE` with `OUTSIDE_ACCOUNT_EPOCH` counts distinct from operational gap counts

#### Scenario: Gap crosses midnight
- **WHEN** one persisted gap interval intersects multiple JST calendar days
- **THEN** every intersected day is counted once as a gap day and retains all applicable stable reason codes

### Requirement: Evaluation UI truthfulness
The Evaluation screen SHALL obtain the current owner score from `/evaluation/owner-score`, display liquidation returns, winner, synthetic fee assumption, cutoff mode, and coverage, and render unknown points as gaps. Existing immutable report benchmark charts without `OWNER_SCORE_V1` SHALL be labeled as legacy realized-equity comparison and SHALL NOT be used as the owner score.

#### Scenario: Owner score is available
- **WHEN** the API returns an available result
- **THEN** the UI displays bot, buy-and-hold, and cash liquidation series plus owner score and winner with the semantics version

#### Scenario: Owner score is inconclusive
- **WHEN** the API returns `INCONCLUSIVE`
- **THEN** the UI displays coverage and integrity reasons without a winner

#### Scenario: Legacy immutable report is displayed
- **WHEN** the Evaluation screen renders an existing report benchmark without current owner-score semantics
- **THEN** it labels the chart as legacy realized equity and does not visually merge it with the owner-score panel
