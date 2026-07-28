## Context

paper の resting BUY LIMIT は、発注時に取得した orderbook から「同一 limit 価格の exchange bid 数量 + 先行する同価格の自 paper order 数量」を `queueAheadBtc` として保存する。WebSocket の eligible SELL 数量が `queueAheadBtc + order.sizeBtc` に達した event でだけ maker 全量約定にする。つまり `queueAheadBtc` は paper 約定の因果的入力そのものであり、観測できない場合に 0 とみなすと約定を早く見積もる歪みが入る。このため `calculateQueueAhead` は限界価格が返却 bid depth の圏外なら注文自体を fail-closed にしている。

`preview_order` はこの計算を通らない。`placeOrder` 経路だけが `createRestingOrderMarketEligibility` → `calculateQueueAhead` を実行するため、preview accepted の直後に place が落ちるという観測されたパターンになる。

`GmoPublicMarketDataSource.getOrderbook(symbol, depth)` は GMO の `/public/v1/orderbooks` を depth 引数なしで叩き、response 全体を parse してから `take(depth)` する。depth は client 側の切り詰め幅にすぎない。

2026-07-27 の実測（bids 417 levels、best bid 10,668,649 円）:

| depth | 最深 bid からの乖離 |
|---|---|
| 50 | 0.080% |
| 100 | 0.263% |
| 150 | 2.049% |
| 200 | 5.722% |

逆に、best bid からの乖離率で必要な levels 数を引くと 0.1% → 61、0.3% → 103、1.0% → 124、2.0% → 148 となる。

`PAPER_EXECUTION_ORDERBOOK_DEPTH = 50` は queue_ahead 以外に `PaperBrokerRuntime.orderbookFor()` からも参照されており、そちらは `FillSimulator` の MARKET / LIMIT taker slippage walk と `SafetyFloor` の板参照に流れる。

## Goals / Non-Goals

**Goals:**

- LLM が実際に置く押し目 LIMIT の価格分布に対して、queue_ahead の観測範囲が構造的に不足しない状態にする
- どの require で fail-closed したかを audit payload から特定できるようにする
- paper 約定価格の算出規則（slippage walk、fallback 条件）を変えない

**Non-Goals:**

- `createRestingOrderMarketEligibility` の fail-closed 設計自体の変更。paper 真実性の観点で正しい
- depth 圏外の指値に対する保守的 fallback（板全量 + 自注文を queue_ahead とみなす等）の導入。約定を遅く見積もる歪みが入る
- `LlmFailureSafety.kt` の他の Codex 出力抑制（`CODEX_FAILURE_DETAILS_OMITTED` 等）の変更
- MCP tool `get_orderbook` の LLM 向け depth 上限（`mcp-gmo-coin` の 100）の変更。prompt 面の観測範囲は別問題
- 板 depth の永続化（`docs/design.md` L4064 の execution replay 課題）

## Decisions

### 1. queue_ahead 用 depth を既存定数から分離し、500 とする

`PAPER_EXECUTION_ORDERBOOK_QUEUE_DEPTH = 500` を新設し、`calculateQueueAhead` だけがこれを使う。`orderbookFor()` は `PAPER_EXECUTION_ORDERBOOK_DEPTH = 50` のまま据え置く。

分離する理由は、共通定数を深くすると `FillSimulator.limitTakerPrice` / `marketPrice` の walk が参照する levels が増え、`LIMIT_ORDERBOOK_DEPTH_EXHAUSTED_LOG_KEY` の発火条件と最終約定価格が変わるため。paper 約定価格の意味を変える変更は本 change の対象ではなく、変えるなら独立した paper truth の検討が要る。

500 を選ぶ理由:

- GMO が返す全 levels を上限なく観測することが、queue_ahead に関しては最も歪みが小さい。カバー幅を「実用レンジ」として人為的に切ると、その外側にある妥当な指値が引き続き fail-closed になり、同じ issue を将来また踏む
- 板の levels 数は日によって変動する（実測日は bids 417）。固定値で「1% 相当」を狙う設計は板形状の変化に対して脆い。500 は GMO の最大返却数を超える上限として機能し、実質「返却された全 levels」を意味する
- `take(depth)` の切り詰めしかしていないため、depth を上げても HTTP request 数と response サイズは変わらない。増えるのは client 側の domain object 生成数だけで、bids / asks 双方が `take(depth).map` されるため、queue_ahead が使わない ask 側も深く変換される。深い ask に malformed level があると orderbook 全体の parse が失敗し、注文が fail-closed になる条件がその分だけ増える

深い levels の bid を queue_ahead の入力に使うことは、paper 真実性を悪化させない。取引所が実際にその価格に置いている板を、切り詰めずに数えるだけであり、観測できていない数量を推定しているわけではない。

`GmoPublicMarketDataSource.MAX_ORDERBOOK_DEPTH` は 100 なので、500 を渡すと `validateLimit` で弾かれる。この定数も 500 へ引き上げる。`mcp-gmo-coin` 側の同名定数は LLM が prompt で参照する板の広さの上限であり、別の関心事なので変更しない。

### 2. 全 levels の圏外は引き続き fail-closed

depth 500 でも `limitPrice < minimumCoveredBid` になるケース（best bid から 88% 下といった極端な指値、または板が異常に薄い場合）は残る。この場合の挙動は現行どおり `QUEUE_SNAPSHOT_UNAVAILABLE` で注文を作らない。返却された板に存在しない価格レベルの queue は観測できておらず、0 とみなす根拠がないため。

### 3. no-trade audit の message は allowlist した diagnostic だけ残す

`buildNoTradeFailurePayload` から `isCodexProvider` 分岐と `messageOmitted` キーを撤去する。ただし例外 message を無条件に保存はしない。保存対象を「fukurou 自身のコードが生成した定型 diagnostic」に限定する。

当初は「全 message を `SecretRedactor.redactAndTruncate` に通して保存する」設計だったが、これは secret 境界を守れない。理由は 2 つある。

1. **cause の型集合が閉じていない**。`ToolCallGuard.runLockedTool` と `CallerNoTradeGuard.run` は `Throwable` を丸ごと catch し、`recordNoTradeExit` は任意の `Throwable?` を受ける。tool block・risk state repository・LLM process 境界のどこから来た例外でも通るため、message の内容を静的に列挙できない
2. **`SecretRedactor` は起動時に収集した既知値の完全一致置換**。対象 key pattern は `API_KEY` / `SECRET` / `TOKEN` / `PASSWORD` / `CREDENTIAL` の 5 つだけで、rotation 後の値、URL や base64 へ変形された credential、pattern 外の key に入った secret は伏字にならない。`DB_URL` に埋め込まれた接続文字列などがこれに当たる

そこで、message の保存は次の allowlist を満たす場合だけに限る。

- `QUEUE_SNAPSHOT_UNAVAILABLE:` で始まる broker 生成 diagnostic

allowlist に一致しない cause は `cause`（例外の simpleName）だけを残し、`message` キーを出力しない。`messageOmitted` のような provider 分岐マーカーは持たず、allowlist 判定の結果として message キーの有無が決まる。

この範囲で issue #320 の目的は達成される。今回診断できなかったのは「どの require で fail-closed したか」であり、それは全て `QUEUE_SNAPSHOT_UNAVAILABLE:` prefix を持つ broker 自身の文字列だからである。任意の provider 例外 message を開示する必要はない。

allowlist prefix を今後増やす場合は、その prefix を生成するコードが fukurou 自身のものであり、外部入力を message へ埋め込まないことを確認する。

`SecretRedactor` の配線は行わない。allowlist 済み文字列は fukurou 自身が生成した定数で secret を含まないため、redaction は不要であり、guard に redactor を渡す配線も不要になる。

### 4. PR 分割

