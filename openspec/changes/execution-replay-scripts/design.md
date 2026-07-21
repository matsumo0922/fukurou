## Context

Epic #180 の C3 (TTL) の候補を、適用前に記録済みデータで絞り込む手段が要る。decision を固定した執行層だけの replay は LLM 学習データのリークと無縁である。

本設計は独立反証を 2 周経ている。1 周目で offset と trailing の素朴案が退けられ、2 周目で trailing の stored-fact ゲート案 (ATR と REST poll cadence が未保存のため偏り方向を保証できない) と、TTL 反実仮想の広い解釈 (延長・境界曖昧・安全ゲート) が退けられた。本 (3 周目に提出する) 設計は、オーナー判断で scope を「忠実に計算できるコア」に確定させたものである。

### 忠実に計算できる範囲と、できない範囲

| 対象 | 忠実性 | 根拠 |
| --- | --- | --- |
| resting LIMIT の約定レイテンシ | EXACT | 約定は記録済み receipt で発火し、`executions.source_*` に残る |
| TTL を**短縮**した場合の retention | EXACT (境界帯を除く) | 短縮は記録済み約定を取りこぼす方向のみ。延命による口座状態変化が無い |
| position の逆行 (tail) | 台帳事実 | `lowest_price_since_entry_jpy` は exit fill slippage を含む台帳値 |
| TTL を**延長**した場合 | 不可 | 延命先の halt / 残高 / exposure を忠実に再現できない (`ExposedPaperLedgerWriter.kt:1052`) |
| trailing 候補ランキング | 不可 | ATR14 値と REST poll cadence が未保存、誤差双方向 |
| 指値 offset の反実仮想 | 不可 | 板 depth 未保存 |

### 現行実装の把握

#### resting LIMIT の約定規則と queue

約定は queue consumption で発火する (`ExposedPaperLedgerWriter.kt:1155-1170`)。`queue_ahead_btc` は発注時点に固定される値であり (`PaperBroker.kt:475-502`)、その order 自身の TTL を短縮しても変わらない。したがって各 order を独自の記録済み `queue_ahead_btc` と記録済み receipt 列で独立に評価でき、同価格 sibling の結合を考慮する必要がない (sibling は自身の作成時点の固定 queue にしか影響しない)。

#### TTL と失効判定

`expiresAt = min(createdAt + restingEntryOrderTtl, trade_plans.time_stop_at)` (`PaperBroker.kt:795-811`)。config は TTL を短縮方向にしか許さない (`TradingBotConfig.kt:439`)。失効判定の処理時刻は writer の `clock.instant()` で保存されず (`ExposedPaperLedgerWriter.kt:806`)、receipt 永続化から処理までの遅延に厳密な上限は無い。

#### tail に使う stored fact

`positions.lowest_price_since_entry_jpy` は WS+REST の running min で、close 時に exit fill を畳み込む (`ExposedPaperLedgerWriter.kt:2288`)。exit SELL fill は adverse slippage で trigger より低くなりうる (`FillSimulator.kt:199`) ため、この値は市場最安値ではなく台帳値である。初期 R は既存 evaluation が fill-weighted stop から算出する (`EvaluationMath.kt`, `ExposedEvaluationRepository.kt:152`)。`lowest` と entry stop は nullable。

cohort は lineage から `CASE` で導出される派生値である (`ExposedEvaluationRepository.kt:109-142`)。gap は `market_data_gaps` (WS 由来) と `infrastructure_gap_events` (deploy 由来) の 2 系統で、後者のみ `EVALUATION_GAP_INTERVAL_CTE_V1` が投影し、1,000 件超で query を意図的に失敗させる (`EvaluationPopulationSqlV1.kt:35`)。`admission_ordinal` は永続 sequence 由来で正当な欠番を含む。receipt に時刻 index は無く、index は `(session_id, source_sequence)` と `admission_ordinal` のみ (`TradingPersistenceBootstrap.kt:487-496`)。receipt retention は「最低 365 日」。read-only role `fukurou_mcp` は receipt SELECT を持たず `command_event_log` INSERT を持つ (`mcp-role.sql`)。

## Goals / Non-Goals

**Goals:** 各 order の約定レイテンシと短縮 TTL retention を EXACT で出す。position の逆行を台帳事実として集計する。cohort を分離し gap/unknown を母集団に残す。read-only を構造的に守る。

