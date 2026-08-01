# submission-gateway-session Specification

## Purpose
TBD - created by archiving change submission-retry-within-run. Update Purpose after archive.
## Requirements
### Requirement: Gateway は close されるまで複数の submission 要求を処理する

app-owned submission gateway は、1 度の要求処理で接続と受付を終了してはならない (MUST NOT)。gateway が close されるまで、同一接続上の後続フレームと、切断後の新規接続を順に受け付けなければならない (SHALL)。要求の処理は逐次で行い、同一 gateway 内で 2 つの要求を並行に処理してはならない (MUST NOT)。

#### Scenario: 拒否後の再提出が同一接続で成立する

- **WHEN** client が拒否される submission を送り、応答を受け取ったあと、同じ接続で受理可能な submission を送る
- **THEN** 2 つ目の要求に `accepted=true` が返り、decision が repository へ永続化される

#### Scenario: 接続を張り直した再提出も成立する

- **WHEN** client が拒否される submission を送って接続を閉じ、新しい接続を開いて受理可能な submission を送る
- **THEN** 2 つ目の要求に `accepted=true` が返る

#### Scenario: 受理後の追加提出も接続を切らずに処理する

- **WHEN** 受理された submission と同一の canonical payload を、同じ接続でもう一度送る
- **THEN** 冪等性の既存契約どおり同一の decision ID が返り、decision 行は増えない

#### Scenario: client の切断で gateway は終了しない

- **WHEN** client が要求を送らずに接続を閉じる
- **THEN** gateway は例外を外部へ伝播させず、後続の接続を受け付けられる状態を保つ

### Requirement: 確定した semantic submission 状態を後続要求で劣化させない

同一 gateway が複数の要求を処理する場合でも、一度 `COMMITTED` になった semantic submission 状態は、後続の要求によって `IN_FLIGHT` / `REJECTED` / `NOT_ATTEMPTED` へ戻してはならない (MUST NOT)。監査へ写像される terminal は run 内で観測された最も確定的な状態を表さなければならない (SHALL)。

#### Scenario: commit 後の拒否で UNKNOWN へ落ちない

- **WHEN** submission が受理されたあと、同じ gateway へ conflict になる別 payload を送る
- **THEN** 2 つ目の要求は拒否されるが、`semanticSubmissionState()` は `COMMITTED` のままである

#### Scenario: 拒否のあとの受理は COMMITTED へ進む

- **WHEN** 最初の submission が拒否され、続く submission が受理される
- **THEN** `semanticSubmissionState()` は `COMMITTED` になる

### Requirement: 再提出が成立した run を no-entry へ誤分類しない

同一 run 内で先行する提出が拒否され、後続の trade decision が受理された場合、run にあるすべての `NO_TRADE_EXIT` が `rejectionCode` を持つときだけ、それらを superseded として no-entry 判定から除外しなければならない (SHALL)。`rejectionCode` を持たない汎用 tool 失敗の `NO_TRADE_EXIT` が 1 件でもあれば、記録順序にかかわらず superseded としてはならず (MUST NOT)、最新の `NO_TRADE_EXIT` の final reason と no-entry 証跡を維持しなければならない (SHALL)。拒否イベント自体は診断のため監査に残さなければならない (SHALL)。

#### Scenario: 拒否後に受理された entry run が no-entry にならない

- **WHEN** `submit_decision` が一度拒否されて `NO_TRADE_EXIT` が記録され、その後同一 run で entry を伴う decision が受理される
- **THEN** run outcome は no-entry ではなく、commit 済み decision に基づく分類になる

#### Scenario: 受理がない run は従来どおり no-entry になる

- **WHEN** `submit_decision` が拒否され、その run で受理される提出が 1 つも無い
- **THEN** run outcome は従来どおり no-entry で、final reason は拒否由来のままである

#### Scenario: commit 後の汎用 tool 失敗は superseded にならない

- **WHEN** entry decision が commit されたあと、`place_order` など別の tool が失敗し、`rejectionCode` を持たない `tool_call_failed` の `NO_TRADE_EXIT` が記録される
- **THEN** run outcome は従来どおり no-entry で、final reason は `tool_call_failed` のまま保持される

#### Scenario: 拒否と汎用 tool 失敗が混在しても順序に依存しない

- **WHEN** `rejectionCode` を持たない汎用 tool 失敗の `NO_TRADE_EXIT`、より新しい `rejectionCode` を持つ拒否の `NO_TRADE_EXIT`、commit 済み entry decision が同じ run に存在する
- **THEN** run outcome は no-entry のままで、final reason は最新の `NO_TRADE_EXIT` の reason を保持する

#### Scenario: 拒否イベントは削除されない

- **WHEN** 拒否のあとに提出が受理される
- **THEN** 拒否時の `NO_TRADE_EXIT` は監査から消えず、rejection code を含んだまま残る

### Requirement: 停止と待ち合わせの意味論を維持する

`awaitCompletion()` は最初の要求に対する応答の書き込みが成功した時点で待機を解除しなければならない (SHALL)。応答を書き込めなかった接続を request 完了として扱ってはならない (MUST NOT)。要求が 1 度も届かない場合や応答を書けないまま gateway が停止する場合も、停止時には待機を解除しなければならない (SHALL)。`close()` は受付ループを停止させ、既存の cleanup 失敗集約の契約を変えてはならない (MUST NOT)。

#### Scenario: 最初の要求で待機が解除される

- **WHEN** gateway へ 1 つ目の要求を送って応答を受け取る
- **THEN** `awaitCompletion()` は返る

#### Scenario: 要求なしの停止でも待機が解除される

- **WHEN** 要求が届かないまま gateway を close する
- **THEN** `awaitCompletion()` は返り、socket file が削除される

