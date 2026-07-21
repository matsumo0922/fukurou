本 change は 2 つの PR に分けて実装する。Stage 1 完了時点で「どのルールが実際に FAIL しているか」が SQL で分かり、Stage 2 が「どれだけの余裕か」を足す。spec の全要件は Stage 2 完了時に満たされる。

## 1. Stage 1 / 観測 model と policy version

- [ ] 1.1 `safety/SafetyFloorRuleObservation.kt` を新設し、`RuleStatus`（PASS / FAIL / NA）、`NaReason`（NOT_APPLICABLE_PATH / MISSING_INPUT / PRECONDITION_UNMET / EVALUATION_ERROR）、`MarginUnit`（JPY / JPY_PER_BTC / RATIO / R / COUNT / SECONDS）、`EvaluationPointId`、`RuleObservation`、`SafetyFloorObservationReport` を定義する
- [ ] 1.2 `EvaluationPath`（本 change は `PLACE_ORDER` のみ）と `CallSite`（PREVIEW / PLACE）を定義する。後続 stage 用に拡張可能にしておく
- [ ] 1.3 PLACE_ORDER 経路の **27 evaluation point**（23 ルール + 4 ルールの分割分）の集合を定義する。design.md D4 の表を正本とする
- [ ] 1.4 `SafetyFloorDefaults` に `policyVersion: String` 定数を追加し、閾値定数変更時に更新する規約を KDoc に書く

## 2. Stage 1 / observer 骨格と status

- [ ] 2.1 `safety/SafetyFloorMarginObserver.kt` を新設する。`SafetyFloor` の評価関数は変更しない
- [ ] 2.2 observer の入口で `observedAt` を 1 回だけ確定させ、全 evaluation point で共有する（point 間で clock を読み直さない）
- [ ] 2.3 27 evaluation point の status 述語を実装する。各評価を個別に包み、例外は `NA(EVALUATION_ERROR)` に落とす
- [ ] 2.4 `MAX_DRAWDOWN_HALT` を sticky state と数値閾値の 2 point に分ける
- [ ] 2.5 `STOP_LOSS_REQUIRED` を `stop > 0` と `entry - stop > 0` の 2 point に分ける
- [ ] 2.6 `NO_AVERAGING_DOWN` を含み損と価格差の 2 point に分ける
- [ ] 2.7 `BALANCE_RATE_AND_COST_LIMIT` を cash 側と fee 側の 2 point に分ける
- [ ] 2.8 事前条件を実装する。fee がパース不能なとき cost 依存 point を `NA`、`tradeGroupId == null` または対象 position が空のとき pyramid 系 3 point **と `STOP_LOSS_LOOSENING`** を `NA`、stop >= entry のとき EV 系 2 point を `NA` とする（design.md D5）。**`filterTargetPositions(null)` は全 open position を返すため、前提を置かないと無関係な position で FAIL を作る**
- [ ] 2.9 `STOP_LOSS_LOOSENING` は `currentStopLossJpy` が非 null の position のみで評価する。**全件 null のときだけ `NA`**（verdict 側は null position を個別にスキップする、`SafetyFloor.kt:800-820`）
- [ ] 2.10 双方向の乖離検査を実装する。`Rejected(R)` なら R の少なくとも 1 point が FAIL（rule 単位）、`Accepted` なら全 point が FAIL でない（point 単位）。破れたら `divergence` を立ててログ出力する

## 3. Stage 1 / 永続化

- [ ] 3.1 `persistence/TradingTables.kt` に `SafetyFloorMarginReportsTable` と `SafetyFloorRuleMarginsTable` を追加する。child 側にも `observed_at` を持たせる。report 側に `call_site`（PREVIEW / PLACE）と `observation_schema_version` を持たせる
- [ ] 3.1a Stage 1 は `observation_schema_version = 1` を記録する。margin を保持しない世代であることを示す cohort marker とする
- [ ] 3.2 `persistence/TradingPersistenceBootstrap.kt` の `ensureSchema()` の列挙に 2 テーブルを追加する
- [ ] 3.3 index を生 SQL で追加する。child の `(rule, observed_at)` と `(report_id)`
- [ ] 3.4 `safety/SafetyFloorMarginRepository.kt` を新設する。append-only + `Result<T>` + InMemory 実装併走
- [ ] 3.5 `persistence/ExposedSafetyFloorMarginRepository.kt` を実装する。後続 stage が JDBC transaction 内から呼べるよう transaction 非依存の境界にする
- [ ] 3.5a **report 1 件と child 27 件を単一 transaction で append し、1 件でも失敗したら全 rollback する**。部分行を残さない
- [ ] 3.6 算出が全面的に失敗した場合に report レコードを欠測状態で残す経路を実装する。**永続化自体が失敗した場合は SQL evidence を保証しない**（ログのみ）

