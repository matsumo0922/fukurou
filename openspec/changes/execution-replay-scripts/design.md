## Context

Epic #180 の C3 (TTL/offset) と C1 (trailing) の候補を、適用前に記録済みデータで絞り込む手段が要る。decision を固定した執行層だけの replay は LLM 学習データのリークと無縁である。

本設計は独立反証を 2 周経ている。1 周目で offset と trailing の素朴案が「記録済みデータからは目的を達成できない」と判明し、2 周目に向けて trailing を stored fact ベースで再構成し、offset を除外した。以下は 2 周目に提出する設計であり、各判断が反証のどの指摘に対応するかを明記する。

### 現行実装の把握

#### resting LIMIT entry の約定規則 — queue consumption

約定判定は価格クロスではなく queue 消費である (`ExposedPaperLedgerWriter.kt:1155-1170`)。

```
fill ⇔ event.side == SELL ∧ event.priceJpy ≤ limitPrice
     ∧ Σ(該当 event の sizeBtc) ≥ queue_ahead_btc + order.sizeBtc
```

`queue_ahead_btc` は発注時点に板から算出され、同一指値の自 open order 数量を含む (`PaperBroker.kt:490`)。板そのものは保存されない。

#### TTL の決定と失効判定

`expiresAt = min(createdAt + restingEntryOrderTtl, trade_plans.time_stop_at)` (`PaperBroker.kt:795-811`)。`orders.expiry_source` には確定後の区分しか残らないため、TTL を延ばす反実仮想には `trade_plans.time_stop_at` の join が要る。失効判定の処理時刻は writer の `clock.instant()` であり (`ExposedPaperLedgerWriter.kt:806`)、保存されない。判定は REST tick (5 秒 poll) と WS event の両経路から呼ばれる。

#### trailing stop と、それを支える stored fact

trailing stop = `highestPriceSinceEntry − ATR14 × 2.0` を tick step へ floor し単調 tighten する (`PaperLedgerRepository.kt:589-595`)。係数 `2.0` はコード固定値 (`SafetyFloorDefaults.trailingAtrMultiplier`)。tighten は REST tick 経路の ATR でのみ起きる (WS 経路は ATR=null で呼ぶ、`ExposedPaperLedgerWriter.kt:868`)。ATR14 系列は保存されない。

一方で次は stored fact として保存されている (explore 検証済み)。

| stored fact | 列 | 性質 |
| --- | --- | --- |
| production が到達した真の最高値 | `positions.highest_price_since_entry_jpy` | WS+REST 両経路の running max。close 後も exit 込みで保持 (`ExposedPaperLedgerWriter.kt:2287,2302`) |
| production が到達した真の最安値 | `positions.lowest_price_since_entry_jpy` | 同上の running min。実際の最大逆行 (MAE) |
| 初期保護 STOP | entry `orders.protective_stop_price_jpy` | trailing tighten で不変。初期 R 復元の唯一の immutable source |
| 生存区間 | `positions.opened_at` / `closed_at` | epoch millis |
| exit 価格・時刻 | `executions.price_jpy` / `executed_at` | SELL execution |

`current_stop_loss_jpy` / `current_take_profit_jpy` は close 時に NULL 化される (`:2298`) ため、決済直前 trailing 値は closed 行から取れない。exit 理由は `executions` に無く `orders.reason_ja` / `order_type` から分類する。

GMO klines は date 指定で過去日を取れる (`buildKlinesRequest`, `GmoPublicMarketDataSource.kt:365-371`)。ATR14 の warmup は 14 本、production は 64 本窓で計算する (`IndicatorCalculator.kt:459`)。取引所 API client は HTTP 試行ごとに `requestAuditSink.append` で `command_event_log` へ書きうる (`GmoPublicRequestAudit.kt:79`)。

`admission_ordinal` は永続 sequence 由来で正当な欠番を含む。receipt の retention は正本上「最低 365 日」であり無期限ではない。cohort は lineage から `CASE` で導出される派生値である (`ExposedEvaluationRepository.kt:109-142`)。

