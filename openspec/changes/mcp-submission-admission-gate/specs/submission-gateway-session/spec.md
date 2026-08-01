## ADDED Requirements

### Requirement: Gateway は risk を増やす submission を admission blocker が無いときだけ処理する

app-owned submission gateway は、terminal submission を repository へ渡す前に LLM execution admission blocker の有無を検査しなければならない (SHALL)。blocker が存在するとき、`SUBMIT_FALSIFICATION` と、risk を増やす action の `SUBMIT_DECISION` を拒否しなければならず (SHALL)、対応する repository へ到達させてはならない (MUST NOT)。

risk を減らす action（`EXIT` / `REDUCE` / `ADJUST_PROTECTION`）と `NO_TRADE` の decision submission は、blocker が存在しても処理しなければならない (SHALL)。admission が不健全な状態でこれらを止めることは、既にあるリスクを減らせなくする点で fail-closed の目的に反する。

gateway は admission health の状態を変更してはならない (MUST NOT)。

#### Scenario: blocker 有りで falsification submission が拒否される

- **WHEN** admission blocker が存在する状態で、binding が正しい `SUBMIT_FALSIFICATION` 要求を FALSIFIER phase の gateway へ送る
- **THEN** 応答は `accepted=false` となり、falsification repository は呼ばれず、falsification 行は増えない

#### Scenario: blocker 有りで risk を増やす decision submission が拒否される

- **WHEN** admission blocker が存在する状態で、`ENTER` を含む risk を増やす action の `SUBMIT_DECISION` 要求を gateway へ送る
- **THEN** 応答は `accepted=false` となり、decision repository は呼ばれず、decision 行は増えない

#### Scenario: blocker 有りでも risk を減らす decision submission は通る

- **WHEN** admission blocker が存在する状態で、`EXIT` / `REDUCE` / `ADJUST_PROTECTION` のいずれかの `SUBMIT_DECISION` 要求を gateway へ送る
- **THEN** 応答は `accepted=true` となり、decision が repository へ永続化される

#### Scenario: blocker 有りでも NO_TRADE は通る

- **WHEN** admission blocker が存在する状態で、`NO_TRADE` の `SUBMIT_DECISION` 要求を gateway へ送る
- **THEN** 応答は `accepted=true` となり、decision が repository へ永続化される

#### Scenario: blocker 無しなら従来どおり処理される

- **WHEN** admission blocker が存在しない状態で、受理可能な submission を送る
- **THEN** 応答は `accepted=true` となり、wire 応答と永続化の挙動はこの要件の導入前と同一である

#### Scenario: gateway は admission health を書き換えない

- **WHEN** admission 由来の拒否が発生する
- **THEN** admission health の blocker 集合と flag はその拒否によって変化しない

#### Scenario: binding 不一致は admission より先に判定される

- **WHEN** admission blocker が存在する状態で、binding が一致しない要求を gateway へ送る
- **THEN** 応答の拒否理由は binding mismatch であり、admission 由来の識別子ではない

### Requirement: admission 由来の拒否は確定した submission 状態を劣化させない

admission 由来の拒否が発生しても、一度 `COMMITTED` になった semantic submission 状態を `IN_FLIGHT` / `REJECTED` / `NOT_ATTEMPTED` へ戻してはならない (MUST NOT)。admission 由来の拒否は gateway を終了させてはならず (MUST NOT)、後続要求を処理できる状態を保たなければならない (SHALL)。

gateway は admission 由来の拒否によって reservation を終端させてはならない (MUST NOT)。停滞した invocation の回収は既存の execution claim recovery の責務のままとする。

#### Scenario: commit 後の admission 拒否で COMMITTED が維持される

- **WHEN** submission が受理されたあと、admission blocker が登録された状態で同じ gateway へ risk を増やす要求を送る
- **THEN** その要求は拒否されるが、`semanticSubmissionState()` は `COMMITTED` のままである

#### Scenario: admission 拒否で reservation は終端しない

- **WHEN** admission blocker により submission が拒否される
- **THEN** 当該 invocation の launch reservation の status と execution claim state は、その拒否によって変化しない

### Requirement: gate は best-effort であり残余 race を持つ

admission blocker の検査は repository 呼び出しの直前に行わなければならない (SHALL)。検査と repository commit の間に blocker が登録された場合、その commit は許容される (MAY)。gateway はこの区間を atomic にすることを保証しない (SHALL NOT)。

この残余 race を閉じることは、submission 経路へ claim fence を通す別の設計を要する。本要件は gate が「検査時点で判明している blocker」に対して働くことだけを保証する。

#### Scenario: 検査通過後の blocker 登録は commit を止めない

- **WHEN** admission 検査を通過した submission が repository へ渡る直前に blocker が登録される
- **THEN** その submission は commit され、拒否されない

### Requirement: admission gate の適用範囲

admission blocker による submission gate の対象は、app-owned submission gateway 経由の falsification と、risk を増やす decision とする (SHALL)。MCP server process が実行する read-only tool call は対象としない (SHALL NOT)。

新規 LLM 起動と `/health/ready` の判定は従来どおり `isHealthy()`（3 集合に加えて `recoveryScanHealthy` / `heartbeatHealthy` の 2 flag を含む）を用いる (SHALL)。submission gate は 3 集合のみを条件とし (SHALL)、periodic recovery scan の実行中に生じる一時的な flag 低下によって submission を拒否してはならない (MUST NOT)。

MCP server は独立 process として起動されるため process-local な admission health へ到達できない。read-only tool call の抑止は、tool allowlist、tool call budget、manifest 有効期限、`HARD_HALT`、global trading lock によって行う。

#### Scenario: read-only tool call は admission に依存しない

- **WHEN** admission blocker が存在する状態で、MCP server が read-only tool を実行する
- **THEN** その tool call は admission health を理由に拒否されない

#### Scenario: recovery scan 中の submission は拒否されない

- **WHEN** blocker が存在しない状態で periodic recovery scan が実行中（`recoveryScanHealthy` が一時的に false）に submission を送る
- **THEN** 応答は `accepted=true` となり、admission を理由に拒否されない

#### Scenario: 資金を動かす操作は admission gate を通る

- **WHEN** admission blocker が存在する状態で、runner が承認済み entry の発注を試みる
- **THEN** 発注は既存の admission 検証で失敗し、broker は呼ばれない
