## Context

`LlmExecutionAdmissionHealth`（`trading/.../daemon/LlmExecutionAdmissionHealth.kt`）は process-local な Kotlin `object` で、`heartbeatHealthy` / `recoveryScanHealthy` の 2 flag と `ambiguousClaims` / `recoveryBlockers` / `heartbeatFailures` の 3 集合から `isHealthy()` を導く。false のとき新規 LLM 起動（`tryReserve` / `claimForExecution`）と `/health/ready` が止まる。

現状の gate 適用範囲を経路ごとに並べると、非対称が見える。

| 経路 | admission gate | 実行 process |
|---|---|---|
| 新規 LLM 起動（`tryReserve`） | あり（`withHealthyAdmission`） | app |
| runner の発注（`validateExecutionAdmission`） | あり | app |
| **submission gateway（`submit_decision` / `submit_falsification`）** | **なし** | **app** |
| MCP tool call 全般 | なし | MCP（別 process） |

この表の 3 行目が実測で確認された欠落である。admission health を fail-closed にした状態で MCP tool を呼ぶ probe を同一 JVM 内で実行したところ、`get_ticker` と `submit_decision` の双方が通り、`submit_decision` は decision を repository へ記録した。同一 process という最も有利な条件でも素通りしたので、admission チェックが handler のコードパス上に存在しないことの直接証明になる。

### 独立反証で判明した、より深い欠陥

初版の設計は「gateway に precondition を 1 つ足せば issue #352 の中心シナリオが閉じる」としていた。独立 falsifier がこれを反証した。**blocker の登録が遅すぎて、gate を置いても中心シナリオを通過できる。**

`LlmProcessTreeTerminationRegistry` は phase ごとの子プロセス終了時に proof を記録する（`LlmInvoker.kt:115,146,157`）。しかし `LlmExecutionAdmissionHealth.registerRecoveryBlocker` を呼ぶのは `runOneShot` 全体の `finally`（`OneShotLlmRunner.kt:619-620`）だけである。

一方 PROPOSER と FALSIFIER は同一 `invocationId` の同一 `runOneShot` 呼び出しの中で順に走る（`runOneShotAfterPreflight:1294` → `runApprovedEntryFlow:1439` → `runFalsifierPhase:1456`）。

したがって PROPOSER が UNCERTAIN 終端しても、FALSIFIER が動いている間 admission は healthy のままで、issue の中心シナリオ（PROPOSER が intent 発行 → termination UNCERTAIN → FALSIFIER が承認）は gate を素通りする。**gate の位置ではなく、blocker 登録のタイミングが問題だった。**

同時に、gate の適用範囲にも安全方向の逆転が見つかった。`RISK_REDUCTION_ONLY` phase は `EXIT` / `REDUCE` / `ADJUST_PROTECTION` を submit するために存在する（`LlmDecisionSubmissionGateway.kt:800-805`、`OneShotLlmRunner.kt:1060`）。既存の `ToolCallGuard` は decision を HARD_HALT 中でも通す（`ToolCallGuard.kt:79-87`）。これは意図的な安全方向で、危険なときこそポジションを閉じさせる。全 `SUBMIT_DECISION` を一律に gate すると、**admission が不健全なときに限って建玉を減らせなくなる**。

## Goals / Non-Goals

**Goals:**

- UNCERTAIN 終端した phase より後の terminal submission を、同一 invocation 内で止める。
- admission blocker があるとき、risk を増やす terminal submission が確定しないようにする。
- 拒否点を既存の閉じた rejection code 語彙に合流させ、監査から admission 起因の拒否を特定できるようにする。
- 「admission health が何を gate するか」を spec 上で確定させる。

**Non-Goals:**

- MCP process 全般の tool call を gate すること。read-only tool は資金を動かさず、`place_order` はどの phase の allowlist にも含まれない。
- manifest への `claimantToken` 追加、admission state の DB 投影、IPC 機構の新設。
- PR #351 が入れた blocker 自己解除ロジックの変更。
- `LlmExecutionRecoveryService.tick()` の健全性判定そのものの変更。
- 監視・アラート基盤の追加（Epic #286 の方針）。

## Decisions

### D1: 完了済み child の historical uncertainty を gate 条件に加える（F1 の処置）

**帰属: ユーザー確認済み**（2 度の反証結果を提示のうえ「historical uncertainty API 案へ」を選択）

`LlmProcessTreeTerminationRegistry` に、**完了済み child の UNCERTAIN 履歴だけ**を返す read API を追加する（例: `hasCompletedUncertainChild(invocationId)` = `proofs[id]?.anyUncertain == true`）。gateway の precondition はこれを admission blocker と並ぶ第 2 の条件として見る。

