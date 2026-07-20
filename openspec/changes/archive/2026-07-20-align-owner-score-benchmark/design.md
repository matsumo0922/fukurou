## Context

現行 `/evaluation/benchmark` は GMO 日足と closed trade の realized PnL から系列を作るため、open BTC の含み損益を反映しない。`equity_snapshots` には account epoch、cash、BTC 数量、取得時刻が既に保存され、`market_data_gaps` と market-data session には観測欠損の記録がある。

この変更は single-owner hobby project の実験スループットを優先する。（ユーザー確認済み）owner score を判定するために必要な計算と表示だけを既存経路へ追加し、評価専用の永続化や新しい運用機構は作らない。

## Goals / Non-Goals

**Goals:**

- current account epoch の completed 90 GMO business days（06:00 JST 境界）で bot、buy & hold、cash を同じ元本から比較する。
- bot の含み損益と、比較用の synthetic entry / exit fee を反映する。
- 保存済み gap や入力欠損が多い期間を `INCONCLUSIVE` にする。
- rolling と fixed cutoff、および計算 semantics を画面から判別できるようにする。

**Non-Goals:**

- benchmark 専用 table、immutable fee policy、DB trigger、backup migration。
- immutable evaluation report の再生成や schema 変更。
- attribution を全履歴から再証明すること、記録されていない停止期間を推測すること。
- 新規 dashboard / endpoint、slippage、税、maker rebate。

## Decisions

### 1. 既存 benchmark を直接置き換える

（ユーザー確認済み: hobby project の要領を優先）`GET /evaluation/benchmark` と既存 Evaluation 画面の benchmark card を `OWNER_SCORE_V1` に更新する。別 endpoint、別 panel、legacy API compatibility layer は作らない。

既存 `EvaluationMath.benchmark` はimmutable reportのlegacy fact生成が共有しているため変更しない。新しい `ownerScoreBenchmark` を追加し、routeだけを切り替える。responseはsemantics version、cutoff mode/time、fee rate、coverage、winner、owner scoreを返す。immutable reportのschema・計算・hashは変更せず、画面に併記する場合だけlegacy realized benchmarkと表示する。

queryは`cutoff`へ一本化する。既存の`from` / `to`はowner-score routeでは受け付けず400、`epochId` / `cohort`はactive CURRENTと一致する場合だけ許可し、それ以外は`UNSUPPORTED_SCOPE`とする。closed-trade populationを使わないため`truncated` / `attributionCoverage` / `TRUNCATED_POPULATION` / `BASELINE_NOT_COMPARABLE`は新responseから外し、young epochや入力不足は`INCONCLUSIVE`へ統合する。consumerは同一repoのWebだけなので同じPRで更新する。（agent 仮決め）

### 2. 90 completed GMO business days を一度だけ決める

cutoff 省略時は request clock を一度読み `ROLLING`、指定時はその instant を使い `FIXED_CUTOFF` とする。既存 GMO 1day candle の営業日境界（06:00 JST）を正本とし、cutoff以下の最新expected close boundaryから連続する90日分のslotを先に生成する。取得candleはslotへleft joinし、欠損slotを過去candleで繰り上げずunknownにする。たとえば cutoff が00:30 JSTなら、当日06:00のslotは含めない。future cutoffは拒否する。

current epoch の開始前は `OUTSIDE_ACCOUNT_EPOCH` として unknown に残し、window や denominator を短縮しない。（ユーザー確認済み）epoch未帰属の旧snapshotは品質が低く、owner scoreへ救済して混ぜない。削除やbackfillもこのchangeでは行わず、current epochの証拠が81日分貯まるまでは意図どおり `INCONCLUSIVE` とする。

### 3. V1 fee はコード定数にする

（agent 仮決め）`OWNER_SCORE_V1` の synthetic taker fee は `0.0005`（0.05%）に固定する。これは実際の各fill feeの再現ではなく、B&H entry と両戦略の仮想清算へ同じ物差しを当てるための概算である。rate は response とUIへ表示し、変更する場合は `OWNER_SCORE_V2` として扱う。

これにより、runtime config retention、epoch fee policy table、provisioning、backup変更を不要にし、同じV1/cutoffは同じ保存入力から再計算できる。実際の約定feeはsnapshotのcashへ既に反映されるため再加算しない。

### 4. 既存 snapshot から日次 bot equity を作る

evaluation repository に、対象 epoch と90日範囲へ絞った account snapshot 読み取りを追加する。既存の全件 `findAll()` は使わない。各日末以前に当該epochのsnapshotが1件以上あれば、最新 `EPOCH_START` / `BOOTSTRAP` / `FILL` / `DAILY` snapshot の cash と BTC 数量をcarry forwardし、その日のcloseで再評価する。保存済み `total_equity_jpy` と mark price は使わない。

