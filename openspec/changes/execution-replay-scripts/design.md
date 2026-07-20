## Context

Epic #180 の C1 (trailing) と C3 (TTL/offset) は、パラメータ候補を実適用で 1 つずつ観察すると 1 候補あたり数週間かかる。適用前に記録済みデータで候補の方向を絞る手段が要る。

LLM 学習データのリークがあるため、decision を再実行する replay は成立しない。decision を固定した執行層だけの replay はリークと無縁であり、本 change はこれに限定する。

### 現行実装の把握 (本設計の前提となる読み取り結果)

paper execution は 2 系統で駆動される。

- `applyMarketEvent(PaperMarketTradeEvent)` — WebSocket trade event。resting entry の約定、protective STOP と virtual TP の発火、position mark の更新、TTL 失効判定を行う。
- `maintainProtections(TickSnapshot)` / `reconcile(TickSnapshot)` — REST tick (5 秒 poll)。position mark と ATR trailing の ratchet、TTL 失効判定を行う (`PaperOrderLifecyclePolicy.reconcilerInterval = 5s`)。

#### resting LIMIT entry の約定規則 — queue consumption model

本番の約定判定は価格クロスではなく queue 消費である (`ExposedPaperLedgerWriter.kt:1155-1170`)。

```
fill ⇔ event.side == SELL
     ∧ event.priceJpy ≤ limitPrice
     ∧ Σ(該当 event の sizeBtc) ≥ queue_ahead_btc + order.sizeBtc
```