**成立の根拠**: `anyUncertain` は `record()` でのみ立つ（`LlmInvoker.kt:209`）。`record()` は child 終了時に呼ばれるため、この flag は「完了した child のうち少なくとも 1 つが UNCERTAIN だった」を意味する。`markChildStarted` は `copy(childUnresolved = true)` するだけで `anyUncertain` を消さない（`:197-200`）ので、後続 phase の実行中も履歴が保持される。

状態遷移で確認すると意図通りに働く。

| 時点 | `childUnresolved` | `anyUncertain` | gate |
|---|---|---|---|
| 正常な最初の PROPOSER 実行中 | true | false | 許可 |
| PROPOSER が UNCERTAIN で完了 | false | **true** | 以降拒否 |
| 続く FALSIFIER 実行中 | true | **true** | 拒否 |
| PROPOSER が PROVEN_EXITED で完了 | false | false | 許可 |

registry entry は UNCERTAIN でない場合のみ `resolve()` される（`OneShotLlmRunner.kt:637-640`）ので、UNCERTAIN の履歴は run 終了まで残る。

**却下した代替**:

- **phase 境界で `registerRecoveryBlocker` を呼ぶ**（初版改訂案）。**自己ロックアウトを起こすため成立しない**。`invokePhase` 直後の `requireLiveClaimForInvocation`（`OneShotLlmRunner.kt:1377`）は `requireLiveClaim`（`:647`）→ `validateExecutionAdmission` → `withHealthyAdmission`（`ExposedLlmLaunchReservationRepository.kt:317`）と辿り、`check(isHealthyLocked())` で throw する。PROPOSER 終了直後に blocker を登録すると、その直後の自分自身の claim 確認が自分の blocker で落ち、decision を読む前に run 全体が死ぬ。さらに `handleNonEnterDecision`（`:1398`）も同じ経路を通るため、D2 で守ったはずの risk-reducing action が別経路で実行不能になる。加えて blocker は reservation terminal 後さらに hardTimeout + processTerminationGrace 経過まで解除されない（`LlmExecutionClaimSupervisor.kt:311-340`）ので、影響範囲も広い。
- **`LlmProcessTreeTerminationRegistry.find()` を gate 条件に使う**。`find()` は `childUnresolved` でも UNCERTAIN を返す（`LlmInvoker.kt:219-220`）ため、**実行中の child 自身が UNCERTAIN と見え、正常な PROPOSER submission を 100% 拒否する**。`anyUncertain` のみを見る新 API はこの偽陽性を持たない。

**この判定は admission health を変更しない**ため、`isHealthy()` の意味論、新規起動 gate、`/health/ready`、runner の `validateExecutionAdmission` はいずれも不変である。gate は gateway の中だけで閉じる。

### D1a: UNCERTAIN entry の lifecycle を閉じる（H1 の処置）

**帰属: agent 仮決め**

`LlmProcessTreeTerminationRegistry` の production な `resolve()` 呼び出しは `OneShotLlmRunner.kt:640` の 1 箇所だけで、しかも `processTreeTerminationProof != UNCERTAIN` の条件下にある（`:637`）。したがって **UNCERTAIN の entry は JVM 終了まで残る**。registry は process-global な `ConcurrentHashMap` で、Reflection / EVALUATION_REPORT / daemon scheduler など他 lifecycle も同じ `ShellLlmInvoker` を共有するため、entry は invocation ごとに蓄積する。

これは既存のリークだが、この change が registry を gate の判定材料にすることで correctness 問題へ格上げされる。同じ invocationId で後続の gateway が作られた場合、恒久的に拒否される。

**処置**: `finally` の末尾で、UNCERTAIN の場合も `LlmProcessTreeTerminationRegistry.resolve(invocationId)` を呼ぶ。

**安全性の根拠**: この時点で当該 run の全 phase の gateway は既に close されている（`LlmInvocationAuditor.kt:110` が phase ごとに close する）。したがって履歴を残す必要がない。UNCERTAIN が意味する「終了を証明できない child が残りうる」ことは、同じ `finally` の `registerRecoveryBlocker`（`:620`）によって admission blocker へ移されており、そちらは DB terminal 確認と exact token 一致による既存の解除契約（PR #350 / #351）で管理される。registry の役割は「同一 run 内の後続 phase へ履歴を伝える」ことに限定され、run が終わればその役割は尽きる。

