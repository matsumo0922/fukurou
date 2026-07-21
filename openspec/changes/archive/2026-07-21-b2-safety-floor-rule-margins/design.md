## Context

`SafetyFloor.kt`（1,428 行）は 25 の `SafetyFloorRule` を 13 の validator 関数で評価する。評価は early-return 型で、最初に FAIL した validator の `SafetyViolation` を返して残りを評価しない。PASS した validator は比較に使った値をローカル変数のまま捨てる。

その結果、Epic #180 の C 群のうち **trailing と EV** を動かす判断材料が存在しない。1 週間で発火したのは `EXPECTED_VALUE_GATE` の 20 件のみで、残り 24 ルールについては「余裕で通過」と「スレスレで通過」を区別できない。

TTL は `SafetyFloorRule` ではなく order 作成時に `restingEntryOrderTtl` と `timeStopAt` から決まる（`PaperBroker.kt:795-810`）ため、本 change では TTL の判断材料は得られない。

### 評価経路の実態（コード確認済み）

25 ルールのうち update 専用は `ATR_TRAILING_FLOOR` と `IMMEDIATE_STOP_TRIGGER` の 2 つ。

| 経路 | 評価するルール数 | 評価の場所 |
|---|---|---|
| `evaluatePlaceOrder` (L503-519) | **23** | `PaperBroker.kt:369-384` |
| `evaluateRestingEntryFill` (L526-541) | **20**（23 − falsifier 系 3） | **`ExposedPaperLedgerWriter.kt:1060` の JDBC transaction 内** |
| `evaluateUpdateProtection` (L556-578) | 4（`MAX_DRAWDOWN_HALT` + `STOP_LOSS_LOOSENING` + update 専用 2） | `PaperBroker.kt:763` |
| `evaluateCancelOrder` (L591-595) | 1 | — |
| `evaluateClosePosition` (L584-586) | 0（risk-reducing として無条件 `Accepted`） | — |

`evaluateRestingEntryFill` が `PaperBroker` ではなく ledger writer の transaction 内で呼ばれることは、本設計の stage 分割の直接の理由である。`evaluateRestingEntryFillInvariant`（`ExposedPaperLedgerWriter.kt:1620-1649`）は `SafetyFloorContext` を transaction 内の SELECT から組み立てており、ここに観測の書き込みを足すと **ledger の transaction に監査書き込みが同居する**。

### rule と validator は 1 対 1 でない

- 1 validator が複数 rule を出す: `validateExpectedValue`（4）、`validateEconomicEventBlackout`（4）、`validateFalsifierGate`（2 + 入れ子で 1）
- **1 rule が複数 validator から出る**: `STOP_LOSS_LOOSENING`（`validatePyramidStopTightening` L815 / `validateStopTightening` L1081）、`BALANCE_RATE_AND_COST_LIMIT`（`validateSymbolFeeRates` L952 / `validateBalanceAndCost` L974）
- **1 rule が複数の独立した条件を持つ**: `MAX_DRAWDOWN_HALT`（sticky state と数値閾値の OR）、`STOP_LOSS_REQUIRED`（stop > 0 と stop < entry の AND）、`NO_AVERAGING_DOWN`（含み損と価格差の OR、単位が異なる）

したがって観測の単位は rule ではなく **evaluation point**（`(path, rule, pointId)`）とする。

## Goals / Non-Goals

**Goals:**

- accepted / denied 双方で、対象経路の全 evaluation point の `PASS` / `FAIL` / `NA` と margin を保存する
- evaluation point ごとの margin 定義（式・単位・符号・境界）を本ドキュメントで確定させ、実装フェーズに判断を残さない
- **拒否判定の意味論を一切変えない**
- 観測の失敗・遅延が取引判断と risk-reducing 操作に伝播しない

**Non-Goals:**

- future-only shadow 追跡（別 change）
- TTL の判断材料（`SafetyFloorRule` ではないため本 change では得られない）
- 較正ダッシュボード / calibration curve / p 較正の常設 UI
- 既存 `SafetyViolation.measuredValue` / `limitValue` の文字列形式の是正
- 既存の例外リスクの根治
- 汎用 metrics / 監視基盤

