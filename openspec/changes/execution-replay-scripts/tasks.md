## 1. read-only 基盤 (PR 1)

- [ ] 1.1 `me.matsumo.fukurou.trading.replay` package を作り、read-only の DataSource 構築を実装する。`ExposedPaperLedgerWriter` / `PaperBroker` を依存に含めない
- [ ] 1.2 対象期間を必須引数として受け取り、期間で絞った receipt を streaming で読む reader を実装する。`normalized_payload` から `PaperMarketTradeEvent` を復元する規則は既存の receipt 読み出し実装に揃える
- [ ] 1.3 `(session_id, source_sequence)` の連続性を検査し欠落区間を列挙する検出器を実装する。`admission_ordinal` の欠番を欠落と解釈しない
- [ ] 1.4 gap 投影を実装する。`infrastructure_gap_events` は `EVALUATION_GAP_INTERVAL_CTE_V1` に乗せ、`market_data_gaps` は `started_at` / `recovered_at` (未回復は現在まで開区間) として別途投影する。投影が件数上限で失敗した場合は run 全体を失敗させ部分結果を出さない
- [ ] 1.5 `orders` / `executions` / `positions` の lineage から既存規則と同じ `EvaluationCohort` を導出する分類器を実装する。receipt の有無を cohort 判定に使わない
- [ ] 1.6 対象件数の上限と statement timeout を実装する。上限超過は打ち切らず run を失敗させる

## 2. 出力契約 (PR 1)

- [ ] 2.1 JSON Lines の出力モデルを定義する。`fidelity` / `cohort` / `population_status` / `unknown_reason` を全行の必須項目にする
- [ ] 2.2 cohort ごとに分離した集計行を出力する集計器を実装する。run-level failure と per-target `UNKNOWN` を出力上で区別する
- [ ] 2.3 eligible 件数、理由別の `UNKNOWN` 件数、入力欠如の件数を開示する summary を実装する

## 3. TTL 反実仮想 (PR 1)

- [ ] 3.1 対象 resting LIMIT entry order を選択する query を実装する。`expires_at` / `expiry_source` / `effective_ttl_seconds` / `queue_ahead_btc` / limit 価格 / size / eligibility 境界を取得し、`trade_plans` を join して `time_stop_at` を取得する
- [ ] 3.2 実効期限を候補 TTL による期限と `time_stop_at` の早い方として算出する。解決不能な time stop を `TIME_STOP_UNRESOLVED` で `UNKNOWN` とする
- [ ] 3.3 production と同じ queue consumption 規則で約定を判定する。SELL かつ指値以下の receipt の数量累積和が記録済み `queue_ahead_btc` + 注文数量に達した時点を約定とする。累積はメモリ上でのみ進める
- [ ] 3.4 eligibility 境界より前の receipt で約定させないガードを実装する
- [ ] 3.5 処理時刻の曖昧性判定を実装する。候補期限の前後いずれか 1 poll 間隔 (5 秒) 以内に約定条件を満たす receipt がある対象を `PROCESSING_CLOCK_AMBIGUOUS` で `UNKNOWN` とする
- [ ] 3.6 同価格 order の queue 結合判定を実装する。対象の生存区間と重なる同一指値の別 order を持つ対象を `QUEUE_COUPLED_SIBLING` で `UNKNOWN` とする
- [ ] 3.7 約定価格と手数料を `DefaultPaperExecutionSimulator.simulatePendingLimit` から得る経路を通す
- [ ] 3.8 各 order の約定レイテンシを出力する。各 order を独立に変更した反実仮想である旨を出力に含める
- [ ] 3.9 TTL 探索格子を既存 order 分布から決める。現行 30 分を含み 1800 秒以下に収める

## 4. TTL 検証 (PR 1)

