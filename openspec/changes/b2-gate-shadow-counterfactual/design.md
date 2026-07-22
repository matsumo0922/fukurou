# Design: gate-shadow counterfactual (TTL expiry, production-only)

## Context

change 1（`b2-safety-floor-rule-margins`）で「通った注文の余裕」は観測できるようになった。本 change はその対、「失効した注文のその後」を future-only で観測する。

**scope は TTL 失効・production（Postgres/Exposed）経路だけに絞る**（4 度の falsify の結論）。EV 拒否は admit されず因果境界を持たない。in-memory ledger は admission_ordinal の権威を持たない（test/dev 経路）。どちらも別問題として切り出す。

TTL 失効注文が持つ因果境界（HEAD `c4aa2924` で確認）:

- **TTL 失効は admit 済みの order 行を残す。** `ExposedPaperLedgerWriter.expireRestingEntryOrders:806`（ledger transaction 内）は status/`expired_at`/`cancel_reason=TTL_EXPIRY` を更新するだけで、`order.id` / `market_data_session_id`（`OrdersTable:653`）/ geometry / `queue_ahead_btc`（`:645`）はそのまま残る。
- production eligibility は `market_data_session_id = event.connectionSessionId AND admission_ordinal > boundary`（`ExposedPaperLedgerWriter.kt:1099,1043`）。
- `paper_market_event_receipts`（`TradingTables.kt:856`）: `session_id` / `source_sequence` / `socket_observed_at`(callback で確定、`receivedAt.toEpochMilli()`) / `normalized_payload`(`{exchangeAt, priceJpy, side, sizeBtc, symbol}`、decoder は無い) / `admission_ordinal`(INSERT transaction 内で採番、`nextval` グローバル seq・欠番あり・**global unique index は既存**、`TradingPersistenceBootstrap.kt:493`) / `recorded_at`(DB clock)。**session 複合の `(session_id, admission_ordinal)` index は無い**（D6 で追加）。
- **receipt の INSERT は callback とは別 executor で非同期に commit される**（`GmoPublicWebSocketMarketEventStream.kt:274`）。admission_ordinal は commit 時採番で、commit は ordinal 順とは限らない。→ **live に走査すると低 ordinal の遅延 commit を取りこぼしうる。settle 後に走査すればこの race は無い**（D3）。
- daemon: `LlmDaemonScheduler.tickUnsafe` は `launchEnabled=false` で L273 早期 return、その後 `reserveAndLaunch`（`:382`）へ進む。observer hook は同期・逐次。tick 既定 1 分。
- 取りこぼしの正本: `cancel_reason=TTL_EXPIRY` の order 行（durable、`order.id` で一意）。
- `ensureSchema()` は単一 Exposed transaction（`TradingPersistenceBootstrap.kt:1339`）で `createMissingTablesAndColumns` を呼ぶ。→ 大規模テーブルへの `CREATE INDEX` を同 transaction に入れると起動時に INSERT を止める（D6）。
- 既存資産: `dedupe_shadow_*`（`TradingTables.kt:1253`）のパターンをミラーする。

## Goals / Non-Goals

**Goals:**
- production 経路で TTL 失効した **LIMIT / STOP long entry** について、失効 ledger transaction 内で因果境界（session + admission watermark）と geometry・`order_id` を線形化して捕捉し、best-effort で永続化する。
- 窓が settle した後（`window_start_time + horizon + settlementGrace` 経過後）に、境界以降・同一 session の event から「約定の必要条件」を観測し `CROSSED` / `UNKNOWN` の 2 値で記録する。
- 観測・resolution が ledger を変更しないこと。shadow が SQL 照会できること。因果境界より前・別 session・窓外の event を用いないこと。

**Non-Goals（Next steps に 1 行ずつ）:**
- EV 拒否の shadow（因果境界を持たない。別 change）。
- in-memory ledger 経路の shadow（admission_ordinal 権威を持たない test 経路。production only）。
- `NOT_CROSSED`、約定/非約定の断定、MARKET、NO_TRADE/Falsifier。
- `queue_ahead_btc` を使った忠実な反実仮想（記録のみ）。
- live（settle 前）の増分 resolution。settle 後に 1 回だけ走査する。
- backfill。

