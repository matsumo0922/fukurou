## 1. Policy resolution

- [ ] 1.1 `CONDITIONAL_NOT_APPLIED` / `ADD_LONG_REQUIRES_FALSIFIER` と policy/action ごとの canonical attributes 解決を追加する
- [ ] 1.2 typed config の canonical hash を再計算し runtime config snapshot と照合する config identity を実装する
- [ ] 1.3 既存 decision の全 attributes exact reuse と mismatch の fail-closed を実装する

## 2. Runtime wiring

- [ ] 2.1 in-memory / PostgreSQL の policy repository を `TradingRuntime` に配線する
- [ ] 2.2 `PaperBroker` が intent ごとの durable policy decision を SafetyFloor context に渡す

## 3. Runner application

- [ ] 3.1 entry intent 発行後に policy decision を保存し、失敗時は no-trade にする
- [ ] 3.2 required policy と ADD_LONG だけ Falsifier を起動し、OFF ENTER では falsification attribution を作らない
- [ ] 3.3 durable decision から action を含む internal permit を作り preview/place command に束縛する
- [ ] 3.4 normalized command と OFF permit の SHA-256 から予約済み runner v2 client request ID を導出する
- [ ] 3.5 policy decision の machine-readable runner audit と authority に合う注文理由を追加する

## 4. SafetyFloor enforcement

- [ ] 4.1 fresh approval または exact OFF permit のどちらかだけを entry authority として受け入れる
- [ ] 4.2 permit 欠損、不一致、policy read failure、ADD_LONG/ALWAYS_ON/CONDITIONAL bypass を拒否する
- [ ] 4.3 OFF ENTER を place lock 内の open position 0 件に束縛する
- [ ] 4.4 runner v2 client request は既存 lookup 前に permit と fingerprint を検証し、新規作成と replay を予約する
- [ ] 4.5 intent consumption、intent payload、資金保護 rule が不変であることを確認する

## 5. Tests and documentation

- [ ] 5.1 canonical policy attributes、reuse、snapshot/config hash mismatch の unit test を追加する
- [ ] 5.2 runner の ALWAYS_ON/OFF/CONDITIONAL と persistence failure の回帰テストを追加する
- [ ] 5.3 broker/SafetyFloor の正規 permit、MCP permit 欠損、tamper、ADD_LONG、ENTER TOCTOU、read failure の回帰テストを追加する
- [ ] 5.4 exact internal replay、MCP の未使用/既存 v2 namespace collision、payload mismatch、outcome unknown の回帰テストを追加する
- [ ] 5.5 PostgreSQL runtime wiring と durable readback の integration test を更新する
- [ ] 5.6 `docs/mcp-runtime.md` を ENTER on/off 適用済み・ADD_LONG/conditional 未適用の現在形へ更新する
- [ ] 5.7 関連 docs grep、OpenSpec strict validation、関連 test、detekt、build を実行する
