本 change は 2 つの PR に分けて実装する。PR-2 で観測基盤と TTL 失効の recorder（失効経路内で因果境界を捕捉）まで、PR-3 で resolver（因果境界以降の event を CROSSED/UNKNOWN に分類）と daemon 配線、DoD テストまで。spec の全要件は PR-3 完了時に満たされる。change の archive は PR-3 マージ後に 1 回だけ行う。

## 1. PR-2 / 観測 model とテーブル

- [x] 1.1 `shadow/GateShadowObservation.kt` を新設し、`GateShadowOutcome`(CROSSED / UNKNOWN)、`ShadowDataQuality`（OK と欠落理由コード）、`GateShadowObservation`（`orderId` / `marketDataSessionId` / `startAdmissionOrdinal` / `windowStartTime` / geometry）、`GateShadowResolution` を定義する（`:trading` は Compose 非依存なので `@Immutable` は付けない）
- [x] 1.2 `persistence/TradingTables.kt` に 3 テーブルを追加する（design.md D6）。`gate_shadow_observations`(append-only、`order_id` NOT NULL + UNIQUE / `start_admission_ordinal` / `window_start_time` / `market_data_session_id` / `data_quality` NOT NULL)、`gate_shadow_scan_progress`(mutable、`observation_id` PK / `last_scanned_admission_ordinal`)、`gate_shadow_resolutions`(`observation_id` PK / `outcome` / `data_quality` NOT NULL)
- [x] 1.3 `paper_market_event_receipts` に `(session_id, admission_ordinal)` index を **`CREATE INDEX CONCURRENTLY`** で追加する。`ensureSchema()` の schema transaction の**外**の先行 provisioning stage とする（startup transaction 内の通常 `CREATE INDEX` は大規模テーブルの INSERT を止める、design.md D6）
- [x] 1.4 `persistence/TradingPersistenceBootstrap.kt` の `ensureSchema()` 列挙に 3 テーブルを追加する（index は 1.3 の別 stage）
- [x] 1.5 `scripts/backup/restore-inventory-v1.txt` に 3 テーブルを追加する（`DatabaseBackupRestoreContractTest` が落ちる）

## 2. PR-2 / repository と捕捉

- [x] 2.1 `shadow/GateShadowRepository.kt` を新設する。append-only observation + mutable scan-progress + 単調昇格 resolution。`Result<T>` + InMemory 実装併走。`CancellationException` は再 throw する
- [x] 2.2 `persistence/ExposedGateShadowRepository.kt` を実装する。transaction 非依存の境界。resolution は **CROSSED-wins upsert**（`CROSSED` は `ON CONFLICT (observation_id) DO UPDATE SET outcome='CROSSED'`、`UNKNOWN` は `ON CONFLICT DO NOTHING`、design.md D4）
- [x] 2.3 **production（Exposed）の失効 ledger transaction 内**で immutable capture payload を作る。失効 order 行から `order_id` / `market_data_session_id` / geometry / `expired_at` を、`start_admission_ordinal` は同 transaction 内で読む現在の admission high-watermark を取る（event admission と同じ session 直列化で線形化。**commit 後の post-hoc `MAX()` は使わない**）
- [ ] 2.3a **（falsify F2）** capture の in-txn read（fence 読み・lineage read）を **SAVEPOINT で隔離**し、read が例外を投げても savepoint まで rollback して TTL cancel は commit させる。capture read 失敗が risk-reducing cancel を巻き戻さないことを回帰テストで証明する
- [x] 2.4 in-memory ledger 経路は shadow 対象外（admission_ordinal 権威を持たない test 経路。production only）。in-memory では capture を no-op にする
- [x] 2.5 capture payload を cancel 成功後に best-effort 保存する。**ledger transaction 外・cancel を巻き込まない**。LIMIT/STOP のみ対象
- [ ] 2.5a **（falsify F3・async 分離）** 保存を **bounded channel + drain coroutine** で async best-effort にし、writer は non-blocking に enqueue して即 return する（global `tradingLock` の critical path に DB 保存を載せない）。channel 満杯時は drop（reconciliation が拾う）。scope は runtime 所有・shutdown で cancel。`PaperReconcileResult.gateShadowObservations` の上位露出は不要になるので削除または内部化する（N1）
- [x] 2.6 取りこぼしを SQL reconciliation で算出する経路を用意する（design.md D7）。`cancel_reason=TTL_EXPIRY` かつ LIMIT/STOP の order 行に対し `order_id` join で observation 欠落を算出。in-memory counter は使わない
- [x] 2.7 DI / runtime 配線に repository を追加する