## Decisions

### D1: 結果は 2 値（ユーザー確認済み・芯）

- **`CROSSED`**: 因果境界（session + admission watermark）以降・窓内の観測 event に注文境界を跨ぐものが 1 件でも存在した。陽性の観測言明。約定したとは断定しない。
- **`UNKNOWN`**: 窓を読み切ってもクロスを確認できなかった、または decode に失敗した。「約定機会が無かった」を含意しない。母集団に残す。

### D2: 因果境界は失効 ledger transaction 内で線形化して捕捉する（ユーザー確認済み: TTL scope / agent 仮決め: 実装）

`ExposedPaperLedgerWriter.expireRestingEntryOrders` の**失効 ledger transaction 内**（event admission と同じ session 直列化が効く地点）で、immutable capture payload を作る:

```
order_id, market_data_session_id, start_admission_ordinal (= 同 transaction 内で読む現在の admission high-watermark),
geometry, window_start_time (= expired_at)
```

- **この watermark 以降に admit される event は必ず失効後**であることを、admission sequence の **allocation fence** で確定する。単純な `MAX(admission_ordinal)`（committed row の最大値、`ExposedPaperLedgerWriter.kt:788`）は**採番済み・未 commit の receipt を見逃すため下界にならない**（falsify #1）: その receipt が後で commit されると、失効前に admission を開始した event が `ordinal > start_admission_ordinal` に紛れ込む。receipt admission は `nextval('paper_market_admission_ordinal_seq')` で採番する（`ExposedMarketDataIntegrityRepository.kt:305`）。**fence primitive は同 sequence の allocation 済み最大値**（`SELECT last_value, is_called FROM paper_market_admission_ordinal_seq`。sequence は非 transactional なので未 commit 含む採番済み最大が見える。`is_called=false` なら未採番として 0 相当）を使う。exclusive session lock で読む案は cancel（risk-reducing）を receipt commit 待ちに巻き込むため採らない（D7 と整合）。**`selectGlobalPaperMarketAdmissionBoundary`（`:788`）の committed `MAX()` は採番済み・未 commit の receipt を見逃すため使わない**。
- `market_data_session_id` は order 行から取る。特定できなければ `data_quality` に理由を刻み resolver は `UNKNOWN`。
- 分類対象 predicate: `session_id = observation.market_data_session_id AND admission_ordinal > observation.start_admission_ordinal AND socket_observed_at >= window_start_time AND socket_observed_at <= window_start_time + horizon`。ordinal 下界（線形化・scan の起点）と **socket_observed_at 下界（因果の芯）** を両方課す。
- **なぜ ordinal 下界だけでは不足か（falsify F1）**: receipt admission は shared session lock 下で `nextval` するが、fence は同 lock を取らず sequence の `last_value` を読む。lock 取得済み・**未採番**の receipt（失効前に callback で観測された event を含む）は fence に映らず、後で `nextval > fence` で commit されると ordinal 下界だけでは失効後と誤認される。allocation fence は「採番済み・未 commit」は囲うが「観測済み・未採番」は囲えない。よって **`socket_observed_at >= window_start_time`（失効時刻）を hard な下界に加え、失効前に観測された event を必ず排除する**。これは #193 の不変条件「activation 前の event で fill 判定しない」を守る。
- wall-clock rollback で真に失効後の event が `socket_observed_at < window_start_time` になった場合はその event を除外する（`UNKNOWN` 方向＝非約定を主張しない安全側。過剰 `CROSSED` を作るより過小の方が真実性を破らない）。ordinal 下界は scan の pagination 起点として残す。

### D3: settle 後に 1 回走査する。live の commit race を避ける（agent 仮決め・falsify #3 対応）

