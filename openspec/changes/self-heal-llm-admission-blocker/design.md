## Context

`LlmExecutionAdmissionHealth` は process-local singleton で、5 つの入力（`ambiguousClaims` / `recoveryBlockers` / `heartbeatFailures` / `heartbeatHealthy` / `recoveryScanHealthy`）がすべて健全な場合だけ `isHealthy()` を true にする。このうち `recoveryBlockers` は「child process の生死が不明」を表し、次の 3 箇所から登録される。

- `OneShotLlmRunner.kt:620` — `ProcessTreeTerminationProof.UNCERTAIN` で終わった runner の finally
- `LlmExecutionClaimSupervisor.kt:308` — recovery scan が CLAIMED 候補の termination fence を見つけられない
- `LlmExecutionClaimSupervisor.kt:351` — recovery の `PreconditionChanged`
- （`ReflectionTerminalPersistenceSupervisor.kt:40` も同じ API を使うが、こちらは専用 token `reflection-terminal:<id>` を持ち、自身の retry loop が確実に解除する）

解除経路は `completeRecoveryHealth`（recovery が候補として拾い直せた場合）と runner 自身の finally だけである。2026-07-31 の障害では recovery scan が同じ候補を拾い直せず、reservation は DB 上で終端済みなのに blocker だけが残った。

## Goals / Non-Goals

Goals

- DB の終端事実だけを根拠に、blocker を無人で解除する
- 解除を監査可能にする
- fail-closed の意味（生死不明な間は admission を止める）を維持する

Non-Goals

- fail-closed 機構の撤去・緩和
- blocker の永続化 / 複数 process 対応
- codex CLI timeout そのものの解消
- 運用者向けの手動解除 route の追加（安全性を下げる方向の surface を増やさない）

## Decisions

### D1: 解除条件は「DB 終端」と「静穏期間経過」の AND とする

**帰属: agent 仮決め**

DB の終端だけで即解除しない。reservation の終端 row は「JVM 側が finish を書いた」ことしか意味せず、`UNCERTAIN` は「子孫 process が生き残っているかもしれない」状態である（`OneShotLlmRunner` の finally は `UNCERTAIN` でも `finish()` を必ず呼ぶため、terminal row は blocker 登録とほぼ同時に存在しうる）。したがって DB 終端だけを条件にすると、blocker が登録された次の tick で即解除され、fail-closed が実質無効化される。

`hardTimeout + processTerminationGrace` を静穏期間として要求する。この 2 値は既に `OneShotExecutionPolicy` にあり、「起動から強制終了までに要する最大時間」を表す。blocker 登録時刻からこれだけ経過していれば、生き残った子孫が新しい注文を出す現実的な窓は閉じている。既定値は hardTimeout 180 秒 + grace 10 秒 = 190 秒で、障害時の 2 時間 15 分に比べれば十分短い。

代案（DB 終端のみで即解除）は falsify 対象として提示する。

### D2: blocker registry に登録時刻を持たせる

**帰属: agent 仮決め**

静穏期間を測るには登録時刻が要る。`ConcurrentHashMap.newKeySet<ClaimHealthKey>()` を `ConcurrentHashMap<ClaimHealthKey, Instant>` に置き換える。登録 API は `registerRecoveryBlocker(invocationId, claimantToken, registeredAt)` へ観測時刻を受け取り、既存の登録は上書きしない（`putIfAbsent`）。同一 blocker が再登録されても最初の観測時刻を保ち、静穏期間が延び続けることを防ぐ。

時刻源は呼び出し側の `Clock` を使い、`Instant.now()` を singleton 内部で呼ばない（テスト可能性のため）。

### D3: 解除は audit 成功後に行う

**帰属: agent 仮決め**

「解除したが audit が残っていない」状態を作らない。audit → 解除の順にする。逆順（解除 → audit）だと audit 失敗時に無記録の解除が残る。audit が失敗した場合は blocker を残し、次 tick が再試行する（audit は同じ blocker に対して複数回試行されうるが、成功するのは解除に至った 1 回だけなので `command_event_log` に重複は残らない）。

