## Context

`LlmExecutionAdmissionHealth` は process-local singleton で、5 つの state（`heartbeatHealthy` / `recoveryScanHealthy` / `ambiguousClaims` / `recoveryBlockers` / `heartbeatFailures`）のいずれかが不健全なら `isHealthy() = false` を返す。この値は `claimForExecution` / `validateExecutionAdmission` の `withHealthyAdmission` gate と `/health/ready` の両方に入っている。

`recoveryBlockers` の登録経路は 3 つある。

1. `OneShotLlmRunner.kt:620` — process tree termination proof が `UNCERTAIN`（timeout / cancellation による forced kill 後、`/proc` 走査後の遅延 fork を排除できない）
2. `LlmExecutionClaimSupervisor.kt:308` — recovery scan が CLAIMED 候補に対して termination fence を見つけられない
3. `LlmExecutionClaimSupervisor.kt:351` — recovery mutation の precondition が scan 時点から変化した

解除経路は `LlmExecutionRecoveryService.completeRecoveryHealth()` の 1 つだけで、これは `scanStaleExecutionClaims` が返した候補にしか作用しない。その SQL は `status = 'RUNNING'` の行だけを返す。

固着の因果はこうである。経路 1 では、`OneShotLlmRunner` の同一 `finally` ブロックが blocker 登録（620 行）の直後に reservation を terminal へ persist する（631〜634 行）。UNCERTAIN の場合は 636 行の条件で `resolveClaim` をスキップするため、blocker は残ったまま reservation が `RUNNING` を離れる。以後 scan の候補集合から永久に消えるので、blocker を触る経路が構造的に消滅する。

**思考実験で言えばこうなる。** 部屋の鍵を持った作業員が中で行方不明になった。管理人は「作業員が中にいるかもしれない」と入室を止める（正しい）。次に事務方が作業台帳を「作業終了」で締める。翌朝からの巡回は「作業中の台帳」だけを見て回るので、締められた台帳のこの部屋は巡回コースから外れる。管理人の札は誰も外さない。建物ごと再起動（`docker restart`）するまで、入室禁止は解けない。

実障害では 04:41 に blocker が付き、07:06 の `docker restart` まで daemon が 1 回も起動しなかった。

`heartbeatFailures` も同じ形の固着を持つ。`heartbeatClaim` は heartbeat 失敗時に `recordHeartbeatResult(healthy = false)` を残して return し、UNCERTAIN 経路では `resolveClaim` が呼ばれないため、同じ理由でこの key を触る経路がない。実障害では観測されていないが、原因は同一である。

## Goals / Non-Goals

**Goals:**

- DB の終端事実だけを根拠に、in-memory blocker を recovery scan の各 tick で自己解除する
- 遅延 fork した child が注文を出しうる窓の間は解除しない
- 解除の監査証跡を `command_event_log` に残す
- 解除判定が recovery tick の 5 秒 bounded budget と `check(pendingRecoveries.isEmpty())` の invariant を壊さない

**Non-Goals:**

- fail-closed 機構自体の撤去・緩和
- codex CLI timeout の解消
- blocker の永続化・分散対応
- 監視・アラート基盤の追加（Epic #286 の方針）
- `ambiguousClaims` の解除経路の変更（`LlmExecutionClaimSupervisor.reconcile` が既に自前の retry loop で解決している）
- runtime から blocker を無条件に消す public reset API の追加（`test-admission-health-isolation` spec が「complete reset MUST be available through a test-fixture variant only」を要求している）

## Decisions

### Decision 1: 解除判定は blocker 起点で、scan 候補とは独立した pass にする

`scanStaleExecutionClaims` を「terminal 行も返す」ように広げる案を捨てた。あの SQL は「stale な RUNNING を FAILED へ遷移させる候補」を返す契約で、cursor（`sort_heartbeat_at, sort_claimed_at, invocation_id`）と `EXECUTION_RECOVERY_SCAN_LIMIT = 100` の paging がその意味に紐づいている。terminal 行を混ぜると、`toRecoveryRequestOrNull` が terminal 行に対して recovery request を作らないよう追加分岐が必要になり、`stagePendingRecovery` / `pendingRecoveries` invariant にも terminal 行という別種の要素が入る。scan の意味が二重になる。

