# llm-admission-blocker-recovery Specification

## Purpose
TBD - created by archiving change recover-admission-blocker-on-terminal. Update Purpose after archive.
## Requirements
### Requirement: Recovery scan resolves admission blockers from confirmed database terminals

**Trace:** Issue #350 受け入れ条件「UNKNOWN 終端で登録された blocker が、DB 終端確認後の recovery scan で自動解除される」

Every bounded recovery scan tick SHALL evaluate each process-local execution admission blocker against the current database state of its invocation. It SHALL resolve a blocker only when all of the following hold: the reservation is terminal (`FINISHED` or `FAILED`), the reservation records a non-null finish time, the blocker's claimant token matches the reservation's persisted execution claim token exactly, and the evaluation time has reached the finish time plus the configured hard timeout plus the process termination grace.

Resolution SHALL clear the recovery blocker and the heartbeat failure recorded under the same invocation and claimant token, because a terminal reservation makes past heartbeat outcomes irrelevant to admission.

The decision SHALL rest only on persisted reservation facts. The system MUST NOT resolve a blocker from process-local inference, elapsed wall-clock alone, absence of a database row, or the observation that no new blocker arrived.

#### Scenario: Reservation terminal predates the clearance window

- **WHEN** a blocker's reservation is terminal with a recorded finish time and the tick observes a time at or after finish time plus hard timeout plus process termination grace, and the blocker's claimant token equals the persisted execution claim token
- **THEN** the tick clears the recovery blocker and the same-key heartbeat failure, and admission health reports healthy once no other blocker, ambiguous claim, or unhealthy state remains

#### Scenario: Reservation is still running

- **WHEN** a blocker's reservation status is `RUNNING`
- **THEN** the blocker remains registered and admission stays fail-closed

#### Scenario: Clearance window has not elapsed

- **WHEN** a blocker's reservation is terminal but the tick observes a time before finish time plus hard timeout plus process termination grace
- **THEN** the blocker remains registered and admission stays fail-closed

#### Scenario: Reservation records no finish time

- **WHEN** a blocker's reservation is terminal but its persisted finish time is absent
- **THEN** the blocker remains registered because the clearance window has no anchor

#### Scenario: Claimant token does not match the reservation

- **WHEN** a blocker's claimant token differs from the persisted execution claim token for that invocation, including a blocker registered under a synthetic non-claim token
- **THEN** the tick leaves that blocker registered and resolves no state under the mismatched key

#### Scenario: Reservation row is absent

- **WHEN** no reservation row exists for a blocker's invocation
- **THEN** the blocker remains registered because absence is not a terminal fact

### Requirement: Blocker resolution leaves an audit trail

**Trace:** Issue #350 受け入れ条件「解除の監査イベントが command_event_log に残る」

Each resolved blocker SHALL append exactly one `command_event_log` event recording the invocation, claimant token, terminal reservation status, persisted finish time, resolution time, and the clearance window that was satisfied. The payload MUST NOT contain database passwords, exchange API keys, LLM credentials, or Cloudflare tokens.

Audit append failure SHALL fail the tick instead of resolving the blocker, so no blocker is cleared without a recorded reason.

#### Scenario: Blocker resolves successfully

- **WHEN** the tick resolves a blocker
- **THEN** exactly one audit event carries the invocation, claimant token, terminal status, finish time, resolution time, and clearance window

#### Scenario: Audit append fails

- **WHEN** the audit append for a resolution fails
- **THEN** the blocker remains registered, the tick reports failure, and recovery scan health becomes unhealthy until a later tick succeeds

### Requirement: Blocker evaluation respects the bounded recovery tick budget

The blocker evaluation pass SHALL execute inside the existing bounded recovery tick and SHALL check the deadline start reserve and coroutine cancellation before each blocker's database read. A database read failure SHALL mark recovery scan health unhealthy and fail the tick rather than silently skipping blockers.

The pass SHALL NOT alter the stale claim scan contract, its paging cursor, or the invariant that a recovery page completes with no unresolved recovery attempt.

#### Scenario: Deadline reserve is exhausted mid-pass

- **WHEN** the remaining tick budget falls below the start reserve before a blocker's database read
- **THEN** the tick fails with the deadline exception and recovery scan health becomes unhealthy, rather than reporting a completed pass

#### Scenario: Database read fails during the pass

- **WHEN** reading a blocker's reservation state fails
- **THEN** recovery scan health becomes unhealthy and the tick fails

#### Scenario: Stale claim recovery runs in the same tick

- **WHEN** a tick performs both blocker evaluation and stale claim recovery
- **THEN** the stale scan candidate set, cursor progression, and pending-recovery invariant remain unchanged by the blocker pass

### Requirement: Runtime gains no unconditional blocker reset

The blocker evaluation pass SHALL read blocker state through a read-only enumeration and SHALL resolve state only through the existing terminal-confirmation resolution path. The change MUST NOT add a runtime route, configuration switch, or public production capability that clears blockers without database terminal confirmation.

#### Scenario: Runtime surface is inspected

- **WHEN** the runtime routes, configuration, and public admission health API are inspected
- **THEN** no capability clears admission blockers without a confirmed database terminal
- **AND** the complete reset remains available to test fixtures only

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

