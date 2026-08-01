## ADDED Requirements

### Requirement: UNCERTAIN 終端は phase 境界で admission blocker として登録される

**Trace:** Issue #352 受け入れ条件「admission の意味が経路によって異なる状態を解消する」

LLM invocation の 1 phase が終了した時点で、その invocation の process tree termination proof が `UNCERTAIN` であるとき、system は当該 invocation と claimant token に対する recovery blocker を登録しなければならない (SHALL)。登録は同じ invocation の次の phase が起動するより前に完了しなければならない (SHALL)。

登録の判定は、child process の終了時に記録された proof に基づかなければならない (SHALL)。phase の途中で proof が未確定であること（child が起動して未終了であること）を理由に blocker を登録してはならない (MUST NOT)。同一 invocation と claimant token に対する重複登録は冪等でなければならない (SHALL)。

one-shot 実行全体の終了時に行われる既存の blocker 登録は維持しなければならない (SHALL)。phase 境界での登録は、それを前倒しするものであって置き換えるものではない。

#### Scenario: PROPOSER の UNCERTAIN 終端が FALSIFIER 起動前に blocker を登録する

- **WHEN** PROPOSER phase の child process が `UNCERTAIN` proof で終端し、同じ one-shot が続けて FALSIFIER phase を起動しようとする
- **THEN** FALSIFIER phase の起動より前に当該 invocation の recovery blocker が登録されており、admission は blocker を保持した状態になる

#### Scenario: UNCERTAIN 終端後の falsification submission が拒否される

- **WHEN** PROPOSER phase が intent を保存したあと `UNCERTAIN` proof で終端し、後続の FALSIFIER phase が `SUBMIT_FALSIFICATION` を gateway へ送る
- **THEN** その submission は admission blocker により拒否され、falsification repository へ到達しない

#### Scenario: PROVEN_EXITED 終端では blocker を登録しない

- **WHEN** phase の child process が `PROVEN_EXITED` proof で終端する
- **THEN** その phase 境界では recovery blocker を登録せず、後続 phase の submission は admission を理由に拒否されない

#### Scenario: phase 実行中の未確定 proof では blocker を登録しない

- **WHEN** phase の child process が起動済みでまだ終了していない
- **THEN** その時点では recovery blocker を登録せず、当該 phase 自身の submission は admission を理由に拒否されない

#### Scenario: 重複登録が冪等である

- **WHEN** phase 境界で登録された blocker と同じ invocation と claimant token に対し、one-shot 全体の終了時に再度登録が行われる
- **THEN** blocker 集合の状態は 1 回の登録と同一であり、解除は 1 回の解除操作で成立する