resolver は observation を `now >= window_start_time + horizon + settlementGrace` を満たすまで**触らない**（pending）。この時点で窓内の event は全て commit 済み（settle）なので、ordinal 順の走査で遅延 commit の低 ordinal を飛ばす race が無い。

- settle 後、`(session_id, admission_ordinal)` index で `admission_ordinal > start_admission_ordinal` を昇順に読み、`window_start_time <= socket_observed_at <= window_start_time + horizon` で窓内に絞る（下界は D2 の因果の芯）。境界クロス発見で `CROSSED`。窓を読み切ってクロス無しなら `UNKNOWN`。
- **admission_ordinal の欠番（rollback 由来）は settle 後は最終的**なので、「読み切った」は「窓の終了境界（`socket_observed_at > window_start+horizon` の最初の行、または session の現 high-watermark）に達した」で判定する。欠番を gap と誤認して永久 pending にしない（falsify #3）。
- `settlementGrace` 既定 300 秒（**（agent 仮決め）** gap 検知 150 秒 + INSERT 遅延マージン）。grace を越えて遅延 commit された窓内 crossing は残余リスク（`UNKNOWN` は非約定を主張しないので真実性は破らない）。

### D4: 終了境界の canonical time は `socket_observed_at`。時計異常は data_quality（agent 仮決め・falsify #4 対応）

終了境界に使う event 時刻は **`socket_observed_at` に固定**する（callback で確定、`exchangeAt` の取引所側時計・遅延に依存しない）。走査は `admission_ordinal` 昇順で行い、**`socket_observed_at > window_start+horizon` の 1 件で走査を打ち切らず、session の現 high-watermark まで読み切る**（`admission_ordinal` 順と `socket_observed_at` 順は wall-clock rollback で食い違いうるため、終了時刻を越えた最初の 1 件を読み切り扱いにすると、その先の窓内 event を取りこぼす）。窓内判定は各 event ごとに `socket_observed_at <= window_start+horizon` で行う。同一 session 内で `socket_observed_at` の非単調（rollback）を検出したら observation の `data_quality` に flag を立てる。終了境界のブレは `CROSSED`（約定非断定）・`UNKNOWN`（非約定非断定）の意味論上、母集団の horizon 端をわずかに揺らすだけで真実性を破らない。

### D5: 約定の必要条件 = 価格が注文境界を跨ぐこと（agent 仮決め）

窓内・同一 session の各 receipt の `normalized_payload.priceJpy`:
- **LIMIT long entry**: `eventPrice <= limitPriceJpy`。
- **STOP long entry**: `eventPrice >= triggerPriceJpy`（BUY 限定）。

比較は BigDecimal・境界含む。`distance_jpy`（表示用）だけ丸める。MARKET・非 BUY は対象外（observation は残すが resolution を書かず対象外集計）。decode 失敗の event は跳ばして `data_quality` に記録し走査継続。

### D6: 3 テーブル + receipts への CONCURRENT index（agent 仮決め・falsify #5 対応）

