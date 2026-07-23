## Context

現状の MCP 起動は 3 層で相互固定されている。

- **受け手（Kotlin / :mcp）**: `FukurouMcpServer.kt:414` の `fun main()`（引数なし）が `McpLaunchBootstrap.read()`（`McpLaunchBootstrap.kt:23-32`）を呼ぶ。`read()` は fd 3（manifest、`/proc/self/fd/3`）と fd 4（password）を読み、fd 5（submission gateway）の存在を require してから `decode(manifestBytes, passwordBytes, clock)`（`McpLaunchBootstrap.kt:34-113`）に渡す。fd 定数は `MANIFEST_FD=3` / `PASSWORD_FD=4` / `SUBMISSION_GATEWAY_FD=5`（`McpLaunchBootstrap.kt:152-154`）。gateway client は fd 5 を reflection（`sun.nio.ch.SocketChannelImpl`）で再構築する `fromConnectedDescriptor`（`LlmDecisionSubmissionGatewayClient.kt:96-123`）を production で使い、`main()` L450 で呼ぶ。
- **送り手（C / インフラ）**: setuid launcher `fukurou-mcp-launcher.c`（214 行）が manifest と password を memfd 化して fd 3/4 に、gateway socket へ connect して fd 5 に設置し、app UID で connect してから mcp UID(10003) へ drop する。supervisor `fukurou-runtime-supervisor.c`（1,786 行）が ENTRYPOINT（`Dockerfile:115`）で、MCP profile の argv 完全一致（`java --add-opens=... -jar /app/fukurou-mcp-all.jar`、`:508`）、env 完全一致（PATH + `FUKUROU_INVOCATION_ID`、`:515-522, :696-699`）、fd_role_bitmap（`:481, :644-654`）を検証する。LLM CLI 本体も setuid launcher `fukurou-llm-agent-launcher.c`（340 行）経由（`docker-compose.prod.yml:45-46`）。**UID drop は supervisor の `setresuid` だけが担っており、`Dockerfile` に `USER` 命令は存在しない**（`grep "^USER" Dockerfile` = 0 件）。
- **manifest の正本**: `McpLaunchManifest`（`McpLaunchManifest.kt:20-45`）は `submissionSocketPath`（既存フィールド、`:43`）を持ち、production では `LlmInvocationAuditor.createSubmissionGateway`（`LlmInvocationAuditor.kt:236-274`）が per-invocation で `LlmDecisionSubmissionGateway.start()` を呼び socket を bind（`LlmDecisionSubmissionGateway.kt:137-140`、owner-only 0600 = `OWNER_ONLY_SOCKET_PERMISSIONS`、`:636-638`）し、`McpLaunchManifestWriter.bindSubmissionSocket()`（`McpLaunchManifest.kt:81-99`）で manifest に socket path を書き込む。

このほか、supervisor は起動仲介以外に 3 つの機構を抱えており、issue #288 本文の撤去 inventory には記載がない（falsify で判明）。

