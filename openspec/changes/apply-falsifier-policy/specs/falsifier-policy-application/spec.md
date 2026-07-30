## ADDED Requirements

### Requirement: entry intent ごとに policy decision を適用前に固定する

runner は entry intent ごとに active process の policy と runtime config identity を含む durable policy decision を、Falsifier 起動または省略より前に保存しなければならない（MUST）。

#### Scenario: ALWAYS_ON decision を保存する

- **WHEN** `ALWAYS_ON_V1` で entry intent を処理する
- **THEN** runner は `required=true` と `ALWAYS_ON` reason を保存してから Falsifier を起動する

#### Scenario: OFF decision を保存する

- **WHEN** `OFF_V1` で `ENTER` intent を処理する
- **THEN** runner は `required=false` と `POLICY_OFF` reason を保存してから Falsifier を省略する

#### Scenario: ADD_LONG は OFF でも Falsifier を要求する

- **WHEN** `OFF_V1` で `ADD_LONG` intent を処理する
- **THEN** runner は `required=true` と `ADD_LONG_REQUIRES_FALSIFIER` reason を保存して Falsifier を必須にする

#### Scenario: CONDITIONAL は未適用として閉じる

- **WHEN** `CONDITIONAL_V1` で entry intent を処理する
- **THEN** runner は未適用 reason を伴う `required=true` decision を保存し、Falsifier を必須にする

### Requirement: retry は既存 policy attribution を変更しない

runner は同じ intent の既存 policy decision を exact readback し、現在の policy/action から導出した policy、required、reasonCodes と runtime config identity が全て一致する場合だけ再利用しなければならない（MUST）。

#### Scenario: 同一 snapshot の再実行

- **WHEN** 同じ intent、policy、runtime config version/hash で entry flow を再実行する
- **THEN** runner は既存 decision を再利用し、decision/event を増やさない

#### Scenario: config switch 後の再実行

- **WHEN** intent に保存済みの policy decision と現在の policy または runtime config identity が異なる
- **THEN** runner は既存 decision を上書きせず entry を no-trade にする

#### Scenario: canonical attributes が不一致

- **WHEN** policy と config identity は一致するが required または reasonCodes が canonical 組合せと異なる
- **THEN** runner は既存 decision を再利用せず entry を no-trade にする

#### Scenario: 新規 entry 前の policy persistence failure

- **WHEN** 新規 entry の policy decision 保存または exact readback が失敗する
- **THEN** runner は Falsifier と paper entry を開始せず no-trade にする

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
- **THEN** runner は固定 namespace の version ID と canonical typed config hash を使用する

### Requirement: OFF bypass は runner permit と durable decision に束縛する

SafetyFloor は fresh `APPROVED` がない `ENTER` を、wire から設定できない internal permit と canonical durable `OFF_V1 / required=false / POLICY_OFF` decision の identity が完全一致する場合だけ許可しなければならない（MUST）。

#### Scenario: 正規の OFF entry

- **WHEN** runner が durable `required=false / OFF_V1` decision から作った permit で同じ intent を preview/place する
- **THEN** SafetyFloor は fresh falsification 条件だけを満たしたものとして他の全 rule を評価する

#### Scenario: permit がない

- **WHEN** fresh `APPROVED` がなく、MCP caller または別 caller が permit なしで既存 OFF intent を preview/place する
- **THEN** SafetyFloor は `MISSING_FRESH_FALSIFICATION` で拒否する

#### Scenario: permit と durable decision が不一致

- **WHEN** decision ID、intent ID、action、policy、runtime config version/hash、required、reasonCodes のいずれかが一致しない
- **THEN** SafetyFloor は entry を拒否する

#### Scenario: 新規 entry で durable decision を読めない

- **WHEN** 新規 preview/place で broker が policy decision を読めない、または監査片側欠損を検出する
- **THEN** preview/place は失敗し、entry 副作用を作らない

#### Scenario: ADD_LONG に OFF permit を渡す

- **WHEN** fresh `APPROVED` のない `ADD_LONG` に OFF permit を渡す
- **THEN** SafetyFloor は entry を拒否する

#### Scenario: preview 後に position が出現する

- **WHEN** OFF ENTER の preview 後、place lock 取得前に resting BUY が約定して open position が出現する
- **THEN** place 時の SafetyFloor は OFF bypass を拒否し、買い増しを作らない

### Requirement: runner replay identity は command と authority に一致する

OFF ENTER runner は normalized order business fields と policy authority の canonical SHA-256 fingerprint を予約済み client request namespace に使用し、broker は既存 result lookup より前に同じ internal permit から fingerprint を再計算できる場合だけ新規作成または既存 replay を許可しなければならない（MUST）。

#### Scenario: exact internal retry

- **WHEN** runner が同じ intent、normalized command fields、policy permit から同じ fingerprinted client request を retry する
- **THEN** broker は既存 result を mutation なしで返す

#### Scenario: wire caller が runner ID を再利用する

- **WHEN** internal permit を持たない MCP caller が既存 `runner-place-v2-` client request ID を別 intent または payload で送る
- **THEN** broker は既存 accepted result を返さず拒否する

#### Scenario: wire caller が未使用 runner ID を送る

- **WHEN** internal permit を持たない MCP caller が fresh `APPROVED` と正しい未使用 `runner-place-v2-` client request ID を送る
- **THEN** broker は既存 lookup より前に拒否し、新規 order を作らない

#### Scenario: internal retry の payload が違う

- **WHEN** permit は同じでも数量、価格、STOP/TP、time stop その他の normalized business field が異なる
- **THEN** fingerprint が一致せず broker は既存 result を返さない

#### Scenario: authority recovery

- **WHEN** commit 後の retry を監査する
- **THEN** durable order の intent ID、permit 専用 fingerprinted client request ID、policy decision/event から元 authority を特定できる

#### Scenario: commit の可能性がある recovery failure

- **WHEN** place 実行後の completion audit/ACK を失い、policy authority を再構築できない
- **THEN** runner は結果を outcome unknown とし、no-trade を記録しない

#### Scenario: durable result がない policy repository outage

- **WHEN** 同じ fingerprinted client request の durable result がなく policy decision を読めない
- **THEN** broker は fail closed し、order を作らない

### Requirement: ALWAYS_ON の既存 gate を維持する

`ALWAYS_ON_V1` と未適用の `CONDITIONAL_V1` は fresh `APPROVED` falsification を要求し、policy permit で省略してはならない（MUST NOT）。

#### Scenario: ALWAYS_ON で approval がない

- **WHEN** `ALWAYS_ON_V1` decision の intent に fresh `APPROVED` がない
- **THEN** runner は paper entry を開始しない

#### Scenario: CONDITIONAL で approval がない

- **WHEN** `CONDITIONAL_V1` decision の intent に fresh `APPROVED` がない
- **THEN** runner は paper entry を開始しない

### Requirement: audit は実際の authority を表す

system は policy decision と runner phase audit に policy、required、reason、decision ID、runtime config identity を残し、Falsifier を省略した entry に Falsifier approval を記録してはならない（MUST NOT）。

#### Scenario: OFF で Falsifier を省略する

- **WHEN** `OFF_V1` が entry を許可する
- **THEN** Falsifier invocation、falsification record、Falsifier phase observation は作られず、注文理由は policy bypass を表す

#### Scenario: ALWAYS_ON で Falsifier を実行する

- **WHEN** `ALWAYS_ON_V1` が fresh `APPROVED` を得て entry を許可する
- **THEN** 従来の Falsifier attribution と、別の policy decision audit の両方が残る