**Non-Goals:** proposal の「本 change で実施しないこと」に従う。trailing ランキング、offset、TTL 延長、外部 API を含まない。

## Decisions

各判断の末尾に、対応する 2 周目反証 ID を付す。

### D1. TTL は短縮のみを反実仮想の対象とし、延長を扱わない — [B-06]

config が短縮しか許さない。短縮は記録済み約定を取りこぼす方向のみで、延命先の安全ゲート・口座状態を必要としないため B-06 が構造的に発生しない。TTL 延長は Non-Goal とする。

### D2. fill の権威を記録済み execution に置き、DROPPED だけを EXACT とする — [F1, F2, B-05, B-07]

fill を queue から再導出して判定に使わない。約定の権威は記録済み execution 行とする [F1]。各 order について次を出す。

- **記録済み結果** (ground truth): entry execution 行が存在すれば約定、`orders.expired_at` が立てば TTL 失効、`cancel_reason` (execution 無し・`expired_at` NULL) が立てば非 TTL 終端 (D2b)、いずれも無ければ snapshot 時点で OPEN (D2c)。
- **market 応答レイテンシ** `L`: `executions.executed_at − orders.created_at`。`executed_at` は fill を発火させた market event の **socket 受信時刻** (`fill.executedAt = event.receivedAt`, `ExposedPaperLedgerWriter.kt:1058`, `PaperMarketTradeEvent.kt:48`) である。したがって `L` は「発注から、約定を発火させた market event が到着するまで」の EXACT なレイテンシであり、処理 wall-clock ではない。
- **短縮 TTL の confirmed-DROPPED**: 候補 TTL `T'` の論理期限 `E' = created_at + T'` を `executed_at` (= 約定を発火させた market event の socket 時刻) と比較する。

失効判定は WS event 経路 (`:869`) と、それとは独立な REST 周期 tick 経路 (`:421`, 5 秒間隔) の両方で、保存されない処理 wall-clock (`clock.instant()`) を用いて評価される [F2 再指摘]。処理は socket 受信から無上限に遅延しうる [B-05]。したがって:

- `E' ≤ executed_at` なら、`E' ≤ executed_at ≤ (fill の処理時刻)` により、fill が処理されるまでに必ず `E'` を過ぎた失効判定が挟まる。**DROPPED は EXACT**。
- `E' > executed_at` の場合、production が fill を処理した時刻 (socket + 無上限遅延) と、その間に走る REST tick の失効判定を復元できないため、**RETAINED か DROPPED かを確定できない**。この候補は `RETENTION_UNCONFIRMED` で `UNKNOWN` とし、**RETAINED を主張しない**。

fidelity 契約 [B-07, F2]: `L` (market 応答レイテンシ) と confirmed-DROPPED は `EXACT`。`E' > executed_at` の retention は `UNKNOWN`。本 replay は延長を扱わず、延長 fill も RETAINED も主張しない。

主出力はこの EXACT な market 応答レイテンシ分布と、各短縮 TTL 候補が確実に取りこぼす fill 件数 (confirmed-DROPPED) である。これにより「どこまで TTL を短縮すると fill を確実に失い始めるか」を保守的に (取りこぼしを過小評価しない形で不確定分を UNKNOWN として開示しつつ) 絞り込める。RETAINED を確定できないことは C3 の TTL 短縮方向の選択には支障しない (短縮は fill を増やさないため、confirmed-DROPPED が意思決定の主軸になる)。

### D2c. snapshot 時点で OPEN の order を明示する — [F2 再確認で判明した非 blocking]

window 終端付近で作られ snapshot 時点でまだ OPEN (execution 無し・`expired_at` NULL・`cancel_reason` NULL) の resting entry order は、約定 / TTL 失効 / 非 TTL 終端のどれにも当たらない。system はこれを `OPEN_AT_SNAPSHOT` として母数開示に明示し、黙って落とさない。fill を主張しない。

### D2b. execution 行を持たない order に fill を合成しない — [F1]

queue 到達後、production は hard-halt ゲート (`ExposedPaperLedgerWriter.kt:872`) と order ごとの resting-entry fill invariant (drawdown / exposure / balance / group risk / EV、`:1060, 1652-1687`, `SafetyFloor.kt:526-538`) を fill 時点の口座状態で評価し、棄却すると execution 行を作らず `status=CANCELED` (`expired_at` は NULL、`cancel_reason ∈ {HARD_HALT, MARKET_DATA_GAP, LEGACY_UNCLASSIFIED}`) で終端する。

