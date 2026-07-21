## Why

TTL 失効させた entry は、その後の市場でどう振る舞ったかが残らない。「失効させたのは機会損失だったのか」を後から評価できないため、TTL の較正が経験に基づけない。

issue #193（B2）の後半が負う分。change 1（rule margins）で「通ったときの余裕」は観測できるようになった。本 change は対になる「失効した注文のその後」を future-only で観測記録する。

paper 真実性の不変条件により、これは反実仮想の**断定**を作ってはならない。したがって記録するのは、**約定の必要条件（価格が注文境界を跨いだか）を陽性に確認できた観測事実**だけとする。

**scope は TTL 失効・production（Postgres）経路だけに絞る。** EV 拒否は admit されず因果境界を持たない。in-memory ledger は admission_ordinal の権威を持たない test 経路。4 度の設計反証でこの構造的困難を確認し、両者を別 change の Next step とした。production の TTL 失効注文は admit 済みで、`order_id` / `market_data_session_id` を持ち、失効 ledger transaction 内で admission watermark を線形化して読めるため、production eligibility と同じ因果境界で健全に観測できる。

## What Changes

- 対象は **TTL 失効**（resting entry の TTL 超過 cancel）の **LIMIT / STOP long entry** のみ。
- 失効経路内（session context が生きている地点）で、order の geometry・`order_id`・`market_data_session_id`・失効時点の admission watermark を immutable に捕捉し、cancel（risk-reducing）を巻き込まないよう ledger transaction 外・commit 後に best-effort 保存する。取りこぼしは正本（`cancel_reason=TTL_EXPIRY` の order 行）との SQL reconciliation で durable に算出する。
- LLM daemon の tick に **shadow resolver** を配線し、`session 一致 AND admission_ordinal > 捕捉 watermark`（production eligibility と同じ因果境界）の市場イベントを読み、各観測を 2 値に分類して resolution 行を書く:
  - `CROSSED`: 注文境界を跨ぐ市場イベントを **1 件でも発見**した。価格必要条件が満たされた陽性の観測事実。**約定した、とは断定しない**。
  - `UNKNOWN`: horizon + settlement grace を経過し、現時点で可視な全 event を読み切ってもクロスを確認できなかった、または decode に失敗した。**否定は主張しない**。母集団に残す。
- 観測・resolution ともに ledger を一切変更しない。既存 `dedupe_shadow_*` のパターンをミラーする。

## Capabilities

### New Capabilities
- `gate-shadow-counterfactual`: TTL 失効した LIMIT/STOP entry の geometry と因果境界（session + admission watermark）を捕捉し、以降の同一 session の市場イベントから「約定の必要条件が満たされたか」を陽性に観測して `CROSSED` / `UNKNOWN` で記録する。約定の断定も、機会が無かったことの断定も行わない。

### Modified Capabilities
<!-- なし。SafetyFloor / ledger の要件は変えない。 -->

## Impact

- 新規テーブル 3 つ: `gate_shadow_observations`(append-only、`order_id` UNIQUE) / `gate_shadow_scan_progress`(mutable cursor) / `gate_shadow_resolutions`(observation ごと 1 行・CROSSED 単調昇格)。`SchemaUtils.createMissingTablesAndColumns` と `scripts/backup/restore-inventory-v1.txt` に追加。
- `paper_market_event_receipts` に `(session_id, admission_ordinal)` index を追加（admission watermark 以降の有界 range scan 用。additive）。
- `ExposedPaperLedgerWriter`: production の TTL 失効経路に best-effort 捕捉を追加（判定は変えない、cancel を巻き込まない）。in-memory ledger 経路は shadow 対象外で no-op（production-only 境界を維持）。
- `daemon/LlmDaemonScheduler.kt`: resolver を tick に配線（`launchEnabled=false` でも走る位置、admission range で有界化、cursor で前進）。
- `normalized_payload` の decoder を新設。
- EV 拒否は本 change に含めない（因果境界を持たないため。別 change の Next step）。
