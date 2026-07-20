## 1. model

- [ ] 1.1 `shadow/GateShadowModels.kt` を新設し、`GateKind`（FALSIFIER / SAFETY_FLOOR / ENTRY_REJECTED / TTL_EXPIRY）、`ShadowOutcome`（NO_FILL_CERTAIN / FILL_UNDETERMINED / FILLED_EQUIVALENT / STOP_HIT / TP_HIT / UNKNOWN_DATA_GAP）、`ConditionalTrajectory`（STOP_FIRST / TP_FIRST / NEITHER）を定義する
- [ ] 1.2 `ActivationCursor`（`admissionOrdinal: Long` / `marketAt: Instant`）と `PlanGeometrySnapshot`（symbol / orderType / entry / stop / tp / sizeBtc / queueAheadBtc?）を定義する
- [ ] 1.3 `GateKind` ごとに使用可能な `ShadowOutcome` の集合を型または検証で表現する。queue 位置が無いゲートで `FILLED_EQUIVALENT` 以降を作れないようにする（design.md D1）
- [ ] 1.4 observation horizon の既定値を定数化し、観測レコードに保存する設計にする

## 2. 永続化

- [ ] 2.1 `persistence/TradingTables.kt` に `GateShadowObservationsTable` と `GateShadowOutcomesTable` を追加する（design.md D6 の列構成）
- [ ] 2.2 `persistence/TradingPersistenceBootstrap.kt` の `ensureSchema()` の列挙に 2 テーブルを追加する
- [ ] 2.3 UNIQUE 制約を生 SQL で追加する。`gate_shadow_outcomes(observation_id)` と `gate_shadow_observations(gate_kind, decision_id, geometry_hash)`
- [ ] 2.4 `shadow/GateShadowRepository.kt` を新設する。append-only + `Result<T>` + InMemory 実装併走
- [ ] 2.5 `persistence/ExposedGateShadowRepository.kt` を実装する

## 3. market event の読み出し

- [ ] 3.1 `normalized_payload`（`{exchangeAt, priceJpy, side, sizeBtc, symbol}`）のデコーダを新設する
- [ ] 3.2 `paper_market_event_receipts` を **ordinal 区間**で読む range reader を新設する。時刻 index は存在しないため ordinal 区間に閉じる（design.md D7）
- [ ] 3.3 読み出しに上限件数を設け、unbounded query を作らない
- [ ] 3.4 `market_data_gaps` を時刻範囲で引く reader を新設する。`idx_market_data_gaps_unresolved_started` が効く形にする

## 4. resolver

- [ ] 4.1 `shadow/GateShadowOutcomeResolver.kt` を新設する
- [ ] 4.2 cursor 判定を実装する。`admission_ordinal > cursor.ordinal` **かつ** `sourceTimestamp >= cursor.marketAt` の AND とする（design.md D3）
- [ ] 4.3 価格の跨ぎ判定を実装する。BUY LIMIT では `event.side == SELL` かつ `event.priceJpy <= entryPrice`
- [ ] 4.4 queue 位置がある場合、`consumeLimitQueue`（`InMemoryPaperLedgerRepository.kt:1016-1029`）と同一条件で約定を判定する。共通化はせず同値をテストで固定する
- [ ] 4.5 約定確定後の stop / TP 到達順を判定する
- [ ] 4.6 queue 位置が無い場合、跨ぎの有無で `FILL_UNDETERMINED` / `NO_FILL_CERTAIN` を決め、条件付き軌跡を別フィールドに入れる
- [ ] 4.7 horizon 超過時に確定させる。延長しない
- [ ] 4.8 gap と交差した観測を `UNKNOWN_DATA_GAP` にする
- [ ] 4.9 結果の書き込みを冪等にする（`observation_id` の UNIQUE に依存）

## 5. 観測の記録

- [ ] 5.1 `shadow/GateShadowRecorder.kt` を新設する。geometry と cursor を受けて観測を作る単一の記録面
- [ ] 5.2 `OneShotLlmRunner.kt:1456` `recordFalsificationNoTrade` に配線する（3 reason 共通）
- [ ] 5.3 `OneShotLlmRunner.kt:1318` / `:1339` / `:1472` / `:1502` / `:1720` に配線する
- [ ] 5.4 `PaperBroker.kt:369-384` の SafetyFloor 拒否に配線する。EV 拒否は `PaperTradeResult.safetyViolation.rule` で識別する
- [ ] 5.5 TTL 失効に配線する。起点時刻は `expiredAt`（論理期限）を使い `canceledAt` は使わない（design.md D5）。`queue_ahead_btc` を観測へ持ち込む
- [ ] 5.6 `DecisionExecutionLifecycle.kt` の 7 経路には**配線しない**。EXIT / REDUCE / ADD_LONG / ADJUST_PROTECTION は entry geometry を持たない
- [ ] 5.7 MARKET 注文を観測対象から除外する
- [ ] 5.8 `recordNoTrade` の choke point には配線しない（geometry を運んでいない）

## 6. daemon 配線

- [ ] 6.1 `OpportunityEpisodeLifecycleObserver` と同型の fun interface を追加する
- [ ] 6.2 `LlmDaemonSchedulerDependencies`（`LlmDaemonScheduler.kt:1119-1134`）に no-op デフォルト付きで追加する
- [ ] 6.3 `LlmDaemonScheduler.kt:284` 付近で配線する。**fail-open** とし、失敗が tick を落とさないようにする（既存 observer の `.getOrThrow()` を踏襲しない）
- [ ] 6.4 実装を `LlmDaemonSchedulerWorker.kt:235` 付近で注入する

## 7. テスト

- [ ] 7.1 **DoD**: activation 前の event を無視することの回帰テスト。拒否時刻より前の event が entry 価格を跨いでいても fill 判定に使われないこと
- [ ] 7.2 **DoD**: shadow 書き込みが ledger / cash / position / strategy PnL / order を変更しないことのテスト
- [ ] 7.3 ordinal が cursor より後で market 時刻が前の event が除外されること（D3 の AND 条件）
- [ ] 7.4 queue 位置が無いゲートで `FILLED_EQUIVALENT` を作れないこと
- [ ] 7.5 価格が跨がなければ `NO_FILL_CERTAIN`、跨げば `FILL_UNDETERMINED` になること
- [ ] 7.6 queue 位置があるとき `consumeLimitQueue` と同値の判定になること
- [ ] 7.7 horizon 超過で確定し、その後の event で結果が変わらないこと
- [ ] 7.8 gap 交差で `UNKNOWN_DATA_GAP` になり、gap 前に決着済みなら変わらないこと
- [ ] 7.9 resolver の再実行で結果が 1 件のままであること
- [ ] 7.10 resolver の失敗が daemon tick を落とさないこと
- [ ] 7.11 geometry を持たない NO_TRADE で観測が作られないこと

## 8. 仕上げ

- [ ] 8.1 `make detekt` と関連テストを通す
- [ ] 8.2 shadow 結果を SQL で照会する例を `docs/` の該当箇所に追記する。`gate_kind` ごとに outcome 語彙が異なることと、`UNKNOWN_DATA_GAP` を母集団から除く方法を明記する（新規ファイルは作らない）
- [ ] 8.3 PR の「人間に確認してほしいこと」に `admission_ordinal` の cross-session 順序保証（design.md のリスク欄、高リスク・要人間確認）を転記する
- [ ] 8.4 完了報告に、`FILL_UNDETERMINED` が多数を占める場合の限界と、拒否時点の queue snapshot 取得という解消手段（別 change 候補）を明記する