## Decisions

### D0: 経路ごとに stage を切り、本 change は PLACE_ORDER のみを対象とする（agent 仮決め）

3 経路を 1 PR に入れると、observer 本体・25 述語の複製・2 テーブル・repository 2 実装・DI・`PaperBroker` 配線・**ledger writer transaction 配線**・テストが同時に載り、1,000 行の目安を大きく超える。加えて fill 経路は ledger transaction 内という質の異なる配線を要する。

| stage | 対象 | 配線先 |
|---|---|---|
| **本 change** | `PLACE_ORDER`（23 ルール / **27 evaluation point**） | `PaperBroker.kt:369-384`（`placeOrder`）と `:591-605`（`previewOrder`） |
| 後続 change A | `RESTING_ENTRY_FILL`（20） | `ExposedPaperLedgerWriter.kt:1060` の transaction 内 |
| 後続 change B | `UPDATE_PROTECTION`（4） | `PaperBroker.kt:763` |

本 change は observer の骨格・model・永続化・PLACE_ORDER の述語を作る。後続 stage は述語を再利用し、配線と経路固有の考慮だけを足す。

**本 change 内の PR 分割:**

本 change だけでも 27 述語・model・2 テーブル・repository 3 実装・DI・2 箇所の配線・テストを含み、1,000 行の目安に収まらない見込みである（推定 1,200〜1,700 行）。tasks.md を 2 stage に分け、stage ごとに PR を出す。

| PR | 内容 | 概算 |
|---|---|---|
| Stage 1 | model / 2 テーブル / repository 3 実装 / observer 骨格 / 双方向乖離検査 / 2 箇所の配線 / **27 point の status（PASS/FAIL/NA）** | 〜800 行 |
| Stage 2 | **NUMERIC 17 point の margin 値**と式のテスト | 〜700 行 |

Stage 1 単体で「どのルールが実際に FAIL しているか」が SQL で分かる。Stage 2 が「どれだけの余裕か」を足す。spec の要件は Stage 2 完了時に全て満たされる。

**この分割により本 change から外れる論点:**

- ledger transaction 内での監査書き込み（後続 change A）
- risk-reducing な `update_protection` が観測書き込みで遅延する懸念（後続 change B。`close_position` と `cancel_order` はもともと観測対象外）

### D1: SafetyFloor を変更せず、margin 収集を独立した observer として実装する（agent 仮決め・本設計の中核）

`evaluatePlaceOrder` を含む評価関数の本体を 1 行も変更しない。margin 収集は新設の `SafetyFloorMarginObserver` が、同じ `command` / `context` に対して evaluation point 単位の独立した述語を評価する。

**observer は SafetyFloor と同一の `SafetyFloorConfig` インスタンスを共有する。** `SafetyFloor.config` は `private`（`SafetyFloor.kt:489`）で外から読めず、`SafetyFloorPlaceOrderRiskDetails`（`:461-477`）も EV の実測値を返すだけで `minExpectedValueR` などの閾値そのものを返さない。observer が既定値の `SafetyFloorConfig()` を独自に構築すると、runtime config で閾値が変更されている場合に **status は双方 PASS のまま margin だけが誤る**（例: 実閾値 0.20 / EV 0.25 で真の margin 0.05 のところ、既定値 0.10 を使って 0.15 と記録する）。status が一致するため D2 の乖離検査にも掛からない。DI で SafetyFloor と observer に同じ config インスタンスを注入する。

**observer は自前で `SafetyFloorRiskCalculator` を構築する。** `SafetyFloorRiskCalculator` は `internal class`（`SafetyFloorRiskCalculator.kt:35`）で、`SafetyFloor.riskCalculator` は `private val`（`SafetyFloor.kt:494`）であるため、既存インスタンスへ到達する経路が無い。SafetyFloor に accessor を足すのは安全床本体の変更にあたるため採らない。observer は同じ `SafetyFloorConfig` / `Clock` / `PaperExecutionConfig` を受け取り、同じ引数で calculator を構築する。calculator は状態を持たないため、二重構築による副作用は無い。observer は trading module 内に置くので `internal` の可視性で足りる。

