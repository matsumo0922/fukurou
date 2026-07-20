## Why

Issue #197 の現行 benchmark は bot を realized PnL のみ、buy & hold を fee 無視で計算するため、未決済 BTC の含み損益や清算 cost を含む owner の勝利条件を判定できない。さらに crash / daemon 停止を含む観測不能期間を勝敗から分離していないため、取引できなかった期間を bot の優位として誤認しうる。

## What Changes

- （ユーザー確認済み）current account epoch の直近 90 JST 日を対象に、window 開始時の清算 equity を共通元本として bot、fee 込み buy & hold、cash の日次系列を比較する。
- （ユーザー確認済み）valid day が 90 日の 90%（81 日）未満、または比較境界が観測不能なら、勝敗と owner score を `INCONCLUSIVE` にする。
- （agent 仮決め）bot equity は epoch-scoped account snapshot の cash と BTC 数量を日足 close で mark-to-market し、全 BTC を taker として清算する synthetic fee assumption を控除する。保存済み `total_equity_jpy` や realized PnL の再加算は使わない。
- （agent 仮決め）buy & hold は各 rolling window の開始時に共通元本を全額 BTC へ換え、entry / exit の両方へ同じ epoch-frozen synthetic fee assumption を適用する。cash 線は同じ共通元本を維持する。
- （反証反映済み agent 決定）epoch ごとの benchmark fee assumption を append-only policy として固定し、runtime-config retention や現在の取引所 fee から過去の物差しを再推定しない。
- （反証反映済み agent 決定）market-data gap、process restart 前の未観測区間、infrastructure gap、日足欠損、account-state 欠損、lineage / attribution 不整合を stable reason code 付き `UNKNOWN` / `INCONCLUSIVE` にし、欠損値を補間しない。
- （反証反映済み agent 決定）既存 `/evaluation/benchmark` と immutable report revision は互換性のため変更せず、additive な `GET /evaluation/owner-score` と Evaluation 画面の専用 panel を追加する。旧 realized benchmark は current owner score と表示しない。
- （agent 仮決め）省略時は request 時点から計算する rolling 表示、`cutoff` 指定時は同じ semantics の fixed-cutoff 表示として区別し、response に `benchmarkSemanticsVersion` と実効入力を返す。

## Capabilities

### New Capabilities

- `owner-score-benchmark`: fee 込み清算 equity、rolling / fixed cutoff、truth coverage、versioned owner score の評価 contract。

### Modified Capabilities

- なし。

## Impact

- `trading` の evaluation model / math / PostgreSQL read repository、runtime-config activation transaction、append-only epoch benchmark policy、backup critical-table inventory、bounded indexes
- `fukurou` の additive `GET /evaluation/owner-score` DTO と route-local OpenAPI
- `web` の generated OpenAPI types と Evaluation 画面の owner-score panel / legacy label
- evaluation repository / math / route / Web UI の回帰テスト
- `README.md`、`docs/design.md` の owner score と benchmark semantics