## 3. PR-2 / テスト

- [x] 3.1 **DoD**: shadow の観測書き込みが cash / position / strategy PnL を変更しないこと
- [x] 3.2 TTL 失効で observation が 1 件書かれ、`order_id` / `market_data_session_id` / `start_admission_ordinal` / geometry が正しいこと
- [x] 3.3 shadow の捕捉が失敗しても TTL cancel が完了し rollback されないこと。欠落が `order_id` join の reconciliation で検出できること
- [x] 3.4 `CancellationException` が握り潰されないこと
- [x] 3.5 対象外（MARKET / 非 BUY / TTL 以外の cancel）では observation が書かれないこと
- [x] 3.6 永続化の往復テスト（Exposed）。resolution の CROSSED-wins upsert（`UNKNOWN` 後に `CROSSED` で上書き、`CROSSED` 後に `UNKNOWN` は no-op）

## 4. PR-2 / 仕上げ

- [x] 4.1 `docs/deploy.md` に `paper_market_event_receipts` への `(session_id, admission_ordinal)` index を `CREATE INDEX CONCURRENTLY` で追加する運用手順を書く（10.3 ユーザー確認済み）: 実行主体・deploy 順序・fresh DB 初期化順序・invalid index の drop/retry・既存行数/index size/WAL 増加の確認（新規ファイルは作らず deploy.md に追記）
- [x] 4.2 `make detekt` と関連テストを通す
- [ ] 4.3 PR-2 を出す（change は open のまま）

## 5. PR-3 / decoder と scan

- [ ] 5.1 `shadow/ReceiptPayloadDecoder.kt` を新設し、`normalized_payload`(`{exchangeAt, priceJpy, side, sizeBtc, symbol}`) を typed に decode する。decode 失敗は例外にせず不能として返す
- [ ] 5.2 分類対象 event を design.md D2 の predicate で絞る: `session_id = observation.market_data_session_id AND admission_ordinal > observation.start_admission_ordinal AND socket_observed_at >= window_start_time AND socket_observed_at <= window_start_time + horizon`。**（falsify F1）** ordinal 下界だけでなく `socket_observed_at >= window_start_time` の下界を必ず課す（admission race で失効前 event が ordinal 下界を満たしうるため）。終了境界の時刻は `socket_observed_at` に固定。`(session_id, admission_ordinal)` index で admission_ordinal 昇順に cursor 読み。session 内 `socket_observed_at` の非単調（wall-clock rollback）を検出したら `data_quality` に flag（design.md D4）
- [ ] 5.3 **settle 判定**: observation は `now >= window_start_time + horizon + settlementGrace` を満たすまで走査しない（design.md D3、commit race 回避）。settle 済みは 1 observation あたり read row 上限を設け、打ち切り位置を `gate_shadow_scan_progress.last_scanned_admission_ordinal` に upsert して次 tick が再開する。欠番を gap 誤認して永久 pending にしない

## 6. PR-3 / resolver

- [ ] 6.1 `shadow/GateShadowResolver.kt` を新設する。`OpportunityEpisodeLifecycleObserver` と同型の `suspend observe(observedAt): Result<Unit>`
- [ ] 6.2 境界クロス判定（design.md D5）: LIMIT は `eventPrice <= limitPrice`、STOP は `eventPrice >= triggerPrice`（BUY 限定）。比較は保存精度、`distance_jpy` のみ表示用に丸める。対象外 order type / side は resolution を書かず対象外集計に回す
- [ ] 6.3 terminal 規則（design.md D3/D4）: settle 後、クロス発見で `CROSSED`（証拠記録、CROSSED-wins upsert）。`UNKNOWN` は **(1) 窓の終了境界まで読み切り (2) クロス未発見** で確定。settle 前は走査しない
- [ ] 6.4 decode 失敗は event を跳ばして `data_quality` に記録し走査継続（即 UNKNOWN にしない）
- [ ] 6.5 resolver は独自 transaction、失敗は 1 observation 単位で隔離（fail-open）
- [ ] 6.6 1 tick で処理する observation 数の上限を設け、超過分は次 tick。cursor で前進。**打ち切りを log する（silent cap 禁止）**

## 7. PR-3 / daemon 配線