**根拠（反証を経て縮小済み）:**

early-return を撤廃して全 validator を集約する案は、次の 2 点で安全床の判定経路を危険にする。

| # | 内容 | 現在それを防いでいるもの |
|---|---|---|
| A1 | `symbolRules.takerFee.toBigDecimal()` が `NumberFormatException`（`OrderFeeRates.kt:18-30`）。fee 文字列が壊れると後段の cost 計算系（`validateGroupRisk` / `validateBalanceAndCost` / `validateExpectedValue` / `validateExpectedMoveToCost`）が全滅する | `validateSymbolFeeRates`(L511) が後段 4 validator の手前に置かれている |
| C1 | `divideOrZero`(L1316) が分母 0 で 0 を返すため、`riskAmount == 0`（stop >= entry）だと EV が `p - 1` に退化し、`NON_POSITIVE_EXPECTED_VALUE` と `EXPECTED_VALUE_GATE` が**偽の値で FAIL する** | `validateStopLoss`(L508) が先に FAIL する |

加えて、安全床本体に差分を入れること自体が資金保護 5 不変条件に対するリスクである。observer 方式なら verdict を生むコードに差分が入らないため、拒否判定の同値性が**構造的に自明**になる。

**当初 D1 の根拠に挙げていたが、反証により取り下げた項目:**

- 空 position への `maxOf`（L827）と `Instant.parse`（Calculator L144/L148）は、`validatePyramidingGates`(L784-795) 内部で既に guard されている。トップレベルを集約に変えるだけでは露出しない。**ただし observer は pyramid sub-validator を個別 evaluation point として評価するため、observer 側では明示的な事前条件が必要**（D5）。帰属を訂正する
- `EXPECTED_MOVE_TO_COST_RATIO` の分母は `riskAmount` ではなく `roundTripCost`（Calculator L291-302）であり、最低 5bps の slippage reserve があるため `stop >= entry` で 0 にならない。C2 として挙げていた反例は成立しない

**代償（受容する）:** ルールの述語が verdict 側と observer 側の 2 箇所に存在し、乖離しうる。緩和は D2。

**検討して棄却した代替案:**

| 案 | 棄却理由 |
|---|---|
| 全 validator を評価して集約 | A1 / C1。安全床の判定経路に挙動変更が入る |
| validator を「評価」と「判定」に分離し verdict 側も新関数を使う | 13 validator 全部の内部書き換えが必要で、判定が変わらない保証を人間がレビューできない |
| FAIL 時のみ margin を保存 | 「余裕で通過か、スレスレか」という目的を満たさない |

### D2: 乖離検査は evaluation point 単位で双方向に行う（agent 仮決め）

D1 の代償に対する緩和。verdict と観測の間には次の 2 方向の関係が成立しなければならない。

- **verdict が `Rejected(rule = R)`** のとき、R に対応する evaluation point の**少なくとも 1 つ**が `FAIL` である
- **verdict が `Accepted`** のとき、その経路の**全** evaluation point が `FAIL` でない

後者が重要である。`Accepted` は全 validator を通過したことを意味するため、観測側の `FAIL` は early-return では説明できず、必ず述語の乖離である。当初設計はこれを「正常でありうる」として見逃していた。

観測側の記録キーは `(path, rule, pointId)` である。ただし **`Rejected` 方向の検査は rule 単位に留まる**。`SafetyViolation`（`SafetyFloor.kt:408-423`）は `rule` しか持たず、どの evaluation point が拒否を生んだかを識別できないためである。`BALANCE_RATE_AND_COST_LIMIT` が fee 側で拒否されたとき、観測の fee 側が誤って `PASS`、cash 側が `FAIL` でも、rule 単位の検査は通ってしまう。

