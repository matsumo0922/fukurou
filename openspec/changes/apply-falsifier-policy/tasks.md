## 1. Policy resolution

- [ ] 1.1 `CONDITIONAL_NOT_APPLIED` reason と policy ごとの required/reason 解決を追加する
- [ ] 1.2 runtime config snapshot または typed config から安定した config identity を解決する
- [ ] 1.3 既存 decision の exact reuse と policy/config mismatch の fail-closed を実装する

## 2. Runtime wiring

- [ ] 2.1 in-memory / PostgreSQL の policy repository を `TradingRuntime` に配線する
- [ ] 2.2 `PaperBroker` が intent ごとの durable policy decision を SafetyFloor context に渡す

## 3. Runner application

- [ ] 3.1 entry intent 発行後に policy decision を保存し、失敗時は no-trade にする
- [ ] 3.2 required policy だけ Falsifier を起動し、OFF では falsification attribution を作らない
- [ ] 3.3 durable OFF decision から internal permit を作り preview/place command に束縛する
- [ ] 3.4 policy decision の machine-readable runner audit と authority に合う注文理由を追加する

## 4. SafetyFloor enforcement

- [ ] 4.1 fresh approval または exact OFF permit のどちらかだけを entry authority として受け入れる
- [ ] 4.2 permit 欠損、不一致、policy read failure、ALWAYS_ON/CONDITIONAL bypass を拒否する
- [ ] 4.3 intent consumption、intent payload、資金保護 rule が不変であることを確認する

## 5. Tests and documentation

- [ ] 5.1 policy resolution/reuse/config mismatch の unit test を追加する
- [ ] 5.2 runner の ALWAYS_ON/OFF/CONDITIONAL と persistence failure の回帰テストを追加する
- [ ] 5.3 broker/SafetyFloor の正規 permit、MCP permit 欠損、tamper、read failure の回帰テストを追加する
- [ ] 5.4 PostgreSQL runtime wiring と durable readback の integration test を更新する
- [ ] 5.5 `docs/mcp-runtime.md` を on/off 適用済み・conditional 未適用の現在形へ更新する
- [ ] 5.6 関連 docs grep、OpenSpec strict validation、関連 test、detekt、build を実行する
