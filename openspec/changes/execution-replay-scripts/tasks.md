## 1. read-only 基盤

- [ ] 1.1 `me.matsumo.fukurou.trading.replay` package を作り、read-only の DataSource 構築を実装する。`ExposedPaperLedgerWriter` / `PaperBroker` / `GmoPublicMarketDataSource` を依存に含めない
- [ ] 1.2 対象期間を必須引数として受け取り、期間で絞った receipt を streaming で読む reader を実装する。`normalized_payload` から `PaperMarketTradeEvent` を復元する規則は既存の receipt 読み出し実装に揃える
- [ ] 1.3 `(session_id, source_sequence)` の連続性を検査し、欠落区間を列挙する検出器を実装する。`admission_ordinal` の欠番を欠落と解釈しない
- [ ] 1.4 gap 投影を実装する。`infrastructure_gap_events` は `EVALUATION_GAP_INTERVAL_CTE_V1` に乗せ、`market_data_gaps` は `started_at` / `recovered_at` (未回復は現在まで開区間) として別途投影する
- [ ] 1.5 gap 投影が件数上限により失敗した場合に run 全体を失敗させ、部分結果を出力しない経路を実装する
- [ ] 1.6 `orders` / `executions` / `positions` の lineage から既存規則と同じ `EvaluationCohort` を導出する分類器を実装する。receipt の有無を cohort 判定に使わない
- [ ] 1.7 対象件数の上限と statement timeout を実装する。上限超過時は打ち切らず run を失敗させる

## 2. 出力契約

- [ ] 2.1 JSON Lines の出力モデルを定義する。`fidelity` / `cohort` / `population_status` / `unknown_reason` を全行の必須項目にする
- [ ] 2.2 cohort ごとに分離した集計行を出力する集計器を実装する
- [ ] 2.3 eligible 件数、理由別の `UNKNOWN` 件数、入力欠如の件数を開示する summary を実装する
- [ ] 2.4 各 order を独立に変更した反実仮想である旨を出力に含める

## 3. TTL 反実仮想

- [ ] 3.1 対象となる resting LIMIT entry order を選択する query を実装する。`expires_at` / `expiry_source` / `effective_ttl_seconds` / `queue_ahead_btc` / limit 価格 / size / eligibility 境界を取得し、`trade_plans` を join して `time_stop_at` を取得する
- [ ] 3.2 実効期限を、候補 TTL による期限と `time_stop_at` の早い方として算出する。`time_stop_at` を解決できない対象を `TIME_STOP_UNRESOLVED` で `UNKNOWN` とする
- [ ] 3.3 production と同じ queue consumption 規則で約定を判定する関数を実装する。SELL かつ指値以下の receipt の数量累積和が記録済み `queue_ahead_btc` + 注文数量に達した時点を約定とする。累積はメモリ上でのみ進める
- [ ] 3.4 eligibility 境界より前の receipt で約定させないガードを実装する
- [ ] 3.5 処理時刻の曖昧性判定を実装する。候補期限の直前 1 poll 間隔 (5 秒) 以内に約定条件を満たす receipt がある対象を `PROCESSING_CLOCK_AMBIGUOUS` で `UNKNOWN` とする
- [ ] 3.6 同価格 order の queue 結合判定を実装する。対象の生存区間と重なる同一指値の別 order が存在する対象を `QUEUE_COUPLED_SIBLING` で `UNKNOWN` とする
- [ ] 3.7 約定価格と手数料を `DefaultPaperExecutionSimulator.simulatePendingLimit` から得る経路を通す
- [ ] 3.8 TTL 探索格子を既存 order の分布から決める。現行 30 分を含み、1800 秒以下に収める

## 4. 検証

- [ ] 4.1 fixture 回帰テストを追加する。実約定した既知 order 1 件について、replay が選んだ発火 receipt が `executions.source_session_id` / `source_sequence` / `source_price_jpy` と一致し、約定価格と手数料が記録済み execution と一致することを検証する
- [ ] 4.2 失効 fixture 回帰テストを追加する。算出した論理期限が `orders.expired_at` と一致することを検証する
- [ ] 4.3 read-only 回帰テストを追加する。replay 完走前後で対象テーブルの内容が同一であることを検証する
- [ ] 4.4 外部 API 非依存の回帰テストを追加する。replay の依存グラフに取引所 API client が含まれないことを検証する
- [ ] 4.5 曖昧性除外の回帰テストを追加する。候補期限の直前に約定条件を満たす receipt がある対象が `UNKNOWN` となり、約定にも失効にも確定しないことを検証する
- [ ] 4.6 queue 結合除外の回帰テストを追加する。同一指値の重なる order を持つ対象が `UNKNOWN` となることを検証する
- [ ] 4.7 time stop 優先の回帰テストを追加する。候補 TTL が `time_stop_at` を超える場合に実効期限が延長されないことを検証する
- [ ] 4.8 ordinal 欠番の回帰テストを追加する。`admission_ordinal` に欠番があるが `source_sequence` が連続している場合に `UNKNOWN` とならないことを検証する
- [ ] 4.9 gap 交差の回帰テストを追加する。`market_data_gaps` のみと交差する対象が `UNKNOWN` となることを検証する
- [ ] 4.10 cohort 分離の回帰テストを追加する。receipt を持たない現行 lineage の order が現行 cohort のまま入力欠如として扱われること、および他 cohort が現行の集計に入らないことを検証する
- [ ] 4.11 境界超過の回帰テストを追加する。対象件数が上限を超えた場合に部分結果を出さず run が失敗することを検証する

## 5. 実行経路とドキュメント

- [ ] 5.1 `trading/build.gradle.kts` に `runOneShotLlm` と同型の `JavaExec` task を 1 本登録する
- [ ] 5.2 `scripts/` に既存慣習に沿った wrapper を 1 本追加する。`#!/usr/bin/env bash` + `set -Eeuo pipefail` + `readonly` 定数 + `fail()` の骨格に揃える
- [ ] 5.3 `docs/design.md` の実験手順節に replay スクリプトの位置づけ、`EXACT` の定義、`UNKNOWN` の理由区分、各 order 独立の限定を追記する
- [ ] 5.4 変更した機能名・クラス名・コマンド名で `docs/` と `README` を grep し、誤りになった記述がないか確認する
- [ ] 5.5 実データで母数を実測し、絞り込みに足りるかを完了報告に含める
- [ ] 5.6 `make detekt` と関連テストを通す