**保証の縮退:** 複数 point を持つ 4 ルールについて、`Rejected` 方向の乖離検出は point 単位まで絞り込めない。この限界を residual risk として記録する。`Accepted` 方向の検査は全 point を対象とするため point 単位で機能し、乖離の大半はそちらで捕捉される。`SafetyViolation` に point 識別子を足すことは安全床本体の変更であり、D1 の方針に反するため採らない。

乖離を検出したら `divergence` を立ててログに出す。**verdict は変更しない。**

なお `Rejected` のとき、拒否ルールより後段の evaluation point が `FAIL` であることは乖離ではない（verdict は early-return するため後段を評価していない）。

**clock 由来の残余リスク:** verdict と observer は別々に clock を読むため、market data の鮮度境界や calendar window 境界にちょうど重なると、同じ入力で異なる status になりうる。SafetyFloor を変更しない以上、clock を共有させることはできない。この乖離は D2 の `Accepted` 側検査で **divergence として検出・記録される**ため、silent な誤りにはならない。保証を「乖離が起きない」から「乖離が起きたら記録される」へ縮退させ、residual risk として受容する。

### D3: margin の符号は「正 = 閾値までの余裕」に統一する（agent 仮決め）

上限型は `limit - measured`、下限型は `measured - limit` と定義を反転させて揃える。境界の扱いは判定式に従い、evaluation point ごとに「境界」列に明記する。

### D4: PLACE_ORDER 経路の evaluation point 棚卸し（ユーザー確認済み・本設計の正本）

23 ルールを **27 evaluation point** に展開する。NUMERIC 17 / BOOLEAN 10（`23 + 4 = 27`）。

複数 point に分ける rule は 4 つ。

- `MAX_DRAWDOWN_HALT` → sticky state（BOOLEAN）と数値閾値（NUMERIC）。現行述語は `hardHaltEnabled || maxDrawdownPolicy.isHardHalt(measuredDrawdown)` の **OR**（`SafetyFloor.kt:614-620`）であり、数値だけでは HARD_HALT 中に drawdown が浅い状態を PASS と誤る
- `STOP_LOSS_REQUIRED` → `stop > 0` と `entry - stop > 0` の AND。単一の `min` にすると違反の所在が判別できない
- `NO_AVERAGING_DOWN` → 含み損（jpy）と価格差（jpy/BTC）の OR。**単位が異なるため `min` を取れない**。0.01 BTC の position で価格差 `-100,000 JPY/BTC`、含み損 `-1,000 JPY` のとき、直接 `min` すると違反量が 100 倍に歪む
- `BALANCE_RATE_AND_COST_LIMIT` → cash 側（NUMERIC）と fee 側（BOOLEAN）

#### NUMERIC（17 point）

