## Why

Issue #320: falsifier が APPROVED を出した押し目 LIMIT entry が `place_order` 段階で `IllegalArgumentException` により fail-closed し、trade にならないケースが production で 5 回発生している（07-12 / 07-26 x3 / 07-27）。いずれも proposer ENTER → falsifier APPROVED → `preview_order` accepted → 直後の `place_order` 失敗という同じ event pattern を示す。

`preview_order` は queue_ahead を計算せず、`placeOrder` 経路だけが `createRestingOrderMarketEligibility` を通るため、preview accepted の直後に place が落ちる形になる。ただし audit payload が Codex provider の message を省略していたため、5 件が同関数の 7 つの require のどれで落ちたかは事後に特定できない。

特定できないまま残る問題として、`PaperBroker.calculateQueueAhead` が queue_ahead 算出用に取得する orderbook depth が 50 levels に固定されている。この 50 は以下の require の判定範囲を決める。

```kotlin
require(limitPrice >= minimumCoveredBid) {
    "QUEUE_SNAPSHOT_UNAVAILABLE: limit price is outside returned bid depth."
}
```

`getOrderbook` は depth を API へ渡さず、全 levels を取得してから client 側で `take(depth)` している。depth は取引所への要求ではなく、返却済みデータの切り詰め幅にすぎない。2026-07-27 の実測では bids が 417 levels あり、そのうち depth 50 が覆うのは best bid から 0.080%、depth 100 でも 0.263% だった。報告された 5 件のうち唯一価格が判る run `5b16ff53` は、限界価格と 1 分足終値の差が -0.23% である。

つまり client 側の 50 固定は、取引所が実際に板を出している価格帯の指値を観測範囲から除外し得る。depth を拡大しても取得する response は同一であり、HTTP request 数と response サイズは変わらない。

fail-closed 設計自体は paper 真実性（queue_ahead は paper 約定の因果的入力であり、観測できない場合に注文を作らない）の観点で正しい。修正対象は観測範囲だけに限る。

併せて、`buildNoTradeFailurePayload` が Codex provider の場合に例外 message を `messageOmitted: true` で落とすため、今回の調査では「どの require で落ちたか」を audit から特定できず、乖離幅からの消去法が必要だった。message は `QUEUE_SNAPSHOT_UNAVAILABLE: ...` 等の定型文字列であり、Epic #286 の「診断性を犠牲にする出力隠蔽」の残党に該当する。

## What Changes

- queue_ahead 算出専用の orderbook depth 定数を新設し、GMO が返す全 levels（500）を観測範囲とする。`PaperBroker.calculateQueueAhead` だけがこの定数を使う。
- `orderbookFor()`（`FillSimulator` の MARKET / LIMIT taker slippage walk、`SafetyFloor` の板参照）が使う既存 `PAPER_EXECUTION_ORDERBOOK_DEPTH = 50` は変更しない。paper 約定価格の算出規則を本 change では変えない。
- `GmoPublicMarketDataSource.MAX_ORDERBOOK_DEPTH` を 100 から 500 へ引き上げる。LLM 向け MCP tool 側の `MAX_ORDERBOOK_DEPTH = 100`（`mcp-gmo-coin`）は prompt 面の上限であり変更しない。
- `buildNoTradeFailurePayload` の Codex 分岐と `messageOmitted` キーを撤去し、`PaperBroker` / `ExposedPaperLedgerWriter` が生成する 10 個の固定 diagnostic 文字列と**完全一致**する message だけを provider によらず記録する。allowlist 外の cause は例外型名だけを残す。prefix 一致では外部由来の例外が secret を混入した文字列で通過し得るため採らない。
- 2 PR 構成とする。PR-A: queue snapshot depth の拡大と回帰テスト。PR-B: `messageOmitted` の撤去と diagnostic allowlist の導入。互いに独立で、レビュー観点（paper 真実性 / secret 境界）が異なる。

## Capabilities

### New Capabilities

- `paper-queue-snapshot-depth`: paper resting BUY LIMIT の queue_ahead 観測範囲と、その範囲外に対する fail-closed 境界を定義する。

### Modified Capabilities

- `llm-cli-invocation-contract`: no-trade audit payload が provider 分岐を持たず、allowlist した定型 diagnostic だけを保持することを明記する。

## Impact

- `trading/src/main/kotlin/me/matsumo/fukurou/trading/broker/PaperBroker.kt`: queue_ahead 用 depth 定数の新設と `calculateQueueAhead` の参照差し替え
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/exchange/gmo/GmoPublicMarketDataSource.kt`: `MAX_ORDERBOOK_DEPTH` の引き上げ
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/tool/NoTradeAuditPayload.kt`: Codex 分岐の撤去と diagnostic allowlist の導入
- `trading/src/test/kotlin/.../broker/PaperBrokerTest.kt`: depth 50 圏外・新 depth 圏内の受理と、全 levels 圏外の fail-closed 継続の回帰テスト
- `trading/src/test/kotlin/.../tool/CallerNoTradeGuardTest.kt` / `runner/OneShotLlmRunnerTest.kt`: `messageOmitted` assertion の更新
- `docs/mcp-runtime.md`: 「板 depth 外」の fail-closed 記述を現在の観測範囲へ更新
- 破壊的変更: なし。既存 order・execution・評価集計を書き換えない
- 依存: なし
- residual risk（ユーザー確認済み）: depth 拡大により resting entry の admission 母集団が変わるが、execution semantics version（`PAPER_WS_V1`）は bump しない。評価の連続性に関わる判断のため PR で明示する
- 本 change は過去 5 件の retrospective な救済を主張しない。発注時点の板 depth が永続化されていないため事後検証できない