- [ ] 7.1 `daemon/LlmDaemonScheduler.tickUnsafe` に resolver を配線する。`launchEnabled=false` の早期 return（`:274`）**より前**に置く（shadow は分析であり launch 無効時も走る）。tick は既定 1 分
- [ ] 7.2 `horizon`（既定 24 時間）と `settlementGrace`（既定 300 秒）を config 化する
- [ ] 7.3 resolver 呼び出しを tick 全体の失敗から隔離する。1 tick の作業に **wall-time 予算 + DB statement / lock / connection 取得 timeout** を課す（row 上限だけでは DB 待ちを bound できない、design.md D8）。予算超過・timeout 時は cursor を壊さず pending のまま fail-open、次 tick へ
- [ ] 7.4 receipts の複合 index（1.3）が未完成の間は resolver を default-off にする（capture は動いてよい）。**（falsify F5）** ready 判定は index 名と `indisvalid/indisready/indislive` だけでなく `pg_index.indkey` の列定義（`session_id, admission_ordinal`）まで確認し、同名・別定義の index で誤って有効化しない
- [ ] 7.5 **（falsify F4）** reconciliation に導入時刻の下界（`canceled_at >= baseline`）を渡し、deploy 前の TTL 失効を capture 欠落として計上しない。baseline は config または初回 observation 時刻から決める

## 8. PR-3 / テスト

- [ ] 8.1 **DoD**: 因果境界以前（`admission_ordinal <= start_admission_ordinal`）の event が分類に使われないこと。別 session の event も使われないこと
- [ ] 8.2 **DoD**: shadow の resolution 書き込みが cash / position / strategy PnL を変更しないこと
- [ ] 8.3 境界を跨ぐ event を発見すると `CROSSED`、証拠が記録され、paper 約定が作られないこと
- [ ] 8.4 settle 前（`window_start_time + horizon + grace` 未経過）は走査されず resolution が書かれないこと。read 上限で未読を残したまま `UNKNOWN` を確定させないこと。読み切ってクロス未発見なら `UNKNOWN`、母集団に残ること
- [ ] 8.5 decode 失敗の event を跳ばして後続 crossing を発見し `CROSSED` になること
- [ ] 8.6 admission_ordinal の欠番（rollback 由来）で永久 pending にならず、読み切り判定が終了境界で成立すること
- [ ] 8.7 `socket_observed_at > window_start_time + horizon` の crossing を `CROSSED` にしないこと
- [ ] 8.8 LIMIT の `<=` 境界、STOP の `>=` 境界がそれぞれ正しいこと
- [ ] 8.9 並行に `UNKNOWN` と `CROSSED` を書いても `CROSSED` が残ること（CROSSED-wins）
- [ ] 8.10 resolver が wall-time 予算を超過しても pending のまま打ち切られ、launch を妨げないこと
- [ ] 8.11 resolution / scan-progress の往復テスト（Exposed）

## 9. PR-3 / 仕上げ

- [ ] 9.1 `make detekt` と関連テストを通す（`:trading` と `:fukurou` 両方）
- [ ] 9.2 shadow を SQL 照会する例を `docs/` に追記する。**`CROSSED` は約定ではない・`UNKNOWN` は非約定ではない**旨、取りこぼしを reconciliation で見る旨、grace 超過の遅延 crossing を取りこぼす残余リスクを明記する（新規ファイルは作らない）
- [ ] 9.3 change を archive する（`openspec archive`、specs sync 付き）。**PR-3 マージ後に 1 回だけ**
- [ ] 9.4 完了報告に residual risk（EV を落とした / NOT_CROSSED を取らない / grace 超過遅延 crossing の取りこぼし / 捕捉取りこぼし / horizon 打ち切り / レコード無期限増加 / receipts index 追加コスト）と scope 外に残した既存例外リスクを列挙する

## 10. 人間に確認してほしいこと（2026-07-21 ユーザー確認済み）

- [x] 10.1 **（ユーザー確認済み）** resolver を `launchEnabled=false` でも走らせる。`launchEnabled` 早期 return **より前**に配置する（shadow は分析なので取引停止中も観測を続ける）。→ PR-3 §7.1
- [x] 10.2 **（ユーザー確認済み）** horizon 24 時間 / settlementGrace 300 秒 / resolver wall-time 予算 数百 ms を初期値として採用し config 化する（後で変更可）。grace 超過の遅延 commit crossing は取りこぼす（残余リスク・受容済み）。resolver は同期配置で毎 tick 最大 N の launch 遅延（bounded delay・受容済み）。→ PR-3 §7.2/§7.3
- [x] 10.3 **（ユーザー確認済み）** `CREATE INDEX CONCURRENTLY` の実行主体・deploy 順序・fresh DB 初期化順序・invalid index の drop/retry・additive migration の追加コスト（既存行数/index size/WAL）を `docs/deploy.md` に書く。→ PR-2 §4.1
