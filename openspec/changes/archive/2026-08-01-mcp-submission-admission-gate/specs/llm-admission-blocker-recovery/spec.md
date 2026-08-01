## ADDED Requirements

### Requirement: 完了済み child の UNCERTAIN 履歴が後続の terminal submission を止める

**Trace:** Issue #352 受け入れ条件「admission の意味が経路によって異なる状態を解消する」

process tree termination registry は、ある invocation において**完了した child の少なくとも 1 つが `UNCERTAIN` proof で終端したか**を照会できる read API を提供しなければならない (SHALL)。この照会は、現在実行中で未終了の child の存在を `UNCERTAIN` として扱ってはならない (MUST NOT)。

app-owned submission gateway は、terminal submission の可否判定にこの照会結果を用いなければならない (SHALL)。照会が true を返すとき、当該 invocation の後続の terminal submission は admission blocker の有無に関わらず拒否対象となる (SHALL)。

この判定は process-local admission health の状態を読み書きしてはならない (MUST NOT)。新規 LLM 起動 gate、`/health/ready`、runner の execution admission 検証の意味論は不変でなければならない (SHALL)。

#### Scenario: PROPOSER の UNCERTAIN 終端が後続 FALSIFIER の承認を止める

- **WHEN** PROPOSER phase が intent を保存したあと child が `UNCERTAIN` proof で終端し、後続の FALSIFIER phase が `SUBMIT_FALSIFICATION` を gateway へ送る
- **THEN** その submission は拒否され、falsification repository へ到達しない

#### Scenario: 実行中の child は UNCERTAIN 扱いされない

- **WHEN** 最初の PROPOSER phase の child が起動済みでまだ終了しておらず、その child が `SUBMIT_DECISION` を gateway へ送る
- **THEN** その submission は UNCERTAIN 履歴を理由に拒否されない

#### Scenario: PROVEN_EXITED 終端は後続を止めない

- **WHEN** PROPOSER phase の child が `PROVEN_EXITED` proof で終端し、後続の FALSIFIER phase が submission を送る
- **THEN** その submission は UNCERTAIN 履歴を理由に拒否されない

#### Scenario: UNCERTAIN 履歴は後続 phase の実行中も保持される

- **WHEN** PROPOSER が `UNCERTAIN` で終端したあと FALSIFIER phase の child が起動し、実行中になる
- **THEN** 照会は引き続き true を返し、FALSIFIER の submission は拒否される

#### Scenario: 判定は admission health を変更しない

- **WHEN** UNCERTAIN 履歴により submission が拒否される
- **THEN** admission health の blocker 集合と flag はその拒否によって変化せず、新規起動 gate と `/health/ready` の判定も変化しない

### Requirement: UNCERTAIN 履歴は履歴を必要とする範囲の終了時に解放される

process tree termination registry の entry は、その履歴を参照する範囲が終了した時点で解放されなければならない (SHALL)。終端の proof が `UNCERTAIN` であることを理由に entry を保持し続けてはならない (MUST NOT)。

解放の責務は履歴の参照範囲に応じて定める (SHALL)。

- 単一 phase のみを実行する呼び出し元では、phase の監査完了時に解放する。解放は submission gateway の close と、proof を読む監査処理の後でなければならない (SHALL)
- 同一 invocation の複数 phase をまたいで履歴を参照する呼び出し元では、phase 終了では解放せず、当該 invocation の実行全体の終了時に解放する (SHALL)

複数 phase をまたぐ呼び出し元における解放は、終端状態の永続化が失敗した場合にも行われなければならない (SHALL)。永続化の後に解放を置いてはならない (MUST NOT)。

解放の時点で当該範囲の submission gateway は既に閉じられているため、履歴を保持する必要がない。`UNCERTAIN` が意味する「終了を証明できない child が残りうる」ことは、同じ終了処理で登録される admission recovery blocker が表す (SHALL)。

registry の解放は admission recovery blocker と execution termination fence の解放を伴ってはならない (MUST NOT)。後者 2 つは DB terminal 確認と claimant token の一致を経てのみ解放される既存契約を維持する。

#### Scenario: UNCERTAIN で終端した run の entry が解放される

- **WHEN** one-shot 実行が `UNCERTAIN` proof で終了し、終了処理が完了する
- **THEN** registry には当該 invocation の entry が残らず、同一 invocation を照会しても UNCERTAIN 履歴は報告されない

#### Scenario: entry 解放は admission blocker を解除しない

- **WHEN** `UNCERTAIN` で終端した run の registry entry が解放される
- **THEN** 同じ終了処理で登録された admission recovery blocker は登録されたまま残り、execution termination fence も解放されない

#### Scenario: 後続 run が過去の履歴に影響されない

- **WHEN** ある invocation が `UNCERTAIN` で終端したあと、同じ process 内で新しい submission gateway が作られる
- **THEN** その gateway の submission は過去の run の UNCERTAIN 履歴を理由に拒否されない

#### Scenario: 終端の永続化が失敗しても解放される

- **WHEN** `UNCERTAIN` で終端した実行の終了処理で、終端状態の永続化が例外で失敗する
- **THEN** registry の entry は解放されており、admission recovery blocker は登録されたまま残る

#### Scenario: 単一 phase の実行が phase 終了で解放する

- **WHEN** 単一 phase のみを実行する呼び出し元の phase が `UNCERTAIN` で終端し、その監査が完了する
- **THEN** registry には当該 invocation の entry が残らない

#### Scenario: 複数 phase の実行は phase 終了で解放しない

- **WHEN** 複数 phase をまたぐ呼び出し元の最初の phase が `UNCERTAIN` で終端し、その監査が完了する
- **THEN** registry の entry は残り、後続 phase の gate 判定が UNCERTAIN 履歴を参照できる
