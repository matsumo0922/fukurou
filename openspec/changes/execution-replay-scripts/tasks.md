## 1. read-only 基盤 (PR 1)

- [ ] 1.1 `me.matsumo.fukurou.trading.replay` package を作り、read-only の DataSource を単一の `REPEATABLE READ` read-only transaction で構築する。`ExposedPaperLedgerWriter` / `PaperBroker` を依存に含めない
- [ ] 1.2 対象期間を必須引数で受け取り、対象 order が跨ぐ session と source sequence 範囲を確定してから、indexed な `(session_id, source_sequence)` で receipt を読む reader を実装する。全 receipt の無索引 time scan を発行しない
- [ ] 1.3 `(session_id, source_sequence)` の連続性を検査し欠落区間を列挙する。`admission_ordinal` の欠番を欠落と解釈しない
- [ ] 1.4 gap 投影を実装する。`infrastructure_gap_events` は `EVALUATION_GAP_INTERVAL_CTE_V1`、`market_data_gaps` は `started_at`/`recovered_at` として別途投影する。投影が件数上限で失敗したら run 全体を失敗させ部分結果を出さない
- [ ] 1.5 lineage から既存規則と同じ `EvaluationCohort` を導出する。receipt の有無を cohort 判定に使わない
- [ ] 1.6 対象件数の上限と statement timeout を実装する。上限超過は打ち切らず run を失敗させる

## 2. 出力契約 (PR 1)

- [ ] 2.1 JSON Lines の出力モデルを定義する。`fidelity` / `cohort` / `population_status` / `unknown_reason` を全行の必須項目にする
- [ ] 2.2 cohort ごとに分離した集計行を出力する。run-level failure と per-target `UNKNOWN` を区別する
- [ ] 2.3 eligible 件数、理由別の `UNKNOWN` 件数、入力欠如の件数、各 order 独立の反実仮想である旨を summary に出す

## 3. TTL 短縮感度 (PR 1)

- [ ] 3.1 対象 resting LIMIT entry order を選択する query を実装する。`created_at` / `expires_at` / `expiry_source` / `queue_ahead_btc` / limit 価格 / size / eligibility 境界 / `market_data_session_id` を取得し、`trade_plans` を join して `time_stop_at` を取得する
- [ ] 3.2 production と同じ queue consumption 規則で約定を判定し、約定を発火させた receipt と約定レイテンシ L を求める。queue 累積はメモリ上でのみ進める。約定価格と手数料は `DefaultPaperExecutionSimulator.simulatePendingLimit` から得る
- [ ] 3.3 記録済み結果 (約定 / 失効) を ground truth として出力する。約定 order では発火 receipt が `executions.source_*` と整合することを前提に L を EXACT とする
- [ ] 3.4 短縮 TTL 候補ごとに retention を判定する。論理期限が約定の論理時刻より境界帯を超えて後なら RETAINED、前なら DROPPED。実効期限は候補 TTL と `time_stop_at` の早い方とし、解決不能な time stop を `TIME_STOP_UNRESOLVED` で `UNKNOWN`
- [ ] 3.5 境界帯 (下限 = reconciler poll grace) 内の候補を `PROCESSING_CLOCK_AMBIGUOUS` で `UNKNOWN` とし、処理遅延の上限を排除できない旨を出力に明記する
- [ ] 3.6 eligibility 境界より前の receipt で約定させないガードを実装する
- [ ] 3.7 TTL 短縮の探索格子を既存 order 分布から決める。現行 30 分を上限に含める

## 4. TTL 検証 (PR 1)

- [ ] 4.1 fixture 回帰テスト。約定した既知 order 1 件で、発火 receipt が `executions.source_session_id`/`source_sequence`/`source_price_jpy` と一致し、約定価格と手数料が記録済み execution と一致する
- [ ] 4.2 短縮 retention の回帰テスト。約定より境界帯を超えて前で失効する候補が DROPPED、後の候補が RETAINED になる
- [ ] 4.3 境界帯の回帰テスト。約定の論理時刻の境界帯内の候補が `PROCESSING_CLOCK_AMBIGUOUS` で `UNKNOWN` になる
- [ ] 4.4 read-only 回帰テスト。replay 完走前後で対象テーブル内容が同一である。取引所 API client が依存グラフに含まれない
- [ ] 4.5 time stop 優先・ordinal 欠番・gap 交差・cohort 分離・境界超過の各 `UNKNOWN` / 失敗経路の回帰テスト

## 5. tail 事実シート (PR 2)

- [ ] 5.1 対象 position を選択する query を実装する。`average_entry_price_jpy` / `lowest_price_since_entry_jpy` / size / partial close 有無を取得する
- [ ] 5.2 初期リスク R を既存 evaluation と同じ fill-weighted stop から復元する。pyramiding でも同時点に存在した基準になることを確認する
- [ ] 5.3 実際の逆行を平均約定価格と `lowest_price_since_entry_jpy` の差から求め、初期リスクの指定倍数を超えた件数を出力する。最安値が exit fill slippage を含む台帳値である旨を明記する
- [ ] 5.4 最安値または entry stop が null の position を `TAIL_BASIS_UNAVAILABLE` で `UNKNOWN` とし、部分決済 position に基準数量変化を注記する
- [ ] 5.5 壊滅的 tail の閾値 (初期 R の倍数) を実データ分布から決める
- [ ] 5.6 tail の回帰テスト。逆行が閾値を超える既知 position が計上され、fill-weighted R を用い、null 基準が `UNKNOWN` になる

## 6. 実行経路とドキュメント (各 PR が自スクリプト分を担当)

- [ ] 6.1 [PR1] TTL、[PR2] tail の `JavaExec` task を `trading/build.gradle.kts` に `runOneShotLlm` 同型で登録する
- [ ] 6.2 [PR1] TTL、[PR2] tail の `scripts/` wrapper を既存慣習 (`#!/usr/bin/env bash` + `set -Eeuo pipefail` + `readonly` + `fail()`) で追加する
- [ ] 6.3 [PR1] `docs/design.md` に共通の位置づけ・`EXACT` 定義・`UNKNOWN` 区分・trailing/offset 除外理由・read-only credential 前提を追記する。[PR2] tail シートの読み方を追記する
- [ ] 6.4 [各PR] 変更した機能名・クラス名・コマンド名で `docs/` と `README` を grep し誤りになった記述がないか確認する
- [ ] 6.5 [各PR] 実データで cohort 別・理由別の母数を実測し、絞り込みに足りるかを完了報告に含める
- [ ] 6.6 [各PR] `make detekt` と関連テストを通す
