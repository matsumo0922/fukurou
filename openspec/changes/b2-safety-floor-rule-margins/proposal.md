## Why

SafetyFloor は 25 ルールを持つが、発火実績があるのは `EXPECTED_VALUE_GATE` のみ（1 週間で 20 件）で、残り 24 ルールについては「一度も拒否していない」ことしか分からない。現在の評価は early-return 型で、最初に FAIL したルールだけが `SafetyViolation` に実測値と閾値を残し、PASS したルールは比較に使った値をローカル変数のまま捨てている。

そのため「余裕で通過しているのか、閾値スレスレで通過しているのか」を区別できず、Epic #180 の C 群のうち **trailing と EV** の変更を判断する材料が存在しない。健康診断で「異常なし」としか書かれていない診断書と同じ状態で、実測値がなければ次に何を動かすべきかを決められない。

C 群のうち TTL は `SafetyFloorRule` ではなく order 作成時に `restingEntryOrderTtl` と `timeStopAt` から決まる（`PaperBroker.kt:795-810`）ため、本 change では TTL の判断材料は得られない。

## What Changes

- **`SafetyFloor` の評価関数を変更せずに**、margin を収集する独立した observer を新設する。拒否判定を生むコードには手を入れない
- observer は **evaluation point 単位**（`(評価経路, rule, point 識別子)`）で `PASS` / `FAIL` / `NA` と margin を評価する。1 rule が複数の validator や複数の独立条件を持つため、rule 単位では観測が衝突する
- accepted / denied の**双方**で観測を新テーブルへ append する
- verdict と観測の乖離を**双方向**に検査する。`Accepted` なのに観測が `FAIL` の場合も乖離として記録する
- 本 change の対象は **`PLACE_ORDER` 経路のみ**（27 evaluation point = 23 ルール + 4 ルールの分割分）。`RESTING_ENTRY_FILL` と `UPDATE_PROTECTION` は後続 change とする。本 change 自体も status（Stage 1）と margin 値（Stage 2）の 2 PR に分ける
- SafetyFloor の policy version 定数を導入し、観測レコードに記録する
- 観測の失敗・キャンセル・遅延が取引判断に伝播しないよう隔離する

**BREAKING なし**（外部 API・wire contract の変更なし）

## Capabilities

### New Capabilities

- `safety-floor-rule-margins`: SafetyFloor の evaluation point について、accepted / denied 双方の decision で PASS/FAIL/NA と margin を観測可能な形で保存する。拒否判定の意味論を変えないことを不変条件として含む。

### Modified Capabilities

なし（既存 spec に SafetyFloor の要件は存在しない）

## Impact

### stage 分割

3 つの評価経路は配線先の性質が異なるため、1 PR にまとめない。

| stage | 対象経路 | evaluation point 数 | 配線先 |
|---|---|---|---|
| **本 change** | `PLACE_ORDER` | 27 | `PaperBroker.kt:369-384`（`placeOrder`）と `:591-605`（`previewOrder`）|
| 後続 change A | `RESTING_ENTRY_FILL` | 20 ルール相当 | **`ExposedPaperLedgerWriter.kt:1060` の JDBC transaction 内** |
| 後続 change B | `UPDATE_PROTECTION` | 4 ルール相当 | `PaperBroker.kt:763` |

`evaluateRestingEntryFill` が `PaperBroker` ではなく ledger writer の transaction 内で呼ばれること（`evaluateRestingEntryFillInvariant`、`ExposedPaperLedgerWriter.kt:1620-1649`）が分割の直接の理由である。監査書き込みを ledger の transaction に同居させる設計判断が別途必要になる。

### 変更する中核

- `trading/src/main/kotlin/me/matsumo/fukurou/trading/safety/SafetyFloor.kt`（1,428 行）— **評価関数を変更しない**。early-return は、fee 文字列のパース失敗が後段の cost 計算系を全滅させること（`OrderFeeRates.kt:18-30`）と、`riskAmount == 0` で EV が `p - 1` に退化して偽の FAIL を生むこと（`SafetyFloorRiskCalculator.kt:267-279`）を偶然に防いでいる。加えて安全床本体に差分を入れること自体が資金保護 5 不変条件に対するリスクである
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/safety/SafetyFloorRiskCalculator.kt` — PASS 側でも数値を返す経路の露出。既存メソッドが揃っており追加は軽い
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/safety/SafetyFloorDefaults.kt`（24 行）— policy version 定数の新設

### 呼び出し元

- `trading/src/main/kotlin/me/matsumo/fukurou/trading/broker/PaperBroker.kt` の 2 箇所。`placeOrder`（`:369-384`）と `previewOrder`（`:591-605`）が同じ `evaluatePlaceOrder` を呼ぶ

### 永続化

- `trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/TradingTables.kt` — 新テーブル 2 つ
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/TradingPersistenceBootstrap.kt` — `ensureSchema()` の列挙に追加。**Flyway 不使用**のため versioned migration は不要
- 新規 repository — `audit/LlmInputManifestRepository.kt` / `persistence/ExposedLlmInputManifestRepository.kt` の作法に倣う

### 資金保護との関係

本 change は資金保護 5 不変条件の判定経路を**変更しない**。拒否判定の同値性は、verdict を生むコードに差分が入らないことによって構造的に保証される。観測の失敗は verdict に影響しない。

### 明示的に含めないもの

- `RESTING_ENTRY_FILL` / `UPDATE_PROTECTION` / `CANCEL_ORDER` / `CLOSE_POSITION` 経路の観測
- future-only shadow 追跡（別 change `b2-gate-shadow-counterfactual`）
- TTL の判断材料（`SafetyFloorRule` ではない）
- 較正ダッシュボード / calibration curve / p 較正の常設 UI
- 汎用の metrics / 監視基盤