これらの order は約定でも TTL 失効でもない。system は execution 行を持たない order に fill を主張しない。対象選択で `cancel_reason` を持つ CANCELED を `NON_TTL_TERMINAL` として分類し、TTL retention 分析の母数から除外する (短縮しても TTL 由来で結果が変わらないため)。queue consumption 規則は fixture の cross-check にのみ使い、fill 生成には使わない。

### D3. 各 order を独自の記録済み queue で独立評価し、sibling ゲートを置かない — [N-01]

`queue_ahead_btc` は発注時点で固定され、その order の TTL 短縮で変わらない。したがって記録済み `queue_ahead_btc` と記録済み receipt 列だけで各 order を独立に評価する。前回設計の同価格 sibling UNKNOWN ゲートは過剰 [N-01] なので置かない。

### D4. tail は fill-weighted R と台帳最安値から求め、ラベルを縮退する — [B-03, B-04, B-10]

初期 R を既存 evaluation と同じ fill-weighted stop から復元する [B-03] (pyramiding でも同時点に存在した基準になる)。実際の逆行を `average_entry_price_jpy − lowest_price_since_entry_jpy` から求める。この最安値は exit fill slippage を含む台帳値であるため [B-04]、出力に「台帳記録値、exit fill slippage を含みうる」と明記し、市場最安値とは主張しない。

`lowest` または entry stop が null の position、および fill-weighted stop が average entry 以上で risk width が非正になる position (`EvaluationMath.kt:26` が `width > 0` のみ採用) は `TAIL_BASIS_UNAVAILABLE` で `UNKNOWN` とする [B-04, F3]。tail は exit 理由に依らず逆行幅を出すが、partial close で基準 size が変わる position はその旨を注記する [B-10]。

### D5. 実効期限は `trade_plans.time_stop_at` を join して算出する — [前回 2-C]

候補 TTL による期限と記録済み time stop の早い方を実効期限とする。短縮候補が time stop より後なら time stop が支配する。解決できない対象は `TIME_STOP_UNRESOLVED` で `UNKNOWN`。

### D6. gap は 2 系統を別々に投影し、投影失敗は run 全体の失敗にする — [前回 1-A, 3-B]

`infrastructure_gap_events` は既存 CTE、`market_data_gaps` は `started_at`/`recovered_at` として別途投影する。交差対象を `UNKNOWN`。CTE が 1,000 件超で失敗した場合は部分結果を出さず run 全体を失敗させ、run-level failure と per-target `UNKNOWN` を区別する。

### D7. receipt の欠落判定は `(session_id, source_sequence)` で行う — [前回 1-C]

`admission_ordinal` の欠番は欠落判定に使わない (正当な穴を含む)。ordinal は eligibility 比較のみ。`source_sequence` の欠落区間を跨ぐ対象を `RECEIPT_SEQUENCE_GAP` で `UNKNOWN`。

### D8. cohort は lineage から導出し、入力欠如と独立に扱う — [前回 1-B]

cohort を既存 `CASE` 規則で導出し、`NO_REPLAY_INPUT` を別軸の `population_status` とする。`LEGACY_PRE_WS` / `UNSUPPORTED_EXECUTION_SEMANTICS` を `CURRENT` 集計へ混ぜない。

### D9. receipt 読み取りを対象 order の session/sequence 範囲で駆動し、全期間 time scan を避ける — [B-11]

receipt に時刻 index は無い [B-11]。対象 order は `market_data_session_id` と eligibility ordinal を持つため、対象 order が跨ぐ session と source_sequence 範囲を先に確定し、indexed な `(session_id, source_sequence)` で必要な receipt だけを読む。全 receipt の time predicate scan を発行しない。対象件数の上限と statement timeout を設け、超過時は打ち切らず run を失敗させる。

### D10. 全入力を単一の read-only snapshot で読み、read-only を構造的に守る — [B-09]

全入力を単一の `REPEATABLE READ` read-only transaction で読み、query 間の mixed snapshot (order を読んだ後に receipt が追加され、存在しなかった組合せを EXACT 判定する) を防ぐ [B-09]。既存に `REPEATABLE READ, readOnly=true` の先例がある (`ExposedLlmDecisionReconstructionRepository.kt:167`)。