## 4. Stage 1 / 配線

- [ ] 4.1 `broker/PaperBroker.kt:369-384`（`placeOrder`）で verdict 取得直後に observer を呼ぶ
- [ ] 4.2 `broker/PaperBroker.kt:591-605`（`previewOrder`）にも配線する。`evaluatePlaceOrder` の呼び出し地点は 2 つある。**preview は verdict 直後に観測し、掃引を起動しない**（preview に ledger 変更の副作用を持たせない）
- [ ] 4.3 **`CallSite.PLACE` かつ `Rejected` かつ `hardHaltRequired` の場合は、`activateHardHaltAndSweep`（`PaperBroker.kt:1135-1158`）の後に観測を書き込む**。risk-reducing な掃引を監査書き込みで遅延させない
- [ ] 4.3a **掃引が例外で終了した場合も観測を 1 回試みる**。`sweepOpenRisk` の `executeRiskExit(...).getOrThrow()`（`:1092-1105`）失敗で観測が丸ごと落ちると denied decision が記録されない。掃引の例外はそのまま伝播させ、観測の失敗で覆い隠さない
- [ ] 4.4 **`CancellationException` は捕捉せず再 throw する**。素の `runCatching` を使わない
- [ ] 4.5 書き込みに短い statement timeout を設ける
- [ ] 4.6 `evaluateRestingEntryFill` / `evaluateUpdateProtection` / `evaluateCancelOrder` / `evaluateClosePosition` には配線しない（scope 外）
- [ ] 4.7 DI / runtime 配線に repository と observer を追加する。runtime config version は `command.auditContext.decisionRunContext.runtimeConfigVersionId` から best-effort で取り、取れない場合は null を記録する（ambient な現在値は読まない）
- [ ] 4.8 **observer に SafetyFloor と同一の `SafetyFloorConfig` / `Clock` / `PaperExecutionConfig` を注入する**。`SafetyFloor.config` は `private`（`SafetyFloor.kt:489`）、`riskCalculator` も `private`（`:494`）、`SafetyFloorRiskCalculator` は `internal class`（`SafetyFloorRiskCalculator.kt:35`）なので、observer は同じ引数で calculator を自前構築する。既定値を独自構築すると status 一致・margin のみ誤りとなり乖離検査で検出できない

## 5. Stage 1 / テスト

- [ ] 5.1 **DoD**: verdict が観測の導入前後で同一であることの回帰テスト
- [ ] 5.2 **DoD**: 観測レコードの書き込みが cash / position / strategy PnL を変更しないことのテスト
- [ ] 5.3 PLACE_ORDER 経路の 27 evaluation point を網羅していることのテスト（`SafetyFloorRule.entries` ベース）
- [ ] 5.4 `Accepted` かつ観測 FAIL で `divergence` が立つこと
- [ ] 5.5 `Rejected(R)` で R の point が FAIL なら `divergence` が立たないこと
- [ ] 5.6 `Rejected` で拒否ルールより後段の point が FAIL でも `divergence` が立たないこと
- [ ] 5.7 `MAX_DRAWDOWN_HALT` が sticky HARD_HALT かつ drawdown が浅い状態で、sticky point は FAIL、数値 point は PASS になること
- [ ] 5.8 事前条件による NA 化。stop >= entry で EV 系が `NA` になること
- [ ] 5.9 空 position で pyramid 系が例外を投げず `NA` になること
- [ ] 5.10 `STOP_LOSS_LOOSENING` で stop 未設定 position が混在するとき、非 null 分のみで評価され `NA` にならないこと
- [ ] 5.11 fee 文字列がパース不能でも observer が例外を投げないこと
- [ ] 5.12 preview と place の観測が区別して保存されること
- [ ] 5.12a `tradeGroupId == null` かつ無関係な open position が存在するとき、`STOP_LOSS_LOOSENING` が `NA` になり `FAIL` にならないこと
- [ ] 5.12b 既定値と異なる閾値が有効なとき、observer が有効な閾値に基づく margin を記録すること（Stage 2 の式に効くが、config 注入の検証は Stage 1 で行う）
- [ ] 5.13 HARD_HALT を伴う拒否で、掃引の後に観測が書き込まれること
- [ ] 5.13a 掃引が例外で終了しても観測が試みられ、掃引の例外が伝播すること
- [ ] 5.14 `CancellationException` が握り潰されないこと
- [ ] 5.15 算出の全面失敗時に report が欠測状態で残ること（DB 生存時）
- [ ] 5.16 永続化失敗時に verdict が変わらず注文処理が続行されること
- [ ] 5.17 永続化の往復テスト（Exposed repository）
- [ ] 5.18 child の途中で保存が失敗したとき、その decision の観測レコードが 1 件も残らないこと
- [ ] 5.19 HARD_HALT 状態の `preview_order` で掃引が起動されず、verdict 直後に観測されること