`LlmExecutionAdmissionHealth.resolveClaim` と `LlmExecutionTerminationFenceRegistry.resolve` は従来どおり UNCERTAIN では呼ばない。これらは blocker の解除に相当し、DB 確認を経ずに解除してはならないためである。**registry の resolve だけを条件から外す。**

### D2: gate は risk を増やす submission に限定する（F5 の処置）

**帰属: agent 仮決め**

gateway の precondition は、`SUBMIT_FALSIFICATION` と、`EXIT` / `REDUCE` / `NO_TRADE` **以外**の action の `SUBMIT_DECISION` に適用する。`EXIT` / `REDUCE` / `NO_TRADE` の decision submission は gate 条件が該当しても通す。

**理由**: 既存の `ToolCallGuard` が HARD_HALT 中でも decision を通す設計と方向を揃える。gate の目的は「信頼できない状態で新しいリスクを取らせない」ことであり、既にあるリスクを減らす操作を止めるのは目的に反する。`NO_TRADE` は何も起こさないので止める理由がない。

**`ADJUST_PROTECTION` は例外に含めない**。runner の実装は take-profit のみを変更し、stop は変更しない（`DecisionExecutionLifecycle.kt:299-305` が `newTakeProfitPriceJpy` だけを渡す）。検証も `targetPrice <= currentPrice` と `targetPrice <= stopPrice` を弾くだけで、既存 TP との単調性も上限も課さない（`:421-427`）。したがって take-profit を無制限に遠ざけて exposure を延ばすことができ、risk を減らす保証がない。`RISK_REDUCTION_ONLY_ACTIONS` に含まれることは runner の phase 制約であって、untrusted な submitter に対する安全性の証明ではない。

monotonic tightening（新 TP が既存 TP 以下）を条件に例外化する案もあるが、既存 TP の取得と比較を gateway へ持ち込むことになり、gate 1 つの change には重い。`ADJUST_PROTECTION` を gate 対象に含めても、正当な run は gate 条件が該当しないので通る。

この判断は action を見るため、precondition は payload decode の後に置く必要がある。D3 はこれに合わせて改める。

### D3: precondition の位置は 2 段に分ける

**帰属: agent 仮決め**

- `SUBMIT_FALSIFICATION`: phase 認可の直後、payload decode の前。falsification は承認そのものなので action による例外がない。
- `SUBMIT_DECISION`: payload decode の後、repository 呼び出しの直前。action を見て D2 の判定を行うため。

初版は「binding 検証より前」としていたが、D2 が action を要求するので撤回する。F6（binding 不一致 client への health 1bit 漏洩）もこの変更で解消する。binding 検証が先に走るため、binding が一致しない client は admission の状態を観測できない。

### D4: 実行中の scan と失敗した scan を区別する（F3 / R4 の処置）

**帰属: ユーザー確認済み**（R4 の反証結果を提示のうえ「直近 tick 失敗を区別して gate」を選択）

`recoveryScanHealthy` は 2 つの異なる意味に使われている。呼び出し 11 箇所のうち、**tick 冒頭の 1 箇所だけが「正常な scan の実行中」を表し、残り 10 箇所はすべて実障害を表す**。

| 呼び出し元 | 意味 |
|---|---|
| `LlmExecutionClaimSupervisor.kt:182`（tick 冒頭の無条件 false） | **正常な実行中** |
| `:216` scan 失敗 / `:268` `:291` recovery mutation 失敗 / `:321` blocker read 失敗 / `:367` audit append 失敗 / `:434` outcome unknown | 実障害 |
| `LlmExecutionRecoveryWorker.kt:71` tick 例外 | 実障害 |
| `Application.kt:925,992` startup recovery 失敗 | 実障害 |

初版の D4（3 集合のみを見る）は、この 2 つをまとめて無視していた。誤拒否は消えるが、**recovery が stale claim を発見できない状態でも risk-increasing submission を通す**。これは fail-closed の目的に反する。

そこで「scan が現在実行中である」ことを表す状態 `recoveryScanInProgress` を分離し、`recoveryScanHealthy` を「最後に完了した scan の成功状態」として定義し直す。状態機械は次のとおり。

| 遷移 | `recoveryScanHealthy` | `recoveryScanInProgress` |
|---|---|---|
| production 初期状態 | false（`Application.kt:925` が初回 tick 前に false にする） | false |
| tick 開始 | 前回の値を維持 | true |
| tick 成功 | true | false |
| tick 失敗 / timeout / cancellation | false | false |

**tick の終了時は成否によらず必ず `recoveryScanInProgress` を false にする**。個別の failure site に依存すると漏れるため、tick の完了 API で集約し、`try`/`finally` で確実に下ろす。両フラグの更新は `admissionLock.write` の内側で行い、submission 側の read に中間状態を見せない。