replay の実行には receipt を含む read set への SELECT 権を持ち write 権を持たない専用 read-only credential を前提とする。既存 `fukurou_mcp` role は要件を満たさない [B-09] ため、本 change は必要な read-only 権限の付与を運用前提として文書化する (role 定義の変更は本 change のコードには含めない。付与はオーナー承認の運用作業)。`ExposedPaperLedgerWriter` / `PaperBroker` を依存に含めない。回帰テストで完走前後のテーブル内容一致を検証する。

### D11. queue 規則は fixture cross-check にのみ使う — [F1, 前回 D7]

fill の権威は記録済み execution である (D2)。queue consumption 規則 (`consumeLimitQueue` の式) は本 replay の fill 判定には使わず、fixture 回帰テストで「記録済み execution の source receipt が我々の queue 理解と整合するか」を検証する cross-check にのみ使う。約定価格と手数料の照合には `simulatePendingLimit` を用いる。eligibility 境界以前の receipt を約定 anchor に選ばない。

### D12. 配置と PR 分割・全 task の帰属 — [B-12]

`:trading` に `me.matsumo.fukurou.trading.replay` を置き、`build.gradle.kts` に `runOneShotLlm` 同型の `JavaExec` task を 2 本登録する。出力は JSON Lines、集計は cohort ごとに分離する。

PR を 2 本に分ける。**PR 1 = 共通基盤 + TTL 短縮感度** (tasks 群 1〜4、および 6 の PR1 分)。**PR 2 = tail 事実シート** (tasks 群 5、および 6 の PR2 分)。tasks 群 6 (JavaExec / wrapper / docs / 母数実測 / detekt) は各 PR が自分のスクリプト分を担う形で明示的に割り当てる [B-12]。共通基盤は PR 1 で確定し PR 2 が再利用する。どちらの PR も pure DB で、1 実装者 / 1 レビュアーが独立にレビューできる規模とする。

## Risks / Trade-offs

- **[confirmed-DROPPED は真の取りこぼしの下界]** → `E' > executed_at` の一部も production では drop しうるが確定できず `RETENTION_UNCONFIRMED` に入る。confirmed-DROPPED は総 drop 数の下界であるため、C3 で「安全に短縮できる境界」を読む際は confirmed-DROPPED の立ち上がりではなく `RETENTION_UNCONFIRMED` の立ち上がりを慎重側の境界として読む。出力は両件数を併記し、慎重側境界を明示する。
- **[tail の最安値が exit slippage で過大評価]** → D4 で台帳値であることを明記し、市場最大逆行とは主張しない。tail 方向には保守的 (やや多めに検出) である。
- **[`UNKNOWN` / `NO_REPLAY_INPUT` が多く母数が残らない]** → 実装後に実データで cohort 別・理由別の母数を実測し、絞り込みに足りるかを報告に含める。足りなければ「母数不足」を結論とする。
- **[replay が production の約定規則から乖離]** → fill 発火条件は simulator の外にあり型で守れない。fixture 回帰テストで `executions.source_*` と突き合わせる。
- **[read-only credential が未整備]** → D10 で運用前提として文書化し、単一 snapshot txn と回帰テストで多重防御する。role 付与はオーナー承認。

## Migration Plan

production への deploy を伴わない。read-only 接続から実行する。rollback 不要。role 付与を伴う場合はオーナー承認の運用作業として別に行う。

## Open Questions

- TTL 短縮の探索格子は既存 order 分布から決める。現行 30 分 (`TradingBotConfig.kt:764`) を上限に含め、それ以下の短縮候補を並べる。
- 壊滅的 tail の閾値 (初期 R の倍数) と対象件数上限は実データ分布から決める。
- 長い replay window (数ヶ月) では infrastructure gap が 1,000 件を超えて gap CTE が run 全体を失敗させうる (D6)。長 window は windowing を前提とする。

## Next steps (本 change では実施しない)

- trailing ランキング: ATR 値と REST poll cadence の永続化を前提に別 change
- offset 反実仮想: 板 depth の永続化を前提に別 change

## 帰属タグ一覧

| 決定 | タグ |
| --- | --- |
| scope を忠実なコアに確定 (trailing ランキング・offset の除外) | ユーザー確認済み |
| D1〜D12 (D2b 含む) | agent 仮決め |

trailing ランキングと offset を「記録済みデータでは不可能」として除外することはオーナーが確定した。read-only credential の付与 (D10) はオーナー承認を要する運用前提として PR の「人間に確認してほしいこと」へ転記する。
