## 1. read-only 基盤

- [ ] 1.1 `me.matsumo.fukurou.trading.replay` package を作り、read-only の DataSource 構築を実装する。`ExposedPaperLedgerWriter` / `PaperBroker` に依存しないことを import レベルで守る
- [ ] 1.2 `paper_market_event_receipts` を `admission_ordinal` 昇順で読み、`normalized_payload` から `PaperMarketTradeEvent` を復元する reader を実装する。既存の `selectPaperMarketEventReceipt` の復元規則に揃える
- [ ] 1.3 receipt の `(session_id, source_sequence)` 連続性を検査し、欠落区間を列挙する検出器を実装する
- [ ] 1.4 `market_data_gaps` と `infrastructure_gap_events` を `EVALUATION_GAP_INTERVAL_CTE_V1` で区間へ投影し、対象の生存区間との交差を判定する gap 判定器を実装する
- [ ] 1.5 `orders` / `executions` / `positions` の lineage 3 列から既存規則と同じ `EvaluationCohort` を導出する分類器を実装する。receipt を持たない対象を `NO_REPLAY_INPUT` とする

## 2. 出力契約

- [ ] 2.1 JSON Lines の出力モデルを定義する。`fidelity` / `basis` / `usage` / `cohort` / `population_status` を全行の必須項目にする
- [ ] 2.2 cohort × fidelity ごとに分離した集計行を出力する集計器を実装する。異なる fidelity を単一の集計値へ混ぜないことを型で守る
- [ ] 2.3 eligible / `UNKNOWN` / `NO_REPLAY_INPUT` の件数を必ず開示する summary を実装する

## 3. TTL×offset replay

- [ ] 3.1 対象となる resting LIMIT entry order を選択する query を実装する。`expires_at` / `expiry_source` / `effective_ttl_seconds` / `queue_ahead_btc` / eligibility 境界の各列を取得する
- [ ] 3.2 production と同じ queue consumption 規則で約定を判定する関数を実装する。SELL かつ指値以下の receipt の数量累積和が `queue_ahead_btc` + 注文数量に達した時点を約定とする
- [ ] 3.3 実効期限を system TTL と記録済み LLM time stop の早い方として算出する。`expiry_source` が `LLM_TIME_STOP` の order で system TTL 変更が実効期限を動かさないことを保証する
- [ ] 3.4 eligibility 境界より前の receipt で約定させないガードを実装する
- [ ] 3.5 記録済み指値での TTL 反実仮想を `fidelity=EXACT` として出力する経路を実装する
- [ ] 3.6 offset 反実仮想を実装する。TTL 窓内の該当 SELL 数量累積和と、約定に必要な queue 上限を出力する。上限が負の場合のみ約定しないことを確定し、それ以外では二値判定を出さない
- [ ] 3.7 約定価格と手数料を `DefaultPaperExecutionSimulator.simulatePendingLimit` から得る経路を通す
- [ ] 3.8 探索格子を既存 order の分布から決める。TTL は現行 30 分を含み 1800 秒以下に収める。offset は既存指値の分布から決める

## 4. trailing exit replay

- [ ] 4.1 対象となる closed trade を選択する query を実装する。`highest_price_since_entry_jpy` / `current_stop_loss_jpy` / entry 価格 / R の算出に要る列を取得する
- [ ] 4.2 receipt から position 生存区間の価格経路を復元する。WS 経路が全 event で highest/lowest を進める production 規則に揃える
- [ ] 4.3 5 分足を再取得し `IndicatorCalculator` で ATR14 を再構成する。取得失敗時は対象を `UNKNOWN` とし、代替値で exit を確定させない
- [ ] 4.4 trailing stop を `highestPriceSinceEntry − ATR × 係数` の tick step floor と単調 tighten で算出する。production の式に揃える
- [ ] 4.5 起動条件 (即時 / 0.5R / 1R 到達後) を切り替えられるようにする
- [ ] 4.6 exit 判定を実装し、結果を `fidelity=APPROXIMATE` として出力する。`basis` と `usage`、および ratchet が production より密であることによる偏りの向きを各行に含める
- [ ] 4.7 候補ごとの最大逆行幅と、閾値超えの件数を出力する。閾値は既存 trade の分布から決める

## 5. 検証

- [ ] 5.1 fixture 回帰テストを追加する。実約定した既知 order 1 件について、replay が選んだ発火 receipt が `executions.source_sequence` / `source_price_jpy` と一致し、約定価格と手数料が記録済み execution と一致することを検証する
- [ ] 5.2 TTL 失効の fixture 回帰テストを追加する。`orders.expired_at` (論理期限) の一致を検証し、`canceled_at` との差分を実測値として記録する
- [ ] 5.3 read-only 回帰テストを追加する。replay 完走前後で対象テーブルの内容が同一であることを検証する
- [ ] 5.4 gap 交差の回帰テストを追加する。gap と交差する対象が `UNKNOWN` となり、約定とも失効とも判定されないことを検証する
- [ ] 5.5 cohort 分離の回帰テストを追加する。`LEGACY_PRE_WS` の対象が `CURRENT` の集計の母数にも分子にも入らないことを検証する
- [ ] 5.6 offset 反実仮想が二値判定を出さないことの回帰テストを追加する。queue 上限が 0 以上の行に約定確定が現れないことを検証する
- [ ] 5.7 trailing 候補の順位保存を検証する回帰テストを追加する。現行係数の replay 結果が記録済み exit と同じ方向であることを検証する

## 6. 実行経路とドキュメント

- [ ] 6.1 `trading/build.gradle.kts` に `runOneShotLlm` と同型の `JavaExec` task を 2 本登録する
- [ ] 6.2 `scripts/` に既存慣習に沿った wrapper を 2 本追加する。`#!/usr/bin/env bash` + `set -Eeuo pipefail` + `readonly` 定数 + `fail()` の骨格に揃える
- [ ] 6.3 `docs/design.md` の実験手順節に replay スクリプトの位置づけ、fidelity の読み方、offset 反実仮想が二値判定を出さない理由を追記する
- [ ] 6.4 変更した機能名・クラス名・コマンド名で `docs/` と `README` を grep し、誤りになった記述がないか確認する
- [ ] 6.5 `make detekt` と関連テストを通す