## 6. Stage 1 / 仕上げ

- [ ] 6.1 `make detekt` と関連テストを通す
- [ ] 6.2 Stage 1 を PR として出す

## 7. Stage 2 / margin 値

- [ ] 7.1 NUMERIC 17 point の margin を design.md D4 の式で算出する。符号は「正 = 閾値までの余裕」に統一する
- [ ] 7.2 `ECONOMIC_EVENT_BLACKOUT` を `min over events(max(startsAt - observedAt, observedAt - endsAt))` で実装する。**内側は max**
- [ ] 7.3 `FOMC_CALENDAR_EXPIRED` の境界を `>= 0` とする
- [ ] 7.4 `placeOrderRiskDetails` の既存 15 フィールドを流用し、コストゼロで取れる point で重複計算しない
- [ ] 7.5 `PYRAMID_ADD_RISK_LIMIT` の `initialTradeGroupRiskBudget` を calculator から取得する
- [ ] 7.6 BOOLEAN 10 point は margin を欠測のままとする
- [ ] 7.7 `observation_schema_version = 2` を記録する。Stage 1 の行（version 1）へ backfill は行わない

## 8. Stage 2 / テスト

- [ ] 8.1 margin の符号のテスト。余裕がある PASS で正、違反した FAIL で負
- [ ] 8.2 `ECONOMIC_EVENT_BLACKOUT` が window 外で正、window 内で負になること。開始前・終了後の両方
- [ ] 8.3 `FOMC_CALENDAR_EXPIRED` が `observedAt == validThrough` で PASS になること
- [ ] 8.4 NUMERIC 17 point それぞれの式のテスト
- [ ] 8.5 単位が evaluation point ごとに正しく記録されること（特に `NO_AVERAGING_DOWN` の JPY と JPY_PER_BTC）
- [ ] 8.6 現状 PASS に潰れている 2 件（`EXPECTED_MOVE_TO_COST_RATIO` の TP null、`STOP_LOSS_LOOSENING` の全件 stop null）が `NA` になること

## 9. Stage 2 / 仕上げ

- [ ] 9.1 `make detekt` と関連テストを通す
- [ ] 9.2 margin ベクトルを SQL で照会する例を `docs/` の該当箇所に追記する。**margin の照会は `observation_schema_version >= 2` で絞ること**を明記する（新規ファイルは作らない）
- [ ] 9.3 完了報告に、後続 change（`RESTING_ENTRY_FILL` は ledger transaction 内配線、`UPDATE_PROTECTION` は risk-reducing 遅延の考慮）を明記する
- [ ] 9.4 完了報告に、residual risk を明記する。`Rejected` 方向の乖離検出が rule 単位に留まること、clock 由来の margin 差が検出されないこと、retention 未設定でレコードが無期限に増えること、Stage 1 の行に margin が無く backfill しないこと
- [ ] 9.5 完了報告に、scope 外として残した既存例外リスク（`Ticker.ask/bid` の `toBigDecimal()` 不統一、`mergedPositionRisk` の非 guard 除算、Position の String フィールド群のパース）を列挙する
