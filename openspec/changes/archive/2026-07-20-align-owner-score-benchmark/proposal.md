## Why

Issue #197 の現行 benchmark は bot を realized PnL のみ、buy & hold を fee 無視で計算するため、保有 BTC の含み損益と清算 cost を含む owner の勝利条件を判定できない。また、日足や market-data gap が欠けた期間にも勝敗を表示すると、daemon 停止を bot の優位と誤認しうる。

このリポジトリは single-owner の hobby project である。（ユーザー確認済み）新しい評価基盤を作るのではなく、既存 benchmark を必要十分な範囲で直す。

## What Changes

- （ユーザー確認済み）current account epoch の直近 90 completed GMO business days（06:00 JST 境界）について、window 開始時の bot 清算 equity を共通元本として bot、fee 込み buy & hold、cash を比較する。
- （ユーザー確認済み）valid day が 81/90 日未満、または開始日・終了日が valid でなければ、勝敗を `INCONCLUSIVE` にする。
- 既存 `GET /evaluation/benchmark` と既存 Evaluation 画面を owner-score semantics に更新する。新しい endpoint や dashboard は作らない。
- bot は epoch-scoped `equity_snapshots` の cash / BTC 数量を日足 close で mark-to-market する。buy & hold は window 開始時に全額購入したと仮定する。（ユーザー確認済み）この起点差によるbot有利のfee biasは既知前提として表示する。
- `OWNER_SCORE_V1` の synthetic taker fee をコード定数 `0.0005` とし、entry / hypothetical exit に適用して response に表示する。fee を変える場合は semantics version を上げる。
- 既存の日足、account snapshot、`market_data_gaps` を使って valid / gap / unknown 日数を表示する。（ユーザー確認済み）1 GMO business day の累積 gap が1時間以上ならunknownとし、短いgapは件数・秒数だけを表示する。欠損を補間したり、過去の paper state を作り直したりしない。
- cutoff 省略時は rolling、指定時は同じ 90 日計算を固定 cutoff として返す。

## Capabilities

### New Capabilities

- `owner-score-benchmark`: 既存 benchmark を fee 込み清算 equity、90 日 coverage、rolling / fixed cutoff に合わせる。

### Modified Capabilities

- なし。

## Impact

- `trading`: legacy benchmarkを残した新owner-score model / math と、期間・epochを絞った snapshot / gap 読み取り
- `fukurou`: 既存 `/evaluation/benchmark` response と route-local OpenAPI
- `web`: 既存 benchmark 表示と generated OpenAPI types
- focused regression tests、`README.md`、`docs/design.md`

## Out of Scope

- 新規DB table、fee-policy永続化、trigger、backup profile変更
- 新規 endpoint / dashboard、immutable report schema の変更
- 全 execution lineage の再監査、専用 repeatable-read集約、汎用gap監視基盤
- epoch未帰属の旧snapshotの救済・backfill・自動削除
- slippage、税、maker rebate、8-arm ablation、shadow broker
