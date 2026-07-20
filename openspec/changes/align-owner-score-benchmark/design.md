## Context

Issue #197 は owner score を「rolling 3 ヶ月の post-cost bot return から post-cost buy-and-hold return を引いた値」と定義する。現行 `EvaluationMath.benchmark` は closed trade の realized PnL を積み上げ、既存 route も buy and hold が fee を含まず bot equity が unrealized PnL を含まないと明記している。immutable evaluation report はこの legacy calculation を保持する。

リポジトリには、epoch-scoped `equity_snapshots` の cash / BTC quantity、daily candle、account epoch、market-data session / gap、infrastructure gap、execution semantics、evaluation exclusion が既にある。本変更は single-owner hobby project の実験速度を優先し、paper truth を守るために必要な既存 evidence だけを再利用する。

## Goals / Non-Goals

**Goals:**

- bot、buy and hold、cash を同じ 90 JST 日 window と共通開始資産で比較する。
- bot の含み損益と明示された synthetic liquidation fee を含める。
- crash / gap /欠損と current population の不整合を勝敗から除外する。
- rolling と 3 ヶ月レビュー用 fixed cutoff を同じ semantics で提供する。
- 既存 benchmark API と immutable report の互換性を守る。

**Non-Goals:**

- ledger mutation、過去 snapshot / fill の作成、取引実行、SafetyFloor、account switching。
- fee policy 用 database table、runtime-config activation 変更、backup profile 変更。
- slippage、tax、maker rebate、shadow counterfactual、新しい dashboard。
- 新しい汎用 lineage framework や運用 interval ledger。
- immutable evaluation report JSON の拡張。

## Decisions

### 1. Additive endpoint と legacy contract の分離

`GET /evaluation/owner-score` を追加し、既存 `/evaluation/benchmark` の query、response、realized-equity semantics を変更しない。immutable report JSON と canonical hash も変更しない。Evaluation UI は既存 chart を “Legacy bot realized vs benchmark equity” と表示し、新 panel だけを `OWNER_SCORE_V1` の authority とする。

### 2. 1つの cutoff から完了済み 90 JST 日を固定する

route は optional `cutoff` を1回だけ解決する。省略時は request clock を1回読み `ROLLING`、指定時は `FIXED_CUTOFF` とする。cutoff の JST 日付の前日を最終日とし、89日前を初日とする。未来 cutoff は拒否し、epoch が若い場合も denominator を短縮しない。

### 3. Synthetic fee を semantics version のコード定数にする

`OWNER_SCORE_V1` の synthetic taker fee は `0.0005` に固定する。この値は B&H の仮想 entry / exit と bot の仮想清算だけに使い、実約定 fee の provenance とは扱わない。snapshot cash には実際の fee が既に反映されているため再計算しない。

fee を変える場合は `OWNER_SCORE_V2` を追加する。DB policy や runtime config retention へ接続しないため、fixed-cutoff history は binary revision と semantics version で一意に解釈できる。

### 4. 既存 evaluation repository で evidence を1 snapshotに固定する

既存の Exposed evaluation repository patternを使い、read-only repeatable-read transaction で active CURRENT epoch、90日分のdaily candle、epoch-scoped account snapshot、gap、既存 population / exclusion evidence を読む。読み取りは90日windowと既存query limitでboundedにする。新しいschemaや汎用query frameworkは作らない。

各日末以前の最新 `EPOCH_START` / `BOOTSTRAP` / `FILL` / `DAILY` snapshotからcash/BTC quantityを取得し、その日のcloseで再評価する。stored mark price / total equityは使わない。同一最大 `captured_at` に異なるcash/BTCがある日は `ACCOUNT_STATE_AMBIGUOUS` とする。

### 5. 既存gap evidenceを保守的に日へ投影する

通常の market-data gap は `[started_at, recovered_at ?: queryNow)`、infrastructure gap は既存のOPEN/CLOSE境界を使う。`PROCESS_RESTART` は関連sessionの `last_transport_activity_at`、なければ `connected_at` まで開始を遡る。open intervalの終端はfrozen `queryNow` と cutoff の早い方にする。欠損intervalを推測や補間で埋めない。

### 6. Population integrity は既存情報を再利用する

active CURRENT account epochだけを対象にし、既存execution semantics、evaluation exclusion、attribution / cohort集計からwindowにlegacy、unsupported、missing、excluded evidenceが確認された場合は `INCONCLUSIVE` にする。新しい全履歴lineage scannerは作らない。既存情報だけで判定できない事象もconclusiveへ昇格させない。

### 7. 清算計算とcoverage gate

valid close `P`、固定fee `f` に対して、botは `cash + btcQuantity * P * (1 - f)` とする。window初日のbot清算equityを共通元本 `S` とし、`buyHoldBtc = S / (startClose * (1 + f))`、`buyHold = buyHoldBtc * P * (1 - f)`、cashは `S` とする。

valid dayは90日のうち81日以上かつ両境界validを必須とする。gap、outside epoch、candle/account欠損、population不整合があればstable reasonとcountを返す。period returnを計算できない場合はreturn、score、winnerをnullにし `INCONCLUSIVE` とする。

### 8. Evaluation UI は簡潔な専用panelを追加する

Evaluation pageはowner-score endpointを独立queryし、bot / B&H / cash、score / winner、cutoff、synthetic fee、coverage、reasonを表示する。unknown pointはchart gapとtable rowで示す。新しいdashboardや監視機構は作らない。

## Risks / Trade-offs

- [固定feeが将来の実feeとずれる] → assumptionをresponseへ表示し、変更時は新semantics versionにする。
- [up to 9 unknown daysがoutage biasを残す] → reason/countを常に表示し、両境界は必須にする。
- [intentional HALTの完全な期間履歴がない] → durable evidenceのないintervalを推測せず、既知のgapだけを使う。
- [既存population evidenceでは完全なlineage証明ができない] → conclusiveへ推定せず、既知のlegacy/exclusion/missingをfail-closedにする。
- [current epochが若い] → `OUTSIDE_ACCOUNT_EPOCH` を返し、90日denominatorを短縮しない。

## Migration Plan

1. Backend PRでmodel、calculator、既存repositoryを使うevidence read、additive route、OpenAPI、focused tests、関連docsを実装する。
2. UI PRでgenerated types、owner-score panel、unknown表示、legacy label、UI tests、残りのdocsを実装する。
3. 通常deploy後にrolling/fixed cutoffとinconclusive reasonをread-only確認する。schema migration、NAS root作業、secret/config変更はない。

Rollbackは新route/panelを含まない旧imageへ戻すだけでよい。DBやledgerに新しい状態を残さない。

## Open Questions

- なし。coverage threshold、B&H window origin、`OWNER_SCORE_V1` の固定synthetic feeはユーザー確認済み。