#### Scenario: close 後は受付が止まる

- **WHEN** gateway を close したあとに socket へ接続を試みる
- **THEN** 接続は成立せず、gateway の worker thread は終了している

### Requirement: Gateway は risk を増やす submission を gate 条件が満たされるときだけ処理する

app-owned submission gateway は、terminal submission を repository へ渡す前に次の 2 つを検査しなければならない (SHALL)。(1) LLM execution admission blocker の有無 (2) 当該 invocation における完了済み child の `UNCERTAIN` 履歴の有無。いずれかが該当するとき、`SUBMIT_FALSIFICATION` と、risk を増やす action の `SUBMIT_DECISION` を拒否しなければならず (SHALL)、対応する repository へ到達させてはならない (MUST NOT)。

2 つの条件は役割が異なる。admission blocker は process-global で、別 invocation の異常も含めて admission 全体の健全性を表す。`UNCERTAIN` 履歴は invocation-local で、同一 run 内の過去 phase が終了を証明できなかったことを表す。

risk を減らす action（`EXIT` / `REDUCE`）と `NO_TRADE` の decision submission は、gate 条件が該当しても処理しなければならない (SHALL)。これらを止めることは、既にあるリスクを減らせなくする点で fail-closed の目的に反する。

`ADJUST_PROTECTION` はこの例外に含めてはならない (MUST NOT)。当該 action は take-profit のみを変更して stop を変更せず、既存 take-profit との単調性も上限も課されないため、risk を減らすことが保証されない。

gateway は admission health の状態を変更してはならない (MUST NOT)。

#### Scenario: blocker 有りで falsification submission が拒否される

- **WHEN** admission blocker が存在する状態で、binding が正しい `SUBMIT_FALSIFICATION` 要求を FALSIFIER phase の gateway へ送る
- **THEN** 応答は `accepted=false` となり、falsification repository は呼ばれず、falsification 行は増えない

#### Scenario: blocker 有りで risk を増やす decision submission が拒否される

- **WHEN** admission blocker が存在する状態で、`ENTER` を含む risk を増やす action の `SUBMIT_DECISION` 要求を gateway へ送る
- **THEN** 応答は `accepted=false` となり、decision repository は呼ばれず、decision 行は増えない

#### Scenario: blocker 有りでも risk を減らす decision submission は通る

- **WHEN** admission blocker が存在する状態で、`EXIT` または `REDUCE` の `SUBMIT_DECISION` 要求を gateway へ送る
- **THEN** 応答は `accepted=true` となり、decision が repository へ永続化される

#### Scenario: blocker 有りで ADJUST_PROTECTION は拒否される

- **WHEN** admission blocker が存在する状態で、`ADJUST_PROTECTION` の `SUBMIT_DECISION` 要求を gateway へ送る
- **THEN** 応答は `accepted=false` となり、decision repository は呼ばれない

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

2 つの gate 条件の検査は repository 呼び出しの直前に行わなければならない (SHALL)。検査と repository commit の間に、admission blocker が登録された場合、または `UNCERTAIN` 履歴が真へ遷移した場合、その commit は許容される (MAY)。gateway はこの区間を atomic にすることを保証しない (SHALL NOT)。

この残余 race を閉じることは、submission 経路へ claim fence を通す別の設計を要する。本要件は gate が「検査時点で判明している状態」に対して働くことだけを保証する。

#### Scenario: 検査通過後の状態遷移は commit を止めない

- **WHEN** gate 検査を通過した submission が repository へ渡る直前に、admission blocker が登録されるか `UNCERTAIN` 履歴が真になる
- **THEN** その submission は commit され、拒否されない

### Requirement: admission gate の適用範囲

admission blocker による submission gate の対象は、app-owned submission gateway 経由の falsification と、risk を増やす decision とする (SHALL)。MCP server process が実行する read-only tool call は対象としない (SHALL NOT)。

新規 LLM 起動と `/health/ready` の判定は従来どおり `isHealthy()` を用いる (SHALL)。

submission gate は次を条件とする (SHALL)。3 集合が空であること、および recovery scan が実障害を報告していないこと。periodic recovery scan が正常に実行中であることを理由に submission を拒否してはならない (MUST NOT)。一方、recovery scan が失敗した状態（DB 障害、timeout、blocker 照会の失敗、recovery 結果の不明を含む）では、risk を増やす submission を拒否しなければならない (SHALL)。

recovery scan が stale claim を発見できない状態は、未知の停滞 invocation が存在しうることを意味するため、fail-closed の対象とする。

MCP server は独立 process として起動されるため process-local な admission health へ到達できない。read-only tool call の抑止は、tool allowlist、tool call budget、manifest 有効期限、`HARD_HALT`、global trading lock によって行う。

#### Scenario: read-only tool call は admission に依存しない

- **WHEN** admission blocker が存在する状態で、MCP server が read-only tool を実行する
- **THEN** その tool call は admission health を理由に拒否されない

#### Scenario: 正常な recovery scan 実行中の submission は拒否されない

- **WHEN** blocker が存在せず recovery scan が実障害を報告していない状態で、periodic recovery scan の実行中に submission を送る
- **THEN** 応答は `accepted=true` となり、gate を理由に拒否されない

#### Scenario: recovery scan の実障害中は risk を増やす submission が拒否される

- **WHEN** blocker が存在しないが recovery scan が DB 障害または timeout で失敗した状態で、risk を増やす `SUBMIT_DECISION` を送る
- **THEN** 応答は `accepted=false` となり、decision repository は呼ばれない

#### Scenario: 資金を動かす操作は admission gate を通る

- **WHEN** admission blocker が存在する状態で、runner が承認済み entry の発注を試みる
- **THEN** 発注は既存の admission 検証で失敗し、broker は呼ばれない

