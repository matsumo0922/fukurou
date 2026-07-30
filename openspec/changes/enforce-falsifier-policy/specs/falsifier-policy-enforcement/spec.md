## ADDED Requirements

### Requirement: OFF ENTER だけが Falsifier を省略できる

runner は canonical な durable `OFF_V1 / ENTER / required=false / POLICY_OFF` decision と、それに完全一致する internal permit がある場合だけ Falsifier を省略しなければならない（MUST）。
その他の policy/action または authority 不一致では fresh `APPROVED` を要求しなければならない（MUST）。

#### Scenario: canonical OFF ENTER

- **WHEN** runner が canonical OFF ENTER decision と permit を確立する
- **THEN** runner は Falsifier invocation、falsification record、Falsifier phase observation を作らず entry flow を続行する

#### Scenario: ALWAYS_ON

- **WHEN** `ALWAYS_ON_V1` の entry intent を処理する
- **THEN** runner と SafetyFloor は従来どおり fresh `APPROVED` を要求する

#### Scenario: CONDITIONAL

- **WHEN** `CONDITIONAL_V1` の entry intent を処理する
- **THEN** runner と SafetyFloor は `CONDITIONAL_NOT_APPLIED` として fresh `APPROVED` を要求する

#### Scenario: ADD_LONG

- **WHEN** action が `ADD_LONG` である
- **THEN** policy が `OFF_V1` でも fresh `APPROVED` を要求する

### Requirement: permit は internal command path に限定する

system は OFF permit を runner が構築する internal command path だけに渡し、MCP wire schema、OpenAPI、外部 JSON input に露出してはならない（MUST NOT）。

#### Scenario: runner command

- **WHEN** runner が canonical OFF ENTER entry を preview/place する
- **THEN** internal command は foundation の permit と同じ全 identity を保持する

#### Scenario: MCP caller

- **WHEN** MCP caller が `preview_order` または `place_order` を送る
- **THEN** caller は permit を指定できず、fresh `APPROVED` のない entry は拒否される

### Requirement: broker と SafetyFloor は durable authority を再検証する

broker は permit 付き command の ledger side effect 前に policy decision/event を intent ID で durable readbackし、decision ID、intent ID、action、policy、required/reasons、runtime config version/hash が permit と完全一致することを検証しなければならない（MUST）。
SafetyFloor は broker が検証した authority が command と一致する場合だけ fresh falsification の代替として受理しなければならない（MUST）。

#### Scenario: 全 identity が一致する

- **WHEN** OFF ENTER permit、durable decision/event、command intent の全 identity が一致する
- **THEN** SafetyFloor は fresh falsification 条件だけを policy authority で満たす

#### Scenario: decision が欠損または不一致

- **WHEN** decision/event が欠損、partial、不一致、または repository read が失敗する
- **THEN** broker は新規 ledger side effect 前に entry を fail closed にする

#### Scenario: 他の SafetyFloor rule

- **WHEN** valid な OFF authority があるが STOP、drawdown、risk、exposure、cash、EV、blackout、または他の safety rule に違反する
- **THEN** SafetyFloor は既存 rule で entry を拒否する

#### Scenario: 消費済み intent

- **WHEN** valid な OFF authority があるが intent が消費済みである
- **THEN** system は既存の intent consumption rule で entry を拒否する

### Requirement: OFF ENTER は placement 時にも flat でなければならない

system は OFF permit 付き place を、placement と intent consumption の排他境界内で open position が 0 件の場合だけ受理しなければならない（MUST）。

#### Scenario: preview 後に position が発生する

- **WHEN** OFF ENTER preview 後、place 前に resting BUY が約定して open position が発生する
- **THEN** place は Falsifier なしの追加 entry を作らず拒否される

#### Scenario: placement lock 内でも flat

- **WHEN** OFF ENTER place の排他境界内で open position が 0 件であり、他の全 gate も通過する
- **THEN** system は既存の atomic intent consumption と ledger write を実行する

### Requirement: v2 replay identity は command と policy authority に束縛する

OFF ENTER place は normalized command business fields と permit の全 identity の canonical SHA-256 fingerprint を `runner-place-v2-<hash>` として使用しなければならない（MUST）。
broker は `runner-place-v2-` namespace を OFF permit 専用に予約し、既存 result lookup より前に permit、durable decision、recomputed fingerprint を新規/replay の双方で検証しなければならない（MUST）。

#### Scenario: 正規の retry

- **WHEN** 同じ command と同じ durable OFF authority で v2 place を retry する
- **THEN** broker は authority と fingerprint を検証して既存 result を返し、ledger mutation を増やさない

#### Scenario: payload が異なる

- **WHEN** intent、数量、価格、STOP/TP、time stop、または policy authority が既存 v2 ID の canonical projection と異なる
- **THEN** broker は既存 result を返さず新規 mutation も行わない

#### Scenario: permit のない既存 v2 ID

- **WHEN** MCP caller が正しい既存 v2 client request ID を permit なしで送る
- **THEN** broker は既存 result lookup 前に要求を拒否する

#### Scenario: permit のない未使用 v2 ID

- **WHEN** MCP caller が正しい形式の未使用 v2 client request ID を permit なしで送る
- **THEN** broker は新規 mutation 前に要求を拒否する

### Requirement: failure は commit possibility を保存する

system は policy authority を新規 side effect 前に確立できない failure を fail-closed no-trade としなければならない（MUST）。
paper place が commit した可能性を否定できない後続 failure は outcome unknown とし、no-trade と記録してはならない（MUST NOT）。

#### Scenario: pre-side-effect policy failure

- **WHEN** policy repository read、authority照合、または fingerprint 検証が新規 ledger side effect 前に失敗する
- **THEN** system は orderを作らず fail-closed no-trade とする

#### Scenario: commit 後の ACK または completion failure

- **WHEN** place が commit した可能性がある後に ACK、completion audit、または authority再読が失敗し、既存結果を確定できない
- **THEN** runner は outcome unknown とし、注文が存在しないという no-trade record を作らない

#### Scenario: authority を再構築できる retry

- **WHEN** durable decision/event と order の intent ID / fingerprinted client request ID から exact authority を再構築できる
- **THEN** broker は検証後に既存 result を返す

#### Scenario: authority を再構築できない retry

- **WHEN** place commit の可能性があるが exact authority を再構築できない
- **THEN** system は再 mutation と no-trade への縮退を行わず outcome unknown を維持する

### Requirement: 既存の監査正本を使用する

system は OFF enforcement のために ToolCallGuard へ新しい pre-mutation event を追加してはならない（MUST NOT）。
policy decision/event と、order に保存される intent ID / fingerprinted client request ID を authority の監査正本として使用しなければならない（MUST）。

#### Scenario: OFF order の事後監査

- **WHEN** OFF permit により作成された order を事後監査する
- **THEN** durable order identity と policy decision/event から command と authority の相関を復元できる
