## ADDED Requirements

### Requirement: Gateway は拒否理由を有限語彙の rejection code として応答する

app-owned submission gateway が submission を拒否したとき、応答フレームは既存の `accepted=false` と `error` code に加えて、拒否点を識別する `reason` を含まなければならない (SHALL)。`reason` はコードが定義した閉じた集合の `snake_case` 識別子に限られ (MUST)、例外 message、payload 断片、filesystem path、環境変数値を含んではならない (MUST NOT)。集合の各値は gateway 内の単一の拒否点に対応し、既存の 3 つの `error` code は変更しない (SHALL)。

#### Scenario: operation ごとの phase 認可違反が区別される

- **WHEN** FALSIFIER phase の gateway へ `SUBMIT_DECISION` 要求を送る、または PROPOSER phase の gateway へ `SUBMIT_FALSIFICATION` 要求を送る
- **THEN** 応答は `accepted=false`、`error=SUBMISSION_REJECTED` となり、`reason` は decision と falsification の phase 認可違反を互いに異なる識別子で示す

#### Scenario: binding mismatch の拒否理由が拒否点ごとに区別される

- **WHEN** invocationId / phase / phaseManifestId / effectiveInvocationHash のいずれか 1 つだけが gateway の binding と異なる要求を送る
- **THEN** 応答の `reason` は、どの binding が一致しなかったかを互いに異なる識別子で示す

#### Scenario: request decode の拒否点がそれぞれ区別される

- **WHEN** frame decode、decision payload decode、falsification payload decode、未知 operation、payload 欠落・型不正、必須 string field 欠落・型不正のいずれかで gateway が要求を拒否する
- **THEN** 応答の `reason` は 6 拒否点を互いに異なる識別子で示し、frame decode 失敗は送信者の過失を断定しない中立な識別子になる

#### Scenario: typed conflict と unknown も rejection code を持つ

- **WHEN** 同一 authority へ異なる canonical payload を提出して conflict になる、または authority が結果を再構成できず unknown になる
- **THEN** `error` は既存の `DECISION_SUBMISSION_CONFLICT` / `DECISION_SUBMISSION_UNKNOWN` のまま、`reason` にそれぞれ対応する識別子が載る

#### Scenario: rejection code は閉じた語彙に限られる

- **WHEN** gateway が生成しうるすべての拒否応答を列挙する
- **THEN** すべての `reason` 値が定義済み定数の集合に含まれ、各値が `[a-z][a-z0-9_]*` に一致する

#### Scenario: 分類されない失敗でも secret を露出しない

- **WHEN** 拒否点の分類に一致しない任意の `Throwable` が gateway 内で発生する
- **THEN** 応答は `error=SUBMISSION_REJECTED` と汎用の `reason` 識別子だけを含み、その `Throwable` の message を含まない

### Requirement: Client は rejection code を LLM へ返す

gateway client は拒否応答の `reason` を保持したまま呼び出し元へ伝え (SHALL)、MCP tool error の本文に含めなければならない (SHALL)。LLM へ返す文字列は rejection code とコード定義の定型文だけで構成され (MUST)、gateway 由来の自由文字列を素通ししてはならない (MUST NOT)。`reason` を含まない応答（旧 gateway との組み合わせ）でも、client は現在と同じ typed exception を投げなければならない (SHALL)。

#### Scenario: 拒否理由が MCP tool error に現れる

- **WHEN** LLM が `submit_decision` を呼び、gateway が phase 認可違反で拒否する
- **THEN** tool 応答は `isError=true` で、`structuredContent` に該当の rejection code が含まれる

#### Scenario: typed exception の分類は維持される

- **WHEN** gateway が `DECISION_SUBMISSION_CONFLICT` / `DECISION_SUBMISSION_UNKNOWN` を返す
- **THEN** tool error の `type` はそれぞれ `decision_submission_conflict` / `decision_submission_unknown` のままで、rejection code はそれと別のフィールドとして併記される

#### Scenario: reason なし応答でも従来どおり失敗する

- **WHEN** 応答が `accepted=false` かつ `reason` を持たない
- **THEN** client は現在と同じ typed exception を投げ、rejection code を持たない

### Requirement: 監査イベントに rejection code を残す

gateway 由来の拒否で `NO_TRADE_EXIT` を記録するとき、監査 payload は `rejectionCode` を含まなければならない (SHALL)。値は Requirement 1 と同じ閉じた語彙に限られ (MUST)、gateway 由来でない失敗には付与してはならない (MUST NOT)。既存の `reason` / `cause` / `message` allowlist の挙動は変更しない (SHALL)。

#### Scenario: gateway 拒否で rejectionCode が残る

- **WHEN** `submit_decision` が gateway に拒否され、`NO_TRADE_EXIT` が記録される
- **THEN** payload の `rejectionCode` から拒否点が特定でき、`reason` は `tool_call_failed` のままである

#### Scenario: gateway 以外の失敗には付与しない

- **WHEN** market data 例外など gateway 由来でない失敗で `NO_TRADE_EXIT` が記録される
- **THEN** payload に `rejectionCode` キーが存在しない

#### Scenario: 語彙外の値は保存されない

- **WHEN** rejection code として語彙外の文字列を持つ例外が cause として渡される
- **THEN** payload に `rejectionCode` キーが存在しない
