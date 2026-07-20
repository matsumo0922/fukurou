## Context

Epic #180 の C3 (TTL/offset) の候補を、適用前に記録済みデータで絞り込む手段が要る。decision を固定した執行層だけの replay は LLM 学習データのリークと無縁であり、本 change はこれに限定する。

本設計は独立反証を 1 周経ており、当初案から scope を縮退させている。offset 反実仮想と trailing exit replay を除外した理由は proposal.md に記す。

### 現行実装の把握

#### resting LIMIT entry の約定規則 — queue consumption model

約定判定は価格クロスではなく queue 消費である (`ExposedPaperLedgerWriter.kt:1155-1170`)。

```
fill ⇔ event.side == SELL
     ∧ event.priceJpy ≤ limitPrice
     ∧ Σ(該当 event の sizeBtc) ≥ queue_ahead_btc + order.sizeBtc
```

`queue_ahead_btc` は発注時点に板から算出され (`PaperBroker.kt:475-502`)、内訳は「取引所 bid のうち指値と完全一致する価格レベルの厚み」＋「**同一価格の自 open order 数量**」である。値は `orders.queue_ahead_btc` に保存されるが、板そのものは保存されない。

#### TTL の決定

`expiresAt = min(createdAt + restingEntryOrderTtl, trade_plans.time_stop_at)` (`PaperBroker.kt:795-811`)。`orders.expiry_source` には確定後の区分 (`SYSTEM_TTL` / `LLM_TIME_STOP`) しか残らないため、**TTL を延ばす反実仮想では `trade_plans.time_stop_at` の join が必須** である。

失効判定は `!processedAt.isBefore(expiresAt)` で行われ、`processedAt` は writer の `clock.instant()` である (`ExposedPaperLedgerWriter.kt:806`)。この処理時刻は保存されない。判定は REST tick (5 秒 poll) と WS event の両経路から呼ばれる。

#### 保存状況

| 入力 | 保存先 | replay 可否 |
| --- | --- | --- |
| WS trade event | `paper_market_event_receipts` (`normalized_payload` 全文) | 再現可能 |
| receipt の連続性 | `(session_id, source_sequence)` | 再現可能 |
| 約定の根拠 event | `executions.source_session_id` / `source_sequence` / `source_price_jpy` | 再現可能 |
| order の TTL 事実 | `orders.expires_at` / `expiry_source` / `effective_ttl_seconds` | 再現可能 |
| LLM time stop | `trade_plans.time_stop_at` | 再現可能 (join 必要) |
| 発注時の queue | `orders.queue_ahead_btc` | **記録済み指値かつ同価格 order の生存期間が不変な場合のみ** |
| gap | `market_data_gaps` (WS 由来) / `infrastructure_gap_events` (deploy 由来) | 再現可能 (別系統) |
| eligibility 境界 | `orders.market_eligible_after_admission_ordinal` 他 | 再現可能 |
| **失効判定の処理時刻** | なし | **再現不可** |
| 板 depth | なし | 再現不可 |
| REST tick の価格経路 | なし | 再現不可 |
| ATR14 系列 | なし | 再現不可 |

`admission_ordinal` は PostgreSQL sequence 由来であり、transaction が `nextval()` 後に失敗すると正当な欠番が生じる。**欠番を market-data 欠落と解釈してはならない。** 欠落判定には `(session_id, source_sequence)` の連続性を使う。

receipt の retention は正本ドキュメント上「最低 365 日」であり、無期限保証ではない (`docs/design.md`)。

cohort は永続化されず、`orders` / `executions` / `positions` の lineage 3 列から SQL の `CASE` で導出される (`ExposedEvaluationRepository.kt:109-142`)。

## Goals / Non-Goals

**Goals:**

- 記録済み指値のもとで TTL 候補ごとの約定 / 失効を再計算し、厳密性を保証できる対象についてのみ `EXACT` を主張する
- 厳密性を保証できない対象を `UNKNOWN` として母集団に残し、その理由を区別して開示する
- cohort を分離し、`LEGACY_PRE_WS` を `CURRENT` の集計へ混ぜない
- 読み取り専用と外部 API 非依存を、設計と実行経路の両方で保証する

**Non-Goals:**

proposal.md の「本 change で実施しないこと」に従う。特に offset 反実仮想、trailing exit replay、外部 API 呼び出しを含まない。

## Decisions

### D1. `EXACT` の定義を 2 点に限定し、破れる対象を `UNKNOWN` へ落とす (agent 仮決め)

`EXACT` は次の 2 点が記録済み事実と一致することを意味する。