- **起動 command の TOML 契約**: `DefaultLlmCommandRenderer.toCodexConfigToml()`（`:406-415`）は `command` を実行ファイル 1 個の文字列、`args` を `listOf(mcpServer.manifestId)`（常に manifest id 1 個）としてハードコードする。`toClaudeMcpConfigJson()`（`:368-379`）も `command`（単一）+ `args = [manifestId]` で同型。`LlmMcpServerConfig`（`LlmInvocationModels.kt:96-104`）に追加固定 args を持つフィールドは無い（`OneShotRunnerCliConfig.mcpServerArgs` は codex 経路へ伝播していない）。したがって `command = "java -jar /app/fukurou-mcp-all.jar"` のような複数トークン文字列は「空白を含む 1 実行ファイル名」と解釈され起動できない。
- **deploy 時 maintenance の制御プレーン**: `scripts/deploy/deploy-fukurou:309-313` の `request_supervisor()` が `docker exec --user 0 fukurou-ktor "${SUPERVISOR_BINARY}" --control DISABLE/ENABLE/COMMIT <generation>` で supervisor を launch 一時停止/再開の制御に使う（`:435, :443, :529, :550`）。ただし **maintenance の正本は DB テーブル `llm_launch_maintenance`**（`:436-439` の `DB_HELPER maintenance-cas`、`resume_launches_idempotently` の `runtime_snapshot .maintenance.enabled/.generation/.deploymentId`）であり、supervisor はこの DB flag を PID 1 が socket publication 前に読む probe（`LaunchFenceDatabaseProbeMain.kt:5-29`、`SELECT maintenance.generation, maintenance.enabled FROM llm_launch_maintenance`）で参照して launch を gate する enforcement 層に過ぎない。supervisor が応答しない場合の fallback は `write_fence_fallback`（`:315-321`）が `docker compose stop ktor`（`:437`）でコンテナ停止する hard drain。deploy spec の該当要件（`deploy-pipeline-baseline/spec.md` Requirement: Launches are paused during migration）は「disable new launches, wait for in-flight to drain」と mechanism-neutral。
- **timeout/cancel 後の終了証明**: `ShellProcessRunner.kt:68-107` は timeout/cancel 時に `destroyProcessTreeNonCancellable(supervisorAcknowledgementRequired = true/false)` を呼ぶ。実体（`:200-224`）は **app 自身が Linux process group を setsid で作り、`kill -TERM/-KILL -<pgid>` と `/proc` 走査で終了を確認**（`:203-215` の `check(processExited && !isLinuxProcessGroupRunning(pgid))`）しており、supervisor の役割は追加の ack（exit code 124 = `SUPERVISOR_CLEANUP_ACK_EXIT_STATUS`、`:217-223`）を返すことだけ。ack が無いと proof が `PROVEN_EXITED` でなく `UNCERTAIN` に落ちる。

重要な既存事実:
- **`DB_PASSWORD` は既に app container の env に存在する**（`docker-compose.prod.yml:30` `DB_PASSWORD: ${POSTGRES_PASSWORD}`）。現状は C launcher が `/run/secrets/fukurou_mcp_db_password` bind mount（`:53-56`）を読むため Kotlin 側は password の値を知らないが、値そのものは既に app プロセスが持っている。
- `DB_PASSWORD` は renderer の `LLM_FORBIDDEN_ENVIRONMENT_KEYS`（`DefaultLlmCommandRenderer.kt:382-387`）と runner の `DB_ENV_KEYS`（`OneShotLlmRunner.kt:2493-2497`）に含まれ、`withoutLlmSecrets()`（renderer `:345-346`）と `isForbiddenSecretEnvKey()`（runner `:2667-2684`）で LLM CLI 本体プロセスの env から除外される。この不変条件は本 change でも維持する。
- renderer の literal env は既存の `env_vars`（`forwardedEnvironmentVariables`、名前転送、`DefaultLlmCommandRenderer.kt:417-428`）とは別機構。Codex の `[mcp_servers.<name>.env]`（値インライン）と Claude MCP config JSON の per-server `env` object は、いずれも MCP subprocess にだけ値を渡す。`LlmMcpServerConfig` に literal env 用フィールドは無く、新設する。Codex 0.142.5 の inline env サポートはローカル `codex mcp list` で実証済み。
- literal env による MCP credential 供給はこのリポジトリ自身が過去に持っていた形（commit `125b8e81` 以前）であり、その後 fd-passing 隔離へ置換された経緯がある。本 change はその置換を epic #286 の方針（過大な隔離コストの撤去）に沿って巻き戻すものであり、方針の矛盾ではない。
- **本番 proposer の code default は `LlmProvider.CLAUDE`**（`TradingBotConfig.kt:137`、`readLlmRoleAssignment(defaultProvider = LlmProvider.CLAUDE)` `:1256`）。falsifier の code default は CODEX（`:138`）。issue #288 は「現状 provider=codex 固定運用」と書くが、これが runtime config 上書きの結果か code default かは未確認（owner も要確認と回答）。したがって Claude 経路も cutover 対象に含める前提で設計する。

