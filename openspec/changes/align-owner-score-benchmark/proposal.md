## Why

Issue #197 の現行 benchmark は bot を realized PnL のみ、buy & hold を fee 無視で計算するため、未決済 BTC の含み損益や清算 cost を含む owner の勝利条件を判定できない。また、daemon 停止や market-data gap を有効な戦略成績へ混ぜると、取引できなかった期間を bot の優位として誤認しうる。

## What Changes

- current account epoch の直近 90 JST 日を対象に、window 開始時の bot 清算 equity を共通元本として bot、fee 込み buy & hold、cash の日次系列を比較する。
- `OWNER_SCORE_V1` は benchmark 用 synthetic taker fee をコード定数 `0.0005` として固定し、response に実約定 fee ではない assumption と明示する。変更時は新しい semantics version を採用する。
- valid day が 81/90 日未満、比較境界が観測不能、account epoch/cohort が不整合、または既存評価情報に legacy / exclusion が含まれる場合は `INCONCLUSIVE` にする。
- 既存 snapshot、daily candle、market-data gap、infrastructure gap、evaluation exclusion を再利用し、欠損値の補間や ledger の書き換えを行わない。
- 既存 `/evaluation/benchmark` と immutable report JSON は変更せず、additive な `GET /evaluation/owner-score` と Evaluation 画面の専用 panel を追加する。
- 新しい database schema、runtime-config activation 変更、backup profile 変更、NAS root 操作は導入しない。

## Capabilities

### New Capabilities

- `owner-score-benchmark`: fee 込み清算 equity、rolling / fixed cutoff、truth coverage、versioned owner score の評価 contract。

### Modified Capabilities

- なし。

## Impact

- `trading` の owner-score model / calculator と既存 PostgreSQL evaluation read repository
- `fukurou` の additive `GET /evaluation/owner-score` DTO と route-local OpenAPI
- `web` の generated OpenAPI types と Evaluation 画面の owner-score panel / legacy label
- evaluation repository / math / route / Web UI の focused regression test
- `README.md` と `docs/design.md` の owner score / benchmark semantics
