## 1. Policy resolution

- [x] 1.1 `CONDITIONAL_NOT_APPLIED` / `ADD_LONG_REQUIRES_FALSIFIER` と policy/action ごとの canonical attributes 解決を追加する
- [x] 1.2 typed config の canonical hash を再計算し runtime config snapshot と照合する config identity を実装する
- [x] 1.3 既存 decision の全 attributes exact reuse と mismatch の fail-closed を実装する

## 2. Runtime and runner foundation

- [x] 2.1 in-memory / PostgreSQL の policy repository を `TradingRuntime` に配線する
- [x] 2.2 entry intent 発行後に policy decision を保存し、失敗時は Falsifier と paper entry を開始せず no-trade にする
- [x] 2.3 `OFF_V1` / `ENTER` の durable decision から internal-only permit を生成して runner audit に束縛する
- [x] 2.4 ALWAYS_ON、CONDITIONAL、OFF ADD_LONG が permit を生成せず既存 Falsifier gate を維持することを確認する

## 3. Tests and documentation

- [x] 3.1 canonical policy attributes、reuse、snapshot/config hash mismatch の unit test を追加する
- [x] 3.2 runner の policy decision persistence、OFF permit foundation、persistence failure の回帰テストを追加する
- [x] 3.3 PostgreSQL runtime wiring と durable readback の integration test を更新する
- [x] 3.4 `docs/mcp-runtime.md` を policy attribution/permit foundation と既存 gate 維持の現在形へ更新する
- [x] 3.5 関連 docs grep、OpenSpec strict validation、関連 test、detekt、build を実行する

## Deferred

OFF ENTER の Falsifier skip、SafetyFloor/Broker durable permit verification、place-lock open-position 再検査、`runner-place-v2-` namespace、replay fingerprint、outcome unknown classification は後続 enforcement change で扱う。
