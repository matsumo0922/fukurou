## ADDED Requirements

### Requirement: Versioned 90-day owner-score window
The system SHALL use the existing `GET /evaluation/benchmark` endpoint to evaluate the active CURRENT account epoch over 90 consecutive expected GMO one-day business slots whose boundary is 06:00 Asia/Tokyo. An omitted cutoff SHALL be `ROLLING`; an explicit cutoff instant SHALL be `FIXED_CUTOFF`. The system SHALL generate the 90 slots backward from the latest expected close boundary at or before the cutoff, then match fetched candles to those slots. It SHALL NOT replace a missing slot with an older available candle. Existing `from` / `to` parameters SHALL be rejected. An explicit `epochId` / `cohort` SHALL be accepted only when it identifies the active CURRENT scope. The response SHALL expose the cutoff, window, account epoch, and `OWNER_SCORE_V1` semantics version.

#### Scenario: Rolling display
- **WHEN** the client requests `/evaluation/benchmark` without a cutoff
- **THEN** the system evaluates the latest 90 GMO business candles closed by the request instant and labels the result `ROLLING`

#### Scenario: Fixed review cutoff
- **WHEN** the client supplies a valid cutoff instant
- **THEN** the system evaluates the latest 90 GMO business candles closed by that cutoff and labels the result `FIXED_CUTOFF`

#### Scenario: Cutoff precedes the daily close
- **WHEN** the cutoff is 00:30 JST and the next GMO daily candle closes at 06:00 JST
- **THEN** the system excludes that not-yet-closed candle and does not read snapshots or prices after the cutoff

#### Scenario: A candle is missing inside the window
- **WHEN** one expected close slot has no matching fetched candle
- **THEN** the system keeps that slot in the 90-day window as `UNKNOWN` instead of pulling in a 91st older candle

#### Scenario: Epoch is younger than the window
- **WHEN** part of the 90 days precedes the active account epoch
- **THEN** those days remain `OUTSIDE_ACCOUNT_EPOCH` and the denominator remains 90

#### Scenario: Legacy or non-active scope is requested
- **WHEN** `epochId` or `cohort` does not identify the active CURRENT scope
- **THEN** the system returns `UNSUPPORTED_SCOPE` without owner score

#### Scenario: Legacy snapshot has no epoch
- **WHEN** a historical snapshot has no `account_epoch_id`
- **THEN** the system excludes it from the current owner score without backfill or deletion

### Requirement: Fee-inclusive liquidation comparison
The system SHALL derive daily bot equity from the latest active-epoch account snapshot no later than each day end and the daily close. Bot equity SHALL include a synthetic exit fee. Buy and hold SHALL buy at the window-start close and include synthetic entry and exit fees. Cash SHALL remain at the common window-start bot liquidation equity. `OWNER_SCORE_V1` SHALL use and expose the fixed synthetic taker fee rate `0.0005`; actual execution fees already reflected in cash SHALL NOT be added again. The response SHALL disclose that carried bot BTC avoids the fresh B&H entry fee and may favor bot by about 0.1%.

#### Scenario: Open BTC loses value
- **WHEN** the bot holds BTC and the daily close falls
- **THEN** bot equity includes the unrealized loss and synthetic liquidation fee

#### Scenario: Buy and hold pays comparison fees
- **WHEN** valid start and end closes exist
- **THEN** buy-and-hold quantity includes the synthetic entry fee and its liquidation value includes the synthetic exit fee

#### Scenario: No account change occurs on a day
- **WHEN** no new snapshot occurs before a day end
- **THEN** the latest earlier epoch-scoped cash and BTC quantity are carried forward and re-marked at that day's close

### Requirement: Evidence-based coverage
The system SHALL classify each expected GMO business day as valid or unknown using available daily candles, active-epoch account snapshots, and persisted `market_data_gaps`. Snapshot selection and gap bucketing SHALL use the same 06:00 JST candle boundaries. An open gap SHALL end at the request cutoff for evaluation. A business day SHALL become unknown from gaps only when its cumulative persisted gap duration is at least one hour. The response SHALL expose expected, valid, gap, unknown, gap seconds, and reason counts without filling missing values.

#### Scenario: Material persisted gap crosses a day
- **WHEN** persisted gaps total at least one hour within a JST day
- **THEN** that day is unknown and counted once as a gap day

#### Scenario: Short persisted gap
- **WHEN** persisted gaps total less than one hour within a JST day
- **THEN** the system reports their count and duration but does not invalidate the whole day

#### Scenario: Input is missing
- **WHEN** a daily candle or usable epoch account snapshot is missing
- **THEN** the day is unknown and no value is interpolated

### Requirement: Conclusive winner gate
The system SHALL publish returns and `ownerScore = botReturn - buyAndHoldReturn` only when at least 81 of 90 business days are valid and both window boundaries are valid. Every return SHALL use the common starting capital `S` as its denominator: `botEnd / S - 1`, `buyHoldEnd / S - 1`, and cash return zero. It SHALL NOT derive these returns from each series' first point. A positive score SHALL mean `BOT`; zero or a negative score SHALL mean `BUY_AND_HOLD`. Otherwise it SHALL return coverage with `INCONCLUSIVE` and null score/winner.

#### Scenario: Constant-price fee comparison
- **WHEN** the start and end closes are equal and the bot holds BTC throughout the window
- **THEN** buy-and-hold return retains its synthetic entry and exit fees because its denominator is `S`

#### Scenario: Coverage is sufficient
- **WHEN** at least 81 days and both boundary days are valid
- **THEN** the system returns bot, buy-and-hold, and cash returns plus owner score and winner

#### Scenario: Coverage is insufficient
- **WHEN** fewer than 81 days are valid or either boundary is unknown
- **THEN** the system returns `INCONCLUSIVE` without score or winner

### Requirement: Existing Evaluation UI
The existing Evaluation benchmark card SHALL show the V1 liquidation series, owner score, winner, fee assumption and bias, cutoff mode, and coverage. Unknown days SHALL appear as chart gaps. The system SHALL keep the legacy `EvaluationMath.benchmark` calculation and immutable report payload/hash unchanged. Immutable reports that contain realized-equity benchmark facts SHALL be labeled as legacy when shown and SHALL NOT be presented as owner score.

#### Scenario: Owner score is available
- **WHEN** `/evaluation/benchmark` returns a conclusive result
- **THEN** the existing card displays the liquidation comparison and winner

#### Scenario: Owner score is inconclusive
- **WHEN** the endpoint returns `INCONCLUSIVE`
- **THEN** the card displays coverage reasons without a winner