判定条件は経路ごとに次のとおり。

- **submission gate**: 3 集合が空、かつ `recoveryScanHealthy` が true。`recoveryScanInProgress` は無視する
- **新規起動 / `/health/ready`**（`isHealthy()`）: 従来の条件に `!recoveryScanInProgress` を加える

これにより F3 の誤拒否（正常 tick 窓）を避けつつ、R4 の実障害を取りこぼさない。初回 tick 成功前と直近 tick 失敗後は risk を増やす submission が拒否され、次の成功 tick で自動回復する。`heartbeatHealthy` は production の setter が存在せず（定義は `LlmExecutionAdmissionHealth.kt:39` のみ）、実働の heartbeat failure は `heartbeatFailures` 集合に入るため、submission の条件に含めない。

`isHealthy()` は**外部から観測できる判定結果を変更しない**。内部式には `!recoveryScanInProgress` を加える必要があるため実装は変わるが、tick 実行中に false を返す従来の挙動は保たれる。新規起動と readiness が scan 実行中も fail-closed になるのは従来どおりで、新規起動は頻度が低く数百ミリ秒の待ちが実害にならない。

**受容するコスト**: submission gate と新規起動 gate で条件が 1 つ異なる（scan 実行中の扱い）。両者は目的が違う（前者は「この結論を確定してよいか」、後者は「新しい起動を許してよいか」）ので、差は spec に明記して意味を確定させる。

### D5: TOCTOU race は commit 直前検査で縮小し、残余を明示する（F2 の処置）

**帰属: agent 仮決め**

precondition の検査位置を repository 呼び出しの直前に置く（D3）ことで、検査から commit までの区間を最小化する。`withHealthyAdmission` は suspend 関数を受けられないため、検査と commit を完全に atomic にはできない。

残余 race（検査通過後、repository commit 前に blocker が登録される）は受容し、spec に「gate は best-effort であり、検査通過後に unhealthy へ遷移した場合の commit は許容される」と明記する。

**理由**: この race を閉じるには claim fence を submission 経路へ通す設計が要り、新レイヤーの追加になる。得られるのはミリ秒オーダーの窓の解消で、D1 が主要な穴を閉じたあとの残余としては割に合わない。

### D6: gateway は invocation を終端させない

**帰属: agent 仮決め**

拒否のみを行い、reservation の FAILED 化や process kill は行わない。終端は recovery scanner の責務で、そこには条件付き UPDATE による fence と `llm_pid_registrations` の TERMINAL 化がセットで組まれている。gateway から別経路で終端すると二重終端の競合設計が新たに要る。

## Risks / Trade-offs

- **[誤拒否された run の回復は保証されない]**（F4） → D2 と D4 により誤拒否の主要因を除いたが、正当な拒否のあと LLM が再提出する保証はない。tool call budget は拒否でも 1 消費し（`McpToolCallLimiter.kt:81`）、manifest 有効期限は phase timeout そのもの、auditor は invoke 終了後すぐ gateway を close する。**再提出可能性を保証として使わない**。拒否された run は NO_TRADE として終端する前提で設計する。
- **[gate 条件が 2 系統になる]** → D1 の historical uncertainty は invocation-local、admission blocker は process-global で、意味も生存期間も異なる。gateway はこの 2 つを OR で見る。spec に両者の役割を明記して混同を防ぐ。
- **[historical uncertainty は run 終了まで解除されない]** → registry entry は UNCERTAIN でない場合のみ `resolve()` される（`OneShotLlmRunner.kt:637-640`）。一度 UNCERTAIN が出た run では、以降の terminal submission がすべて拒否される。これは意図した挙動で、当該 run は NO_TRADE として終端する。
- **[submission gate と新規起動 gate で条件が異なる]** → D4 の通り意図的。spec に両者の条件を明記する。
- **[残余 TOCTOU race]** → D5 の通り受容して明記する。
- **[test 間の singleton 汚染]** → `LlmExecutionAdmissionHealthTestFixture.reset()` を必ず使う。
- **[read-only MCP tool call は依然 gate されない]** → 意図した非対称として spec に明記する。

## Migration Plan

単一 PR。DB schema 変更なし、wire 互換の破壊なし。rollback は revert のみ。

## Open Questions

なし。F1〜F5 の処置は D1〜D5 で確定した。F6 は D3 の変更により解消。F7（代替案評価の書き直し）は本文の却下理由から過小評価の記述を除いて対応済み。
