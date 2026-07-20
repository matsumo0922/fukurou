## Context

拒否・失効した取引プランの「その後」が観測されていない。Epic #180 の C 群（trailing / TTL / EV 閾値）を緩める判断には、「閾値を通していたらどうなっていたか」の分布が要る。

この観測は paper 真実性の規約と正面から衝突しうる。設計の中心的な制約は次の 3 つである。

- activation 前の market event で fill を判定しない（遡及 fill 禁止）
- 「約定した可能性がある」を「約定した」に変換しない
- 決着不能を決着として記録しない

### paper の約定意味論（本設計の最重要制約）

調査により、paper の resting entry fill が **naive な touch モデルではない**ことが判明した。`InMemoryPaperLedgerRepository.kt:1016-1029` の `consumeLimitQueue`:

```kotlin
if (event.side != OrderSide.SELL) return false
if (event.priceJpy > requireNotNull(order.limitPriceJpy).toBigDecimal()) return false
val queueAhead = requireNotNull(eligibility.queueAheadBtc) { "LIMIT queue snapshot is unavailable." }
val consumed = orderQueueConsumedBtc.getOrDefault(order.orderId, ZERO).add(event.sizeBtc).btcScale()
orderQueueConsumedBtc[order.orderId] = consumed
return consumed >= queueAhead.add(order.sizeBtc.toBigDecimal())
```

BUY LIMIT が約定するには、価格が限度以下の SELL 約定が **`queueAheadBtc + orderSize` 相当の出来高**を消化する必要がある。`queueAheadBtc` は注文時に板を REST で取得して算出される（`PaperBroker.kt:475-483` の `calculateQueueAhead` → `getOrderbook`）。

**この `queueAheadBtc` が、拒否されたプランには存在しない。**

- SafetyFloor 拒否は `PaperBroker.kt:369-384` で起き、queue snapshot を作る `currentRestingOrderMarketEligibility`（`:432-465`）**より前**に return する
- Falsifier 拒否はそもそも注文経路に入らない
- 板の深さは永続化されていないため、事後に再構成できない

したがって、拒否されたプランについて **paper と同じ意味論で「約定したか」を判定することは原理的に不可能**である。

唯一の例外が TTL 失効で、この場合は注文が実際に置かれており `queue_ahead_btc` が `TradingTables.kt:645` に永続化されている。

## Goals / Non-Goals

**Goals:**

- 拒否・失効した plan geometry の事後結果を、判定可能な範囲だけ確定させて保存する
- 判定不能を判定不能として残し、決着済みの母集団と混ぜない
- activation cursor により遡及 fill を構造的に禁じる
- entry geometry を持たない NO_TRADE を追跡対象から除外する

**Non-Goals:**

- 較正ダッシュボード / calibration curve / p 較正の常設 UI
- causal coverage ≥99% の DoD
- klines による過去分の retrospective 推定
- 板深さの永続化（新たな market data 収集基盤の追加）
- paper の約定意味論そのものの変更

## Decisions

### D1: 判定可能性の非対称を outcome 語彙に反映する（agent 仮決め・本設計の中核）

queue 位置が不明なとき、「約定しなかった」は確定できるが「約定した」は確定できない。この非対称は消せないので、outcome 語彙に持ち込む。

**価格が一度も限度を跨がなければ、queue 位置がどうであれ約定しない。** これは queue モデルに依存しない確実な事実である。逆に価格が跨いだ場合、約定するかは queue 位置に依存し、それは失われている。

| outcome | 意味 | 判定に必要なもの |
|---|---|---|
| `NO_FILL_CERTAIN` | horizon 内に限度価格を跨ぐ event が存在しない。queue 位置によらず約定しない | 価格系列のみ |
| `FILL_UNDETERMINED` | 限度価格を跨いだが、queue 位置が不明なため約定の有無を確定できない | 価格系列のみ |
| `FILLED_EQUIVALENT` | paper と同一の queue 消費条件を満たした | `queueAheadBtc` |
| `STOP_HIT` | 約定確定後、TP より先に stop へ到達した | `queueAheadBtc` |
| `TP_HIT` | 約定確定後、stop より先に TP へ到達した | `queueAheadBtc` |
| `UNKNOWN_DATA_GAP` | horizon が market data gap と交差した | — |