代わりに `tickWithinBudget` の中で、blocker registry を起点に `findExecutionClaim(invocationId)` を引く独立 pass を持つ。blocker 集合は process-local で、通常 0〜数件である（100 件の scan limit のような bound が必要な規模ではないが、後述の Decision 5 で bound を置く）。

判定の入力は「blocker の (invocationId, claimantToken)」と「その invocationId の DB snapshot」だけで、推測は入らない。

### Decision 2: 解除条件は「terminal かつ token 厳密一致かつ finished_at + hardTimeout + grace 超過」

3 条件すべてを要求する。

- **terminal**: `snapshot.status != RUNNING`。RUNNING のままなら claim はまだ生きうるので解除しない
- **token 厳密一致**: blocker の `claimantToken` が `snapshot.claimantToken` と一致する。`MISSING_CLAIMANT_TOKEN` の blocker は `snapshot.claimantToken == null` の場合に対応させる
- **窓超過**: `now >= snapshot.finishedAt + policy.hardTimeout + policy.processTerminationGrace`

窓の根拠は UNCERTAIN の意味そのものである。`docs/mcp-runtime.md` が書くとおり、UNCERTAIN は「`/proc` 走査完了後に発生し得る遅延 fork を排除できない」状態を指す。そこで fork された child が生きうる上限として、その invocation 全体に許された実行時間の上限、すなわち `hardTimeout` を採る。加えて termination grace を足す。

`hardTimeout` は `OneShotExecutionPolicy.from()` が `perRunTimeout × 3`（claimed one-shot の 3 phase 分）に `finalizationGrace`（`processTerminationGrace × 2 + persistenceTerminalTimeout`）を足した値である。既定値（`perRunTimeout` 180 秒 / `processTerminationGrace` 10 秒 / `persistenceTerminalTimeout` 10 秒）では 570 秒になる。したがって窓は 570 + 10 = 580 秒（約 9.7 分）で、recovery tick 間隔を含めた復旧は 10 分程度に収まる。実障害の 2 時間 15 分との差が修正の効果である。

なお 1 phase の CLI 呼び出し上限は `phaseTimeout` = `perRunTimeout`（既定 180 秒）で、実障害の codex timeout 190 秒はこの値に対応する。窓に `phaseTimeout` ではなく `hardTimeout` を採るのは、遅延 fork が「どの phase の child か」を blocker から判別できず、invocation 全体の上限で保守的に見る必要があるためである。

代替案として `grace` のみ（約 10 秒）を検討したが、遅延 fork child の生存窓と重なる時間帯に admission を開けることになり、5 不変条件の「生死不明の CLI が注文を出しうる間は止める」という設計意図を崩す。逆に窓を 2 倍で取る案は、数値の根拠が「念のため」になり説明がつかない。

`finished_at` を基準時刻に選ぶのは、それが「reservation が terminal になった時刻」という DB 上の事実だからである。`clock.instant()` の観測時刻を基準にすると、process 再起動をまたいだ場合に窓が伸び縮みする。

### Decision 3: `LlmExecutionClaimSnapshot` に `finishedAt` を additive に追加する

Decision 2 が `finished_at` を必要とする。`LlmExecutionClaimSnapshot` は現在この列を持たないが、`SELECT_LLM_EXECUTION_CLAIM_SQL` に列を 1 つ足して mapper を追随させるだけで済む。

`llm_launch_reservations.finished_at` は既存列（`INSERT` で NULL、`finish()` で set）なので schema 変更は不要である。

`SELECT_STALE_LLM_EXECUTION_CLAIMS_SQL` 側も snapshot 型を共有するため列を足すが、stale scan 候補は定義上 `RUNNING`（= `finished_at IS NULL`）なので値は常に null であり、既存の判定に影響しない。in-memory 実装の `toClaimSnapshot()` も同様に追随させる。

`claimRejection()` などの既存判定は `finishedAt` を読まない。additive であり、既存フィールドの意味は変わらない。

### Decision 4: 解除は `resolveClaim` で 3 カテゴリまとめて行う