## Goals / Non-Goals

**Goals:**
- fd-passing・setuid launcher・supervisor を撤去し、MCP 起動を argv + literal env + 通常ファイル読み取りで説明できる構成にする
- ENTRYPOINT を Ktor server 直接起動へ戻し、明示的な `USER` で非 root 実行にし、3 UID 分離を appuser 1 UID へ統合する
- DB password を literal env で MCP subprocess にだけ渡し（Codex と Claude 両経路）、LLM CLI 本体プロセスに載せない不変条件を保つ
- submit_decision を gateway socket 経由で永続化する経路（paper 真実性）を維持する
- supervisor が担っていた deploy maintenance gate と timeout 終了証明を、supervisor 非依存の手段へ移す
- 本番 cutover は atomic（launcher / supervisor / JVM / deploy 制御の契約を一括切替）

**Non-Goals**（issue #288「やらないこと」の転記）:
- manifest 検証ロジック（`decode()`）の簡素化（S2 = #289）。検証内容を変えずに移植する
- MCP 用 DB role の変更（S3 = #290）
- 取引所 API key / LLM credential の LLM CLI 本体プロセスへの露出（不変条件、`LLM_FORBIDDEN_ENVIRONMENT_KEYS` の GMO/Cloudflare/DB 系維持）
- submit_decision の直接 DB 書き込み化（gateway 経由維持）
- deploy の paused-state marker / gap incident 状態機械（`PAUSED_BEFORE_MIGRATION` 等）自体の再設計。maintenance の「制御 CHANNEL」だけを supervisor CLI から DB flag + drain へ変える

## Decisions

### D1. MCP 起動は shell wrapper 経由（command 単一トークン制約への対処）
renderer の TOML/JSON 出力が `command` を単一実行ファイルとして扱う制約（Context 参照）に対し、**shell wrapper** を採る。image に `scripts/runtime/fukurou-mcp-run`（非 setuid、`#!/bin/sh` + `exec java -jar /app/fukurou-mcp-all.jar "$@"` 相当）を配置し、`FUKUROU_MCP_SERVER_COMMAND`（compose）と `DEFAULT_RUNNER_MCP_SERVER_COMMAND`（`OneShotLlmRunner.kt:2231`）をこの wrapper path にする。`args = [manifestId]` は現状のまま wrapper の `$@` に渡る。

理由: wrapper なら renderer の args モデル（command 単一 + args=[manifestId]）を変えずに済み、Codex（`command`）と Claude（`command`）の両出力に同一構成で効く。代替案（`LlmMcpServerConfig` に固定 args フィールドを新設し renderer で `args = [固定..., manifestId]` を出力）は renderer を両 provider 分岐で改修する必要があり surface が広い。issue #288 本文も「shell wrapper 可」と明記している。`--add-opens` は D3 で reflection が消えるため wrapper 内 java 起動に付けない。

### D2. DB password は literal env、Codex と Claude 両経路、供給元は既存 app env の `DB_PASSWORD`
`LlmMcpServerConfig`（`LlmInvocationModels.kt:96-104`）に `literalEnvironmentVariables: Map<String, String>`（初期値 `emptyMap()`）を追加する。renderer は両経路へ出力する:
- Codex: `toCodexConfigToml()`（`:397-439`）に `[mcp_servers.<name>.env]` テーブルを追加し `key = "value"` を並べる
- Claude: `toClaudeMcpConfigJson()`（`:368-379`）に per-server `env` object を追加する（標準 MCP stdio config の `env` フィールド。実装時に Claude CLI 2.1.199 の受理を回帰テストで確認する）

