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

MCP server は `:mcp:buildFatJar` の独立 fat jar として stdio 起動される別 process なので、process-local singleton には原理的に到達しない。一方 submission gateway は Unix domain socket server として **app process 内で動く**。MCP 側の `submit_decision` は socket 越しに app へ渡り、`LlmDecisionSubmissionGateway.handleRequest` が受ける。ここは singleton へそのまま到達できる。

issue #352 が問題視した実害シナリオ ——「process tree 終了を証明できないまま終端した invocation の遅延 fork child が、PROPOSER と FALSIFIER の両方で生存し、DB 上の同一 intent に対して連携する」—— の連携は、必ず intent 発行（`submit_decision`）と承認（`submit_falsification`）を通る。**この 2 つを gateway で止めれば、別 process へ状態を伝える機構を一切追加せずにシナリオが閉じる。**

## Goals / Non-Goals

**Goals:**

- admission health が unhealthy のとき、terminal な decision / falsification が確定しないようにする。
- 拒否点を既存の閉じた rejection code 語彙に合流させ、監査から admission 起因の拒否を特定できるようにする。
- 「admission health が何を gate するか」を spec 上で確定させ、経路ごとに意味が異なる状態を解消する。

**Non-Goals:**

- MCP process 全般の tool call を gate すること。read-only tool は資金を動かさず、`place_order` はどの phase の allowlist にも含まれない（`McpToolContractCatalog.kt`）。
- manifest への `claimantToken` 追加、admission state の DB 投影、IPC 機構の新設。
- PR #351 が入れた blocker 自己解除ロジックの変更。
- fail-closed 機構自体の撤去・緩和。`LlmExecutionRecoveryService.tick()` の健全性判定の変更を含む。
- 監視・アラート基盤の追加（Epic #286 の方針）。

## Decisions

### D1: gate は gateway に置く（MCP process ではなく）

**帰属: ユーザー確認済み**（方向性 B = gate 機構の実装、を選択）

`LlmDecisionSubmissionGateway.handleRequest` の先頭に precondition を置く。

**採用理由**: gateway は app process 内なので singleton へ直接到達でき、新レイヤーが要らない。かつ、実害シナリオが必ず通る点でもある。

**却下した代替**:

- **manifest に `claimantToken` を載せ、`McpToolCallLimiter` で admission を検証する**。全 tool call を gate できるが、届くのは `llm_launch_reservations` の CLAIMED 状態だけで、`ambiguousClaims` / `recoveryBlockers` / `heartbeatFailures` という process-local blocker は依然届かない。issue の実害シナリオ（termination proof が `UNCERTAIN` = recoveryBlocker）はまさに後者なので、コストを払って本命を取り逃す。加えて manifest は子プロセスへ渡る file であり、fence token を載せることは secret 境界の再検討を要する。
- **admission state を DB へ投影する**。全経路で意味が一致する唯一の案だが、新テーブルまたは `llm_launch_reservations` への列追加、書き込み頻度、整合、回復の設計が要る。得られる追加保証は「read-only tool call の抑止」であり、read-only tool は資金を動かさない。費用対効果が合わない。
- **文書化だけで閉じる**。実装ゼロだが、submission が app process 内にあり singleton へ到達できるという事実を使わないまま「届かないから仕方ない」と書くことになり、事実と食い違う。

### D2: 拒否は既存の rejection code 体系へ合流させる

**帰属: agent 仮決め**

`SubmissionRejectionCode`（`trading/.../decision/SubmissionRejection.kt`）へ値を 1 つ追加し、`error=SUBMISSION_REJECTED` と併せて wire 応答へ載せる。既存 enum は 18 値の閉じた語彙で、`INVOCATION_BINDING_MISMATCH` から `UNCLASSIFIED` まで拒否点ごとに 1 値を割り当てている。admission もこの粒度に収まる。

これにより以下がすべて既存経路のまま動く。

- gateway → client: 既存の error response が `reason` に `wireValue` を載せる。
- client → LLM: MCP tool error の `structuredContent` に rejection code が入る。
- 監査: `NO_TRADE_EXIT` payload の `rejectionCode` に記録される。`reason` は `tool_call_failed` のまま。

**却下した代替**: 新しい `error` code を足す。既存の `error` code を変えない契約（`submission-rejection-diagnostics`）に反し、client 側の typed exception 分岐も増える。rejection code の粒度で足りる。

### D3: precondition は binding 検証より前に置く

**帰属: agent 仮決め**

`handleRequest` の入口、`trustedTerminalEvidence` 抽出と `validateGatewayBinding` より前に置く。