1. 論理期限 (`orders.expired_at`) が replay の算出値と一致する
2. 約定を発火させた receipt が `executions.source_session_id` / `source_sequence` / `source_price_jpy` と一致する

処理時刻の非保存 (Context 参照) により、production が失効を先に見たか約定を先に見たかを決定できない対象が存在する。**判定境界の曖昧区間** を次のように定義し、該当対象を `UNKNOWN` (理由 `PROCESSING_CLOCK_AMBIGUOUS`) とする。

- 候補期限の直前 1 poll 間隔 (`PaperOrderLifecyclePolicy.reconcilerInterval` = 5 秒) 以内に、約定条件を満たす receipt が存在する対象

この窓の外にある対象は、処理時刻がどこにあっても production の判定が一意に決まるため `EXACT` を主張できる。

**代替案 (却下)**: 曖昧対象も `socket_observed_at` で確定させる。→ 「約定した可能性がある」を「約定した」に変換するため禁止事項に該当する。

### D2. 同価格 order の queue 相互作用を持つ対象を `UNKNOWN` へ落とす (agent 仮決め)

`queue_ahead_btc` は同一指値の自 open order 数量を含む (`PaperBroker.kt:490`)。TTL 候補を適用すると同価格の別 order の生存期間が変わり、後続 order の queue が変わるため、記録済み値を流用できない。

replay は対象 order ごとに、**その order の生存区間と重なる同一指値の別 order** を検出する。存在する場合、候補 TTL の適用によりその重なりが変化しうるため `UNKNOWN` (理由 `QUEUE_COUPLED_SIBLING`) とする。

これにより本 replay は「各 order を独立に変更した場合」の反実仮想に限定される。TTL 候補を全 order へ一律適用する policy replay ではないことを出力に明記する。

**代替案 (却下)**: 全 order へ TTL を適用する policy replay を実装する。→ queue の連鎖を全期間で再計算する必要があり、板未保存のため初期 queue が確定しない。

### D3. 実効期限は `trade_plans.time_stop_at` を join して算出する (agent 仮決め)

候補 TTL による期限と記録済みの LLM time stop の早い方を実効期限とする。`orders.expiry_source` が `SYSTEM_TTL` であっても time stop は存在しうるため、区分値ではなく実値を join する。time stop が取得できない order は `UNKNOWN` (理由 `TIME_STOP_UNRESOLVED`) とする。

### D4. gap は 2 系統を別々に投影する (agent 仮決め)

`EVALUATION_GAP_INTERVAL_CTE_V1` が投影するのは `infrastructure_gap_events` のみであり、`market_data_gaps` は含まない (`EvaluationPopulationSqlV1.kt:16`)。両方を扱うため、次のようにする。

- `infrastructure_gap_events` — 既存 CTE に乗せる
- `market_data_gaps` — `started_at` / `recovered_at` (未回復は現在まで開区間) として直接投影する

いずれかと交差する対象を `UNKNOWN` (理由 `MARKET_DATA_GAP` / `INFRASTRUCTURE_GAP`) とする。

既存 CTE は gap 件数が 1,000 を超えると意図的にゼロ除算で query 全体を失敗させる (`EvaluationPopulationSqlV1.kt:35`)。この場合 replay は**対象ごとの `UNKNOWN` ではなく run 全体の失敗**として扱い、部分結果を出力しない。run-level failure と per-target `UNKNOWN` の境界を出力に明記する。

### D5. receipt の欠落判定は `(session_id, source_sequence)` で行う (agent 仮決め)

`admission_ordinal` の欠番は sequence 由来の正当な穴を含むため、欠落判定に使わない。`admission_ordinal` は eligibility 境界の比較にのみ使う。`source_sequence` の欠落を検出した区間を跨ぐ対象を `UNKNOWN` (理由 `RECEIPT_SEQUENCE_GAP`) とする。

### D6. cohort は lineage から導出し、`NO_REPLAY_INPUT` と独立に扱う (agent 仮決め)

cohort の正本は `orders` / `executions` / `positions` の lineage 3 列であり、receipt の有無ではない。無約定時間帯に作成・失効した `CURRENT` order は receipt を持たないが `CURRENT` である。

したがって cohort は既存の `CASE` 規則で導出し、`NO_REPLAY_INPUT` は別軸の `population_status` として付与する。両者を混同しない。

### D7. 約定規則は production の式を写し、価格算出のみ simulator を使う (agent 仮決め)

fill の発火条件は `consumeLimitQueue` の queue 式であり simulator の外側にある。replay はこの式を同じ形で持つ。約定価格と手数料は `simulatePendingLimit` (`FillSimulator.kt:150-172`) を再利用する。乖離検知は D1 の fixture 突き合わせで行う。