**この literal env 経路には forbidden 名 pattern 検閲（`isForbiddenMcpEnvironmentVariable`、`:441-445`）を適用しない**（issue 明記。`DB_PASSWORD` を弾かないため）。`OneShotLlmRunner.mcpServerConfig()`（`:2076-2122`）が `parentEnvironment["DB_PASSWORD"]` を読み `literalEnvironmentVariables = mapOf("DB_PASSWORD" to password)` を設定する。MCP 側は起動時に env の `DB_PASSWORD` を読み `decode()` の passwordBytes に渡す。

**Claude 経路を含める理由（BI-5）**: 本番 proposer の code default は CLAUDE（Context 参照）で、provider=codex 固定が確定していない。Codex だけ cutover して Claude を fd-passing に残すと、本番 proposer が実は Claude だった場合に DB password が届かない critical な破壊になる。両 provider を同時に literal env へ cutover することでこのリスクを消す。Open Question として先送りしない。

**password 供給経路の選択（受け入れ条件で PR 明記が必要）**: env 直渡しを採用し、`/run/secrets/fukurou_mcp_db_password` bind mount と C launcher の password fd 経路を撤去する。理由: `DB_PASSWORD` は既に compose の app env に存在するため bind mount は二重供給。

**LLM CLI 本体に載らない保証**: literal env は Codex TOML の `[mcp_servers.<name>.env]` / Claude JSON の per-server `env` にだけ出力され、CLI 本体プロセスの env（`commandEnvironment`、`:250-254` / `:224`）には入らない。CLI 本体の env は従来どおり `withoutLlmSecrets()` で `DB_PASSWORD` を除外し続ける。両 CLI は `env` を MCP subprocess にだけ渡す。

### D3. submission gateway は manifest の socket path から connect
`McpSubmissionGatewayBinding`（`LlmDecisionSubmissionGatewayClient.kt:128-133`）に `submissionSocketPath: String` を追加し、`decode()` が既に検証済み（`:65-71` で absolute + `.sock`）の `manifest.submissionSocketPath` を binding に載せる。`fromSocketPath(binding)` を新設し、`SocketChannel.open(StandardProtocolFamily.UNIX)` → `connect(UnixDomainSocketAddress.of(binding.submissionSocketPath))`（public API、reflection 不要）で接続する。`main` L450 の `fromConnectedDescriptor` を差し替える。

理由: gateway socket は app owner 0600（`:636-638`）で、現行 launcher は「app UID で connect してから mcp UID へ drop」していた。MCP が app と同一 UID で動けば（D4）0600 socket へ直接 connect できる。gateway server 側（per-invocation bind + 永続化）は変更しない。submit_decision の gateway 経由（paper 真実性）は維持される。**副次効果**: fd 5 reflection 再構築（`:106-122` の `FileDescriptor` private constructor / `sun.nio.ch.SocketChannelImpl`）が消えるため、supervisor MCP profile が要求していた `--add-opens` が不要になる（D1）。

### D4. ENTRYPOINT を Ktor 直接起動へ、明示的 `USER` で非 root、UID を appuser へ統合
supervisor / launcher / proxy header の C 資産と build stage（`Dockerfile:34-41`）、setuid COPY（`:88-90`）を撤去し、ENTRYPOINT（`:115`）を Ktor server 直接起動（`java -jar /app/app.jar` 相当）へ戻す。**`Dockerfile` に `USER appuser`（または compose の `user: "10001"`）を明示的に追加する**。理由（BI-4）: 現状 UID drop は supervisor の `setresuid` だけが担い `Dockerfile` に `USER` 命令が無い。supervisor 撤去後に `USER` を足さないと container が root のまま動く。

3 UID（appuser 10001 / llm-agent 10002 / mcp-runtime 10003、`:64-77`）を **appuser 1 UID へ統合**し、llm-agent / mcp-runtime user と mcp-runtime group を削除する。supervisor/launcher 専用ディレクトリ（`/run/fukurou/control`、`/var/lib/fukurou/launch-fence`、`/run/secrets`）を削除し、`/run/fukurou/mcp-manifests`（appuser 0700）と `/run/fukurou/llm-homes` は残す。