- **`gate_shadow_observations`**（append-only）: `id`, `order_id`(NOT NULL, UNIQUE, TTL 正本 join key), `decision_id`(nullable), `opportunity_episode_id`(nullable), `geometry_hash`, `symbol`, `side`, `order_type`, `size_btc`, `limit_price_jpy`(nullable), `trigger_price_jpy`(nullable), `stop_price_jpy`(nullable), `take_profit_price_jpy`(nullable), `queue_ahead_btc`(nullable, 記録専用), `market_data_session_id`(nullable), `start_admission_ordinal`(long), `window_start_time`(long), `data_quality`(NOT NULL), `observed_at`(long)。
- **`gate_shadow_scan_progress`**（mutable pagination cursor）: `observation_id`(PK), `last_scanned_admission_ordinal`(long), `last_scanned_at`(long)。settle 後の 1 走査を複数 tick に分割するときの pagination 用（settle 済みなので commit race は無い）。
- **`gate_shadow_resolutions`**（1 observation 1 行・単調昇格）: `observation_id`(PK), `outcome`, `crossing_event_sequence`(nullable), `crossing_exchange_at`(nullable), `crossing_price_jpy`(nullable), `distance_jpy`(nullable), `data_quality`(NOT NULL), `resolved_at`(long)。書き込みは CROSSED-wins upsert（`CROSSED`: `ON CONFLICT DO UPDATE SET outcome='CROSSED'`、`UNKNOWN`: `ON CONFLICT DO NOTHING`。falsify で実装可能性確認済み）。
- **`paper_market_event_receipts` への `(session_id, admission_ordinal)` index は `CREATE INDEX CONCURRENTLY` で、`ensureSchema()` の schema transaction の外の先行 provisioning stage として作る**（falsify #5: startup transaction 内の通常 `CREATE INDEX` は既存大規模テーブルの INSERT を止める。`CONCURRENTLY` は transaction 内で実行できないため、schema 一括 transaction とは別に流す）。**resolver の default-off 判定は index の catalog validity で行う**: `pg_index` の `indisvalid AND indisready AND indislive` を確認する（既存に同様の catalog 確認例あり、`TradingPersistenceBootstrap.kt:861`）。`CONCURRENTLY` は失敗すると invalid index を残すので、名前や `pg_indexes` の存在だけで有効化しない。invalid index の drop/retry も運用手順に含める。既存行数・index size・WAL 増加も運用手順で確認する。

3 新テーブルは `createMissingTablesAndColumns` と `restore-inventory-v1.txt` に追加。observation index は `(observed_at)` と `order_id` UNIQUE。

### D7: 捕捉は best-effort、取りこぼしは正本との reconciliation で durable に算出（ユーザー確認済み: 取りこぼし方針）

- 失効 ledger transaction 内で capture payload を作り、**cancel（risk-reducing）を巻き込まない**よう transaction 外・commit 後に best-effort 保存。`CancellationException` は再 throw。
- **capture の in-txn read は cancel を rollback させない（falsify F2）**: fence 読みと lineage read は失効 ledger transaction 内で走るため、そこで例外が出ると Postgres が transaction を poison し cancel UPDATE ごと rollback しうる（stale order が失効せず居座る）。よって capture read は **SAVEPOINT で隔離**し、失敗しても savepoint まで rollback して cancel は commit させる。取りこぼしは reconciliation が拾う。
- **保存は tradingLock の critical path に載せない（falsify F3・ユーザー確認済み: async 分離）**: `applyMarketEvent` は global `tradingLock` 下で writer を await するため、observation の同期 DB 保存が pool 取得待ち等で長引くと market-event 消費が停止し WebSocket buffer 溢れ → `MarketDataBackpressureException` で session 落ち（production market-data gap）。よって保存は **bounded channel + drain coroutine で async best-effort** とし、writer は非ブロッキングに enqueue して即 return する。channel 満杯時は drop（reconciliation が durable に拾う）。
- 取りこぼしは in-memory counter でなく **SQL reconciliation**: `cancel_reason=TTL_EXPIRY`（wire code `resting_entry_order_ttl_expired`）かつ LIMIT/STOP の order 行のうち、対応する observation（`order_id` join）が無いものを欠落として算出。正本は捕捉と独立に durable。**導入前履歴の混入（falsify F4）**: reconciliation は導入時刻の下界（`canceled_at >= baseline`）を持たないと deploy 前失効を欠落計上する。PR-3 で daemon 配線時に baseline 下界を渡し、docs にも初回ベースライン差し引きを明記する。
- **index ready 判定の定義ずれ（falsify F5・残余）**: 同名・別列定義の valid index があると `IF NOT EXISTS` が skip し flag 確認だけでは通す。PR-3 で resolver 有効化前に `pg_index.indkey` の列定義まで検証する。sequence の `CACHE` が 1 でない運用へ変えると `last_value` が cache 予約上端へ先行し fence が過剰除外側に振れる（安全側だが要運用注意）。