`queueAheadBtc` を持つのは TTL 失効のみである。したがって

- **TTL 失効**: `FILLED_EQUIVALENT` / `STOP_HIT` / `TP_HIT` / `NO_FILL_CERTAIN` / `UNKNOWN_DATA_GAP` の全語彙を使う
- **SafetyFloor 拒否 / Falsifier 拒否 / その他の entry 拒否**: `NO_FILL_CERTAIN` / `FILL_UNDETERMINED` / `UNKNOWN_DATA_GAP` のみを使う

**検討して棄却した代替案:**

| 案 | 棄却理由 |
|---|---|
| touch = fill と見なす | paper より緩い基準になり、拒否プランの約定を系統的に過大報告する。「約定した可能性がある」を「約定した」に変換する行為そのもので、paper 真実性の規約に反する |
| 拒否時点で板を取得して queue snapshot を作る | 拒否は hot path であり、REST の板取得を足すと拒否のたびに外部 I/O が入る。Falsifier 拒否は注文経路の外で起きるため broker の板取得経路も使えない。**stage-out 候補**として Next steps に置く |
| 板深さを常時永続化して事後再構成する | 依頼されていない収集基盤の新設にあたる。AGENTS.md の「新しい計測・監視 harness は受け入れ条件である場合だけ」に反する |
| queue 位置を平均値などで推定する | 推定値による約定判定は paper 真実性の禁止事項に直接抵触する |

### D2: 条件付き軌跡を別フィールドに持つ（agent 仮決め）

`FILL_UNDETERMINED` でも C 群の判断には使える情報がある。「もし約定していたら、stop と TP のどちらに先に到達したか」は価格系列だけで判定できるためである。

これを outcome とは**別のフィールド** `conditional_trajectory`（`STOP_FIRST` / `TP_FIRST` / `NEITHER`）に持つ。outcome を汚さないことが要点で、`FILL_UNDETERMINED` は最後まで「約定したとは言っていない」。

SQL 上は「`FILL_UNDETERMINED` かつ `conditional_trajectory = TP_FIRST` の件数」が機会損失の**上界**を、「`NO_FILL_CERTAIN` の件数」が機会損失でなかったことの**下界**を与える。C 群の判断にはこの区間で足りる。

### D3: activation cursor は ordinal と market 時刻の対とする（agent 仮決め）

cursor を `(admission_ordinal, marketTimestamp)` の対とし、event は **両方**の条件を満たす場合にのみ fill 判定へ用いる。

```
event.admission_ordinal > cursor.ordinal AND event.sourceTimestamp >= cursor.marketTimestamp
```

`admission_ordinal` はグローバル sequence `paper_market_admission_ordinal_seq`（`TradingPersistenceBootstrap.kt:482-484`）で session をまたいで単調だが、rollback による欠番が生じうる。また異なる session の並行トランザクションで採番順と commit 順が入れ替わる余地がある（調査時点で未確定）。

ordinal 単独では、採番順と市場時刻の逆転により activation 前の event を取り込む可能性が残る。時刻条件を AND で重ねることで、**逆転が起きた場合は event を捨てる側**（保守側）に倒れる。取りこぼしは fill の過小評価になり、`NO_FILL_CERTAIN` を過大に出す方向だが、これは「約定を捏造しない」という優先順位に沿う。

なお paper 本体も同型の cursor を持つ（`isEligibleForMarketEvent` の `eligibleAfterSequence` と `eligibleFrom`、`InMemoryPaperLedgerRepository.kt:1031-1039`）。shadow の cursor 設計はこれに倣う。

### D4: 追跡対象の限定（agent 仮決め）

`recordNoTrade` の choke point（`OneShotLlmRunner.kt:1928` / `DecisionExecutionLifecycle.kt:693` / `CallerNoTradeGuard.kt:44`）は geometry を運んでいないため、**ここにはフックしない**。

観測は geometry が確定している経路で個別に作る。