理由: 受け入れ条件で `fukurou-llm-agent-launcher.c` も削除対象のため、CLI 本体・MCP・app を別 UID で動かす setuid 手段が消える。setuid を使わない per-subprocess UID 切替は compose の `user:` では実現できない（container 全体が 1 UID）。UID 統合は最も単純で、D3 の gateway socket permission（app owned 0600 に MCP が同一 UID で connect）とも整合する。

**cross-UID cleanup の撤去**: `ShellProcessRunner.deleteProductionPerRunHome()`（`:308-317`）は per-run home を別 UID(llm-agent) が作るため `PRODUCTION_LLM_LAUNCHER cleanup` で消していた。UID 統合後は current-user 削除（`:296-306`）で足りるため、`deleteProductionPerRunHome` と `PRODUCTION_LLM_LAUNCHER`（`:397`）を撤去する。

### D5. 契約テストと launcher 前提テストの追従
- `ReleaseDeployFoundationContractTest.kt:144` の supervisor ENTRYPOINT assertion を新 ENTRYPOINT へ更新（main checkout ではこの 1 箇所のみが supervisor ENTRYPOINT に依存。`.claude/worktrees/` の別 change コピーは対象外）
- 同テストの `paused state preserves one maintenance incident`（`:76-103`）が `request_supervisor DISABLE` の順序を assert する（`:94, :97`）。D8 の新 maintenance channel（DB CAS + drain）へ assertion を更新する
- `DefaultLlmCommandRendererTest.kt` の launcher command 前提 assertion（`:115, :358, :778` の `command = "/usr/local/libexec/fukurou-mcp-launcher"`）を新 wrapper command へ更新し、Codex `[env]` と Claude `env` の literal env 出力の回帰 assertion を足す

### D6. canary / isolation スクリプトの扱い（スコープ境界）
- **削除**: `scripts/mcp-credential-isolation-check`（909 行、fd 隔離 canary）、`scripts/runtime/fukurou-mcp-canary-client.mjs`（196 行）、`scripts/runtime/validate-llm-launcher-probe.mjs`（48 行）。いずれも launcher/supervisor/fd 隔離 probe。main の `.github/workflows/deploy.yml` は既に #299 で cli-acceptance / isolation gate を撤去済みで参照が無い（`ReleaseDeployFoundationContractTest.kt:59` が `run_cli_acceptance_gate` の不在を assert）。
- **本 change では削除しない**: `scripts/runtime/fukurou-cli-canary-mcp.mjs`（76 行）は `CliAcceptanceCanaryMain.kt:343` の `FIXTURE_MCP_COMMAND` が参照する fixture MCP stdio server で、pinned-cli-acceptance-canary capability に属する。launcher/supervisor と非結合であり、削除すると CLI acceptance canary を壊す。最小スコープの原則に従い、本 change の撤去対象は launcher/supervisor/fd 隔離に直接結びつくものに限定する。

### D7. PR 分割 — 3 stage（additive 2 段 + atomic cutover 1 段）
スコープが起動経路だけでなく deploy maintenance と process lifecycle に及ぶため、3 PR に分ける。