### D8: resolver は daemon tick に同期配線し、bounded delay 予算で有界化（ユーザー確認済み: bounded delay / launchEnabled 配置は要人間確認）

- resolver を `OpportunityEpisodeLifecycleObserver` 同型 hook として `tickUnsafe` 先頭付近に同期配線。**（2026-07-21 ユーザー確認済み）** `launchEnabled=false`（`:273`）より前に配置する（shadow は分析なので取引停止中も観測を続ける）。
- **保証は「launch を一切遅延させない」ではなく「設定した wall-time 予算 N までの bounded delay」とする**（ユーザー確認済みの縮退。falsify #6: 同期逐次 tick では resolver の実行時間分だけ launch 開始が必ず遅れるため、絶対保証は原理的に不可能）。resolver に wall-time 予算 N + statement/lock/connection acquire timeout を課し、予算超過・timeout 時は cursor を壊さず pending のまま fail-open にして次 tick に回す。これにより launch 遅延は毎 tick 最大 N に bound される。1 tick の処理 observation 数と 1 observation の read row も上限を持つ。打ち切りは log（silent cap 禁止）。予算値 N は **（agent 仮決め）** 既定を置き（例: tick interval の数 % に相当する数百 ms）、運用で調整可能にする。
- resolver は独自 transaction、失敗は 1 observation 単位で隔離。tick 全体を落とさない。

### D9: PR-4 daemon 配線・活性化と page 跨ぎ data_quality 累積（B1）

PR-3 で resolver エンジンを default-off で追加した。PR-4 は activation と、PR-3 adversary review が指摘した B1/B2 を対応する。PR-4 falsify（gpt-high, 2026-07-22）で境界条件 6 点を詰めた（core 方式は健全）。