## Goals / Non-Goals

**Goals:**

- TTL 候補の約定 / 失効を、厳密性を保証できる対象について `EXACT` で再計算する
- trailing の壊滅的 tail を stored fact から `EXACT` で確認する
- trailing 候補の exit を、stored fact でゲートした忠実な部分集合について `APPROXIMATE` で順位づける
- 忠実に扱えない対象を `UNKNOWN` として母集団に残し、理由を区別して開示する
- cohort を分離し、read-only 境界を構造的に守る

**Non-Goals:** proposal.md の「本 change で実施しないこと」に従う。特に offset の反実仮想、production ATR との厳密一致を含まない。

## Decisions

各判断の末尾に、対応する反証指摘 (前回 ID) を付す。

### D1. `EXACT` (TTL) の定義を限定し、崩れる対象を `UNKNOWN` へ落とす — [2-A, 2-B]

`EXACT` は「論理期限 (`orders.expired_at`) の一致」と「約定を発火させた receipt が `executions.source_*` と一致」の 2 点だけを意味する。次を `UNKNOWN` にする。

- **処理時刻の曖昧性** [2-A]: 処理時刻は保存されない。候補期限の前後いずれか 1 poll 間隔 (5 秒) 以内に約定条件を満たす receipt がある対象は、production が失効と約定のどちらを先に見たか決定できない。**窓は片側ではなく両側** に取る (期限直前の約定は「約定が先か失効が先か」不定、期限直後の約定は「その poll で失効済みか」不定)。理由 `PROCESSING_CLOCK_AMBIGUOUS`。
- **同価格 order の queue 結合** [2-B]: `queue_ahead_btc` は同一指値の自 open order 数量を含む。対象の生存区間と重なる同一指値の別 order が存在する対象は、候補 TTL でその重なりが変わるため記録済み queue を流用できない。理由 `QUEUE_COUPLED_SIBLING`。

これにより本 replay は各 order を独立に変更した反実仮想に限定される。policy replay ではないことを出力に明記する。

### D2. 実効期限は `trade_plans.time_stop_at` を join して算出する — [2-C]

候補 TTL による期限と記録済み time stop の早い方を実効期限とする。`expiry_source` の区分値に依拠しない。time stop を解決できない対象は `TIME_STOP_UNRESOLVED` で `UNKNOWN`。

### D3. gap は 2 系統を別々に投影し、投影失敗は run 全体の失敗にする — [1-A, 3-B]

`EVALUATION_GAP_INTERVAL_CTE_V1` は `infrastructure_gap_events` のみを投影する [1-A]。`market_data_gaps` は `started_at` / `recovered_at` (未回復は現在まで開区間) として別途投影する。いずれかと交差する対象を `UNKNOWN` (`MARKET_DATA_GAP` / `INFRASTRUCTURE_GAP`) とする。

既存 CTE は gap 1,000 件超で意図的にゼロ除算し query 全体を失敗させる [3-B]。この場合 replay は**部分結果を出さず run 全体を失敗**させ、run-level failure と per-target `UNKNOWN` を出力上で区別する。

### D4. receipt の欠落判定は `(session_id, source_sequence)` で行う — [1-C]

`admission_ordinal` の欠番は sequence 由来の正当な穴を含むため欠落判定に使わない [1-C]。ordinal は eligibility 境界比較にのみ使う。`source_sequence` の欠落区間を跨ぐ対象を `RECEIPT_SEQUENCE_GAP` で `UNKNOWN`。

### D5. cohort は lineage から導出し、入力欠如と独立に扱う — [1-B]

cohort の正本は lineage であり receipt の有無ではない [1-B]。無約定時間帯の `CURRENT` order は receipt を持たないが `CURRENT` である。cohort は既存 `CASE` 規則で導出し、`NO_REPLAY_INPUT` は別軸の `population_status` として付与する。`LEGACY_PRE_WS` / `UNSUPPORTED_EXECUTION_SEMANTICS` を `CURRENT` 集計へ混ぜない。

