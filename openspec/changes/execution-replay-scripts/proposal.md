## Why

C1 (trailing) と C3 (TTL/offset) のパラメータ候補を、本番適用前に記録済みデータで絞り込む手段が無い。候補を実運用で 1 つずつ試すと 1 候補あたり数週間かかり、Epic #180 の期間ベース証拠バーに乗らない。

decision を固定した執行層だけの replay は LLM 学習データのリークと無縁であり、候補の方向を絞る根拠として使える。paper execution の因果的正本である `paper_market_event_receipts` が既に durable journal として無期限保存されているため、記録済み指値のもとでの TTL 反実仮想は実履歴と厳密一致する形で計算できる。

## What Changes

- read-only の replay スクリプトを 2 本追加する。どちらも ledger・production API・runtime config へ一切書き込まない。
  1. **TTL×offset replay**: 記録済みの resting LIMIT entry order に対し、TTL と指値 offset を変えた場合の約定条件を `paper_market_event_receipts` 上で再計算する。
  2. **trailing exit replay**: 記録済みの closed trade に対し、trailing の起動条件 (即時 / 0.5R / 1R 到達後) と ATR 係数を変えた場合の exit を再計算する。
- 両スクリプトの出力に **fidelity ラベル** を必須項目として持たせる。記録済み指値での TTL 反実仮想のみ `EXACT`、それ以外は `EXACT` を主張しない。
- 両スクリプトの出力で cohort (`CURRENT` / `LEGACY_PRE_WS` / `UNSUPPORTED_EXECUTION_SEMANTICS`) を分離し、gap と交差する対象を `UNKNOWN` として母集団に残す。

### 保存されていない入力に起因する 2 つの明示的な制約

**1. offset の反実仮想では約定の二値判定を出さない (agent 仮決め)**

本番の約定規則は価格クロスではなく queue consumption であり (`ExposedPaperLedgerWriter.kt:1155`)、発注時点の板から算出した `queue_ahead_btc` に依存する。板そのものは保存されないため、記録済みと異なる指値における queue は原理的に不明である。

そこで offset の反実仮想では、receipt から厳密に計算できる量 (TTL 窓内に観測された該当 SELL 数量の累積和) と、約定に必要な queue の上限だけを出力し、fill / no-fill の二値判定を出さない。上限が負の場合に限り、queue の値に依存せず約定しないことを確定する。

**2. trailing replay は近似であることを明示する (ユーザー確認済み)**

trailing stop の入力である ATR14 系列は mark 時点に取引所 API から取得され永続化されない (`PaperBroker.kt:1334`)。REST tick の発火時刻を保存する table も存在しない。したがって trailing exit の実履歴との厳密一致は不可能である。

本 change では trailing replay を `APPROXIMATE` と明示したうえで実施し、その出力は **候補間の相対順位づけと壊滅的 tail の有無の確認にのみ** 使う。「実際にこう約定した」という主張には使わない。この制約は出力自身が `fidelity` / `basis` / `usage` フィールドで自己申告する。

## Capabilities

### New Capabilities
- `execution-parameter-replay`: 記録済み execution データに対して TTL / offset / trailing のパラメータを変えた反実仮想を再計算し、fidelity・cohort・unknown を明示して出力する read-only 分析の契約

### Modified Capabilities

なし。既存 spec の要件は変更しない。read-only の分析経路のみを追加する。

## Impact

- **新規コード**: `:trading` module に replay の算出ロジックと 2 本のエントリポイントを追加する。`scripts/` に実行 wrapper を置く。
- **読み取り対象テーブル**: `paper_market_event_receipts`、`market_data_gaps`、`infrastructure_gap_events`、`market_data_sessions`、`orders`、`executions`、`positions`、`paper_account_epochs`。
- **再利用する既存実装**: `EVALUATION_GAP_INTERVAL_CTE_V1` (gap 区間投影)、`EvaluationCohort` と既存 cohort 導出規則、`DefaultPaperExecutionSimulator.simulatePendingLimit` (約定価格と手数料)、`SafetyFloorDefaults.trailingAtrMultiplier` (現行係数の基準値)。
- **外部依存**: trailing replay のみ ATR 再構成のため GMO の 5 分足を再取得する。TTL×offset replay は外部 API に依存しない。
- **書き込み**: なし。DB 接続は read-only role を前提とする。
- **ドキュメント影響**: あり (`docs/design.md` の実験手順節に replay スクリプトの位置づけと fidelity の読み方を追記)。

## やらないこと

- 汎用 replay framework、versioned assumption manifest 体系、L2 replay (prompt / provider 再実行)
- replay 結果だけを根拠にした本番適用 (方向の絞り込みまで。採否は 2〜4 週の実適用観察で判断)
- 板 depth の永続化 (offset 反実仮想を `EXACT` にするには必要だが、production 書き込みの追加になるため scope 外)
- ATR14 / tick snapshot の永続化 (trailing を `EXACT` にするには必要だが、同じ理由で scope 外)
- MARKET / STOP entry の replay (板依存のため厳密再現できない)
- LLM decision の再実行や、過去 klines を用いた戦略バックテスト
