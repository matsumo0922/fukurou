## Why

MCP subprocess の起動情報（manifest / DB password / submission gateway socket）は現在、root 所有の setuid launcher（`scripts/runtime/fukurou-mcp-launcher.c`、mode 4755）が fd 3/4/5 に設置し、約 1,786 行の runtime supervisor（`scripts/runtime/fukurou-runtime-supervisor.c`）が argv/env/fd の固定契約を検証してから起動している。LLM CLI 本体も setuid launcher（`fukurou-llm-agent-launcher.c`）経由で起動する。

この機構は #282 で本番を約 1 週間停止させ、調査エージェント 2 体が構造を理解できず誤診した。single-owner の自宅 NAS 上 paper DB に対して防御コストが過大であり、攻撃者モデルが成立しない。epic #286 で owner が撤去を決定した。

## What Changes

- MCP 起動を「shell wrapper 経由の argv manifest id + literal env の DB password + 通常のファイル読み取り」に置き換える。fd-passing・setuid launcher・supervisor を撤去し、ENTRYPOINT を Ktor server 直接起動へ戻す。renderer が `command` を単一実行ファイルとして扱うため、`java -jar ...` 複数トークンを直接渡さず shell wrapper（`scripts/runtime/fukurou-mcp-run`）を挟む
- ENTRYPOINT を Ktor 直接起動へ戻すと UID drop を担っていた supervisor `setresuid` が消えるため、`Dockerfile` に `USER appuser` を明示追加する。3 UID 分離（appuser / llm-agent / mcp-runtime）を appuser 1 UID へ統合し、gateway socket が app owner 0600 のまま MCP が同一 UID で connect できるようにする
- DB password は literal env（Codex `[mcp_servers.<name>.env]` / Claude MCP config JSON の per-server `env`）で MCP subprocess にだけ渡す。**本番 proposer の code default が CLAUDE のため、Codex と Claude 両経路を同時に cutover する**。供給元は compose に既にある app env の `DB_PASSWORD` とし、bind mount と C launcher の password fd 経路を撤去する
- submission gateway は manifest の `submissionSocketPath` から socket path で connect する production constructor を新設する。gateway 経由の永続化（paper 真実性の不変条件）は変えない
- supervisor が兼務していた 2 機構を supervisor 非依存へ移す: (a) deploy 時の launch maintenance gate を supervisor `--control` から、DB テーブル `llm_launch_maintenance`（正本、変更なし）を daemon が launch 前に読む方式 + in-flight drain へ移す。(b) timeout/cancel 終了証明を supervisor の exit 124 ack から app 側の既存 process-group proof に一本化する
- manifest 検証ロジック（`McpLaunchBootstrap.decode()`）は検証内容を変えずに移植する（簡素化は S2 = #289 の範囲）

本 change は 3 stage・3 PR に分ける。Stage 1 は新起動 primitive、Stage 2 は daemon maintenance gate を additive に追加して各回帰テストで exercise し、Stage 3 が本番配線を atomic に切り替えて C 資産と supervisor 依存を削除する。本番 cutover 自体は atomic（契約が launcher / supervisor / JVM / deploy 制御で相互固定のため一括切替）。

## Capabilities

### New Capabilities

なし

### Modified Capabilities

- `llm-cli-invocation-contract`: MCP subprocess の起動が privileged descriptor passing（fd 3/4/5）・setuid launcher・supervisor に依存せず、argv の manifest id・literal env の DB password（Codex / Claude 両経路）・manifest 記載の socket path 経由で行われる要件を追加する。DB password が LLM CLI 本体プロセスの env に載らない不変条件、submit_decision の gateway 経由永続化、launch が daemon 側の DB maintenance flag で gate される要件、timeout 終了証明が外部 supervisor でなく app 側 process-group で成立する要件を含める。既存の tool policy 要件のうち「fixed supervisor / launcher」に言及する scenario を撤去後の起動機構に合わせて表現し直す
- `deploy-pipeline-baseline`: 「Launches are paused during migration」要件は mechanism-neutral（disable + drain）のため spec 本文は改訂不要。実装上、launch disable の enforcement を supervisor `--control` から DB flag + daemon gate へ移す（design.md D8）

## Impact

### 撤去（C / インフラ）
- `scripts/runtime/fukurou-mcp-launcher.c`（214 行）、`fukurou-runtime-supervisor.c`（1,786 行）、`fukurou-llm-agent-launcher.c`（340 行）、`fukurou-runtime-proxy.h`（114 行）、`fukurou-runtime-protocol.h`（42 行）
- `scripts/mcp-credential-isolation-check`（909 行）、`scripts/runtime/fukurou-mcp-canary-client.mjs`（196 行）、`scripts/runtime/validate-llm-launcher-probe.mjs`（48 行）
- `Dockerfile`：launcher-build stage（L34-41）、setuid COPY（L88-90）、撤去対象 canary COPY（L92, L94）、supervisor ENTRYPOINT（L115）、3 UID 定義（L64-77）
- `docker-compose.prod.yml`：`FUKUROU_MCP_SERVER_COMMAND`（L41）、`FUKUROU_CLAUDE/CODEX_COMMAND_TEMPLATE`（L45-46）、password bind mount（L53-56）