| ゲート | 位置 | geometry |
|---|---|---|
| Falsifier 拒否（3 reason） | `OneShotLlmRunner.kt:1456` `recordFalsificationNoTrade` | `runApprovedEntryFlow` 冒頭の `requireNotNull(decision.tradeIntent)` |
| provider failure / phase observation | `:1318` / `:1339` / `:1472` | `decision.tradeIntent.draft` |
| place_order 失敗・拒否 | `:1502` / `:1720` | `intent.toPlaceOrderCommand(...)` |
| SafetyFloor 拒否（EV 含む） | `PaperBroker.kt:369-384` | `PlaceOrderCommand` |
| TTL 失効 | `InMemoryPaperLedgerRepository.kt:757-778` / `ExposedPaperLedgerWriter.kt:807-838` | `Order` 行 |

`DecisionExecutionLifecycle.kt` の 7 経路は EXIT / REDUCE / ADD_LONG / ADJUST_PROTECTION であり、新規 entry plan geometry を持たない。**除外する**。これが issue の禁止事項「executable plan のない NO_TRADE への架空 entry 生成」に対応する。

EV 拒否は reason が `place_order_rejected` に潰れるため、`PaperTradeResult.safetyViolation.rule` で識別する。

MARKET 注文（`geometryCanonical` で `entry=null`、`DecisionIdentityGenerator.kt:70-80`）は即時約定を前提とするため、resting 前提の shadow 判定になじまない。**観測対象から除外する**。

### D5: 起点時刻（agent 仮決め）

- 拒否系: 拒否が記録された時刻
- TTL 失効: **`expiredAt`（論理期限）を使う**。`canceledAt`（処理時刻）ではない。両者の差は `PaperOrderLifecyclePolicy.ttlCancellationDelay()` が示すとおり最大 10 秒（`cancellationGrace = reconcilerInterval * 2`、`:58-61`）あり、処理時刻を起点にすると論理期限から処理までの間の event を取りこぼす

### D6: 永続化（agent 仮決め）

`dedupe_shadow_observations` / `dedupe_shadow_resolutions`（`TradingTables.kt:1253-1280`）の observation → resolution 2 段 append-only 構造を踏襲する。ただし既存テーブルは dedupe 判定に型付けされたカラム（`distance_jpy` / `atr_price_ratio` / `invalidation_state`）を持つため、同居させず新テーブルを切る。

- `gate_shadow_observations` — `id` / `gate_kind`（FALSIFIER / SAFETY_FLOOR / ENTRY_REJECTED / TTL_EXPIRY）/ `gate_detail`（SafetyFloorRule 名など）/ `decision_id` / `opportunity_episode_id`(FK) / `thesis_id` / `geometry_hash` / `symbol` / `order_type` / `entry_price_jpy` / `stop_price_jpy` / `take_profit_price_jpy` / `size_btc` / `queue_ahead_btc`(nullable) / `cursor_admission_ordinal` / `cursor_market_at` / `horizon_seconds` / `runtime_config_version` / `safety_floor_policy_version` / `falsifier_policy_version` / `observed_at`
- `gate_shadow_outcomes` — `id` / `observation_id`(FK, UNIQUE) / `outcome` / `conditional_trajectory`(nullable) / `decided_at` / `evidence_admission_ordinal`(nullable) / `evidence_market_at`(nullable)

`observation_id` に UNIQUE を張ることで、spec の冪等性（resolver の再実行で結果が 1 件）を DB 制約で保証する。観測側の重複防止は `(gate_kind, decision_id, geometry_hash)` の UNIQUE とする。

migration は `SchemaUtils.createMissingTablesAndColumns` に 2 行追加（Flyway 不使用）。

### D7: market event の読み出し（agent 仮決め）

`paper_market_event_receipts` を **ordinal 区間**で読む。既存 index は `(session_id, source_sequence)` と `(admission_ordinal)` の 2 つの UNIQUE のみで、時刻範囲 index は存在しない（`TradingPersistenceBootstrap.kt:488-497`）。ordinal 区間なら既存の UNIQUE index がそのまま効くため、**index 追加が不要**。

`normalized_payload` は `{exchangeAt, priceJpy, side, sizeBtc, symbol}`（`ExposedMarketDataIntegrityRepository.kt:313-325`）。デコーダが存在しないため新設する。