- **daemon 配線 + fail-open 境界（falsify V3 対応）**: resolver を `LlmDaemonScheduler.tickUnsafe` の `launchEnabled` 早期 return（`:274`）**より前**に同期配線する（shadow は分析なので取引停止中も走る。既存 `episodeLifecycleObserver` は launchEnabled 後 `:284` だが resolver はそれより前）。**index ready probe（F5）と `resolver.observe` を同一の fail-open catch boundary に入れる**（probe の false だけでなく failure=permission/timeout も shadow-disabled 扱い。`CancellationException` は再throw）。これで shadow 側の故障が tick 全体を落とし HARD_HALT 判定・resting maintenance・launch を skip させることを防ぐ（shadow の故障が production control path を止めない）。resolver 内も 1 observation 単位 fail-open（D8）。
- **index gate（F5・falsify V1/V5 対応）**: scheduler は index が range scan に使える状態のときだけ resolver を走らせる。ready 判定は index 名 + `indisvalid/indisready/indislive` + **`pg_index.indkey` の列定義（`session_id, admission_ordinal`）に加え `pg_am.amname='btree'`・`indpred IS NULL`（非 partial）・`indexprs IS NULL`（非 expression）・`indnkeyatts=2`** まで確認する。同名でも hash/partial/expression の index では range scan に使えず seq scan で毎 tick timeout/負荷を作り default-off invariant を破るため。index invalid/未完成の間は capture のみ継続し resolver だけ止まる分離は健全。
- **予算（S2・falsify V3/V5 対応）**: 1 tick の resolver 作業に wall-time 予算（既定 数百 ms）+ DB statement/lock/connection timeout。**加えて JDBC driver の `socketTimeout`（read timeout）を resolver の DB 呼び出しに設定する**（JDBC は blocking call で coroutine `withTimeout` は read を中断せず、server-side statement_timeout の応答が network blackhole で届かないと予算を越えるため）。これで control path より前でも bounded delay を driver level で保証する。超過は cursor を壊さず pending fail-open。**cursor・累積 data_quality・last socket time は同一 atomic upsert**で進め、advance は処理済み ordinal までに限る（retry の二重処理は CROSSED-wins/UNKNOWN idempotent で真実性を壊さない）。
- **config**: `horizon`（24h）/ `settlementGrace`（300s）/ wall-time 予算 を daemon 側 config に足す（PR-3 は resolver constructor 引数）。
- **reconciliation baseline（F4・falsify V1/V2 対応）**: `countMissingTtlExpiryObservations` に **capture 開始以前に確定した immutable な activation timestamp**（明示 config を deploy で固定、または専用 boundary row）を `canceled_at >= baseline` として渡す。**「初回 observation 時刻」fallback は使わない**（activation 後・初回保存前の欠落を永久に隠すため）。baseline 未設定なら reconciliation は「baseline 未確定」を明示し 0 と誤計上しない。
- **B1: page 跨ぎ data_quality 累積（agent 仮決め・PR-3 adversary + falsify V2 対応）**: resolver は read 上限で 1 observation の走査を複数 tick に分割する。`data_quality` を pending 中に失うと UNKNOWN の data_quality が page 跨ぎで楽観化する（outcome は正しいが欠損記録が歪む）。対策として `gate_shadow_scan_progress` に **`data_quality`（累積・劣化方向のみ）** と **`last_socket_observed_at`（page 境界の非単調検出用）** を追加する。resolver は tick 開始時に progress から復元して `scanForCrossing` の起点にし、pending 時に累積 data_quality + last socket time を保存、読み切り時に累積 data_quality で resolution を確定する。single enum の劣化順序は `OK < NON_MONOTONIC_SOCKET_TIME < MISSING_* < PAYLOAD_DECODE_FAILED` で最悪値を保持し、**一度劣化したら OK に戻さない**。
  - **縮退（falsify V1/V2・受容）**: single enum なので複数種の劣化が併発すると最悪 1 つだけ残す（complete な劣化列挙は non-goal。`data_quality != OK` で「この観測は不完全」は保たれる）。**data_quality の品質範囲は outcome ごとに定義する**: UNKNOWN は「読み切りまでに累積した品質」、CROSSED は「crossing 発見までに観測した品質」（CROSSED は陽性確定で完全性不要のため suffix を読まない）。窓外 event（SQL の socket 上界で除外）を跨ぐ非単調は検出対象外（outcome 不変・data_quality 精度のみ）。
- **B2: unresolved 絞り（agent 仮決め・PR-3 adversary 対応）**: `findUnresolvedObservations` の SQL に `order_type IN ('LIMIT','STOP') AND side='BUY'` を課し、対象外 observation が per-tick 上限を占有する head-of-line blocking を構造的に防ぐ（現状 capture は対象を絞るため unreachable な防御分岐）。

## Risks / Trade-offs

- **[EV / in-memory を落とし TTL・production のみ]** → 因果境界・ordinal 権威を持つ経路に限定。EV と in-memory は別 change の Next step。TTL・production のみでも #193 の shadow DoD を満たす。
- **[settle 後走査で CROSSED 検出が horizon+grace 遅れる]** → shadow は offline 較正用で real-time 性は不要。settle 走査は commit race を消す対価。
- **[grace 超過の遅延 commit crossing 取りこぼし]**（ユーザー確認済み・受容） → settle=grace 経過で 1 回走査し、grace を越えて commit された窓内 crossing は取りこぼす。`UNKNOWN` は非約定を主張しないので paper 真実性は破らない。取りこぼし得る条件を docs 化。
- **[resolver の bounded launch delay]**（ユーザー確認済み・受容） → 同期配置のため毎 tick 最大 N ms の launch 遅延。絶対無遅延でなく予算内 bounded delay として受容。
- **[`NOT_CROSSED` 不取得 / CROSSED・UNKNOWN の片側性]** → 意図的受容。docs 化。
- **[捕捉取りこぼし]** → 正本 reconciliation で durable に算出。
- **[receipts への CONCURRENT index 追加コスト]** → 別 stage・default-off。運用手順で確認。
- **[既存の例外リスク]** → scope 外。完了報告に列挙。
