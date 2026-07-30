## 1. Internal authority and identity

- [ ] 1.1 実装前に human-authored diff を見積もり、1,000 行超が確実なら inactive authority plumbing と skip activation の stacked 2 PR に分ける
- [ ] 1.2 `FalsifierPolicyPermit` を `PlaceOrderCommand` の internal-only path へ渡し、MCP/OpenAPI/JSON wire schema が変わらないことを確認する
- [ ] 1.3 normalized business fields と permit 全 identity から canonical SHA-256 `runner-place-v2-<hash>` を導出する
- [ ] 1.4 policy decision repository を PaperBroker runtime へ配線し、permit と durable decision/event の全 identity を typed authority として検証する

## 2. Broker and SafetyFloor enforcement

- [ ] 2.1 `runner-place-v2-` namespace を OFF permit 専用に予約し、既存 result lookup 前に新規/replay双方の permit、durable authority、fingerprintを検証する
- [ ] 2.2 SafetyFloor context に broker-verified authority を渡し、OFF ENTER の場合だけ fresh falsification 条件を置換する
- [ ] 2.3 intent consumption と他の SafetyFloor rule の順序・意味を維持し、permit のない MCP caller と non-OFF action を fresh approval gate へ残す
- [ ] 2.4 OFF ENTER の place/intent consumption 排他境界内で open position 0 件を再検査する

## 3. Runner application and failure semantics

- [ ] 3.1 foundation の decision/permit を entry flow へ引き継ぎ、canonical OFF ENTER だけ Falsifier invocation と phase observation を省略する
- [ ] 3.2 OFF preview/place command に internal permit と v2 ID を設定し、fresh approval path の既存 namespace を維持する
- [ ] 3.3 OFF entry の監査理由を policy bypass として記録し、実行していない Falsifier approval を記録しない
- [ ] 3.4 policy authority failure を side effect 前の no-trade と commit possibility 後の outcome unknown に分離し、ToolCallGuard の新しい pre-mutation eventを追加しない
- [ ] 3.5 durable decision/event と order intent ID / fingerprinted client request ID から exact authority を復元できる retry だけ既存結果を返す

## 4. Tests and documentation

- [ ] 4.1 runner test で OFF ENTER の Falsifier skip、ALWAYS_ON/CONDITIONAL/ADD_LONG の fresh approval、policy failure の fail closed を検証する
- [ ] 4.2 SafetyFloor/Broker test で全 identity mismatch、permit なし MCP caller、他 safety rule と intent consumption の維持を検証する
- [ ] 4.3 broker concurrency/replay test で place-lock 内の position race、v2 新規/replay validation、payload/authority fingerprint mismatch を検証する
- [ ] 4.4 post-place test で commit possibility 後の ACK/completion/authority failure が no-trade ではなく outcome unknown になることを検証する
- [ ] 4.5 PostgreSQL runtime wiring と durable decision readback の integration test を更新する
- [ ] 4.6 `docs/mcp-runtime.md` を OFF enforcement と activation禁止の現在形へ更新し、関連用語を docs/ と README で grep する
- [ ] 4.7 OpenSpec strict validation、関連 test、admission isolation regression、detekt、build を exact HEAD で実行する
