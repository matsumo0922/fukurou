## ADDED Requirements

### Requirement: entry intent ごとに policy attribution を適用前に固定する

runner は entry intent ごとに active process の policy と runtime config identity を含む durable policy decision を、Falsifier 起動前に保存しなければならない（MUST）。この change は Falsifier と paper entry の既存 gate を変更してはならない（MUST NOT）。

#### Scenario: ALWAYS_ON decision を保存する

- **WHEN** `ALWAYS_ON_V1` で entry intent を処理する
- **THEN** runner は `required=true` と `ALWAYS_ON` reason を保存してから従来どおり Falsifier を起動する

#### Scenario: OFF ENTER decision を保存する

- **WHEN** `OFF_V1` で `ENTER` intent を処理する
- **THEN** runner は `required=false` と `POLICY_OFF` reason を保存するが、この change では従来どおり Falsifier を起動する

#### Scenario: ADD_LONG は OFF でも Falsifier を要求する

- **WHEN** `OFF_V1` で `ADD_LONG` intent を処理する
- **THEN** runner は `required=true` と `ADD_LONG_REQUIRES_FALSIFIER` reason を保存し、従来どおり Falsifier を起動する

#### Scenario: CONDITIONAL は未適用として閉じる

- **WHEN** `CONDITIONAL_V1` で entry intent を処理する
- **THEN** runner は `required=true` と `CONDITIONAL_NOT_APPLIED` reason を保存し、従来どおり Falsifier を起動する

### Requirement: retry は既存 policy attribution を変更しない

runner は同じ intent の既存 policy decision を exact readback し、action、policy、required、reasonCodes と runtime config identity が canonical attributes と全て一致する場合だけ再利用しなければならない（MUST）。

#### Scenario: 同一 snapshot の再実行

- **WHEN** 同じ intent、policy、runtime config version/hash で entry flow を再実行する
- **THEN** runner は既存 decision を再利用し、decision/event を増やさない

#### Scenario: config switch 後の再実行

- **WHEN** intent に保存済みの policy decision と現在の policy または runtime config identity が異なる
- **THEN** runner は既存 decision を上書きせず entry を no-trade にする

#### Scenario: canonical attributes が不一致

- **WHEN** policy と config identity は一致するが action、required、または reasonCodes が canonical 組合せと異なる
- **THEN** runner は既存 decision を再利用せず entry を no-trade にする

### Requirement: config identity は typed config と一致する

runner は typed `TradingBotConfig` の canonical runtime key/value hash を再計算し、注入された runtime config snapshot hash と一致する場合だけ policy decision に使用しなければならない（MUST）。

#### Scenario: production snapshot が一致する

- **WHEN** snapshot hash と typed config の canonical hash が一致する
- **THEN** runner は snapshot version ID / hash を policy decision に保存する

#### Scenario: production snapshot が不一致

- **WHEN** snapshot hash と typed config の canonical hash が一致しない
- **THEN** runner は policy decision と Falsifier と paper entry を作らず no-trade にする

#### Scenario: direct runner に snapshot がない

- **WHEN** direct/test runner が snapshot なしで起動する
- **THEN** runner は `process-config-v1` と canonical typed config hash を使用する

### Requirement: OFF permit は internal-only foundation である

runner は durable `OFF_V1 / ENTER / required=false / POLICY_OFF` decision の全 identity から immutable internal permit を作らなければならない（MUST）。permit を MCP wire schema または `PlaceOrderCommand` に露出してはならない（MUST NOT）。

#### Scenario: 正規の OFF decision

- **WHEN** runner が canonical OFF ENTER decision を durable readback または保存する
- **THEN** runner は decision identity と完全一致する internal permit を作り、内部 audit context に残す

#### Scenario: non-OFF または ADD_LONG decision

- **WHEN** decision が ALWAYS_ON、CONDITIONAL、または OFF ADD_LONG である
- **THEN** runner は OFF permit を作らない

#### Scenario: MCP caller

- **WHEN** MCP caller が preview/order request を送る
- **THEN** request schema は policy permit field を含まず、既存 fresh-approval gate が適用される

### Requirement: enforcement は後続 change まで deferred する

system はこの change で Falsifier を省略せず、SafetyFloor/Broker の authority、`runner-place-v2-` namespace、placement-lock 再検査、replay fingerprint、post-place outcome classification を変更してはならない（MUST NOT）。

#### Scenario: OFF foundation deploy 後の paper entry

- **WHEN** `OFF_V1` decision が保存されている
- **THEN** Falsifier fresh `APPROVED` がない paper entry は既存 SafetyFloor gate で拒否される