- **Stage 1 — additive 起動 primitive（本番未配線）**: `fromSocketPath` / `McpSubmissionGatewayBinding.submissionSocketPath` / `literalEnvironmentVariables` フィールド / renderer の Codex `[env]` + Claude `env` 出力 / `McpLaunchBootstrap.readFromArgs(manifestId, environment)`（既存 `decode()` 再利用） / MCP-run shell wrapper script を image に配置（dormant）。production `main()` / gateway 配線 / renderer default command は変えない。回帰テストで全経路を exercise。
- **Stage 2 — additive maintenance admission gate（本番未配線相当）**: 予約 admission（`tryReserveLlmLaunchInTransaction`、scheduler と manual の共通経路）に maintenance チェックを組み込む（D8）。`enabled == true` なら `MAINTENANCE_ACTIVE` で拒否し skip 理由を記録する。supervisor がまだ存在するため DB flag は二重に enforce されるが結論は一致し無害。回帰テストで両経路を exercise。
- **Stage 3 — atomic cutover + 削除**: production 配線を一括切替する。`main()` → `main(args)` で `readFromArgs`、gateway を `fromSocketPath`、renderer default command を wrapper へ、`OneShotLlmRunner` が `DB_PASSWORD` を literal env で渡す。Dockerfile（`USER appuser` 追加、C 削除、supervisor ENTRYPOINT 削除、UID 統合、wrapper 配置、canary COPY 整理）、compose（command/template/bind mount）、deploy-fukurou（supervisor `--control` 撤去、DB CAS + drain poll へ、D8）、`ShellProcessRunner`（supervisor-124 ack 撤去 = D9、cross-UID cleanup 撤去）。C 資産・fd 経路・`LaunchFenceDatabaseProbeMain`・削除対象 canary を削除。契約/renderer テスト更新、docs 4 ファイル書き直し。

**「本番 cutover は atomic」と「3 PR」は両立**: issue の「部分的に導入できない」は本番切替の atomicity を指す。Stage 1/2 は本番挙動を変えず、Stage 3 が単一の本番切替を行う。**「使われないコードを書かない」規約との整合**: Stage 1/2 の新コードは各 stage の回帰テストで exercise され、同一 change の Stage 3 で本番配線される。分割の正当性は先例ではなく、(1) human-authored diff が 1,000 行目安を超える（Kotlin 追加 + Dockerfile + compose + deploy-fukurou + 契約/renderer テスト + docs 4 本）、(2) additive 準備・maintenance gate・atomic cutover を分けて各 PR を独立レビュー可能にする、という独立根拠で成立する。`2026-07-21-b2-safety-floor-rule-margins` の Stage 1 は production 配線済み（PLACE/PREVIEW 2 箇所、SQL で FAIL 観測可）であり、本設計の Stage 1/2（production 未配線）とは質的に異なる点に注意。全 PR 完了まで change を archive しない（運用メモ openspec-multi-pr-archive-timing）。

### D8. deploy maintenance の enforcement を reservation admission transaction へ組み込む（supervisor `--control` 撤去）
maintenance の正本は既に DB テーブル `llm_launch_maintenance`（`DB_HELPER maintenance-cas`）である（Context 参照）。supervisor 撤去に伴い、enforcement 層を supervisor（PID 1 の launch gate）から **既存の予約 admission transaction** へ移す。

**flag polarity（重要）**: `llm_launch_maintenance.enabled` は **`false` = 平常（launch 許可）、`true` = maintenance 中（launch 禁止）**（`deploy-fukurou:419` が pause 前提として `current_enabled == "false"` を要求、`:440-442` が pause で false→true、`:543-545` が resume で true→false、`fukurou-runtime-supervisor.c:983-995` と整合）。したがって admission は **`enabled == true` のとき launch を拒否**する。

**enforcement point（Blocking 2/3 の解消）**: scheduler（`LlmDaemonScheduler.kt:492`）と manual launch（`ManualLlmLaunchService.kt:157`）は **どちらも同一の `LlmLaunchReservationRepository.tryReserve()` → `tryReserveLlmLaunchInTransaction()`（`ExposedLlmLaunchReservationRepository.kt:752-790`）を通る**。この関数は既に `selectRiskState(forUpdate = true)`（`:756`）で risk_state 行ロックを取り、HARD_HALT / single-attempt / concurrent / budget を **単一 transaction 内で** 判定して予約を insert（`:785`）する。ここに maintenance チェックを組み込む:

- `tryReserveLlmLaunchInTransaction()` に `selectLaunchMaintenance(forUpdate = true)`（`llm_launch_maintenance` singleton 行を **行ロック付きで読む**）を追加し、`enabled == true` なら新しい `LlmLaunchReservationRejectionReason.MAINTENANCE_ACTIVE` を返す。lock order は risk_state → maintenance に固定する（`maintenance-cas` は maintenance 行しか触らないため cross-lock deadlock は生じない）
- 新 rejection reason を `toDaemonSkipReason()` にマップする。scheduler（`:494`）と manual（`:160`）は既に rejection→skip 変換を共有するため、**両経路が自動的に gate される**（Blocking 3 解消。scheduler 専用 gate を別に作らない）

これにより **maintenance 判定と予約 insert が同一 transaction・同一 lock 境界に入る**（Blocking 2 解消）。`maintenance-cas` は maintenance 行を UPDATE（行 write lock）するため、admission の `FOR UPDATE` 読みと serialize する: admission が先にコミットすれば予約は drain が捕捉し、CAS が先にコミットすれば admission は `enabled == true` を読んで拒否する。「worker が false を読んだ直後に CAS→drain が 0 観測→worker が予約 insert」という window は、read と insert が単一 transaction に入るため消える。`maintenance-cas` DB helper の構造は変えない（scope 外）。

**deploy-fukurou の変更**: `request_supervisor DISABLE/COMMIT/ENABLE`（`:311, :435-443, :529-555`）を撤去し、既存の `maintenance-cas` DB CAS（authoritative write、変更不要）＋ **in-flight 掃引待ち**（active な RUNNING reservation が 0 になるまで bounded poll。`runtime_snapshot` または reservation 表で観測）に置き換える。hard fallback（`write_fence_fallback` = `docker compose stop ktor`）は残す。`LaunchFenceDatabaseProbeMain.kt`（supervisor 専用 probe）は削除する（admission が DB flag を読むため不要）。

理由: DB が既に authoritative なため、supervisor の `--control` ack は「supervisor が DB flag を再読した」ことの確認に過ぎない。admission transaction に組み込むことで、二重チェックや別 gate を作らず、既存の risk_state ロック境界で maintenance を atomic に enforce できる。deploy spec の該当要件（`deploy-pipeline-baseline` Requirement: Launches are paused during migration）は mechanism-neutral（disable + drain）で、本設計はこれを満たす（spec 改訂不要）。paused-state marker / gap incident の状態機械自体は変更しない（制御 channel だけを差し替える）。

**スコープ拡大の明示**: この依存は issue #288 本文の撤去 inventory に無かった（supervisor binary を共有する deploy 側の別機構）。owner が「設計修正・falsify ループを続ける」と回答済みのため、本 change のスコープに取り込む。

### D9. timeout/cancel 終了証明は forced-kill か natural exit かで区別を維持する（supervisor-124 ack 撤去、実装時に訂正）
`ShellProcessRunner` は既に app 自身が Linux process group を setsid で作り、`kill -TERM/-KILL -<pgid>` と `/proc` 走査で終了を確認している（Context 参照）。`supervisorAcknowledgementRequired` / `SUPERVISOR_CLEANUP_ACK_EXIT_STATUS`（exit code 124）への依存は撤去する。

**設計時点の記述からの訂正**: 当初案は「`check(processExited && !isLinuxProcessGroupRunning(pgid))` が成立すれば forced-kill でも常に `PROVEN_EXITED` とする」としていたが、Stage 3 実装時に既存テスト（`ShellProcessRunnerTest.run_linuxProcessGroupKillsDescendantForkedAfterTermSignal` 等）と突き合わせたところ誤りと判明した。exit 124 ack は実際には PID1 supervisor ではなく、削除対象の `fukurou-llm-agent-launcher.c`（root process 自身）が「TERM 転送後に子孫の完全な reap を待ってから明示的に exit 124 する」という、`/proc` の time-of-check スキャンより後に発生する fork を含めて保証する、独立した強い証明だった。launcher 削除後はこの ack を生成できる process が存在しないため、forced-kill（timeout/cancel）経路は `/proc` scan 一回だけでは「scan 後に fork した子孫」の race を排除できない。よって:

- **natural exit**（`ShellProcessRunner.kt` の通常 exit 経路）: 従来通り `check(...)` が成立すれば `PROVEN_EXITED`（forced ではないため late-fork race の対象外）
- **forced termination**（timeout / cancellation）: `check(...)` が成立しても `UNCERTAIN` のままとする（ack 相当の証明が無いため）。`OneShotLlmRunner` の recovery blocker 登録は現状通り機能し続ける（forced-kill 後は recovery blocker 経由の確認を必須のままにする）。

これは「ack は冗長」という当初の理解を修正するものであり、動作は launcher 削除後の実態（ack を生成する process が存在しない）に合わせて安全側（保守的）に倒す。既存の `ShellProcessRunnerTest` / `LlmExecutionRecoveryServiceTest` の forced-kill 系アサーションは `UNCERTAIN` へ更新し、natural exit 系アサーションは変更しない。

理由: 実際の終了処理（process group kill + /proc 確認）は既に app 側で完結しているが、forced-kill 直後の late-fork race に対する保証は launcher の ack が担っていた。ack を機械的に「常時 true 扱い」にする（＝当初案）と証明されていない安全主張を作ってしまうため、ack 撤去後は「常時 false 扱い」（forced-kill は常に UNCERTAIN）が唯一の正しい単純化である。epic #286 の「保守側の理解を超える隔離機構は撤去」には整合しつつ、DoD の「資金/paper truth を壊さない」を、recovery blocker 経路を保守側に倒すことで維持する。

## Risks / Trade-offs

- [Risk] Claude MCP config JSON の per-server `env` object を Claude CLI 2.1.199 が期待どおり MCP subprocess にだけ渡すか未検証 → Mitigation: Stage 1 の回帰テストで、`env` が MCP config JSON に出力され CLI 本体プロセス env には載らないことを assert。実配線での DB 接続確認は Stage 3 の回帰テストで担保。差異があれば PR に記録。
- [Risk] deploy maintenance を DB flag + drain へ移す際、maintenance 判定と予約 insert が非原子だと race で pause invariant を破る → Mitigation: D8 のとおり maintenance チェックを `tryReserveLlmLaunchInTransaction` の risk_state 行ロック境界内へ入れ、maintenance 行を `FOR UPDATE` で読んで `maintenance-cas` と serialize させる。scheduler・manual 両経路が同一 admission を通るため gate 漏れも無い。hard fallback（container stop）を残す。
- [Risk] literal env 経路が forbidden 名検閲を通さないため、将来 `DB_PASSWORD` 以外の secret を載せると別ガードが要る → Mitigation: 本 change の literal env は `DB_PASSWORD` のみ。CLI 本体 env は従来ガードで保護。
- [Risk] UID 統合で MCP subprocess が app と同一 UID になり読める範囲が広がる → Mitigation: single-owner paper DB に対する攻撃者モデルは成立しないと owner が epic #286 で判断済み。gateway 経由 submit（paper 真実性）は維持。
- [Trade-off] `forwardedEnvironmentVariables = listOf(FUKUROU_INVOCATION_ID_ENV)`（#287、`:2111`）は launcher 撤去後に機能的必須性を失う（MCP は invocationId を manifest から読む）。無害な残置として維持（削除は別 issue）。

## Migration Plan

- Stage 1 / Stage 2: additive のみ。schema / データ移行なし。デプロイ不要（テストで検証）。
- Stage 3: image 差し替えのみ。schema / データ移行なし。cutover は既存の digest-pinned deploy（`scripts/deploy/deploy-fukurou`）で atomic に行う。ロールバックは前 image への revert（C launcher/supervisor を含む旧 image に戻る）。
- Stage 3 デプロイ後の確認（owner 作業、受け入れ条件外）: paper daemon run で MCP tool call が記録され、DB 接続と submit_decision が新経路で成立すること、deploy の maintenance quiesce が新 channel で機能すること。

## Open Questions

なし（Claude 経路は D2 で実装対象に取り込み、Open Question から外した）。