`EPOCH_START` が保存済みDB値として存在する一方、reader model enumに不足しているため追加する。各candleのclose instant以前に当該epochのsnapshotが一度もないbusiness day、同一最新時刻に異なる口座状態がある日はunknownとする。snapshot選択は`trading_date`の00:00 JST境界へ寄せず、candle close instantへ揃える。06:00境界と00:30 JST cutoffをfocused testで固定する。新規indexは実測で必要になった場合だけ後続対応とし、この変更では追加しない。

### 5. 保存済み gap だけを coverage に使う

対象期間と交差する `market_data_gaps` をbounded queryで読み、`started_at` から `recovered_at` まで、未回復ならcutoffまでを06:00 JST境界のbusiness dayごとに合算する。（ユーザー確認済み）累積gapが1時間以上のbusiness dayだけをunknownにする。1時間未満もgap件数と秒数へ残すが、日全体は無効化しない。`PROCESS_RESTART` のsession遡及はV1では行わず、保存済み `started_at` を使う。日足欠損とaccount snapshot欠損は時間に関係なくunknownとする。

記録されていない intentional stop や infrastructure gap を新しい仕組みで復元しない。これはresidual riskとしてcoverage表示へ明記する。既存 `evaluation_exclusions` はtrade KPIの帰属表示として従来どおり返すが、account全体のowner scoreを二重にgateしない。

### 6. 清算 equity と判定

（ユーザー確認済み）window開始business dayのbot清算equityを共通元本 `S` とする。`S` は開始candleのclose instantと同じ境界で、その時点以前の最新snapshotから算出する。valid close `P`、fee `f=0.0005` に対し:

`bot = cash + btcQuantity * P * (1 - f)`

`buyHoldBtc = S / (startClose * (1 + f))`

`buyHold = buyHoldBtc * P * (1 - f)`

`cash = S`

全returnは共通元本`S`を分母に固定し、`botReturn = botEnd / S - 1`、`buyAndHoldReturn = buyHoldEnd / S - 1`、`cashReturn = 0`とする。series自身のfirst/last比は使わない。`ownerScore = botReturn - buyAndHoldReturn` とし、勝者は正なら `BOT`、それ以外は `BUY_AND_HOLD`。曖昧な `TIE` 状態は持たない。

（ユーザー確認済み）valid day が81日未満、開始日または終了日がunknown、価格・元本が非正なら、seriesとcoverageは返すがreturn、owner score、winnerはnullにして `INCONCLUSIVE` とする。

### 7. テストは失敗しやすい意味に絞る

最低限、次をfocused testで固定する。

1. open BTCの値下がりとexit feeがbot equityへ反映される。
2. B&Hにentry / exit feeが反映される。
3. business day計1時間以上のgapまたは不足coverageではwinnerを返さない。
4. cutoffから生成した連続90 expected slots、欠損candleのunknown、06:00 JST candle closeとsnapshot/gap境界、00:30 JST cutoff、およびsnapshot carry-forward。
5. constant priceでもB&H entry/exit feeが`S`分母のreturnに残る。

route / UI は既存 benchmark testを更新し、OpenAPI type生成後に通常のtest / detekt / buildを実行する。専用concurrency test、backup restore test、property testは追加しない。

## Risks / Trade-offs

- V1 feeは実fee履歴ではなく0.05%の共通仮定である。responseへ表示し、物差し変更時はversionを上げる。
- transaction全体をrepeatable-readで固定しないため、request中に新しいsnapshot/gapが追加されると次回表示が変わりうる。評価はread-only表示であり、再読込で収束するため許容する。
- 1時間未満のgapと記録されていない停止期間はunknownへ落とさない。gap秒数と「保存済み証拠に基づくcoverage」を表示する。
- account snapshotは口座全体の成績を表す。既存CURRENT scope / exclusion判定は使うが、全execution lineageを再監査しない。
- （ユーザー確認済み）window開始時に既にBTCを持つbotはentry feeを再度払わず、fresh B&Hだけがentry feeを払うため、およそ0.1% bot有利になりうる。window開始起点を維持してこのbiasを受容し、V1の既知前提としてresponse/UIへ表示する。
- 既存 `/evaluation/benchmark` のwire contractは変わる。consumerは同一repoのWebだけという前提で、同じ変更で更新する。（agent 仮決め）

## Migration Plan

1. 実装前にproductionをread-onlyで確認し、current epochの開始日・epoch付きsnapshotの最古日・直近90日のgap日別秒数を記録する。旧データは変更しない。
2. legacy `EvaluationMath.benchmark` を変更せず、新owner-score models / mathとepoch-scoped snapshot・gapのbounded readを追加する。
3. 既存 `/evaluation/benchmark` response / OpenAPI / route testをV1へ更新する。
4. generated Web typesと既存 benchmark cardを更新する。
5. README / `docs/design.md` を現在仕様へ更新し、focused testsと通常validationを実行する。

Rollbackは同じコード差分を戻すだけでよい。DB schemaと保存データは変更しない。

## Open Questions

- なし。90% coverage、window開始時B&Hとfee biasの受容、旧データを救済しない方針、日計1時間gap閾値、hobby projectとしての軽量化方針はユーザー確認済み。
