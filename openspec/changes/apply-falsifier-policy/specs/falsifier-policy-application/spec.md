## ADDED Requirements

### Requirement: entry intent ごとに policy decision を適用前に固定する

runner は entry intent ごとに active process の policy と runtime config identity を含む durable policy decision を、Falsifier 起動または省略より前に保存しなければならない（MUST）。

#### Scenario: ALWAYS_ON decision を保存する

- **WHEN** `ALWAYS_ON_V1` で entry intent を処理する
- **THEN** runner は `required=true` と `ALWAYS_ON` reason を保存してから Falsifier を起動する

#### Scenario: OFF decision を保存する

- **WHEN** `OFF_V1` で entry intent を処理する
- **THEN** runner は `required=false` と `POLICY_OFF` reason を保存してから Falsifier を省略する

#### Scenario: CONDITIONAL は未適用として閉じる

- **WHEN** `CONDITIONAL_V1` で entry intent を処理する
- **THEN** runner は未適用 reason を伴う `required=true` decision を保存し、Falsifier を必須にする

### Requirement: retry は既存 policy attribution を変更しない

runner は同じ intent の既存 policy decision を exact readback し、現在の policy と runtime config identity が一致する場合だけ再利用しなければならない（MUST）。

#### Scenario: 同一 snapshot の再実行

- **WHEN** 同じ intent、policy、runtime config version/hash で entry flow を再実行する
- **THEN** runner は既存 decision を再利用し、decision/event を増やさない

#### Scenario: config switch 後の再実行

- **WHEN** intent に保存済みの policy decision と現在の policy または runtime config identity が異なる
- **THEN** runner は既存 decision を上書きせず entry を no-trade にする

#### Scenario: policy persistence failure

- **WHEN** policy decision の保存または exact readback が失敗する
- **THEN** runner は Falsifier と paper entry を開始せず no-trade にする

### Requirement: OFF bypass は runner permit と durable decision に束縛する

SafetyFloor は fresh `APPROVED` がない entry を、wire から設定できない internal permit と durable `OFF_V1` decision の identity が完全一致する場合だけ許可しなければならない（MUST）。

#### Scenario: 正規の OFF entry

- **WHEN** runner が durable `required=false / OFF_V1` decision から作った permit で同じ intent を preview/place する
- **THEN** SafetyFloor は fresh falsification 条件だけを満たしたものとして他の全 rule を評価する

#### Scenario: permit がない

- **WHEN** fresh `APPROVED` がなく、MCP caller または別 caller が permit なしで既存 OFF intent を preview/place する
- **THEN** SafetyFloor は `MISSING_FRESH_FALSIFICATION` で拒否する

#### Scenario: permit と durable decision が不一致

- **WHEN** decision ID、intent ID、policy、runtime config version/hash、required のいずれかが一致しない
- **THEN** SafetyFloor は entry を拒否する

#### Scenario: durable decision を読めない

- **WHEN** broker が policy decision を読めない、または監査片側欠損を検出する
- **THEN** preview/place は失敗し、entry 副作用を作らない

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
