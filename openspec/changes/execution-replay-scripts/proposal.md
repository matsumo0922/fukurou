## Why

Epic #180 の C3 (TTL) の候補を、適用前に記録済みデータで絞り込む手段が無い。候補を実運用で 1 つずつ試すと 1 候補あたり数週間かかる。

decision を固定した執行層だけの replay は LLM 学習データのリークと無縁である。本 change は独立反証を 2 周経ており、記録済みデータから**忠実に計算できる範囲だけ**を実装する。忠実に計算できない軸 (trailing 候補ランキング、指値 offset の反実仮想) は理由を明記して除外する。

## What Changes

read-only の分析スクリプトを 2 本追加する。どちらも ledger・production API・runtime config へ書き込まず、外部取引所 API を呼ばない (pure DB)。

### 1. TTL 短縮感度 (`:trading` の受付 order を対象)

記録済みの resting LIMIT entry order に対し、**指値を記録済みの値に固定したまま、TTL を短縮した場合の約定 / 失効**を `paper_market_event_receipts` 上で再計算する。

production の運用 config は resting entry TTL を **短縮方向にしか変更できない** (`TradingBotConfig.kt:439` で `restingEntryOrderTtl ≤ 1800s`)。したがって反実仮想も短縮のみを対象とする。短縮は記録済みの約定を「取りこぼす」方向にしか働かず、口座状態や安全ゲートを跨いだ延命を伴わないため、延長で生じる問題 (延命先の halt / 残高 / exposure 判定) が構造的に発生しない。

約定の権威は記録済み execution 行に置き、queue から fill を再導出しない (queue 規則は fixture cross-check にのみ使う)。主出力は各 order の**約定レイテンシ** (`executions.executed_at − created_at`、EXACT) と、短縮 TTL ごとの retention である。retention は候補の論理期限を execution の処理時刻 `executed_at` と比較して判定するため、両方向とも EXACT になる。queue 到達後に安全ゲートで棄却され execution を持たない order には約定を主張せず、`NON_TTL_TERMINAL` として分離する。

### 2. tail 事実シート (`:trading` の position を対象)

記録済みの position に対し、実際にどれだけ価格が逆行したかを stored fact から集計する。初期リスク R は既存 evaluation と同じ fill-weighted stop から復元し (`EvaluationMath` 準拠)、実際の逆行は `positions.lowest_price_since_entry_jpy` から求める。

この最安値は exit fill の slippage を畳み込むため、市場が到達した最安値そのものではなく**台帳記録値**である。出力にその旨を明記し、「市場の真の最大逆行」とは主張しない。壊滅的 tail の件数を提示するのが目的である。

## Capabilities

### New Capabilities
- `execution-parameter-replay`: 記録済み execution データから TTL 短縮感度と tail 事実を、fidelity・cohort・unknown を明示して出力する read-only 分析の契約

### Modified Capabilities

なし。

## Impact

- **新規コード**: `:trading` module に算出ロジックと 2 本のエントリポイントを追加する。`scripts/` に実行 wrapper を置く。外部 API 依存・historical reader は含まない。
- **PR 分割**: TTL 短縮感度を PR 1、tail 事実シートを PR 2 とする。どちらも pure DB で独立にレビュー可能。共通の基盤 (read-only 接続、cohort、gap、出力契約) は PR 1 で入れ、PR 2 はそれを再利用する。
- **読み取り対象テーブル**: `paper_market_event_receipts`、`market_data_gaps`、`infrastructure_gap_events`、`market_data_sessions`、`orders`、`trade_plans`、`executions`、`positions`。
- **再利用する既存実装**: `EvaluationCohort` と既存 cohort 導出規則、`DefaultPaperExecutionSimulator.simulatePendingLimit` (約定価格と手数料)、`EvaluationMath` の fill-weighted stop / R 算出規則。
- **書き込み**: なし。DB 接続は read-only role かつ単一の REPEATABLE READ read-only transaction を前提とする。
- **ドキュメント影響**: あり (`docs/design.md` の実験手順節に 2 スクリプトの位置づけ、`EXACT` の定義、`UNKNOWN` 区分、trailing ランキングと offset を除外した理由を追記)。

## 本 change で実施しないこと

### trailing 候補 exit ランキング (除外・issue に不可能と明記)

trailing stop = `highestPriceSinceEntry − ATR14 × 係数` を決めるのは ATR14 の値と、tighten を発火させる REST poll の時刻である。独立反証により次が確定した。

- production は WS event 経路では stop を tighten せず (ATR=null, `ExposedPaperLedgerWriter.kt:868`)、tighten は REST poll (5 秒間隔) でのみ起きる。その poll 時刻は保存されない。receipt ごとに tighten する replay は poll の隙間で production より早く exit しうる。
- ATR14 系列も保存されない。再構成 ATR は production 値と上下双方向にずれ、stop を緩くも厳しくもする。

stop を決める 2 要素が両方とも未保存で誤差が双方向であるため、候補間の相対順位づけも忠実にできない。これは ATR 値と poll cadence の永続化 (production 書き込みの追加) を伴う別 change を要する。

### 指値 offset の反実仮想 (除外・issue に不可能と明記)

約定規則は queue consumption であり (`ExposedPaperLedgerWriter.kt:1155`)、発注時点の板から算出した `queue_ahead_btc` に依存する。板は保存されない。指値を変えるとその価格レベルの queue が必要になるが不明であり、observable な出来高だけでは候補間の約定可能性を順位づけられない。offset を広げて ask を跨ぐ候補は即時 taker 約定になり別経路である。板 depth の永続化を要する別 change とする。

### その他

- 汎用 replay framework、versioned assumption manifest 体系、L2 replay
- 板 depth / ATR14 / REST poll cadence / tick snapshot の永続化
- replay 結果だけを根拠にした本番適用 (方向の絞り込みまで。採否は 2〜4 週の実適用観察で判断)
- TTL の延長方向の反実仮想 (config が許さず、延命先の口座状態・安全ゲートを忠実に再現できない)
- MARKET / crossing LIMIT / STOP entry の replay
- LLM decision の再実行、過去 klines を用いた戦略バックテスト