admission が unhealthy な状況は「この invocation が信頼できるか分からない」状態であり、その要求の中身を解釈する前に落とすのが fail-closed の筋。binding が一致していても admission が不健全なら結論は変わらないので、先に判定して余計な解釈を減らす。

### D4: gateway は invocation を終端させない

**帰属: agent 仮決め**

拒否のみを行い、reservation の FAILED 化や process kill は行わない。

**理由**: 終端は recovery scanner の責務で、そこには条件付き UPDATE による fence と `llm_pid_registrations` の TERMINAL 化、`command_event_log` への記録がセットで組まれている。gateway から別経路で終端すると二重終端の競合設計が新たに要る。得られるものは終端の数秒〜数十秒の前倒しだけで、割に合わない。

### D5: gate の条件に何を使うか（transient unhealthy 窓の扱い）

**帰属: 高リスク・要人間確認（未確定 / falsifier の重点反証対象）**

`LlmExecutionRecoveryService.tick()` は **tick 開始時に無条件で `setRecoveryScanHealthy(false)` し、成功時に true へ戻す**（`LlmExecutionClaimSupervisor.kt:181,187`）。tick は `LlmExecutionRecoveryWorker` が `heartbeatInterval` ごとに回し、tick timeout は 5 秒（`EXECUTION_RECOVERY_TICK_TIMEOUT`）。したがって **平常時にも `isHealthy()` が false になる窓が周期的に存在する**。

新規起動は頻度が低いため今まで問題化していないが、submission は run ごとに 1 回の一点イベントなので、この窓に当たると正常な invocation の decision が拒否されうる。

**仮の既定案（未確定）**: `isHealthy()` をそのまま使い、誤拒否を受容する。理由は、この change の本旨が「admission の意味を経路間で一致させる」ことであり、gate の条件を submission だけ別に定義すると、解こうとした非対称を別の形で残すため。誤拒否は専用 rejection code で監査から識別でき、評価時に infrastructure 起因として母集団から分離できる。

**この決定を未確定とする理由**: 誤拒否は「正常な run の結論が失われる」という実害であり、受容の可否は費用対効果の価値判断を含む。窓の実際の幅は tick timeout（5 秒）ではなく tick の実行時間で決まるため、机上では頻度を確定できない。falsifier には次の 3 点を重点的に反証させる。

1. 窓の実測幅と発生確率。submission のタイミング分布との重なりをどう見積もるか
2. 代替案「3 集合だけを見て `recoveryScanHealthy` を無視する」が本当に非対称を残すのか。実害シナリオ（recoveryBlocker）は 3 集合側なので、捕捉率は落ちない可能性がある
3. 誤拒否された run の回復可能性。再提出が成立する条件と、budget 消費の実害

**却下した代替（現時点）**:

- **bounded wait を入れる**。誤拒否をほぼ潰せるが、gateway に待ち合わせという新しい振る舞いが増え、逐次処理契約との整合検討が要る。gate 1 つの change に対して重い。
- **tick 側の無条件 false を直す**。根治だが fail-closed 機構自体の変更であり scope 外。

## Risks / Trade-offs

- **[transient 窓による誤拒否]** → D5 が未確定。falsifier の反証結果で条件を確定させる。
- **[admission unhealthy が長期化すると decision が一切通らない]** → 意図した挙動。fail-closed であり、`/health/ready` も同時に false なので運用上の可視性はある。新規起動も止まっているので、詰まった invocation は recovery scanner が回収する。
- **[`COMMITTED` からの状態劣化]** → precondition が repository 呼び出しより前で throw するため `submissionState` は触られず、既存の rejection 経路と同じ。回帰テストで固定する。
- **[test 間の singleton 汚染]** → `LlmExecutionAdmissionHealth` は process-local singleton なので、gateway test で unhealthy にしたまま他 test へ漏れると偽陽性・偽陰性を生む。既存の `LlmExecutionAdmissionHealthTestFixture.reset()` を必ず使う。
- **[read-only MCP tool call は依然 gate されない]** → 意図した非対称として spec に明記する。read-only tool は資金を動かさず、`place_order` はどの phase allowlist にも含まれない。この限界を spec に書くこと自体が issue #352 の「admission の意味を明確にする」要求への回答になる。

## Migration Plan

単一 PR。DB schema 変更なし、wire 互換の破壊なし（healthy 時の応答は不変）。rollback は revert のみ。deploy 手順の変更なし。

## Open Questions

- **D5 の gate 条件**: `isHealthy()` をそのまま使うか、3 集合のみを見るか。falsifier の反証を経て確定する。
