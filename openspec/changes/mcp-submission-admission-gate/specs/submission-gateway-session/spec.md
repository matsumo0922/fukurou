## ADDED Requirements

### Requirement: Gateway は admission health が healthy なときだけ submission を処理する

app-owned submission gateway は、要求の処理を開始する前に LLM execution admission health を検査しなければならない (SHALL)。admission が unhealthy のとき、`SUBMIT_DECISION` と `SUBMIT_FALSIFICATION` の双方を拒否しなければならず (SHALL)、decision repository / falsification repository へ到達させてはならない (MUST NOT)。検査は gateway binding 検証と payload 解釈より前に行わなければならない (SHALL)。

admission health は process-local な状態であり、gateway は app process 内で動作するためこれを直接参照できる。gateway は admission health の状態を変更してはならない (MUST NOT)。

#### Scenario: admission unhealthy で decision submission が拒否される

- **WHEN** admission health が unhealthy な状態で、binding が正しい `SUBMIT_DECISION` 要求を gateway へ送る
- **THEN** 応答は `accepted=false` となり、decision repository は呼ばれず、decision 行は増えない

#### Scenario: admission unhealthy で falsification submission が拒否される

- **WHEN** admission health が unhealthy な状態で、binding が正しい `SUBMIT_FALSIFICATION` 要求を FALSIFIER phase の gateway へ送る
- **THEN** 応答は `accepted=false` となり、falsification repository は呼ばれず、falsification 行は増えない

#### Scenario: admission 検査は binding 検証より先に行われる

- **WHEN** admission health が unhealthy な状態で、binding が一致しない要求を gateway へ送る
- **THEN** 応答の拒否理由は binding mismatch ではなく admission 由来の識別子になる

#### Scenario: admission healthy なら従来どおり処理される

- **WHEN** admission health が healthy な状態で、受理可能な submission を送る
- **THEN** 応答は `accepted=true` となり、wire 応答と永続化の挙動はこの要件の導入前と同一である

#### Scenario: gateway は admission health を書き換えない

- **WHEN** admission 由来の拒否が発生する
- **THEN** admission health の blocker 集合と flag はその拒否によって変化しない

### Requirement: admission 由来の拒否は確定した submission 状態を劣化させない

admission 由来の拒否が発生しても、一度 `COMMITTED` になった semantic submission 状態を `IN_FLIGHT` / `REJECTED` / `NOT_ATTEMPTED` へ戻してはならない (MUST NOT)。admission 由来の拒否は gateway を終了させてはならず (MUST NOT)、admission が healthy へ戻ったあとの後続要求を処理できる状態を保たなければならない (SHALL)。

gateway は admission 由来の拒否によって reservation を終端させてはならない (MUST NOT)。停滞した invocation の回収は既存の execution claim recovery の責務のままとする。

#### Scenario: commit 後の admission 拒否で COMMITTED が維持される

- **WHEN** submission が受理されたあと、admission が unhealthy になった状態で同じ gateway へ次の要求を送る
- **THEN** その要求は拒否されるが、`semanticSubmissionState()` は `COMMITTED` のままである

#### Scenario: admission 回復後の再提出が成立する

- **WHEN** admission unhealthy で submission が拒否されたあと、admission が healthy へ戻ってから同一 gateway へ受理可能な submission を送る
- **THEN** 2 つ目の要求に `accepted=true` が返り、decision が repository へ永続化される

#### Scenario: admission 拒否で reservation は終端しない

- **WHEN** admission unhealthy で submission が拒否される
- **THEN** 当該 invocation の launch reservation の status と execution claim state は、その拒否によって変化しない

### Requirement: admission gate の適用範囲を submission に限定する

admission health による gate の対象は、新規 LLM 起動、runner による発注、および app-owned submission gateway 経由の terminal submission とする (SHALL)。MCP server process が実行する read-only tool call は admission health の gate 対象としない (SHALL NOT)。

MCP server は独立 process として起動されるため process-local な admission health へ到達できない。read-only tool call の抑止は、tool allowlist、tool call budget、manifest 有効期限、`HARD_HALT`、global trading lock によって行う。

#### Scenario: read-only tool call は admission に依存しない

- **WHEN** admission health が unhealthy な状態で、MCP server が read-only tool を実行する
- **THEN** その tool call は admission health を理由に拒否されない

#### Scenario: 資金を動かす操作は admission gate を通る

- **WHEN** admission health が unhealthy な状態で、runner が承認済み entry の発注を試みる
- **THEN** 発注は admission 検証で失敗し、broker は呼ばれない
