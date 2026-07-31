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

静穏期間そのものは、admission を開ける唯一の防護ではなく最外層である。旧 child process が terminal reservation 後に risk-increasing な注文へ到達しないことは、以下の 4 層で担保される。

- production の phase allowlist が直接の trade tool を除外する（`McpToolContractCatalog.kt:9-22`）
- MCP manifest expiry が tool handler の手前で拒否する（`McpToolCallLimiter.kt:74-76,191-204`）
- submission gateway が invocation / phase / manifest binding を要求する（`LlmDecisionSubmissionGateway.kt:307-369`）
- runner が実注文の直前に live claim を再検証する（`OneShotLlmRunner.kt:1634,1733-1747`）

`validateExecutionAdmission()` は全経路に置かれた gate ではないため、これを単独の根拠にしない。

### D2: blocker registry は監査用の wall clock と判定用の monotonic 時刻を分けて持つ

**帰属: agent 仮決め**

静穏期間を測るには登録時刻が要る。`ConcurrentHashMap.newKeySet<ClaimHealthKey>()` を `ConcurrentHashMap<ClaimHealthKey, RecoveryBlockerRecord>` に置き換え、record は `registeredAt: Instant`（監査表示用）と `registeredAtNanos: Long`（eligibility 判定用の monotonic 値）の両方を持つ。

判定を wall clock で行わない。registry は process-local なので、`System.nanoTime()` の値をそのまま保持でき、OS clock の後退・前進に影響されない。wall clock だけで判定すると、NAS の時刻補正で clock が後退した場合に静穏期間が成立しなくなり（あるいは前進で不当に早く成立し）、fail-closed が別の理由で固着する。

登録 API は `registerRecoveryBlocker(invocationId, claimantToken, registeredAt, registeredAtNanos)` を取り、既存の登録は上書きしない（`putIfAbsent`）。同一 blocker が再登録されても最初の観測時刻を保ち、静穏期間が延び続けることを防ぐ。

時刻源は呼び出し側の `Clock` と `nanoTime` を使い、singleton 内部で `Instant.now()` / `System.nanoTime()` を直接呼ばない（テスト可能性のため）。

### D3: 解除は audit 成功後に行う

**帰属: agent 仮決め**

「解除したが audit が残っていない」状態を作らない。audit → 解除の順にする。逆順（解除 → audit）だと audit 失敗時に無記録の解除が残る。audit が失敗した場合は blocker を残し、次 tick が再試行する（audit は同じ blocker に対して複数回試行されうるが、成功するのは解除に至った 1 回だけなので `command_event_log` に重複は残らない）。

### D4: 解除 step は recovery scan tick の内部に置き、同じ monotonic deadline を共有する

**帰属: agent 仮決め**

新しい worker を足さず、`LlmExecutionRecoveryService.tick()` の先頭に step を挿入する。これは既に `LlmExecutionRecoveryWorker` が `policy.heartbeatInterval` で回している。新しい scheduler / lifecycle を導入しないのが最小構成。

tick 先頭に置く理由は、既存の scan 本体が例外を投げた場合でも解除が 1 回は走るようにするため。ただし `tick()` は冒頭で `setRecoveryScanHealthy(false)` を呼ぶので、解除 step だけ成功しても scan が失敗すれば `isHealthy()` は false のままになる。これは意図した挙動（scan が壊れている間は admission を開けない）。

解除 step の DB access は、既存 scan と同じ `LlmExecutionRecoveryDeadline` に参加させる。tick 冒頭で `setRecoveryScanHealthy(false)` を立てる以上、解除 step が deadline なしで JDBC を待つと「tick が返らない → `recoveryScanHealthy=false` が永久に残る」という同型の障害を新設してしまう。したがって既存の `findExecutionClaim()`（untimed な `exposedTransaction`）を再利用せず、`prepareRecoveryStatement` / `insertRecoveryEvent` と同じ deadline-aware 経路を使う専用 repository API を足す（D8）。

解除 step 自体は失敗を伝播させ、tick 全体を failure にする。部分成功を隠さない。deadline 超過も failure であり、次 tick が新しい budget で再試行する。

### D5: 解除候補の走査は bounded batch + stable cursor にする

**帰属: agent 仮決め**

`LlmExecutionAdmissionHealth` に副作用のない `snapshotRecoveryBlockers(after: ClaimHealthKey?, limit: Int): List<RecoveryBlockerSnapshot>` を追加する。snapshot は `(invocationId, claimantToken)` の安定順で返し、recovery service は 1 tick あたり最大 `ADMISSION_BLOCKER_RESOLUTION_BATCH_LIMIT`（既定 20）件だけを評価する。singleton に DB 依存を持ち込まない（health は依然として純粋な in-memory state のまま）。

全件を毎 tick 評価しない理由は、registry が無制限に増えうる一方 tick 全体の budget が 5 秒しかないためである。解除されない blocker（RUNNING、静穏期間未経過、record なし）は remove されないので、先頭から毎回走査すると後方の解除可能な blocker が永久に starvation し、しかも tick が timeout して scan 本体にすら到達しなくなる。

