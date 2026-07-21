## Why

Epic #180 の C3 (TTL/offset) と C1 (trailing) の候補を、適用前に記録済みデータで絞り込む手段が無い。候補を実運用で 1 つずつ試すと 1 候補あたり数週間かかる。

decision を固定した執行層だけの replay は LLM 学習データのリークと無縁である。本 change は独立反証を 2 周経ており、記録済みデータから**忠実に計算できる範囲だけ**を実装する。忠実に計算できない軸 (指値 offset の反実仮想) は理由を明記して除外する。

## What Changes

read-only の replay スクリプトを 2 本追加する。どちらも ledger・production API・runtime config へ書き込まない。

### 1. TTL replay (`:trading` の受付 order を対象)

記録済みの resting LIMIT entry order に対し、**指値を記録済みの値に固定したまま** TTL 候補ごとの約定 / 失効を `paper_market_event_receipts` 上で再計算する。外部 API を呼ばない。

厳密性を保証できる対象のみ `EXACT` とし、崩れる対象 (処理時刻の曖昧区間、同価格 order の queue 結合、time stop 未解決) は `UNKNOWN` に落とす。各 order の約定レイテンシ (発注から約定条件成立までの経過) も併せて出力し、TTL 選択の材料にする。

### 2. trailing tail & exit replay (`:trading` の closed trade を対象)

記録済みの closed long position に対し、2 つの出力を分けて出す。

- **壊滅的 tail 確認 (EXACT)**: position が実際に到達した最安値 (`positions.lowest_price_since_entry_jpy`) と初期リスク R (`orders.protective_stop_price_jpy` から復元) から、実際の最大逆行を計算する。これは stored fact だけで求まり、ATR も外部 API も要らない。issue の「壊滅的 tail の事前確認」に対応する。
- **候補 exit ランキング (APPROXIMATE, ゲート付き)**: trailing の起動条件と係数を変えた場合の exit を、receipt から復元した価格経路と再構成 ATR で再計算する。receipt 経路の最高値が保存済みの真の最高値 (`positions.highest_price_since_entry_jpy`) と一致する trade のみを対象とし、一致しない trade は `UNKNOWN` とする。ATR は再構成であるため候補間の相対順位づけにのみ使い、絶対 exit 価格の主張はしない。

trailing replay は ATR 再構成のため GMO の 5 分足を read-only で再取得する。取引所 API client が audit 経由で DB へ書く経路 (`GmoPublicRequestAudit.kt:79`) を避けるため、書き込みを行わない audit sink を注入する。

## Capabilities

### New Capabilities
- `execution-parameter-replay`: 記録済み execution データに対して TTL と trailing のパラメータを変えた反実仮想を再計算し、fidelity・cohort・unknown を明示して出力する read-only 分析の契約

### Modified Capabilities

なし。

## Impact

- **新規コード**: `:trading` module に replay の算出ロジックと 2 本のエントリポイント、trailing 用の historical candle reader を追加する。`scripts/` に実行 wrapper を置く。
- **PR 分割**: TTL replay (capability の TTL 要件群) を PR 1、trailing tail & exit replay (trailing 要件群 + historical reader) を PR 2 とする。PR 1 は pure DB で外部 API 非依存、PR 2 のみ read-only の外部取得を含む。
- **読み取り対象テーブル**: `paper_market_event_receipts`、`market_data_gaps`、`infrastructure_gap_events`、`market_data_sessions`、`orders`、`trade_plans`、`executions`、`positions`。
- **再利用する既存実装**: `EvaluationCohort` と既存 cohort 導出規則、`DefaultPaperExecutionSimulator.simulatePendingLimit`、`IndicatorCalculator` (ATR14)、`buildKlinesRequest` の date 対応 wire、`SafetyFloorDefaults.trailingAtrMultiplier` (現行係数)。
- **書き込み**: なし。DB 接続は read-only role、外部取得は書き込みなし audit sink を前提とする。
- **ドキュメント影響**: あり (`docs/design.md` の実験手順節に 2 スクリプトの位置づけ、`EXACT` / `APPROXIMATE` の定義、各 UNKNOWN 区分、offset を除外した理由を追記)。

## 本 change で実施しないこと

### 指値 offset の反実仮想 (除外・人間判断待ち)

本番の約定規則は queue consumption であり (`ExposedPaperLedgerWriter.kt:1155`)、発注時点の板から算出した `queue_ahead_btc` に依存する。板は保存されない。

独立反証により、指値を変えた反実仮想は次の 2 点で忠実に計算できないことが判明した。

1. queue は価格レベルごとに独立であり、観測可能な出来高から導ける「約定に必要な queue 上限」の大小は候補間の約定可能性の順位を与えない。全候補で上限が非負なら確定除外は 0 件になる。
2. offset を広げて BUY limit が ask 以上へ移動する候補は、production では resting queue に入らず発注時点で即時 taker 約定する (`PaperBroker.kt:389`)。receipt 上の queue 条件では扱えない別経路である。

したがって offset の反実仮想は板 depth の永続化 (production 書き込みの追加) を伴う別 change とするか、C3 の絞り込み手段自体を変えるかをオーナーが判断する。本 change は TTL 軸の絞り込みと、記録済み offset における約定レイテンシの提示までに留める。

### その他

- 汎用 replay framework、versioned assumption manifest 体系、L2 replay
- 板 depth の永続化、tick snapshot の永続化
- replay 結果だけを根拠にした本番適用 (方向の絞り込みまで。採否は 2〜4 週の実適用観察で判断)
- MARKET / crossing LIMIT entry の replay (発注時点の板に依存するため厳密再現できない)
- STOP entry の replay (receipt の価格比較で技術的には可能だが、C3 の絞り込みに不要)
- production の ATR 数値との厳密一致 (production が各 tick で使った candle 窓の終端時刻が保存されないため。trailing の ATR は再構成の近似とする)
- LLM decision の再実行、過去 klines を用いた戦略バックテスト