### 追加（インフラ）
- `scripts/runtime/fukurou-mcp-run`（新規 shell wrapper。非 setuid。`exec java -jar /app/fukurou-mcp-all.jar "$@"` 相当）
- `Dockerfile`：`USER appuser`（または compose `user:`）、wrapper COPY（非 setuid 0555）

### 変更（Kotlin）
- `mcp/.../McpLaunchBootstrap.kt`：`readFromArgs(manifestId, environment)` を新設し manifest を directory+argv id から、password を env から読む。`decode()` の検証ロジックは不変だが、返り値の `McpSubmissionGatewayBinding` に `submissionSocketPath` を追加する（検証内容自体は変えず、S2 境界を侵さない）
- `mcp/.../FukurouMcpServer.kt`：`main()` → `main(args)`、gateway を socket path connect へ
- `mcp/.../LlmDecisionSubmissionGatewayClient.kt`：socket path から connect する `fromSocketPath` を新設。`McpSubmissionGatewayBinding` に `submissionSocketPath` 追加
- `trading/.../invoker/LlmInvocationModels.kt`：`LlmMcpServerConfig` に `literalEnvironmentVariables` 追加
- `trading/.../invoker/DefaultLlmCommandRenderer.kt`：Codex config に `[mcp_servers.<name>.env]`、Claude config JSON に per-server `env` を出力（forbidden 名 pattern 検閲は literal env 経路に適用しない）
- `trading/.../runner/OneShotLlmRunner.kt`：`DEFAULT_RUNNER_MCP_SERVER_COMMAND` を wrapper path へ、`mcpServerConfig()` が `DB_PASSWORD` を literal env で渡す
- `trading/.../invoker/ShellProcessRunner.kt`：supervisor exit 124 ack 依存（`supervisorAcknowledgementRequired` / `SUPERVISOR_CLEANUP_ACK_EXIT_STATUS`）を撤去し app 側 process-group proof に一本化。UID 統合により per-run home の cross-UID cleanup（`PRODUCTION_LLM_LAUNCHER cleanup`）を撤去
- `trading/.../persistence/ExposedLlmLaunchReservationRepository.kt`：`tryReserveLlmLaunchInTransaction` に `llm_launch_maintenance` の `FOR UPDATE` 読みを追加し、`enabled == true` で `MAINTENANCE_ACTIVE` 拒否。scheduler・manual 両経路が同一 admission を通るため両方 gate される
- `LlmLaunchReservationRejectionReason`：`MAINTENANCE_ACTIVE` を追加し `toDaemonSkipReason()` にマップ
- `fukurou/.../LaunchFenceDatabaseProbeMain.kt`：supervisor 専用 probe のため撤去（enforcement は admission gate が担う）

### 変更（deploy）
- `scripts/deploy/deploy-fukurou`：`request_supervisor DISABLE/COMMIT/ENABLE`（L309-313 ほか）を撤去し、既存 `maintenance-cas` DB CAS + in-flight drain poll へ。`SUPERVISOR_BINARY` / `write_fence_fallback` の `--entrypoint`/`--fence-write` 経路を整理（hard fallback の container stop は残す）

### 変更（テスト / docs）
- `fukurou/.../ReleaseDeployFoundationContractTest.kt`（L144 の supervisor ENTRYPOINT 契約、および `paused state...` テスト L76-103 の `request_supervisor DISABLE` 順序 assertion）
- `trading/.../invoker/DefaultLlmCommandRendererTest.kt`（launcher command を前提にした assertion、Codex `[env]` / Claude `env` の literal env 回帰）
- `docs/mcp-runtime.md`・`docs/deploy.md`・`docs/design.md`・`docs/llm-obsidian-production-setup.md`（fd / launcher / supervisor / UID 分離 / maintenance 制御の記述を新構成の現在形へ）

### 対象外
- manifest 検証ロジックの簡素化（S2 = #289）、MCP DB role の変更（S3 = #290）
- 取引所 API key / LLM credential の LLM CLI 本体への非転送（不変条件、維持）
- submit_decision の gateway 経由永続化（不変条件、維持）
- `scripts/runtime/fukurou-cli-canary-mcp.mjs` と `CliAcceptanceCanaryMain.kt`（pinned-cli-acceptance-canary capability。launcher/supervisor と非結合のため本 change では削除しない。詳細は design.md D6）
