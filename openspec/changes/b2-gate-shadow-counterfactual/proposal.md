## Why

SafetyFloor や Falsifier が取引プランを拒否したとき、そのプランが「拒否されて正解だったのか、機会損失だったのか」を判断する材料が存在しない。拒否は記録されるが、拒否時点の plan geometry がその後どうなったか（STOP に触れたか、TP に届いたか、そもそも約定しなかったか）は観測されていない。

Epic #180 の C 群（trailing / TTL / EV 閾値）を緩める判断には、「閾値を通していたらどうなっていたか」の分布が要る。`b2-safety-floor-rule-margins` が「どれだけの余裕で通過・拒否したか」を与えるのに対し、本 change は「拒否したプランのその後」を与える。

この観測は paper 真実性の規約と正面から衝突しうる。「約定した可能性がある」を「約定した」に変換する誘惑が構造的に存在するため、遡及 fill の禁止を設計の中心に置く。

## What Changes

- 拒否・失効した plan geometry に対する事後結果（`FILLED_EQUIVALENT` / `STOP_HIT` / `TP_HIT` / `NO_FILL` / `UNKNOWN_DATA_GAP`）を、shadow 専用テーブルへ append する
- 追跡の起点として **activation cursor**（`admission_ordinal` と market 時刻の対）を拒否時点で確定させ、それ以前の market event を fill 判定に使わない
- 固定の observation horizon を設け、期限内に決着しなかったプランを `NO_FILL` として閉じる。無期限に「いつか当たった」を探さない
- market data gap と交差した観測を `UNKNOWN_DATA_GAP` として残し、決着済みの母集団に混ぜない
- 追跡対象を **entry geometry が確定している経路に限定する**。geometry を持たない NO_TRADE には架空 entry を生成しない
- `paper_market_event_receipts` を ordinal 区間で読む range reader を新設する
- daemon tick に相乗りする fail-open な resolver を新設する

**BREAKING なし**

## Capabilities

### New Capabilities

- `gate-shadow-counterfactual`: 拒否・失効した plan geometry の事後結果を、遡及 fill を禁じた形で追跡する。paper ledger を変更しないこと、activation 前の event を使わないこと、決着不能を決着として記録しないことを不変条件として含む。

### Modified Capabilities

なし

## Impact

### 追跡対象の確定（調査で判明した構造）

`recordNoTrade` の choke point（`OneShotLlmRunner.kt:1928` / `DecisionExecutionLifecycle.kt:693` / `CallerNoTradeGuard.kt:44`）は **geometry を一切運んでいない**。渡るのは `DecisionRunContext` / `reason` / `cause` のみで、`CommandEvent(NO_TRADE_EXIT)` の payload にも geometry は入らない。したがって choke point へのフックでは追跡できない。

geometry が確定している経路は以下に限られる。

| ゲート | 位置 | geometry の入手元 |
|---|---|---|
| Falsifier 拒否（3 reason） | `OneShotLlmRunner.kt:1456` → `recordFalsificationNoTrade` | `runApprovedEntryFlow` 冒頭の `requireNotNull(decision.tradeIntent)` |
| provider failure / phase observation | `OneShotLlmRunner.kt:1318` / `:1339` / `:1472` | `decision.tradeIntent.draft` |
| place_order 失敗・拒否 | `OneShotLlmRunner.kt:1502` / `:1720` | `intent` + `intent.toPlaceOrderCommand(...)` |
| SafetyFloor 拒否（EV 含む） | `PaperBroker.kt:369-384` | `PlaceOrderCommand` |
| TTL 失効 | `InMemoryPaperLedgerRepository.kt:757-778` / `ExposedPaperLedgerWriter.kt:807-838` | `Order` 行（`limitPriceJpy` / `protectiveStopPriceJpy` / `takeProfitPriceJpy` を保持） |

`DecisionExecutionLifecycle.kt` の 7 経路（L131 / L158 / L194 / L220 / L264 / L291 / L314）は全て EXIT / REDUCE / ADD_LONG / ADJUST_PROTECTION、すなわち既存 position の管理であり、新規 entry plan geometry が概念的に存在しない。**追跡対象から除外する**。issue の禁止事項「executable plan のない NO_TRADE への架空 entry 生成」がまさにこの経路群を指す。

EV 拒否は `place_order_rejected` に潰れて記録されるため、`PaperTradeResult.safetyViolation.rule` で識別する。

### 市場データの事後参照

- `persistence/TradingTables.kt:856-870` `paper_market_event_receipts` — `normalized_payload` に `{exchangeAt, priceJpy, side, sizeBtc, symbol}` が入る（`ExposedMarketDataIntegrityRepository.kt:313-325`）。**デコーダは存在しないため新設が必要**
- `admission_ordinal` はグローバル sequence `paper_market_admission_ordinal_seq`（`TradingPersistenceBootstrap.kt:482-484`）。session をまたいで単調。ただし rollback による欠番が生じうる
- **時刻範囲 index は存在しない**（既存は `(session_id, source_sequence)` と `(admission_ordinal)` の 2 つの UNIQUE のみ）。ordinal 区間で読めば index 追加が不要
- `market/PaperMarketEventReceiptRepository.kt:29-32` は `commit` のみ。range read API を新設する
- `market_data_gaps` / `evaluation_exclusions`（`TradingTables.kt:872-897`）を `UNKNOWN_DATA_GAP` 判定に使う。範囲クエリは新規だが `idx_market_data_gaps_unresolved_started` が部分的に効く

### 実行基盤

- `daemon/RestingOrderMaintenanceService.kt:36-45` の `OpportunityEpisodeLifecycleObserver`（`suspend fun observe(observedAt: Instant): Result<Unit>`）と同型の fun interface を追加する
- `daemon/LlmDaemonScheduler.kt:284` 付近で配線する。既存 observer は `.getOrThrow()` されており失敗が tick 全体を落とすため、**新 observer は fail-open にする**
- tick 間隔は 1 分（`TradingBotConfig.kt:829`）

### identity の再利用

- `decision/identity/DecisionIdentityGenerator.kt` の `geometryCanonical`（`:70-80`）が entry / stop / tp を含む正準形を既に持つ。`geo_v1_` hash をそのまま shadow の geometry 識別子に使う
- `opportunity_episodes.id`（UUID 単独）を FK 参照する
- `dedupe_shadow_observations` / `dedupe_shadow_resolutions`（`TradingTables.kt:1253-1280`）の observation → resolution 2 段 append-only 構造を**設計の雛形として踏襲する**。ただし dedupe 判定に型付けされたカラム（`distance_jpy` / `atr_price_ratio` / `invalidation_state`）を持つため、同居させず新テーブルを切る

### 明示的に含めないもの

- 較正ダッシュボード / calibration curve / p 較正の常設 UI
- causal coverage ≥99% の DoD（unknown が silent に混ざらないことのみ要求）
- klines による過去分の retrospective 推定（`LEGACY_RETROSPECTIVE_SENSITIVITY_V0` は既存のまま触らない）
- SafetyFloor の margin 保存（別 change `b2-safety-floor-rule-margins`）
