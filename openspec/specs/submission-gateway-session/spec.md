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