- **PR-A（queue snapshot depth）**: depth 定数の分離・拡大、`MAX_ORDERBOOK_DEPTH` 引き上げ、`PaperBrokerTest` の回帰、`docs/mcp-runtime.md` の更新。レビュー観点は paper 真実性
- **PR-B（audit message）**: `messageOmitted` 撤去、redactor 配線、guard test の更新。レビュー観点は secret 境界

依存関係はない。PR-A は queue_ahead の観測範囲を広げ、PR-B は診断性の改善として独立に入る。OpenSpec change は PR-B 完了後に 1 回だけ archive する。

### 5. 過去 5 件の救済は保証しない

issue #320 が挙げた 5 件のうち、当時の指値と市場価格の対応が示されているのは run `5b16ff53` の 1 件だけで、それも「限界価格と 1 分足終値の差 -0.23%」という proxy である。発注時点の best bid でも、返却された bid levels の最深値でもない。残り 4 件については指値も同時点の板も提示されていない。

板 depth は永続化されていない（`docs/design.md` の execution replay の節が同じ制約を記録している）ため、過去の 5 件が depth 500 で受理されたかを事後に検証する手段は存在しない。

したがって本 change の主張は「観測された押し目 LIMIT の分布（現在値の 0.1〜0.3% 下）に対して queue_ahead の観測範囲が構造的に不足している状態を解消する」までとし、「過去 5 件が救済される」とは主張しない。deploy 後は PR-B が可視化する `QUEUE_SNAPSHOT_UNAVAILABLE` の内訳から、同じ reason の再発有無を既存 audit で確認する。

## Risks / Trade-offs

- **先行注文の取消を反映しない conservative underfill の適用母数が増える**: queue_ahead は発注時点の exact-price 数量を 1 回だけ snapshot する。その後 exchange の先行注文が取り消されても queue は減らないため、実際より約定が遅く見積もられる。これは depth 50 のときから存在する既知の限界（`docs/mcp-runtime.md` に「partial fill と先行 exchange order cancellation は再現しない」と記載）だが、depth 拡大により従来 fail-closed で注文にすらならなかった価格帯へこのモデルが適用されるようになり、適用母数が増える。約定を早く見積もる方向の歪みではないため受容する
- **深い指値の queue_ahead が大きくなり、約定しにくくなる**: 実際にその価格に厚い板があるなら約定しにくいのが正しい。fail-closed で注文が存在しなかった従来より、paper と live の意味は近づく
- **admission 母集団の非連続（人間確認事項）**: 本 change 後は従来 fail-closed になっていた指値が order として成立するため、`place_order_failed` の発生率が下がり、resting entry の母数が増える。「depth 50 圏外の指値が注文にならなかった期間」と「注文になる期間」で母集団構成が変わり、cohort 上は両者を区別できない。

  execution semantics version（`PAPER_WS_V1`）は bump しない。約定規則そのもの（queue consumption による fill 判定）が不変であることに加え、このリポジトリでは #209 で version を定義して以降、#239（resting fill invariants の追加 = fill 可否の変更）を含む複数の admission 変更を経ても一度も bump されていない。depth 拡大は fill 判定を変えず観測範囲だけを広げるもので、#239 より影響が小さい。

  ただしこの判断は評価の連続性に関わるため、PR の人間確認事項として明示する。bump が必要と判断される場合は、deploy 時点で残る V1 open order が deploy 後に fill される mixed lineage、rollback、評価 query の新旧 version 認識を同時に設計する必要があり、本 change のスコープを超える
- **allowlist 外の失敗は cause 型名だけになる**: `caller_failed` / `tool_call_failed` など LLM process 境界の失敗では、引き続き message が audit に残らない。`QUEUE_SNAPSHOT_UNAVAILABLE` 以外の診断性ギャップは本 change では閉じない。必要になった時点で、その prefix を生成するコードの所有者を確認したうえで allowlist へ追加する
