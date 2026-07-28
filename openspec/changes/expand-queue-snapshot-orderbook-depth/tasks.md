## 1. PR-A — Queue snapshot depth の分離と拡大

- [ ] 1.1 `PaperBroker.kt` に queue_ahead 専用の `PAPER_EXECUTION_ORDERBOOK_QUEUE_DEPTH = 500` を新設し、`calculateQueueAhead` の `getOrderbook` 呼び出しだけをこの定数へ差し替える
- [ ] 1.2 `orderbookFor()` が使う `PAPER_EXECUTION_ORDERBOOK_DEPTH = 50` を据え置き、`FillSimulator` / `SafetyFloor` の板参照が変化しないことを確認する
- [ ] 1.3 `GmoPublicMarketDataSource.MAX_ORDERBOOK_DEPTH` を 500 へ引き上げ、`mcp-gmo-coin` 側の同名定数は変更しない

## 2. PR-A — 回帰テスト

- [ ] 2.1 `PaperBrokerTest` の fake market data source に、production と同じく `bids.take(depth)` / `asks.take(depth)` で切り詰める実装を用意する。depth を無視する既存 fake のままでは、定数を差し替えなくてもテストが通ってしまい regression を証明できない
- [ ] 2.2 bid levels 60 段の fake orderbook で 55 段目の価格へ resting BUY LIMIT を置き、注文が受理されて open order が作られる scenario を追加する。旧 depth 50 のままではこの scenario が `QUEUE_SNAPSHOT_UNAVAILABLE` で落ちることを、実装前に一度確認する
- [ ] 2.3 保存された `queueAheadBtc` は `Order` read model に露出していないため、値の検証は event sequence で行う。同価格の bid 数量 + 自注文 size に満たない SELL 数量では約定せず、到達した event で約定することを確認する
- [ ] 2.4 同 fake で全 levels より低い指値が `QUEUE_SNAPSHOT_UNAVAILABLE: limit price is outside returned bid depth.` で fail-closed し、open order が作られない scenario を追加する
- [ ] 2.5 fake が `getOrderbook` に渡された depth を記録し、queue_ahead 経路が queue depth を、`orderbookFor` 経路が execution depth を要求することを検証する
- [ ] 2.6 `GmoPublicMarketDataSourceTest` に depth 500 が `validateLimit` を通ることの回帰を追加する

## 3. PR-A — ドキュメントと検証

- [ ] 3.1 `docs/mcp-runtime.md` の「板 depth 外」fail-closed 記述を、queue_ahead が返却 bid levels 全体を観測し、その全体の圏外だけ fail-closed になる現在形へ更新する
- [ ] 3.2 `make test` / `make detekt` を実行する
- [ ] 3.3 PR-A description に次を記載する。「ドキュメント影響: あり（docs/mcp-runtime.md）」／ OpenSpec change は PR-B 完了まで archive しないこと ／ 人間確認事項として「admission 母集団が変わるが `PAPER_WS_V1` は bump しない」判断と、過去 5 件の救済は板 snapshot 不在で検証不能であること

## 4. PR-B — `messageOmitted` の撤去と diagnostic allowlist

- [ ] 4.1 `NoTradeAuditPayload` に、fukurou 自身が生成した定型 diagnostic を判定する allowlist を導入する。初期要素は `QUEUE_SNAPSHOT_UNAVAILABLE:` prefix のみとし、判定関数に「allowlist へ追加してよいのは外部入力を埋め込まない fukurou 生成文字列に限る」旨の KDoc を付ける
- [ ] 4.2 `buildNoTradeFailurePayload` から `isCodexProvider` 分岐と `messageOmitted` キーを撤去し、allowlist に一致する message だけを `message` キーへ出力する。一致しない場合は `message` キー自体を出力しない
- [ ] 4.3 `llmProvider` 引数が他の用途で使われていなければ signature から外し、`ToolCallGuard` / `CallerNoTradeGuard` の呼び出し側を追従させる

## 5. PR-B — テストと検証

- [ ] 5.1 `CallerNoTradeGuardTest` の `messageOmitted` assertion を撤去し、provider によらず (a) allowlist 一致 message が記録される (b) 非一致 message では `message` キーが出力されない、の 2 scenario へ置き換える
- [ ] 5.2 Codex provider で `QUEUE_SNAPSHOT_UNAVAILABLE:` を含む cause が記録され、任意文字列の cause では message が出ないことを検証する
- [ ] 5.3 `OneShotLlmRunnerTest` L2556 / L2598 付近の `messageOmitted` assertion を更新する
- [ ] 5.4 `make test` / `make detekt` を実行する
- [ ] 5.5 PR-B description に「ドキュメント影響: なし」と、allowlist 外の失敗（`caller_failed` / `tool_call_failed` 等）は引き続き cause 型名だけになるという既知の限界を記載する

## 6. Archive

- [ ] 6.1 PR-B マージ後に `openspec archive expand-queue-snapshot-orderbook-depth` を 1 回だけ実行する
