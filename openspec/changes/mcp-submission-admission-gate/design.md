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

- UNCERTAIN 終端した phase の直後から admission health が unhealthy になるようにし、次 phase の terminal submission を止める。
- admission health が unhealthy のとき、risk を増やす terminal submission が確定しないようにする。
- 拒否点を既存の閉じた rejection code 語彙に合流させ、監査から admission 起因の拒否を特定できるようにする。
- 「admission health が何を gate するか」を spec 上で確定させる。

**Non-Goals:**

- MCP process 全般の tool call を gate すること。read-only tool は資金を動かさず、`place_order` はどの phase の allowlist にも含まれない。
- manifest への `claimantToken` 追加、admission state の DB 投影、IPC 機構の新設。
- PR #351 が入れた blocker 自己解除ロジックの変更。
- `LlmExecutionRecoveryService.tick()` の健全性判定そのものの変更。
- 監視・アラート基盤の追加（Epic #286 の方針）。

## Decisions

### D1: phase 境界で UNCERTAIN を admission へ反映する（F1 の処置）

**帰属: ユーザー確認済み**（反証結果を提示のうえ「F1 を直す scope 拡大へ」を選択）

`LlmInvocationAuditor.invokeAndAudit` が `llmInvoker.invoke(request)` から戻った直後（`LlmInvocationAuditor.kt:96` の後）に、当該 invocation の termination proof を確認し、UNCERTAIN なら `registerRecoveryBlocker` を呼ぶ。

**成立の根拠**: `LlmInvoker.invoke()` は返る時点で必ず `record()` を済ませている（正常系 `:115`、cancellation `:146`、その他 `:157`）。したがって auditor に制御が戻った時点で proof は確定しており、次 phase 起動より前に admission へ反映する隙間が存在する。

`runOneShot` の `finally` にある既存の登録はそのまま残す。phase 境界で登録済みなら二重登録になるが、`recoveryBlockers` は `ConcurrentHashMap.newKeySet` なので同一 key の再追加は冪等である。

**却下した代替**:

- **`LlmProcessTreeTerminationRegistry` を gate の判定に直接使う**。gateway が `find(invocationId)` を見る案。`find()` は `childUnresolved`（子が起動して未終了）でも UNCERTAIN を返す（`LlmInvoker.kt:219-220`）ため、**FALSIFIER 自身が実行中はその FALSIFIER の子が未解決で UNCERTAIN になり、FALSIFIER の正当な submission まで拒否する**。registry が invocationId 単位で phase を区別しないので成立しない。
- **`runOneShot` の phase 呼び出し間に明示的なチェックを挟む**。auditor 側に置くより呼び出し箇所が増え、将来 phase が追加されたときに漏れる。

### D2: gate は risk を増やす submission に限定する（F5 の処置）

**帰属: agent 仮決め**

gateway の admission precondition は、`SUBMIT_FALSIFICATION` と、risk を増やす action の `SUBMIT_DECISION` にのみ適用する。`RISK_REDUCTION_ONLY_ACTIONS`（`EXIT` / `REDUCE` / `ADJUST_PROTECTION` / `NO_TRADE`）の decision submission は admission が unhealthy でも通す。

**理由**: 既存の `ToolCallGuard` が HARD_HALT 中でも decision を通す設計と方向を揃える。admission fail-closed の目的は「信頼できない状態で新しいリスクを取らせない」ことであり、既にあるリスクを減らす操作を止めるのは目的に反する。`NO_TRADE` は何も起こさないので止める理由がない。

この判断は action を見るため、precondition は payload decode の後に置く必要がある。D3 はこれに合わせて改める。

### D3: precondition の位置は 2 段に分ける

**帰属: agent 仮決め**

- `SUBMIT_FALSIFICATION`: phase 認可の直後、payload decode の前。falsification は承認そのものなので action による例外がない。
- `SUBMIT_DECISION`: payload decode の後、repository 呼び出しの直前。action を見て D2 の判定を行うため。

初版は「binding 検証より前」としていたが、D2 が action を要求するので撤回する。F6（binding 不一致 client への health 1bit 漏洩）もこの変更で解消する。binding 検証が先に走るため、binding が一致しない client は admission の状態を観測できない。

### D4: gate 条件は 3 集合のみを使う（F3 の処置）

**帰属: agent 仮決め**（falsifier も同結論を推奨）

`isHealthy()` をそのまま使わず、`ambiguousClaims` / `recoveryBlockers` / `heartbeatFailures` の 3 集合が空であることを条件とする。`recoveryScanHealthy` と `heartbeatHealthy` の 2 flag は見ない。

**理由**: `LlmExecutionRecoveryService.tick()` は tick 開始時に無条件で `setRecoveryScanHealthy(false)` し、成功時に true へ戻す（`LlmExecutionClaimSupervisor.kt:181-190`）。tick は既定 28.5 秒間隔で回り、実行時間の分だけ `recoveryScanHealthy` が false になる。submission は run ごとに 1 回の一点イベントなので、この窓に当たると正常な run の decision が誤拒否される。

一方 issue の実害シナリオ（UNCERTAIN 終端）は `recoveryBlockers` に表現される。`recoveryScanHealthy` を条件から外しても捕捉率は落ちない。`heartbeatHealthy` は production の setter が存在せず（定義は `LlmExecutionAdmissionHealth.kt:39` のみ）、実働の heartbeat failure は `heartbeatFailures` 集合に入る。

この判定用に `LlmExecutionAdmissionHealth` へ専用の read API を追加する。既存の `isHealthy()` は新規起動と `/health/ready` の意味論を保つため変更しない。

**受容するコスト**: submission gate と新規起動 gate で条件が異なる。ただし両者は目的が違う（前者は「この invocation が信頼できるか」、後者は「新しい起動を許してよいか」）ので、条件の差は spec に明記して意味を確定させる。

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
- **[phase 境界登録による fail-closed の前倒し]** → D1 により、UNCERTAIN が出た時点で新規起動も止まる。これは意図した挙動だが、従来より早く止まるため `/health/ready` が false になる頻度が上がりうる。PR #351 の blocker 自己解除（DB が terminal を確認したら解除）が回復経路として働く。
- **[submission gate と新規起動 gate で条件が異なる]** → D4 の通り意図的。spec に両者の条件を明記する。
- **[残余 TOCTOU race]** → D5 の通り受容して明記する。
- **[test 間の singleton 汚染]** → `LlmExecutionAdmissionHealthTestFixture.reset()` を必ず使う。
- **[read-only MCP tool call は依然 gate されない]** → 意図した非対称として spec に明記する。

## Migration Plan

単一 PR。DB schema 変更なし、wire 互換の破壊なし。rollback は revert のみ。

## Open Questions

なし。F1〜F5 の処置は D1〜D5 で確定した。F6 は D3 の変更により解消。F7（代替案評価の書き直し）は本文の却下理由から過小評価の記述を除いて対応済み。
