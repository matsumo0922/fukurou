## ADDED Requirements

### Requirement: admission 由来の拒否は専用の rejection code を持つ

LLM execution admission が unhealthy であることを理由に gateway が submission を拒否したとき、応答の `reason` は他のどの拒否点とも異なる専用の識別子でなければならない (SHALL)。この識別子は既存の閉じた語彙の一員として定義され (MUST)、`[a-z][a-z0-9_]*` に一致しなければならない (MUST)。`error` code は既存の `SUBMISSION_REJECTED` を用い、新しい `error` code を追加してはならない (MUST NOT)。

応答は admission health の内部状態を漏らしてはならない (MUST NOT)。blocker の invocation id、claimant token、blocker の種別、blocker 数、例外 message を含んではならない (MUST NOT)。

#### Scenario: admission 拒否が専用識別子で返る

- **WHEN** admission health が unhealthy な状態で gateway へ submission を送る
- **THEN** 応答は `accepted=false`、`error=SUBMISSION_REJECTED` となり、`reason` は admission 由来を示す識別子で、他の拒否点の識別子と一致しない

#### Scenario: admission 拒否は内部状態を露出しない

- **WHEN** admission health が blocker を保持した状態で submission が拒否される
- **THEN** 応答は rejection code とコード定義の定型文だけを含み、blocker の invocation id、claimant token、blocker 種別を含まない

#### Scenario: 語彙の閉性が維持される

- **WHEN** gateway が生成しうるすべての拒否応答を列挙する
- **THEN** admission 由来の識別子を含むすべての `reason` 値が定義済み定数の集合に含まれる

### Requirement: admission 由来の拒否を監査から識別できる

admission 由来の拒否で `NO_TRADE_EXIT` を記録するとき、監査 payload の `rejectionCode` は admission 由来の識別子でなければならない (SHALL)。`reason` は既存契約どおり `tool_call_failed` のままとする (SHALL)。

この識別子により、admission 起因で終端した run を infrastructure 由来として戦略評価の母集団から分離できなければならない (SHALL)。

#### Scenario: admission 拒否が監査で特定できる

- **WHEN** admission unhealthy により `submit_decision` が拒否され、`NO_TRADE_EXIT` が記録される
- **THEN** payload の `rejectionCode` は admission 由来の識別子で、`reason` は `tool_call_failed` である

#### Scenario: admission 以外の拒否と混同しない

- **WHEN** binding mismatch や phase 認可違反により submission が拒否される
- **THEN** payload の `rejectionCode` は admission 由来の識別子ではなく、それぞれの拒否点の識別子である

### Requirement: Client は admission rejection code を LLM へ伝える

gateway client は admission 由来の `reason` を保持したまま呼び出し元へ伝え (SHALL)、MCP tool error の本文に含めなければならない (SHALL)。LLM へ返す文字列は rejection code とコード定義の定型文だけで構成されなければならない (MUST)。

#### Scenario: admission 拒否が MCP tool error に現れる

- **WHEN** LLM が `submit_decision` を呼び、gateway が admission unhealthy で拒否する
- **THEN** tool 応答は `isError=true` で、`structuredContent` に admission 由来の rejection code が含まれる

#### Scenario: typed exception の分類は既存のまま

- **WHEN** admission 由来の拒否が返る
- **THEN** client は `SUBMISSION_REJECTED` に対応する既存の typed exception を投げ、新しい例外型を導入しない