`queue_ahead_btc` は発注時点に板から算出される (`PaperBroker.kt:475-502)`。内訳は「取引所 bid のうち **指値と完全一致する価格レベル** の厚み」＋「同一価格の自 open order 数量」。この値は `orders.queue_ahead_btc` / `queue_snapshot_at` として保存されるが、**板そのものは保存されない**。

#### TTL の決定

`expiresAt = min(createdAt + restingEntryOrderTtl, LLM の time_stop_at)` (`PaperBroker.kt:795-811`)。どちらが効いたかは `orders.expiry_source` (`SYSTEM_TTL` / `LLM_TIME_STOP`)、実効値は `effective_ttl_seconds` に保存される。失効判定は `!processedAt.isBefore(expiresAt)` で、REST tick と WS event の両経路から呼ばれる。

#### trailing stop

`trailingStop = highestPriceSinceEntry − ATR14 × 2.0` を tick step へ floor し、`max(currentStop, trailingStop)` で単調に tighten する (`PaperLedgerRepository.kt:586-595`)。係数は `SafetyFloorDefaults.trailingAtrMultiplier = 2.0` のコード固定値であり、config で上書きできない (`SafetyFloorDefaults.kt:23`)。

WS event 経路は `updateMarks(event.priceJpy, null, ...)` と ATR を `null` で呼ぶため (`ExposedPaperLedgerWriter.kt:868`)、**WS では highest/lowest だけが進み trailing は tighten しない**。tighten は REST tick 経路でのみ起きる。exit 判定自体は両経路にある。

#### 保存状況

| 入力 | 保存先 | replay 可否 |
| --- | --- | --- |
| WS trade event | `paper_market_event_receipts` (`admission_ordinal` 全順序、`normalized_payload` 全文、無期限 append-only) | 厳密に再現可能 |
| 約定の根拠 event | `executions.source_session_id` / `source_sequence` / `source_price_jpy` 他 | 厳密に再現可能 |
| order の TTL 事実 | `orders.expires_at` / `expiry_source` / `effective_ttl_seconds` | 厳密に再現可能 |
| 発注時の queue | `orders.queue_ahead_btc` / `queue_consumed_btc` / `queue_snapshot_at` | **記録済み指値でのみ**再現可能 |
| market data gap | `market_data_gaps` / `infrastructure_gap_events` | 厳密に再現可能 |
| eligibility 境界 | `orders.market_eligible_after_admission_ordinal` 他 | 厳密に再現可能 |
| **板 (orderbook) の depth** | なし (`queue_ahead_btc` の 1 数値に潰される) | **再現不可** |
| **REST tick の価格経路** | なし | 再現不可 |
| **ATR14 系列** | なし (mark 時点に 5 分足を fetch) | 再現不可 |

cohort は永続化されず、`orders` / `executions` / `positions` の lineage 3 列から SQL の `CASE` で毎回導出される (`ExposedEvaluationRepository.kt:109-142`)。

## Goals / Non-Goals

**Goals:**

- 記録済みの resting LIMIT entry に対し、TTL と offset を変えた場合の約定条件を、記録済み receipt から厳密に計算できる量として出力する
- 記録済みの closed trade に対し、trailing の起動条件と係数の組ごとの exit を、近似であることを明示したうえで再計算する
- 両出力で cohort を分離し、gap・sequence 欠落・input 欠如を `UNKNOWN` / `NO_REPLAY_INPUT` として母集団に残す
- 読み取り専用を、設計と実行経路の両方で保証する

**Non-Goals:**

- 汎用 replay framework、versioned assumption manifest 体系、L2 replay
- 板 / ATR14 / tick snapshot の永続化
- replay 結果だけを根拠にした本番適用
- MARKET / STOP entry の replay (板依存のため厳密再現できない)
- LLM decision の再実行、過去 klines を用いた戦略バックテスト

## Decisions

### D1. offset の反実仮想は二値の fill/no-fill を出さず、「観測量 V と必要 queue 閾値」を出す (agent 仮決め)

**これが本設計の中心的な判断である。**

offset を変えると指値価格が変わり、その価格レベルの `queue_ahead_btc` が必要になる。板は保存されていないため、この値は原理的に不明である。ここで二値の fill/no-fill を出すと「約定した可能性がある」を「約定した」に変換することになり、AGENTS.md の paper 真実性に反する。

代わりに、receipt から**厳密に計算できる量**だけを出す。

- `V(offset, ttl)` = 発注時刻から TTL 窓内に観測された、`side == SELL` かつ `priceJpy ≤ 指値` の receipt の `sizeBtc` 累積和。**これは receipt から厳密に決まる。**
- 約定条件は `queue_ahead ≤ V − order.sizeBtc` である。右辺を `queueHeadroom` として出力する。
- `queueHeadroom < 0` の組は、**queue がゼロでも約定しない**。この場合に限り `NEVER_FILLS` と確定してよい (queue は非負であるため、queue の不明性に依存しない)。
- `queueHeadroom ≥ 0` の組は `FILLS_IF_QUEUE_AT_MOST(queueHeadroom)` として出力し、fill したとは書かない。
- 記録済み offset の行では `queue_ahead_btc` が既知であるため、二値の fill/no-fill を確定でき、`fidelity=EXACT` を主張できる。

この形は候補の絞り込みに十分機能する。`queueHeadroom` は offset を広げるほど単調に減少するため、候補間の比較と「明らかに約定しない offset」の除外ができる。

**代替案 (却下)**: 記録済み `queue_ahead_btc` を他の offset へ流用する。→ 板は価格レベルごとに厚みが違うため根拠が無く、fill を過大にも過小にも見せうる。
**代替案 (却下)**: queue = 0 と仮定して楽観的に fill させる。→ 「約定した可能性がある」を「約定した」に変換する典型であり禁止事項に該当する。

### D2. TTL 単独の反実仮想は `EXACT` とする (agent 仮決め)

指値を記録済みの値に固定して TTL だけを変える場合、`queue_ahead_btc` は記録済み値が正しく使える。この場合の fill / 失効は receipt から厳密に決まるため `fidelity=EXACT` を主張できる。

replay は `orders.expiry_source` を再現し、`LLM_TIME_STOP` が効いていた order では system TTL を変えても実効期限が変わらないことを正しく扱う。

### D3. 約定規則は production の実装と同じ式を使い、`PaperExecutionSimulator` は約定価格の算出にのみ使う (agent 仮決め)

fill の**発火条件**は `consumeLimitQueue` の queue 式であり、`PaperExecutionSimulator` の外側にある。replay はこの式を production と同じ形で持つ。約定**価格**は `simulatePendingLimit` が limit 価格 + maker fee で決めるため (`FillSimulator.kt:150-172`)、この経路を再利用する。

replay と production の乖離を検知するため、fixture 検証では `executions.source_sequence` / `source_price_jpy` と replay が選んだ発火 event を突き合わせる。

### D4. replay の時間軸は receipt の `socket_observed_at` とし、wall clock を使わない (agent 仮決め)

production の TTL 失効は REST tick (5 秒 poll) と WS event の両方から呼ばれるため、失効の**観測時刻**は最大 5 秒遅れうる。replay で wall clock を使うと再現性を失うため、receipt の `socket_observed_at` を仮想 clock として注入する。

この結果、replay の失効時刻は production の `orders.canceled_at` と最大 1 poll 間隔ずれる。`orders.expired_at` (論理期限) との一致は厳密に検証できるため、fixture 検証では `expired_at` を突き合わせ、`canceled_at` との差分は実測値として出力に記録する。

### D5. eligibility 境界を replay でも再現する (agent 仮決め)

production は `orders.market_eligible_after_admission_ordinal` 以前の event で約定させない。session 切り替え時は既存 OPEN position を新 session の sequence まで ineligible にする。これを省くと replay が production より多く約定する。replay はこの境界を orders / positions の記録済み列から復元する。

### D6. gap / unknown 判定は既存の gap 資産を再利用する (agent 仮決め)

`market_data_gaps` (WS 由来) と `infrastructure_gap_events` (deploy 由来) の 2 系統があり、区間投影の共通 SQL `EVALUATION_GAP_INTERVAL_CTE_V1` (`EvaluationPopulationSqlV1.kt:7-52`) が既に存在する。未 CLOSE の gap を「現在まで開いている」として扱う規則も実装済みである。replay 独自の gap 判定を書くと評価経路と結論が食い違うため、この CTE に乗る。

replay 固有の unknown 要因を 2 つ追加する。

- **sequence 欠落**: production は `require(event.sequence == cursor + 1)` で穴を許さない。replay で欠落を検出した場合、その区間を跨ぐ対象を `UNKNOWN` とする。
- **candle 取得失敗**: trailing replay で 5 分足を取得できない場合、その対象を `UNKNOWN` とする。代替 ATR で exit を確定させない。

### D7. trailing replay の近似範囲を ATR 値と ratchet 発火時刻だけに限定する (ユーザー確認済み)

価格経路は receipt から厳密に再現できる (WS 経路が全 event で highest/lowest を進めるため)。したがって近似は次の 2 点に限る。

1. **ATR14 の値** — 5 分足を再取得し `IndicatorCalculator` で再計算する。production が観測した値と一致する保証はない。
2. **ratchet の発火時刻** — production は 5 秒 poll で ratchet したが、その実際の発火時刻は保存されていない。replay は receipt ごとに ratchet を評価する。これは production より密であり、trailing stop は production 以上に tighten される方向へ**一様に**偏る。

偏りの向きが一定であるため候補間の相対順位づけには使える。絶対値としての exit 価格は使わない。出力は各行に `fidelity=APPROXIMATE`、`basis=refetched_5m_candles+receipt_derived_path`、`usage=relative_ranking_only`、および偏りの向きを持つ。

### D8. cohort は既存の導出規則をそのまま使い、replay 由来の行を production の cohort へ混ぜない (agent 仮決め)

cohort は `ExposedEvaluationRepository.kt:109-142` の `CASE` 式で導出される派生値である。replay は同じ規則で対象を分類し、`LEGACY_PRE_WS` と `UNSUPPORTED_EXECUTION_SEMANTICS` を `CURRENT` の集計へ含めない。

`paper_market_event_receipts` は `PAPER_WS_V1` 以降にのみ存在するため、生存区間に receipt を持たない対象は `NO_REPLAY_INPUT` とし、fill 有無を推定しない。

replay は DB へ書かないため、新しい `execution_semantics_version` 値を導入する必要はない。

### D9. 読み取り専用の保証方法 (agent 仮決め)

- replay の DB 接続を read-only の DataSource として構築する
- `ExposedPaperLedgerWriter` / `PaperBroker` を replay の依存に含めない (これらは書き込み経路を持つ)
- 回帰テストで、replay 完走前後の対象テーブル内容が同一であることを検証する

`consumeLimitQueue` は production では `UPDATE orders SET queue_consumed_btc` を伴うが、replay はこの累積をメモリ上でのみ進める。

### D10. 配置とエントリポイント (agent 仮決め)

`:trading` に算出ロジックを置き (`me.matsumo.fukurou.trading.replay`)、`build.gradle.kts` に `runOneShotLlm` (`trading/build.gradle.kts:49-64`) と同型の `JavaExec` task を 2 本登録する。`scripts/` に既存慣習 (`#!/usr/bin/env bash` + `set -Eeuo pipefail` + `readonly` 定数 + `fail()`) に沿った wrapper を置く。新 module は作らない。

出力は JSON Lines とし、1 行 1 対象。集計行は cohort × fidelity の組ごとに分離して末尾に出す。

## Risks / Trade-offs

- **[offset 反実仮想が二値の結論を出さないため、絞り込みが弱い]** → `queueHeadroom` の単調性により「明らかに約定しない offset」は確定除外でき、残る候補間の相対比較もできる。3〜5 ペアへの絞り込みには足りると判断する。足りなかった場合は板の永続化を別 change として検討する (Next steps)。
- **[trailing replay の偏りが候補選択を歪める]** → 偏りは一様に tighten 側へかかるが、係数が小さい候補ほど影響が大きい可能性がある。出力に偏りの向きを明記し、採否は 2〜4 週の実適用観察で決める Epic #180 の運用ルールを変えない。
- **[ATR 再取得が production 観測値と乖離する]** → 乖離量そのものは測定できない (production 値が残っていないため)。fixture 検証では exit の絶対一致ではなく候補間の順位保存を検証する。乖離が順位を変えうる範囲は residual risk として残る。
- **[`EXACT` の主張が過剰になる]** → TTL 反実仮想も失効の観測時刻に最大 1 poll 間隔のずれを持つ (D4)。`EXACT` の定義を「論理期限と約定の発火 event が一致すること」と明示し、`canceled_at` の差分は実測値として出力する。
- **[replay が production の約定規則から乖離する]** → fill 発火条件は simulator の外にあるため型では守れない。fixture 検証で `executions.source_*` と突き合わせ、乖離を検知する。
- **[read-only 前提が破れる]** → D9 の 3 重の保証で担保する。
- **[対象データが少なく結論が出ない]** → CURRENT cohort の receipt 蓄積期間が短い場合、母数が足りない可能性がある。その場合は「母数不足で絞り込み不能」を結論として報告し、候補を無理に順位づけしない。

## Migration Plan

production への deploy を伴わない。replay は手元および NAS の read-only 接続から実行する。rollback は不要 (追加のみ、既存経路への変更なし)。

## Open Questions

- TTL の探索格子は実装時に既存 order の分布から決める。現行 `DEFAULT_RESTING_ENTRY_ORDER_TTL = 30 分` (`TradingBotConfig.kt:764`) を必ず含める。TTL は 1800 秒以下へしか変えられない (conservative-only 制約) ため、格子もこの範囲に収める。
- offset の探索格子は実装時に既存 order の指値と当時の mid 価格の分布から決める。
- trailing の係数候補は現行 `2.0` を必ず含める。他の候補値は tail 分布から決める。
- 壊滅的 tail の閾値は既存 trade の最大逆行幅分布から決める。

## Next steps (本 change では実施しない)

- 板 depth の永続化により offset 反実仮想を `EXACT` にする
- ATR14 / tick snapshot の永続化により trailing replay を `EXACT` にする

## 帰属タグ一覧

| 決定 | タグ |
| --- | --- |
| D7 trailing replay を近似と明示して実施する | ユーザー確認済み |
| D1 offset は観測量と queue 閾値で出す | agent 仮決め |
| D2 / D3 / D4 / D5 / D6 / D8 / D9 / D10 | agent 仮決め |

高リスク・要人間確認に該当する未検証前提は本設計時点では無い。D1 は issue の「TTL×offset の 3〜5 ペア絞り込み」の達成手段を二値判定から観測量ベースへ変更するため、reviewer の必須確認事項として PR に転記する。