### D6. 対象範囲を境界づけ、silent truncation を作らない — [5-A, 1-D]

receipt journal は増加し続け retention 保証は 365 日である [1-D, 5-A]。対象期間の指定を必須引数とし、対象件数の上限を設け、超過時は打ち切らず run を失敗させる。receipt は期間で絞って streaming で読み、statement timeout を設定する。対象期間の receipt が無い対象は `NO_REPLAY_INPUT` とし推定しない。

### D7. 約定規則は production の式を写し、価格算出のみ simulator を使う

fill 発火条件は `consumeLimitQueue` の queue 式であり simulator の外にある。replay はこの式を同じ形で持ち、queue 累積をメモリ上でのみ進める (書かない)。約定価格と手数料は `simulatePendingLimit` を再利用する。乖離検知は fixture 突き合わせで行う。eligibility 境界 (`market_eligible_after_admission_ordinal`) 以前の receipt で約定させない [2-D は offset 除外により消滅]。

### D8. trailing の壊滅的 tail を stored fact から `EXACT` で確認する — [追加論点 B]

初期 R = `average_entry_price_jpy − entry orders.protective_stop_price_jpy` (最古 entry order、pyramiding 考慮) [追加論点 B]。実際の最大逆行 = `average_entry_price_jpy − lowest_price_since_entry_jpy`。この 2 つは stored fact であり、ATR も外部 API も要らない。閾値 (初期 R の何倍で壊滅的とみなすか) を超えた trade の件数を `EXACT` で出す。これが issue の「壊滅的 tail の事前確認」に対応する。

`current_stop_loss_jpy` は close で NULL 化されるため使わない [追加論点 B]。

### D9. trailing 候補 exit を stored fact でゲートして `APPROXIMATE` で順位づける — [2-E]

反証 2-E は「REST が receipt に無い高値を観測し、receipt 経路が高値を取りこぼすと偏りの向きが不定になる」であった。stored fact でこれをゲートする。

1. receipt から position 生存区間 `[opened_at, closed_at]` の WS 価格経路を復元し、その最高値 `receiptMax` を求める。
2. `receiptMax` を保存済みの真の最高値 `positions.highest_price_since_entry_jpy` (exit fill を除いた生存中の値) と照合する。**一致しない (`receiptMax < storedMax`) 対象は、REST が receipt 外の高値を観測したことが確定するため `PRICE_PATH_INCOMPLETE` で `UNKNOWN`** とする [2-E への直接処置]。
3. 一致する対象では、running max の軌跡は receipt から忠実に復元できる。production が同じ最高値へ到達する時刻は replay 以前でありうる (REST が先に観測)。trailing は running max に対し単調に tighten するため、replay は production より遅く tighten し、**exit は production より早くならない (より緩い) 側へ一方向に偏る**。この偏りの向きが保証されるため候補間の相対順位づけに使える。
4. ATR14 は date 指定 klines を再取得して `IndicatorCalculator` で再構成する。production が各 tick で使った candle 窓の終端は保存されないため、ATR は production 値と厳密一致しない。これは APPROXIMATE の主因であり、候補間の相対順位づけにのみ使う。

出力は各行に `fidelity=APPROXIMATE`、`basis`、`usage=relative_ranking_only`、偏りの向きを持つ。ATR 再構成用の candle を取得できない対象は `ATR_UNAVAILABLE` で `UNKNOWN` とし、代替値で exit を確定させない [3-A]。

### D10. historical candle reader と外部 API の隔離 — [3-A, 4-A]

trailing replay は過去日の 5 分足を取得する。既存 `getCandles` は現在から遡るため過去時点を指定できない [3-A]。`buildKlinesRequest` の date 対応 wire を read-only で使う薄い reader を作り、対象 trade の日付とその前日 (warmup 14 本ぶん) を fetch する。production の 64 本窓と終端が異なるため ATR は近似であることを D9 で申告済み。