| evaluation point | margin の式 | 単位 | 境界 | PASS 時の取得コスト | NA 条件 |
|---|---|---|---|---|---|
| `MAX_RISK_PER_TRADE` | `totalEquityJpy × config.maxRiskPerTradeRatio - groupRiskAfterOrder(...)` | jpy | `>= 0` | ゼロ | なし |
| `MAX_TOTAL_EXPOSURE` | `totalEquityJpy × config.maxTotalExposureRatio - (currentExposure + orderExposure)` | jpy | `>= 0` | ゼロ | なし |
| `MAX_DRAWDOWN_HALT.threshold` | `min(account.drawdownRatio, riskState.drawdownRatio) - maxDrawdownPolicy.thresholdRatio` | ratio | `> 0` | ゼロ | なし |
| `BALANCE_RATE_AND_COST_LIMIT.cash` | `availableCash(context) - orderRequiredCash(command, context)` | jpy | `>= 0` | ゼロ | fee がパース不能 |
| `NON_POSITIVE_EXPECTED_VALUE` | `expectedValueDetails.expectedValueR - 0` | R | `> 0` | ゼロ | TP null / p が 0..1 外 / **stop >= entry**（C1 回避） |
| `EXPECTED_VALUE_GATE` | `expectedValueR - config.minExpectedValueR` | R | `>= 0` | ゼロ（同値を再利用） | 同上 |
| `EXPECTED_MOVE_TO_COST_RATIO` | `expectedMoveToCostRatioOrNull(...) - config.minExpectedMoveToCostRatio` | ratio | `>= 0` | ゼロ | TP null（**現状 PASS に潰れている**） |
| `STOP_LOSS_REQUIRED.positive` | `command.protectiveStopPriceJpy - 0` | jpy | `> 0` | ゼロ | なし |
| `STOP_LOSS_REQUIRED.belowEntry` | `estimatedEntryPrice(command, context) - command.protectiveStopPriceJpy` | jpy | `> 0` | 軽 | なし |
| `STOP_LOSS_LOOSENING` | `min over positions where currentStopLossJpy != null(command.protectiveStopPriceJpy - position.currentStopLossJpy)` | jpy | `>= 0` | 軽 | **対象 position の全件で `currentStopLossJpy == null`**。1 件でも非 null があれば非 null 分のみで評価する |
| `NO_AVERAGING_DOWN.unrealizedPnl` | `min over positions(unrealizedPnlJpy)` | jpy | `>= 0` | 軽 | 対象 position が空 |
| `NO_AVERAGING_DOWN.priceDiff` | `min over positions(currentPriceJpy - averageEntryPriceJpy)` | jpy/BTC | `>= 0` | 軽 | 対象 position が空 |
| `PYRAMID_ADD_LIMIT` | `MAX_PYRAMID_ADD_COUNT - max over positions(pyramidAddCount)` | count | `>= 1` | 軽 | 対象 position が空 / `tradeGroupId == null` |
| `PYRAMID_PROFIT_GATE` | `min over positions(unrealizedR - (pyramidAddCount + 1))` | R | `>= 0` | 軽 | 同上 |
| `PYRAMID_ADD_RISK_LIMIT` | `(initialTradeGroupRiskBudget ?: groupRiskBeforeOrder) × PYRAMID_ADD_RISK_RATIO - orderRisk(...)` | jpy | `>= 0` | 中（calculator 1 本追加） | 同上 / `createdAt` がパース不能 |
| `ECONOMIC_EVENT_BLACKOUT` | `min over events(max(between(observedAt, window.startsAt), between(window.endsAt, observedAt)))` | seconds | `> 0` | 新規計算（軽量） | calendar が ACTIVE でない / events が空 |
| `FOMC_CALENDAR_EXPIRED` | `between(observedAt, calendar.validThrough)` | seconds | **`>= 0`** | 新規計算（軽量） | state が MISSING / INVALID |

`ECONOMIC_EVENT_BLACKOUT` の内側は `max` である。window 開始 10 秒前では `between(observedAt, startsAt) = +10`、`between(endsAt, observedAt) < 0` となるため、内側を `min` にすると window 外でも常に負になり、verdict が PASS なのに観測が FAIL を記録する（`SafetyFloor.kt:188-193` の `contains` は window 内のみ true）。window 内では両方が負になり `max` が「境界までの深さ」の符号付き値を与える。

`FOMC_CALENDAR_EXPIRED` の境界は `>= 0` である。`stateAt` は `now > validThrough` で EXPIRED とするため（`FomcBlackoutCalendar.kt:42`）、`observedAt == validThrough` は ACTIVE である。`> 0` にすると境界ちょうどで観測だけが FAIL する。

#### BOOLEAN（10 point）