- [ ] 4.1 fixture 回帰テスト。実約定した既知 order 1 件で、replay の発火 receipt が `executions.source_session_id` / `source_sequence` / `source_price_jpy` と一致し、約定価格と手数料が記録済み execution と一致する
- [ ] 4.2 失効 fixture 回帰テスト。算出した論理期限が `orders.expired_at` と一致する
- [ ] 4.3 read-only 回帰テスト。replay 完走前後で対象テーブル内容が同一である
- [ ] 4.4 外部 API 非依存の回帰テスト。TTL replay の依存グラフに取引所 API client が含まれない
- [ ] 4.5 曖昧性・queue 結合・time stop 優先・ordinal 欠番・gap 交差・cohort 分離・境界超過の各 `UNKNOWN` / 失敗経路の回帰テスト

## 5. trailing 基盤と壊滅的 tail (PR 2)

- [ ] 5.1 対象 closed long position を選択する query を実装する。`opened_at` / `closed_at` / `average_entry_price_jpy` / `highest_price_since_entry_jpy` / `lowest_price_since_entry_jpy` を取得し、最古 entry order の `protective_stop_price_jpy` を join する
- [ ] 5.2 初期リスク R を平均約定価格と entry order 保護 STOP の差から復元する。pyramiding 時は最古 entry を用いる。close で NULL 化される position の stop 列を使わない
- [ ] 5.3 実際の最大逆行を平均約定価格と `lowest_price_since_entry_jpy` の差から計算し、初期リスクの指定倍数を超えた件数を `EXACT` で出力する
- [ ] 5.4 壊滅的 tail 閾値 (初期 R の倍数) を実データ分布から決める

## 6. trailing 候補 exit ランキング (PR 2)

- [ ] 6.1 `buildKlinesRequest` の date 対応 wire を read-only で使う historical candle reader を実装する。書き込みを行わない audit sink を注入する。対象 trade の日付と warmup 分の前日を fetch する
- [ ] 6.2 receipt から生存区間の WS 価格経路を復元し、その最高値 `receiptMax` を求める
- [ ] 6.3 `receiptMax` が保存済みの真の最高値と一致しない対象を `PRICE_PATH_INCOMPLETE` で `UNKNOWN` とする
- [ ] 6.4 ATR14 を `IndicatorCalculator` で再構成する。candle 取得不能な対象を `ATR_UNAVAILABLE` で `UNKNOWN` とし代替値で確定しない
- [ ] 6.5 trailing stop を `highestPriceSinceEntry − ATR × 係数` の tick step floor と単調 tighten で算出する。起動条件 (即時 / 0.5R / 1R) を切り替える
- [ ] 6.6 候補 exit を `APPROXIMATE` で出力する。`basis`、`usage=relative_ranking_only`、および replay の exit が production より早くならない側へ一方向に偏る旨を各行に含める

## 7. trailing 検証 (PR 2)

- [ ] 7.1 壊滅的 tail の回帰テスト。実際の最大逆行が閾値を超える既知 trade が計上され、その行が `EXACT` である
- [ ] 7.2 価格経路欠損の回帰テスト。`receiptMax` が保存済み最高値に満たない trade が `PRICE_PATH_INCOMPLETE` で `UNKNOWN` となる
- [ ] 7.3 ランキング順位保存の回帰テスト。価格経路が一致する trade で現行係数の replay 結果が記録済み exit と同じ方向である
- [ ] 7.4 trailing candle 取得の read-only 回帰テスト。取得が DB を書き換えない

## 8. 実行経路とドキュメント

- [ ] 8.1 `trading/build.gradle.kts` に `runOneShotLlm` 同型の `JavaExec` task を 2 本登録する
- [ ] 8.2 `scripts/` に既存慣習 (`#!/usr/bin/env bash` + `set -Eeuo pipefail` + `readonly` 定数 + `fail()`) に沿った wrapper を 2 本追加する
- [ ] 8.3 `docs/design.md` の実験手順節に 2 スクリプトの位置づけ、`EXACT` / `APPROXIMATE` の定義、各 `UNKNOWN` 区分、offset を除外した理由を追記する
- [ ] 8.4 変更した機能名・クラス名・コマンド名で `docs/` と `README` を grep し誤りになった記述がないか確認する
- [ ] 8.5 実データで cohort 別・理由別の母数を実測し、絞り込みに足りるかを完了報告に含める
- [ ] 8.6 `make detekt` と関連テストを通す
