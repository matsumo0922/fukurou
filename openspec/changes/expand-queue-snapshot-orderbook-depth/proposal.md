## Why

Issue #320: falsifier が APPROVED を出した押し目 LIMIT entry が `place_order` 段階で `IllegalArgumentException` により fail-closed し、trade にならないケースが production で 5 回発生している（07-12 / 07-26 x3 / 07-27）。

原因は `PaperBroker.calculateQueueAhead` が queue_ahead 算出用に取得する orderbook depth が 50 levels に固定されていることにある。`preview_order` は queue_ahead を計算しないため accepted=true を返し、その 100〜200ms 後の `place_order` だけが以下の require で落ちる。

```kotlin
require(limitPrice >= minimumCoveredBid) {
    "QUEUE_SNAPSHOT_UNAVAILABLE: limit price is outside returned bid depth."
}
```

GMO 公開 API の実測（2026-07-27）では、`getOrderbook` は depth を API へ渡さず全 levels を取得してから `take(depth)` している。この日の bids は 417 levels あり、depth 50 のカバー幅は best bid から 0.080%、depth 100 でも 0.263% に留まる。LLM の押し目 LIMIT は現在値の 0.1〜0.3% 下に置かれることが多く、観測範囲がこの分布に対して構造的に狭い。depth を拡大しても取得する response は同一であり、API コストは増えない。

fail-closed 設計自体は paper 真実性（queue_ahead は paper 約定の因果的入力であり、観測できない場合に注文を作らない）の観点で正しい。修正対象は観測範囲だけに限る。

併せて、`buildNoTradeFailurePayload` が Codex provider の場合に例外 message を `messageOmitted: true` で落とすため、今回の調査では「どの require で落ちたか」を audit から特定できず、乖離幅からの消去法が必要だった。message は `QUEUE_SNAPSHOT_UNAVAILABLE: ...` 等の定型文字列であり、Epic #286 の「診断性を犠牲にする出力隠蔽」の残党に該当する。

## What Changes

- queue_ahead 算出専用の orderbook depth 定数を新設し、GMO が返す全 levels（500）を観測範囲とする。`PaperBroker.calculateQueueAhead` だけがこの定数を使う。
- `orderbookFor()`（`FillSimulator` の MARKET / LIMIT taker slippage walk、`SafetyFloor` の板参照）が使う既存 `PAPER_EXECUTION_ORDERBOOK_DEPTH = 50` は変更しない。paper 約定価格の算出規則を本 change では変えない。
- `GmoPublicMarketDataSource.MAX_ORDERBOOK_DEPTH` を 100 から 500 へ引き上げる。LLM 向け MCP tool 側の `MAX_ORDERBOOK_DEPTH = 100`（`mcp-gmo-coin`）は prompt 面の上限であり変更しない。
- `buildNoTradeFailurePayload` の Codex 分岐と `messageOmitted` キーを撤去し、fukurou 自身が生成した定型 diagnostic（`QUEUE_SNAPSHOT_UNAVAILABLE:` prefix）に一致する message だけを provider によらず記録する。allowlist 外の cause は例外型名だけを残す。
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
- 人間確認事項: depth 拡大により resting entry の admission 母集団が変わるが、execution semantics version（`PAPER_WS_V1`）は bump しない。評価の連続性に関わる判断のため PR で明示する
