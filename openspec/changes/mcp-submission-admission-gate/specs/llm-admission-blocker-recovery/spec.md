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