### D4: 解除 step は recovery scan tick の内部に置く

**帰属: agent 仮決め**

新しい worker を足さず、`LlmExecutionRecoveryService.tick()` の先頭に step を挿入する。これは既に `LlmExecutionRecoveryWorker` が `policy.heartbeatInterval` で回している。新しい scheduler / lifecycle を導入しないのが最小構成。

tick 先頭に置く理由は、既存の scan 本体が例外を投げた場合でも解除が 1 回は走るようにするため。ただし `tick()` は冒頭で `setRecoveryScanHealthy(false)` を呼ぶので、解除 step だけ成功しても scan が失敗すれば `isHealthy()` は false のままになる。これは意図した挙動（scan が壊れている間は admission を開けない）。

解除 step 自体は失敗を伝播させ、tick 全体を failure にする。部分成功を隠さない。

### D5: blocker の read API は「解除候補の snapshot を返す」形にする

**帰属: agent 仮決め**

`LlmExecutionAdmissionHealth` に副作用のない `snapshotRecoveryBlockers(): List<RecoveryBlockerSnapshot>` を追加し、recovery service がそれを読んで DB へ問い合わせ、条件を満たしたものだけ `resolveRecoveryBlocker` する。singleton に DB 依存を持ち込まない（health は依然として純粋な in-memory state のまま）。

`MISSING_CLAIMANT_TOKEN` / `<unknown>` / `reflection-terminal:*` といった合成 token を持つ blocker も snapshot に現れる。invocationId で DB を引く判定は token に依存しないので、これらも同じ規則で扱える。ただし `reflection-terminal:*` は自前の retry loop が解除するため、通常はこの経路に到達する前に消える。両者が競合しても `resolveRecoveryBlocker` は冪等な remove なので害はない。

### D6: 監査イベント種別を 1 つ追加する

**帰属: agent 仮決め**

`CommandEventType.LLM_ADMISSION_BLOCKER_AUTO_RESOLVED` を追加し、`OpsRoutes.kt` の projection 名 `llmAdmissionBlockerAutoResolved` を追加する。`event_type` 列は `varchar(96)` で制約がなく、DB migration は不要。

payload には invocationId、claimantToken、observedStatus、elapsedQuietMillis、registeredAt を入れる。claimantToken は UUID 相当のランダム値で secret ではない（既存の recovery audit も token を扱う）。

### D7: `CommandEventLog` は recovery service のコンストラクタ引数にする

**帰属: agent 仮決め**

`LlmExecutionRecoveryService` に `commandEventLog: CommandEventLog` を必須引数として足す。`LlmExecutionRecoveryWorker` は既に `CommandEventLog` を保持しているので、そのまま渡す。既存のテストは既定値なしのコンパイルエラーで漏れが検出される方が安全なので、デフォルト値は置かない。

## Risks / Trade-offs

- **偽解除**: 静穏期間経過後も子孫 process が生きていれば、admission を開けた後に旧 process が注文を出しうる。ただし旧 process の claimant token に対する `validateExecutionAdmission` は reservation が terminal なので false を返し、注文経路は別途弾かれる。D1 の静穏期間はその二重防護の外側の層。
- **静穏期間の設定変更**: `perRunTimeout` を 600 秒へ上げた運用では静穏期間も 610 秒になる。復旧が遅くなるが、危険側には振れない。
- **snapshot と DB 問い合わせの間の race**: snapshot 取得後に新しい blocker が登録されても、その tick では扱わない。次 tick が拾う。逆に snapshot 後に別経路で解除された blocker を再度 remove しても冪等。

## Migration Plan

DB schema 変更なし。設定変更なし。deploy は通常の container 差し替えで完結する。ロールバックは revert のみ（新しい永続状態を作らないため）。

## Open Questions

なし。D1 の静穏期間の妥当性は falsify で検証する。