`market/PaperMarketEventReceiptRepository.kt:29-32` は `commit` のみなので、range read API を新設する。読み出しは 1 観測あたり `[cursor.ordinal, cursor.ordinal + horizon 相当]` に閉じ、上限件数を設ける（unbounded query を作らない）。

### D8: resolver は daemon tick に相乗りし fail-open とする（agent 仮決め）

`OpportunityEpisodeLifecycleObserver`（`daemon/RestingOrderMaintenanceService.kt:36-45`、`suspend fun observe(observedAt: Instant): Result<Unit>`）と同型の fun interface を追加し、`LlmDaemonScheduler.kt:284` 付近で配線する。

既存 observer は `.getOrThrow()` されており失敗が tick 全体を落とす。**新 observer は fail-open**（失敗をログのみ、次 tick で再試行）とする。shadow は観測であり、その失敗が取引 daemon を止めてよい理由がない。

`LlmDaemonSchedulerDependencies`（`:1119-1134`）はデフォルト付きフィールドの慣習があるため、no-op デフォルトで追加すれば既存呼び出し側は無改修。

tick 間隔は 1 分（`TradingBotConfig.kt:829`）。決着判定の時間解像度はこれに律速されるが、判定自体は保存された event 系列に対して行うため、解像度が結果を歪めることはない。

### D9: policy version（agent 仮決め）

`b2-safety-floor-rule-margins` が導入する `SafetyFloorDefaults.policyVersion` を再利用する。Falsifier 側にも同様の定数を新設する。`runtime_config_version` は既存値を併記する。

## Risks / Trade-offs

- **[`FILL_UNDETERMINED` が多数を占め、C 群の判断材料として弱い]** → D2 の `conditional_trajectory` により上界・下界の区間が得られる。区間が広すぎて判断できない場合は、拒否時点の queue snapshot 取得（D1 の棄却案）を別 change として起こす。**本 change ではこの限界を明記して受容する**
- **[`admission_ordinal` の cross-session 順序が commit 順と一致しない可能性]** → 未確定（調査時点）。D3 の時刻条件との AND により、逆転時は event を捨てる保守側へ倒れる。取りこぼしは `NO_FILL_CERTAIN` の過大計上になるが、約定の捏造よりは望ましい。**（高リスク・要人間確認）としてマークし、PR の人間確認事項へ転記する**
- **[shadow の観測数が増えて receipts の読み出しが重くなる]** → ordinal 区間かつ上限件数付きで読む。1 観測あたりの読み出し範囲は horizon で閉じる。daemon tick は 1 分間隔で、同時に未決着の観測が大量に存在する状況は paper の decision 頻度では起きない
- **[TTL 失効とそれ以外で outcome 語彙が異なり、集計時に混同される]** → `gate_kind` で必ず分けて集計する。語彙の使い分けを spec と SQL 例に明記する
- **[paper の約定意味論が将来変わると shadow との整合が崩れる]** → `FILLED_EQUIVALENT` の判定は `consumeLimitQueue` と同じ条件を用いる。共通化はせず（broker の hot path に依存を作らない）、同値であることをテストで固定する

## Migration Plan

1. テーブル 2 つを追加（起動時に自動作成）
2. range reader と resolver を追加。この時点では誰も呼ばない
3. 観測の記録を各ゲートに追加
4. daemon tick に resolver を配線

rollback は 4 → 1 の逆順。shadow 専用テーブルに閉じているため、rollback で paper ledger の挙動が変わることはない。

## Open Questions

- `admission_ordinal` の cross-session 順序保証（D3 のリスク欄。保守側に倒す設計で進行し、人間確認事項に転記する）
- `recordTtlSweepFailure`（`OneShotLlmRunner.kt:1266-1285`）の呼び出し元が対象 order 集合を持つか未確認。持つなら観測対象に加えられるが、**本 change では対象外**とし、`LEGACY_TTL_SWEEP` 経路は追わない

## やらないこと（Next steps 候補）

- 拒否時点の板取得による queue snapshot（`FILL_UNDETERMINED` を減らす唯一の方法）
- 板深さの永続化
- `ADD_LONG` 経路の観測（runner 側に intent はあるが lifecycle に渡っていない）
- `LEGACY_TTL_SWEEP` 経路の観測
- MARKET 注文の観測
- 較正指標の常設集計