replay は queue 累積をメモリ上でのみ進める (production は `UPDATE orders SET queue_consumed_btc` を伴うが、replay は書かない)。

### D8. eligibility 境界を再現する (agent 仮決め)

`orders.market_eligible_after_admission_ordinal` 以前の receipt で約定させない。session 切り替え時の position eligibility も記録済み列から復元する。

### D9. 読み取り専用と外部 API 非依存の保証方法 (agent 仮決め)

- DB 接続を read-only role の DataSource として構築する
- `ExposedPaperLedgerWriter` / `PaperBroker` / `GmoPublicMarketDataSource` を replay の依存に含めない
- 本 change は外部 API を呼ばない。`GmoPublicMarketDataSource` は HTTP 試行ごとに `requestAuditSink.append` を通じて `command_event_log` へ書きうる (`GmoPublicRequestAudit.kt:79`) ため、依存させないことで read-only 境界を構造的に守る
- 回帰テストで、replay 完走前後の対象テーブル内容が同一であることを検証する

### D10. 対象範囲を明示的に境界づける (agent 仮決め)

receipt journal は増加し続けるため、無指定の全件読み取りを許さない。

- 対象期間の指定を必須引数とする
- 対象 order 件数の上限を設け、超過時は打ち切らず run 全体を失敗させる (silent truncation を作らない)
- receipt は期間で絞ったうえで streaming で読む
- statement timeout を設定する

### D11. 配置とエントリポイント (agent 仮決め)

`:trading` に算出ロジックを置き (`me.matsumo.fukurou.trading.replay`)、`trading/build.gradle.kts` に `runOneShotLlm` (`trading/build.gradle.kts:49-64`) と同型の `JavaExec` task を 1 本登録する。`scripts/` に既存慣習 (`#!/usr/bin/env bash` + `set -Eeuo pipefail` + `readonly` 定数 + `fail()`) に沿った wrapper を置く。新 module は作らない。

出力は JSON Lines とし、1 行 1 対象。集計行は cohort ごとに分離して末尾に出す。

## Risks / Trade-offs

- **[`UNKNOWN` が多すぎて母数が残らない]** → D1 / D2 の除外条件により、同価格 order が多い期間や約定が期限直前に集中する order は除外される。母数が絞り込みに足りない場合は「母数不足で絞り込み不能」を結論として報告し、候補を無理に順位づけしない。実装後に実データで母数を実測し、報告に含める。
- **[本 change だけでは C3 の絞り込みが完結しない]** → offset 軸が欠けるため、TTL 軸の絞り込みに留まる。offset の扱いはオーナー判断待ちとして proposal に明記する。
- **[replay が production の約定規則から乖離する]** → fill 発火条件は simulator の外にあり型では守れない。fixture 回帰テストで `executions.source_*` と突き合わせて検知する。
- **[retention により古い対象の入力が欠ける]** → 保証は「最低 365 日」であり無期限ではない。対象期間の receipt が存在しない対象は `NO_REPLAY_INPUT` とし、推定しない。
- **[read-only 前提が破れる]** → D9 の依存排除と回帰テストで担保する。特に外部 API 依存を持ち込まないことを構造的な防御とする。

## Migration Plan

production への deploy を伴わない。replay は read-only 接続から実行する。rollback は不要 (追加のみ、既存経路への変更なし)。

## Open Questions

- TTL の探索格子は実装時に既存 order の分布から決める。現行 `DEFAULT_RESTING_ENTRY_ORDER_TTL = 30 分` (`TradingBotConfig.kt:764`) を必ず含める。TTL は 1800 秒以下へしか変えられない conservative-only 制約があるため、格子もこの範囲に収める。
- 対象 order 件数の上限値は実装時に実データの規模から決める。

## Next steps (本 change では実施しない)

- offset 反実仮想の扱い (板 depth の永続化、または C3 の絞り込み手段の変更) — オーナー判断
- trailing exit replay の扱い (偏り方向が保証できないことを踏まえた再判断) — オーナー判断
- historical candle reader の実装 (trailing を進める場合の前提作業)

## 帰属タグ一覧

| 決定 | タグ |
| --- | --- |
| D1〜D11 | agent 仮決め |

offset 反実仮想と trailing exit replay の実施可否は **人間判断待ち** として本 change から除外した。当初オーナーが承認した trailing の近似実施は、承認の前提であった「偏りが tighten 側へ一様」が独立反証で否定されたため、承認を流用せず再判断を仰ぐ。この 2 点を PR の「人間に確認してほしいこと」へ転記する。
