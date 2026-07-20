## Why

Epic #180 の C3 (TTL/offset) の候補を、適用前に記録済みデータで絞り込む手段が無い。候補を実運用で 1 つずつ試すと 1 候補あたり数週間かかる。

decision を固定した執行層だけの replay は LLM 学習データのリークと無縁である。paper execution の因果的正本である `paper_market_event_receipts` が durable journal として保存されているため、**記録済み指値のもとで TTL だけを変えた反実仮想** は、限定条件下で実履歴と厳密一致する形で計算できる。

本 change はこの範囲に限定する。offset 反実仮想と trailing exit replay は、独立反証で「記録済みデータからは目的を達成できない」ことが判明したため、本 change に含めない (「本 change で実施しないこと」参照)。

## What Changes

- read-only の TTL 反実仮想 replay スクリプトを 1 本追加する。ledger・production API・runtime config へ一切書き込まない。外部 API を一切呼ばない。
- 記録済みの resting LIMIT entry order に対し、**指値を記録済みの値に固定したまま** TTL 候補ごとの約定 / 失効を `paper_market_event_receipts` 上で再計算する。
- 出力は cohort (`CURRENT` / `LEGACY_PRE_WS` / `UNSUPPORTED_EXECUTION_SEMANTICS`) を分離し、gap・sequence 欠落・入力欠如・**厳密性を保証できない対象** をすべて `UNKNOWN` として母集団に残す。
- `EXACT` の主張範囲を「論理期限の一致」と「約定を発火させた receipt の一致」に限定し、厳密性が崩れる対象は `EXACT` を主張せず `UNKNOWN` に落とす。

### `EXACT` の定義と、そこから除外する対象

独立反証により、素朴な「TTL 反実仮想は厳密」という主張は次の 3 つの入力で破れることが判明した。本 change はこれらを `UNKNOWN` として除外することで、残る対象についてのみ `EXACT` を主張する。

1. **処理時刻の非保存** — production の TTL 失効判定は writer の処理時刻 (`clock.instant()`) で行われるが、この時刻は保存されない (`ExposedPaperLedgerWriter.kt:806`)。receipt の `socket_observed_at` が候補期限の直近にある対象は、production が失効を先に見たか約定を先に見たかを決定できない。
2. **同価格 order 間の queue 相互作用** — TTL を変えると、同一指値の別 order の生存期間が変わり、後続 order の `queue_ahead_btc` が変わる (`PaperBroker.kt:490`)。記録済み queue を流用できない。
3. **LLM time stop の非考慮** — 実効期限は system TTL と LLM の time stop の早い方である (`PaperBroker.kt:795`)。`orders` には確定後の値しか残らないため、`trade_plans.time_stop_at` を join しなければ TTL を延ばす候補で誤って延長する。

3 は join で解決する。1 と 2 は解決できないため、該当する対象を `UNKNOWN` として除外する。

## Capabilities

### New Capabilities
- `execution-parameter-replay`: 記録済み execution データに対して TTL を変えた反実仮想を再計算し、fidelity・cohort・unknown を明示して出力する read-only 分析の契約

### Modified Capabilities

なし。既存 spec の要件は変更しない。

## Impact

- **新規コード**: `:trading` module に replay の算出ロジックと 1 本のエントリポイントを追加する。`scripts/` に実行 wrapper を置く。
- **読み取り対象テーブル**: `paper_market_event_receipts`、`market_data_gaps`、`infrastructure_gap_events`、`market_data_sessions`、`orders`、`trade_plans`、`executions`、`positions`。
- **再利用する既存実装**: `EvaluationCohort` と既存 cohort 導出規則、`DefaultPaperExecutionSimulator.simulatePendingLimit` (約定価格と手数料)。
- **外部依存**: なし。本 change は外部 API を呼ばない。
- **書き込み**: なし。DB 接続は read-only role を前提とする。
- **ドキュメント影響**: あり (`docs/design.md` の実験手順節に replay スクリプトの位置づけ、`EXACT` の定義、除外条件を追記)。

## 本 change で実施しないこと

### offset 反実仮想 (人間判断待ち)

本番の約定規則は queue consumption であり (`ExposedPaperLedgerWriter.kt:1155`)、発注時点の板から算出した `queue_ahead_btc` に依存する。板は保存されない。

当初案は「約定に必要な queue の上限 (`queueHeadroom`) を出力して候補を順位づける」ものだったが、独立反証により **queue は価格レベルごとに独立であるため headroom の大小が約定可能性の順位を与えない** ことが判明した。`queueHeadroom` が大きい候補が実際には no-fill で、小さい候補が fill になりうる。すべての候補で headroom が非負なら確定除外は 0 件となり、絞り込みが成立しない。

加えて、offset を広げて BUY limit が ask 以上へ移動する候補は、production では resting queue に入らず発注時点で即時 taker 約定する (`PaperBroker.kt:389`)。これは receipt 上の queue 条件とは別の経路であり、同じ枠組みで扱えない。

したがって offset 反実仮想は記録済みデータからは達成できない。板 depth の永続化を伴う別 change とするか、C3 の絞り込み手段自体を変えるかは、オーナーの判断を要する。

### trailing exit replay (人間判断待ち)

trailing の近似実施はオーナー承認済みだったが、その承認は「replay の ratchet が production より密であるため、偏りは tighten 側へ一様にかかる」という前提の上にあった。独立反証により **この前提が誤り** であることが判明した。

production は REST ticker からも `highestPriceSinceEntry` を更新する (`ExposedPaperLedgerWriter.kt:414`)。REST ticker が WS receipt に存在しない高値を観測した場合、receipt のみから復元した価格経路は高値を取りこぼし、trailing stop は production より**緩く**なる。偏りの向きが一定でないため、候補間の相対順位の保存が保証できない。

さらに、既存の `MarketDataSource.getCandles` は「現在から直近 N 本」を返し、過去時点を指定できない (`GmoPublicMarketDataSource.kt:552`)。過去の trade に対する ATR 再構成には historical reader の新規実装が要る。取得が成功してしまうため `UNKNOWN` にも落ちず、無関係な ATR で exit を確定する危険がある。

偏りの向きが保証できない以上、当初の承認条件は満たされない。実施可否はオーナーの再判断を要する。

### その他

- 汎用 replay framework、versioned assumption manifest 体系、L2 replay
- 板 depth / ATR14 / tick snapshot の永続化
- replay 結果だけを根拠にした本番適用 (方向の絞り込みまで。採否は 2〜4 週の実適用観察で判断)
- MARKET / crossing LIMIT entry の replay (発注時点の板に依存するため厳密再現できない)
- STOP entry の replay (receipt の価格比較で判定でき技術的には可能だが、C3 の絞り込みに不要)
- LLM decision の再実行、過去 klines を用いた戦略バックテスト