| evaluation point | 理由 |
|---|---|
| `MAX_DRAWDOWN_HALT.stickyState` | `riskState.state == RiskHaltState.HARD_HALT`。距離の概念がない |
| `MISSING_TARGET_PRICE` | `takeProfitPriceJpy == null` の有無 |
| `INVALID_WIN_PROBABILITY` | 範囲妥当性検査。`min(p, 1-p)` は定義可能だが「閾値までの余裕」ではなく入力妥当性であり意味が揃わない |
| `MISSING_FRESH_FALSIFICATION` | SafetyFloor に露出しているのは Boolean `freshApproved` のみ |
| `INTENT_CONSUMED` | `snapshot.consumed` |
| `INTENT_MISMATCH` | 7 項目の完全一致判定。乖離許容幅の概念がない |
| `FOMC_CALENDAR_MISSING` | calendar state の判定 |
| `FOMC_CALENDAR_INVALID` | 同上 |
| `SOFT_HALT_ENTRY_BLOCKED` | `riskState.state == SOFT_HALT` |
| `BALANCE_RATE_AND_COST_LIMIT.fee` | `unsafeOrderFeeRateReasonOrNull` が 4 種の閾値比較を 1 つの理由文字列に潰しており数値差を返さない（`OrderFeeRates.kt:120-139`） |

`ECONOMIC_EVENT_BLACKOUT` の全 event 走査は安全である。`FomcBlackoutCalendar.fromEvents`（`:68-96`）は `toSafeWindow()` が null の event を含む calendar を `INVALID` にするため、`ACTIVE` な calendar の全 event は window 非 null が保証され、`validThrough` も非 null である。

### D5: 事前条件が崩れた evaluation point は NA とする（agent 仮決め）

observer は rule を個別に評価するため、verdict 側で validator 内 guard が担っていた保護が効かない。次を明示的な事前条件として実装する。

| 事前条件 | 満たされないときに NA にする evaluation point | 根拠 |
|---|---|---|
| `tradeGroupId != null` かつ対象 position が非空 | `PYRAMID_ADD_LIMIT` / `PYRAMID_PROFIT_GATE` / `PYRAMID_ADD_RISK_LIMIT` / **`STOP_LOSS_LOOSENING`** | `maxOf` が空リストで `NoSuchElementException`（L827）。加えて PLACE_ORDER 経路の `STOP_LOSS_LOOSENING` は `validatePyramidStopTightening` から出るため、`validatePyramidingGates`(L784-790) が `tradeGroupId == null` で即 return する時点で **verdict 側は評価していない**。一方 `filterTargetPositions(null)`(L1226-1232) は全 open position を返すため、observer が同じ前提を置かないと無関係な position で FAIL を作る |
| fee 文字列が数値としてパース可能 | cost 計算に依存する point | A1 |
| `stop < entry`（`riskAmount > 0`） | `NON_POSITIVE_EXPECTED_VALUE` / `EXPECTED_VALUE_GATE` | C1。退化した `p - 1` を FAIL として記録しない |

### D6: 永続化（agent 仮決め）

新テーブル 2 つ。`ExposedLlmInputManifestRepository` の作法（append-only + `Result<T>` + InMemory 実装併走）に倣う。

- `safety_floor_margin_reports` — `id` / `evaluation_path` / **`call_site`**（PREVIEW / PLACE）/ `decision_run_id` / `command_id` / `order_id` / `verdict` / `rejected_rule`(nullable) / `policy_version` / `runtime_config_version`(**nullable**) / **`observation_schema_version`** / `divergence` / `observed_at`

`runtime_config_version` は nullable とする。`PaperBrokerRuntime`（`PaperBroker.kt:234-240`）は runtime config の version を保持しておらず、到達可能な経路は `command.auditContext.decisionRunContext.runtimeConfigVersionId`（`audit/DecisionRunContext.kt:49-56`）の best-effort だけである。LLM 起動由来の command では埋まるが、worker や test 経路では `DecisionRunContext.EMPTY` により null になる。version を確実に取るために DI を広く変更することは、本 change の目的に対して割に合わない。`policy_version` は SafetyFloor 側の定数なので常に埋まる。
- `safety_floor_rule_margins` — `id` / `report_id`(FK) / `rule` / `point_id` / `status` / `na_reason`(nullable) / `margin_value`(decimal, nullable) / `margin_unit`(nullable) / **`observed_at`**