取引所 API client は audit sink 経由で `command_event_log` へ書きうる [4-A]。trailing reader には**書き込みを行わない audit sink を注入**し、DB write を発生させない。TTL replay はそもそも外部 API を呼ばない。

### D11. 読み取り専用の保証 — [4-A]

- DB 接続を read-only role の DataSource として構築する
- `ExposedPaperLedgerWriter` / `PaperBroker` を依存に含めない
- TTL replay は取引所 API client を依存に含めない。trailing replay は書き込みなし audit sink 付きの reader のみを含める
- 回帰テストで、replay 完走前後の対象テーブル内容が同一であることを検証する

### D12. 配置と PR 分割 — [追加論点 D]

`:trading` に `me.matsumo.fukurou.trading.replay` を置き、`build.gradle.kts` に `runOneShotLlm` 同型の `JavaExec` task を 2 本登録する。新 module は作らない。出力は JSON Lines (1 行 1 対象)、集計は cohort ごとに分離する。

反証の PR 規模指摘 [追加論点 D] に対し、実装を 2 PR に分ける。**PR 1 = TTL replay** (pure DB、外部 API 非依存、tasks 群 1〜4)。**PR 2 = trailing tail & exit replay** (historical reader を含む、tasks 群 5〜7)。各 PR が独立にレビュー可能で、PR 1 は外部依存を持たない強い read-only 境界を保つ。

## Risks / Trade-offs

- **[`UNKNOWN` が多すぎて母数が残らない]** → D1 (曖昧性・queue 結合)、D9 (最高値不一致) の除外で母数が減る。実装後に実データで cohort 別・理由別の母数を実測し、絞り込みに足りるかを報告に含める。足りなければ「母数不足で絞り込み不能」を結論とし、候補を無理に順位づけしない。
- **[trailing ランキングが ATR 近似で歪む]** → 価格経路の偏りは D9 のゲートにより一方向 (より緩い) に固定されるが、ATR 近似は別軸で残る。usage を relative_ranking_only に限定し、絶対 exit 価格を主張しない。採否は 2〜4 週の実適用観察で決める Epic #180 の運用を変えない。
- **[offset 軸が欠ける]** → C3 の絞り込みは TTL 軸と、記録済み offset の約定レイテンシ提示に留まる。offset の反実仮想はオーナー判断待ちとして proposal に明記する。
- **[replay が production の約定規則から乖離する]** → fill 発火条件は simulator の外にあり型では守れない。fixture 回帰テストで `executions.source_*` と突き合わせる。
- **[read-only 前提が破れる]** → D11 の依存排除と回帰テスト、D10 の書き込みなし audit sink で担保する。

## Migration Plan

production への deploy を伴わない。read-only 接続から実行する。rollback 不要。

## Open Questions

- TTL 探索格子は既存 order 分布から決める。現行 30 分 (`TradingBotConfig.kt:764`) を必ず含め、1800 秒以下に収める。
- trailing の係数候補は現行 `2.0` を必ず含める。起動条件は即時 / 0.5R / 1R。
- 壊滅的 tail の閾値 (初期 R の倍数) と対象件数上限は実装時に実データ分布から決める。

## Next steps (本 change では実施しない)

- offset 反実仮想 (板 depth の永続化、または C3 の絞り込み手段の変更) — オーナー判断
- production ATR 窓の記録により trailing ランキングを `EXACT` にする

## 帰属タグ一覧

| 決定 | タグ |
| --- | --- |
| D1〜D12 | agent 仮決め |

offset の反実仮想は忠実に計算できないため本 change から除外し、扱いを **人間判断待ち** として PR の「人間に確認してほしいこと」へ転記する。trailing の近似実施はオーナーが以前承認したが、承認の前提 (偏り一様) が反証で否定されたため、D9 の stored-fact ゲートで偏り方向を保証し直した設計として提示する。この再設計自体の妥当性を reviewer の必須確認事項とする。