cursor は service が保持し、batch を返しきったら次 tick は続きから評価する。`snapshotRecoveryBlockers` が limit 未満を返した時点で cursor を null に戻し、次 tick は先頭へ回る。cursor が指す blocker が既に解除されていても、`after` は「これより大きいキー」を返す比較なので破綻しない。

`MISSING_CLAIMANT_TOKEN` / `<unknown>` / `reflection-terminal:*` といった合成 token を持つ blocker も snapshot に現れる。invocationId で DB を引く判定は token に依存しないので、これらも同じ規則で扱える。ただし `reflection-terminal:*` は自前の retry loop が解除するため、通常はこの経路に到達する前に消える。両者が競合しても `resolveRecoveryBlocker` は冪等な remove なので害はない。

### D6: 監査イベント種別を 1 つ追加する

**帰属: agent 仮決め**

`CommandEventType.LLM_ADMISSION_BLOCKER_AUTO_RESOLVED` を追加し、`OpsRoutes.kt` の projection 名 `llmAdmissionBlockerAutoResolved` を追加する。`event_type` 列は `varchar(96)` で制約がなく、DB migration は不要。

payload には invocationId、claimantToken、observedStatus、elapsedQuietMillis、registeredAt を入れる。claimantToken は UUID 相当のランダム値で secret ではない（既存の recovery audit も token を扱う）。

### D7: audit 書き込みは recovery repository 内の deadline-aware transaction で行う

**帰属: agent 仮決め**

`CommandEventLog.append()` は untimed な `exposedTransaction` なので、tick の deadline に参加しない（D4 と同じ理由で使えない）。代わりに、既存の recovery mutation が使う `insertRecoveryEvent(event, deadline, nanoTime)` を再利用し、blocker 解除の判定と audit 書き込みを 1 つの deadline-aware transaction にまとめる（D8）。

`LlmExecutionRecoveryService` は `CommandEventLog` を直接受け取らず、repository API の戻り値だけを見る。`LlmExecutionRecoveryWorker` の配線変更は不要になる。

### D8: 判定と audit を 1 つの repository API に閉じる

**帰属: agent 仮決め**

`LlmLaunchReservationRepository` に次を足す。

```
suspend fun resolveAdmissionBlockerIfTerminal(
    request: LlmAdmissionBlockerResolutionRequest,
    deadline: LlmExecutionRecoveryDeadline,
): Result<LlmAdmissionBlockerResolution>
```

request は invocationId、claimantToken、quiet period を満たしているか（service が monotonic 値で判定済み）、audit payload に必要な観測値を持つ。実装は 1 transaction 内で `prepareRecoveryStatement` により reservation status を読み、terminal かつ service が quiet period 成立を宣言している場合だけ `insertRecoveryEvent` で audit を書いて `Resolved(status)` を返す。それ以外は `Retained(reason)` を返し、audit を書かない。

戻り値が `Resolved` の場合だけ、service が in-memory の `resolveRecoveryBlocker` を呼ぶ。audit が commit されない限り in-memory 解除も起きない（D3 の順序をトランザクション境界で保証する）。lookup 失敗や deadline 超過は `Result.failure` として service へ伝わり、blocker は残る。

quiet period 判定を service 側（monotonic）に置き、DB 側に置かないのは D2 と同じ理由（wall clock を判定に使わない）。

## Risks / Trade-offs

- **偽解除**: 静穏期間経過後も子孫 process が生きていれば、admission を開けた後に旧 process が動きうる。ただし D1 に列挙した 4 層（phase allowlist、manifest expiry、gateway binding、runner の注文直前 claim validation）により risk-increasing な注文へは到達しない。静穏期間はその外側の層。
- **静穏期間の設定変更**: `perRunTimeout` を 600 秒へ上げた運用では静穏期間も 610 秒になる。復旧が遅くなるが、危険側には振れない。
- **batch limit と復旧遅延**: blocker が 100 件溜まっている場合、全件評価に 5 tick かかる。heartbeat interval 単位なので実時間では数十秒程度で、障害時の 2 時間 15 分に比べれば無視できる。
- **reservation record が存在しない blocker**: 仕様上いつまでも残る。absence から終端を推論しない fail-closed invariant を優先した結果で、既知の残存 risk として受容する。production の登録元はいずれも reservation 作成後の invocationId なので、実際には発生しない想定。
- **snapshot と DB 問い合わせの間の race**: snapshot 取得後に新しい blocker が登録されても、その tick では扱わない。次 tick が拾う。逆に snapshot 後に別経路で解除された blocker を再度 remove しても冪等。

## Migration Plan

DB schema 変更なし。設定変更なし。deploy は通常の container 差し替えで完結する。ロールバックは revert のみ（新しい永続状態を作らないため）。

## Open Questions

なし。独立反証で挙がった blocking 3 件（deadline 非参加の DB read、unbounded snapshot による starvation、wall clock 後退）は D2 / D4 / D5 / D7 / D8 で処置済み。