**`observation_schema_version`** は Stage 分割に伴う cohort marker である。Stage 1 は status のみを保存するため、NUMERIC point が `PASS` でも `margin_value` が NULL になる。Stage 2 を deploy しても過去行に margin は付かない（観測時点の context は失われており、後日の backfill は paper 真実性の観点でも採れない）。version 列がないと、同じ policy version の中に「numeric PASS だが margin 欠測」の行が混在し、margin 分布の照会を汚染する。Stage 1 は `1`、Stage 2 以降は `2` を記録し、margin の照会は `observation_schema_version >= 2` で絞る。

`observed_at` を child にも持たせる（非正規化）。`observed_at` が親、`rule` が子にあると `(rule, observed_at)` の index は作れない。index は child の `(rule, observed_at)` と `(report_id)` とする。

`margin_value` は BigDecimal を数値列で保持する。既存 `SafetyViolation.limitValue` は比較演算子や範囲を埋め込んだ文字列（`">=1000"` / `"0..1"` / `"entry_price_below_<数値>"`）であり、**パースの対象にしない**。observer が計算時点の BigDecimal を直接書く。

**report 1 件と child 27 件は単一 transaction で append し、1 件でも失敗したら全て rollback する。** 途中失敗で report と一部 child だけが残ると、SQL 上は「27 point を持つ観測」と区別がつかない不完全なレコード群が恒久化し、status と margin の分布を歪める。永続化失敗は隔離して注文処理を続行する方針であるため、部分行を残さない保証が要る。

migration は `SchemaUtils.createMissingTablesAndColumns` に 2 行追加（Flyway 不使用）。

### D7: policy version（agent 仮決め）

`SafetyFloorDefaults` に `policyVersion: String` 定数を新設し、閾値定数を変更する際に手で更新する規約とする。定数のハッシュによる自動導出は採らない（定数の追加・リネームで、閾値が変わっていないのに母集団が切れる）。`runtime_config_version` は既存値を併記する。

### D8: 書き込み経路と隔離（agent 仮決め）

`evaluatePlaceOrder` の呼び出し地点は **2 つ**ある。両方に配線する。

- `placeOrder`（`PaperBroker.kt:369-384`）
- `previewOrder`（`PaperBroker.kt:591-605`）

preview は実際の注文を伴わないが、同じ 27 evaluation point を評価しており、拒否・通過の分布としては place と同等の情報を持つ。片方だけに配線すると spec の「accepted / denied 双方で記録」が preview 経路で破れる。レコードには経路を区別する列を持たせる。

verdict 取得の直後に observer を呼び、レコードを append する。

- **`CancellationException` は捕捉せず再 throw する。** `runCatching` は cancellation まで捕らえるため、素の `runCatching` だと coroutine キャンセル中に「観測の失敗」として握り潰し、risk-increasing な注文処理を続行させうる。failure isolation と cancellation isolation を分ける
- 書き込みには短い statement timeout を設け、監査テーブルの lock が注文処理を長時間ブロックしないようにする
- **掃引順序の要件は `CallSite.PLACE` にのみ適用する。** `previewOrder`（`PaperBroker.kt:591-608`）は `Rejected` をそのまま返すだけで、HARD_HALT の有効化も掃引も呼ばない。掃引は `placeOrder` が通る `PaperBrokerSafetyGate`（`:1081`）にしか存在しない。preview に掃引を起こさせることは、読み取り目的の操作に ledger 変更の副作用を持たせる**安全床の挙動変更**であり、D1 の「SafetyFloor の意味論を変えない」方針に反するため採らない。preview は verdict 取得直後に観測する
- **`CallSite.PLACE` かつ `Rejected` かつ `hardHaltRequired` の場合は、HARD_HALT の有効化と open risk の掃引を先に行い、その後で観測する。** `activateHardHaltAndSweep`（`PaperBroker.kt:1135-1158`）は `setHardHalt` と `sweepOpenRisk` を行う risk-reducing な後処理であり、観測の同期 append をその手前に置くと、監査テーブルの lock が掃引を statement timeout まで遅らせうる。PLACE_ORDER 自体は risk-increasing だが、その拒否後処理は risk-reducing である
- **掃引が例外で終了した場合も観測を試みる。** `sweepOpenRisk` 内の `executeRiskExit(...).getOrThrow()`（`PaperBroker.kt:1092-1105`）が失敗すると `activateHardHaltAndSweep` は例外終了する。「完了後に観測」を素直に書くと、この経路の denied decision が記録されず「accepted / denied 双方を記録する」要件が破れる。観測は掃引の**成否によらず**その後で 1 回だけ試み、掃引の例外はそのまま呼び出し元へ伝播させる。観測自体の失敗が掃引の例外を覆い隠してはならない
- 観測の**算出**が失敗し、かつ DB が利用可能な場合は、report レコードを欠測として残す。ログのみだと「margin が無い」のか「decision 自体が無い」のかを SQL で区別できない
- **保証の縮退:** DB 停止や statement timeout で**永続化そのもの**が失敗した場合、同じ DB へ欠測 report を書くこともできない。この場合の SQL evidence は保証せず、ログのみとする

