## Why

Issue #207 の on/off 実験を安全に開始するには、foundation が保存する `OFF_V1` / `ENTER` permit を、Falsifier の省略と paper order の実行権限へ一貫して適用する必要がある。
runner 内の分岐だけを信用せず、SafetyFloor と broker が durable decision と command identity を独立に検証し、別 caller や replay からの bypass を防ぐ。

## What Changes

- canonical な `OFF_V1` / `ENTER` permit を runner の internal command path に束縛し、その場合だけ Falsifier を省略する
- `ALWAYS_ON_V1`、`CONDITIONAL_V1`、`ADD_LONG` は fresh `APPROVED` を引き続き必須とする
- SafetyFloor と broker が durable policy decision の全 identity と permit を再検証する
- place lock 内で open position が 0 件であることを再検査し、preview/place 間の action 変化を fail closed にする
- `runner-place-v2-<sha256>` namespace を OFF permit 専用に予約し、既存結果の lookup 前に permit と canonical command fingerprint を新規・replay の双方で検証する
- policy repository failure は新規副作用前なら fail closed、paper commit の可能性を否定できない後続 failure は outcome unknown とする
- runtime docs と authority/replay の回帰テストを同じ変更で更新する

## Capabilities

### New Capabilities

- `falsifier-policy-enforcement`: OFF ENTER の internal permit を Falsifier、SafetyFloor、broker の paper entry authority として適用する契約

### Modified Capabilities

なし。

## Impact

- `OneShotLlmRunner` の Falsifier phase selection と internal place command
- `PlaceOrderCommand` の internal-only authority、`PaperBroker` の replay/placement gate
- `SafetyFloorContext` と fresh falsification gate
- policy decision repository の broker runtime wiring
- runner、broker、SafetyFloor、PostgreSQL wiring のテスト
- `docs/mcp-runtime.md`

MCP wire schema と外部 API は変更しない。
`CONDITIONAL_V1`、shadow/evaluation、production activation、live trading は後続または scope 外である。