`resolveClaim(invocationId, claimantToken)` は `ambiguousClaims` / `recoveryBlockers` / `heartbeatFailures` の同一 key を消す。Context で述べたとおり `heartbeatFailures` も同型の固着を持つので、両方を対象にする。

`recoveryBlockers` だけを選択的に消す方が実装は複雑になる（新しい選択 API が必要）うえ、`heartbeatFailures` の固着を残す。解除根拠（DB terminal + token 厳密一致 + 窓超過）は両カテゴリで同一である。reservation が terminal 済みなら、その claim の heartbeat が過去に失敗したかどうかはもう admission 判断に寄与しない。

`ambiguousClaims` も同じ key で消えるが、`LlmExecutionClaimSupervisor.reconcile` は同じ terminal 確認で既に解除しており、この pass が先に到達しても意味は同じである。

`ReflectionTerminalPersistenceSupervisor` の合成 token（`reflection-terminal:<invocationId>`）は DB の `execution_claim_token` と一致しないため、token 厳密一致の条件で自動的に対象外になる。除外リストのような特別扱いを書かずに済む。これが Decision 2 の token 一致条件を選んだ副次的な理由でもある。reflection の terminal persistence は自前の retry loop が解決する。

### Decision 5: pass は recovery tick の中で bounded に実行し、既存の失敗意味論に従う

配置は `tickWithinBudget` の `reconcilePendingRecoveries` の後、stale scan の前とする。`pendingRecoveries` が空でない状態は「前 tick の recovery が未確定」を意味し、その解決を先に済ませてから blocker 照合に入る方が、同一 invocation に対する判断の順序が素直になる。

bound は既存の作法をそのまま使う。

- 各 blocker の処理前に `requireRecoveryStartReserve(deadline)` を呼ぶ（`ensureActive()` + 750ms start reserve）
- `findExecutionClaim` の失敗は `setRecoveryScanHealthy(false)` して throw する。既存の scan / mutation 失敗と同じ扱いで、tick 全体が失敗し worker が warning を出す
- 監査 append の失敗も同様に throw する。監査が残らないまま解除だけが起きる状態を作らない

処理件数の上限は blocker registry の実サイズに委ねる。100 件の paging を持つ stale scan と違い、blocker は process-local で 1 invocation につき最大 1 件しか登録されないため、bound は deadline の 750ms reserve が実効的に効く。

`LlmExecutionRecoveryService` は現在 `CommandEventLog` を持たないので、constructor に追加する。`LlmExecutionRecoveryWorker` は既に `commandEventLog` を持っているのでそれを渡す。テストの既存 `LlmExecutionRecoveryService(...)` 呼び出しは、監査を検証しないものには `InMemoryCommandEventLog` を渡す。

### Decision 6: 監査イベントは新 `CommandEventType` を 1 つ追加する

`LLM_EXECUTION_ADMISSION_BLOCKER_RESOLVED` を追加する。payload は secret を含まない事実だけを入れる。

```json
{
  "invocationId": "...",
  "claimantToken": "...",
  "reservationStatus": "FAILED",
  "finishedAt": "2026-07-31T04:41:12Z",
  "resolvedAt": "2026-07-31T04:45:30Z",
  "clearanceWindowSeconds": 200
}
```

`claimantToken` は process 内で生成される claim 識別子で、外部 credential ではない。既存の `LLM_EXECUTION_RECOVERY_STARTED` と同じ `llm_execution_recovery` tool name を使う。

`OpsRoutes.kt:2247` 付近の `toActivityAuditEventDefinition()` は網羅 `when` なので、新 enum 値の追加でコンパイルエラーになる。`llmExecutionAdmissionBlockerResolved` を足して追随させる。

### Decision 7: `LlmExecutionAdmissionHealth` に read-only な列挙 API を追加する

pass は blocker 集合を読む必要がある。`recoveryBlockers` と `heartbeatFailures` の (invocationId, claimantToken) を返す read API を追加する。`ClaimHealthKey` は private data class なので、public な戻り値型を新設するか、既存の `LlmExecutionClaimTransition` 相当の形で返す。

read-only であり、`test-admission-health-isolation` spec が禁じる「runtime route / configuration / public production reset capability」には当たらない。既存の `resolveClaim` を解除に使うので、新しい mutation API は追加しない。