`evaluateCancelOrder` / `evaluateClosePosition` は観測対象に含めない。前者は 1 ルールのみ、後者は評価ルールが無く、判断材料にならない。

## Risks / Trade-offs

- **[verdict と observer の述語が乖離する]** → D2 の双方向検査で検出・記録する。verdict は影響を受けない。D1 で「安全床に手を入れない」ことを選んだ対価であり、意図的な受容である
- **[clock の読み取りタイミング差による境界での乖離]** → 保証を「乖離しない」から「乖離を記録する」へ縮退。D2 の `Accepted` 側検査が拾う
- **[述語が 2 箇所に存在し、将来のルール変更で observer 側の更新が漏れる]** → PLACE_ORDER 経路の全 evaluation point を網羅していることを `SafetyFloorRule.entries` ベースのテストで固定する。ルール追加時にテストが落ちる
- **[レコード量]** → PLACE_ORDER 経路で decision あたり 27 行。`previewOrder`（`PaperBroker.kt:591-605`）と `placeOrder`（`:369-384`）の双方が `evaluatePlaceOrder` を呼ぶため、accepted な entry 1 件あたり report 2 行 + child 54 行になる。retention 方針は本 change では設けない。日次件数とディスク見積もりは未算出であり、residual risk として記録する（当初見積もり 22 行 → 26 行 → 54 行と 2 度訂正した）
- **[後続 stage で fill 経路を ledger transaction 内に配線する必要がある]** → 本 change の scope 外。observer と repository を transaction 非依存に設計しておき、後続 stage が transaction 内から呼べる形にする
- **[既存の例外リスクは残る]** → scope 外。observer 側は自衛するが、verdict 側の既存リスク（`requireNotNull(command.priceJpy)`、`Ticker.ask/bid` の `toBigDecimal()` の不統一、`mergedPositionRisk` の非 guard 除算）は触らない。**完了報告に列挙する**

## Migration Plan

1. テーブル 2 つを追加（起動時に自動作成）
2. observer と repository を追加。この時点では誰も呼ばない
3. `PaperBroker.kt:369-384`（`placeOrder`）と `:591-605`（`previewOrder`）の双方から呼び出しを追加

rollback は 3 → 2 の順。テーブルは残っても既存動作に影響しない。verdict 側に変更が無いため、rollback で安全床の挙動が変わることはない。

## Open Questions

なし。

## やらないこと（Next steps 候補）

- `RESTING_ENTRY_FILL` 経路の観測（後続 change A。ledger transaction 内の配線）
- `UPDATE_PROTECTION` 経路の観測（後続 change B。risk-reducing 操作の遅延を考慮する必要がある）
- 既存 `SafetyViolation.measuredValue` / `limitValue` の文字列形式の是正
- 既存例外リスクの根治
- `evaluateCancelOrder` / `evaluateClosePosition` の観測
- margin レコードの retention / 集約
- verdict 側と observer 側の述語の統合