## Risks / Trade-offs

**[遅延 fork child が hardTimeout を超えて生存する] → 未解決の残存リスクとして受け入れる。** `hardTimeout` は invocation 全体に許された実行時間の上限であり、fork された child がこれを超えて生き続ける状況は、CLI 側が上限を無視した場合に限られる。

この残存リスクの評価にあたり、当初この設計は「child が注文を出すには MCP gateway 経由の claim validation を通るので二重に守られる」と記述していたが、これは**誤りであり撤回する**（反証ゲートで指摘された）。MCP module（`mcp/` / `mcp-core/` / `mcp-gmo-coin/`）には `validateExecutionAdmission` も `LlmExecutionAdmissionHealth` への参照も存在しない。`ToolCallGuard` が見るのは `RiskHaltState.HARD_HALT` だけである。さらに MCP server は `:mcp:buildFatJar` の独立 fat jar として stdio server で起動される別 process であり、process-local singleton である `LlmExecutionAdmissionHealth` は原理的に到達しない。

重要なのは、この欠落が blocker の有無と無関係であることである。`LlmExecutionAdmissionHealth.isHealthy()` が gate するのは `withHealthyAdmission {}` の呼び出し元（`tryReserve` / `claimForExecution` / `validateExecutionAdmission` と runner 内の live claim 確認）だけで、いずれも「新規 LLM 起動」と「同一 runner process 内の確認」の経路である。既に走っている child の発注能力は blocker が立っていても止まらない。したがってこの change は既存の欠落を悪化させず、「新規 admission を止め続ける期間」を短縮するだけである。

残存リスクの深刻度を下げる既存の構造として、単独の child は発注を完遂できない。`place_order` は `proposerTools` / `falsifierTools` / `riskReductionTools` のいずれの allowlist にも含まれず（`McpToolContractCatalog.kt:9-22`）、`McpToolCallLimiter` の allowlist フィルタが manifest 記載の tool だけを通す。加えて intent の発行（`submit_decision`、PROPOSER 専用）と承認（`submit_falsification`、FALSIFIER 専用）は別 phase に分離され、1 つの MCP server process は起動時に単一 phase へ bind される。

MCP tool-call 経路への admission gate 追加は、別 process への状態伝播という新しい機構（DB 経由の共有か IPC）を要し、issue #350 の受け入れ条件にも紐付かないため follow-up issue とする。

**[`finished_at` が NULL の terminal 行] → 解除しない。** `finish()` は必ず `finished_at` を set するが、`finishedAt == null` の場合は窓の起点が定まらないため解除条件を満たさないとする。`docker restart` は依然として最後の手段として残る。

**[recovery tick が blocker 照合で予算を食い、stale scan に到達しない] → 順序と reserve で緩和。** blocker 照合は stale scan より前に置くので、blocker が多数ある場合は理論上 stale scan が遅れる。ただし blocker は 1 invocation につき最大 1 件で、通常 0〜数件である。仮に予算が尽きた場合は `requireRecoveryStartReserve` が例外を投げ、tick 全体が失敗して `setRecoveryScanHealthy(false)` になる。これは「recovery が回っていないことを admission に伝える」既存の意味論どおりの失敗であり、静かな取りこぼしにはならない。

**[監査 append の失敗が解除を止める] → 意図した挙動。** 監査なしの解除を許すと、fail-closed が解けた理由を後から辿れなくなる。append 失敗時は tick を失敗させ、次 tick で再試行する。

**[このロジック自体が新しい防御機構になる] → 既存機構の解除経路であり、追加の防御層ではない。** Epic #286 の「保守側の理解を超える防御機構は撤去する」に照らせば、この change は防御を足すのではなく、既存 fail-closed の欠けていた解除条件を埋めるものである。新しい監視 harness や alert は追加しない。

## Migration Plan

DB schema 変更なし、config 変更なし。deploy は通常経路（main merge → self-hosted runner）で完結する。

rollback は revert だけで済む。in-memory state に永続化された副作用はなく、監査イベントは append-only で残るだけである。

merge 後の初回 deploy で container が再起動されるため、既存の固着 blocker（もしあれば）はその時点で消える。
