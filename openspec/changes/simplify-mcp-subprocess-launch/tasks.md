本 change は 3 PR に分けて実装する。Stage 1（新起動 primitive）と Stage 2（daemon maintenance gate）は additive に追加し各回帰テストで exercise する（本番挙動は不変）。Stage 3 が本番配線を atomic に切り替え、C 資産・fd 経路・supervisor 依存を削除する。spec の全要件は Stage 3 完了時に満たされる。全 PR 完了まで change を archive しない。

## 1. Stage 1 / 新起動 primitive の additive 追加（本番未配線）

- [ ] 1.1 `LlmDecisionSubmissionGatewayClient.kt` に `fromSocketPath(binding)` を新設する。`SocketChannel.open(UNIX)` → `connect(UnixDomainSocketAddress.of(binding.submissionSocketPath))`（reflection を使わない）
- [ ] 1.2 `McpSubmissionGatewayBinding` に `submissionSocketPath: String` を追加する
- [ ] 1.3 `McpLaunchBootstrap.kt` の `submissionGatewayBinding` へ `manifest.submissionSocketPath` を載せる。`decode()` の検証内容は変えない
- [ ] 1.4 `LlmMcpServerConfig`（`LlmInvocationModels.kt`）に `literalEnvironmentVariables: Map<String, String> = emptyMap()` を追加する
- [ ] 1.5 `DefaultLlmCommandRenderer.toCodexConfigToml()` に `[mcp_servers.<name>.env]` 出力を追加する。**この経路には forbidden 名 pattern 検閲（`isForbiddenMcpEnvironmentVariable`）を適用しない**
- [ ] 1.6 `DefaultLlmCommandRenderer.toClaudeMcpConfigJson()` に per-server `env` object 出力を追加する（同じく forbidden 検閲を適用しない）
- [ ] 1.7 `McpLaunchBootstrap` に `readFromArgs(manifestId, environment)` を新設する。manifest を `FUKUROU_MCP_MANIFEST_DIRECTORY/<manifestId>.json` から、password を env（`DB_PASSWORD`）から読み、**既存の `decode(manifestBytes, passwordBytes, clock)` を再利用**する
- [ ] 1.8 `scripts/runtime/fukurou-mcp-run` shell wrapper を新設し、Dockerfile に非 setuid 0555 で COPY する（dormant。この stage では配線しない）
- [ ] 1.9 production `main()` / gateway 配線 / renderer default command / compose は変更しない

## 2. Stage 1 / 回帰テスト

- [ ] 2.1 `fromSocketPath` が実際に bind した Unix socket へ connect し、submit → 永続化が成立することのテスト
- [ ] 2.2 renderer が Codex `[mcp_servers.<name>.env]` と Claude per-server `env` の両方に `DB_PASSWORD` を出力し、**CLI 本体プロセスの env（`RenderedLlmCommand.environment`）には `DB_PASSWORD` が載らない**ことのテスト（literal env が MCP subprocess 専用であることの検証）
- [ ] 2.3 `readFromArgs` が directory+argv id で manifest を、env で password を読み、`decode()` の検証を通ることのテスト。fd を前提にしないこと
- [ ] 2.4 `make test` / `make detekt` を実行する

## 3. Stage 2 / maintenance admission gate の additive 追加（本番未配線相当）

- [ ] 3.1 `LlmLaunchReservationRejectionReason` に `MAINTENANCE_ACTIVE` を追加し、`toDaemonSkipReason()` へマップする（scheduler・manual 共通の変換なので両経路に効く）
- [ ] 3.2 `tryReserveLlmLaunchInTransaction`（`ExposedLlmLaunchReservationRepository.kt:752-790`）に、`selectRiskState(forUpdate = true)` の後で `llm_launch_maintenance` singleton 行を `FOR UPDATE` で読む `selectLaunchMaintenance` を追加する。**`enabled == true`（= maintenance 中 = launch 禁止）なら `Rejected(MAINTENANCE_ACTIVE)` を返す**（polarity: false=平常で許可、true=停止で拒否）
- [ ] 3.3 lock order を risk_state → maintenance に固定する（`maintenance-cas` は maintenance 行しか触らないため deadlock は生じない）。`maintenance-cas` DB helper と `llm_launch_maintenance` テーブル構造は変更しない
- [ ] 3.4 admission が maintenance 行を読むため `LaunchFenceDatabaseProbeMain.kt` 相当の別 gate を新設しない（scheduler 専用 gate を作らない）。supervisor がまだ存在する状態で二重 enforce が結論一致し無害であることを確認する

## 4. Stage 2 / 回帰テスト

- [ ] 4.1 `llm_launch_maintenance.enabled = true` のとき、**scheduler 経由・manual（`ManualLlmLaunchService`）経由の両方**で予約が `MAINTENANCE_ACTIVE` で拒否され decision run が起動しないことのテスト
- [ ] 4.2 `enabled = false` のとき従来どおり予約・起動できることのテスト（polarity 検証）
- [ ] 4.3 admission と `maintenance-cas` の race 不在の回帰: maintenance を true に CAS した後に admission が走ると必ず拒否されること（同一 transaction・行ロックで serialize されること）
- [ ] 4.4 `make test` / `make detekt` を実行する

## 5. Stage 3 / 本番配線の atomic cutover（Kotlin）

- [ ] 5.1 `FukurouMcpServer.kt` の `main()` を `main(args)` へ変え、`args[0]` の manifest id で `readFromArgs` を使う
- [ ] 5.2 `main` の gateway 生成を `fromSocketPath` へ差し替える
- [ ] 5.3 `OneShotLlmRunner.mcpServerConfig()` が `parentEnvironment["DB_PASSWORD"]` を読み `literalEnvironmentVariables` に設定する
- [ ] 5.4 `OneShotLlmRunner.kt:2231` の `DEFAULT_RUNNER_MCP_SERVER_COMMAND` を wrapper path（`/usr/local/libexec/fukurou-mcp-run` 相当）へ変える
- [ ] 5.5 `ShellProcessRunner` の `supervisorAcknowledgementRequired` と `SUPERVISOR_CLEANUP_ACK_EXIT_STATUS` 依存を撤去し、app 側 process-group proof（`check(processExited && !isLinuxProcessGroupRunning(pgid))`）で `PROVEN_EXITED` とする（D9）
- [ ] 5.6 `ShellProcessRunner` の `deleteProductionPerRunHome()` と `PRODUCTION_LLM_LAUNCHER` / `CLEANUP_MODE` を撤去し、cleanup を current-user 削除へ一本化する（UID 統合の帰結）
- [ ] 5.7 fd 経路を削除する。`McpLaunchBootstrap.read()`（fd 3/4/5）、`LlmDecisionSubmissionGatewayClient.fromConnectedDescriptor` と `openConnectedDescriptor`、fd 定数（`MANIFEST_FD`/`PASSWORD_FD`/`SUBMISSION_GATEWAY_FD`）
- [ ] 5.8 `LaunchFenceDatabaseProbeMain.kt`（supervisor 専用 probe）を削除する（maintenance enforcement は Stage 2 の admission gate が担う）

## 6. Stage 3 / インフラ切替と C 資産削除

- [ ] 6.1 `docker-compose.prod.yml`：`FUKUROU_MCP_SERVER_COMMAND`（L41）を wrapper path へ、`FUKUROU_CLAUDE/CODEX_COMMAND_TEMPLATE`（L45-46）を `claude` / `codex` 直接へ、password bind mount（L53-56）を撤去する。`DB_PASSWORD`（L30）は残す
- [ ] 6.2 `Dockerfile`：launcher-build stage（L34-41）、setuid COPY（L88-90）、supervisor ENTRYPOINT（L115）を撤去し、ENTRYPOINT を Ktor server 直接起動へ戻す。**`USER appuser`（または compose `user:`）を追加する**（BI-4）
- [ ] 6.3 `Dockerfile`：3 UID（L64-77）を appuser 1 UID へ統合し、llm-agent / mcp-runtime user と mcp-runtime group、`/run/fukurou/control`・`/var/lib/fukurou/launch-fence`・`/run/secrets` ディレクトリを撤去する。`/run/fukurou/mcp-manifests`（appuser 0700）と `/run/fukurou/llm-homes` は残す
- [ ] 6.4 C 資産を削除する: `fukurou-mcp-launcher.c` / `fukurou-runtime-supervisor.c` / `fukurou-llm-agent-launcher.c` / `fukurou-runtime-proxy.h` / `fukurou-runtime-protocol.h`
- [ ] 6.5 fd 隔離 canary を削除する: `scripts/mcp-credential-isolation-check` / `scripts/runtime/fukurou-mcp-canary-client.mjs` / `scripts/runtime/validate-llm-launcher-probe.mjs` と Dockerfile の該当 COPY（L92, L94）。`fukurou-cli-canary-mcp.mjs`（L93）は残す（design.md D6）

## 7. Stage 3 / deploy maintenance の制御プレーン差し替え（D8）

- [ ] 7.1 `scripts/deploy/deploy-fukurou`：`request_supervisor DISABLE/COMMIT/ENABLE`（L309-313 ほか）を撤去し、既存 `maintenance-cas` DB CAS + in-flight drain poll へ置き換える。`SUPERVISOR_BINARY` と `write_fence_fallback` の `--entrypoint`/`--fence-write` 経路を整理する（hard fallback の `docker compose stop ktor` は残す）
- [ ] 7.2 drain 待ちは active な RUNNING reservation が 0 になるまで bounded poll する（`runtime_snapshot` または reservation 表で観測）。CAS で `enabled=true` にした後は admission gate が新規 launch を拒否するため、既存 in-flight のみを待てばよい。paused-state marker / gap incident 状態機械は変更しない

## 8. Stage 3 / 契約テストと回帰テスト

- [ ] 8.1 `ReleaseDeployFoundationContractTest.kt:144` の supervisor ENTRYPOINT assertion を新 ENTRYPOINT へ更新する
- [ ] 8.2 `ReleaseDeployFoundationContractTest.kt` の `paused state preserves one maintenance incident`（L76-103）の `request_supervisor DISABLE` 順序 assertion を、新 maintenance channel（DB CAS + drain）へ更新する
- [ ] 8.3 `DefaultLlmCommandRendererTest.kt`（L115 / L358 / L778）の launcher command 前提 assertion を新 wrapper command へ更新する
- [ ] 8.4 **受け入れ条件**: renderer → config → bootstrap の実配線経由で、MCP subprocess が argv の manifest id + literal env の password で起動し DB へ接続できる回帰テスト 1 本（手組み入力の単体だけにしない）。Codex と Claude 両経路
- [ ] 8.5 **受け入れ条件**: `submit_decision` が gateway socket 経由で永続化される経路の回帰テスト 1 本
- [ ] 8.6 **受け入れ条件**: DB password が LLM CLI 本体プロセスの env に載らないことのテスト（literal env は MCP subprocess 専用、Codex と Claude 両経路）

## 9. Stage 3 / docs

- [ ] 9.1 `docs/mcp-runtime.md` の「現在の構成」「local smoke」「one-shot runner」「secrets / CLI auth」節を新構成の現在形へ書き直す。fd 3/4/5・setuid・launcher・supervisor・3 UID 分離・cleanup helper への言及を残さない。**制約**: `## receipt-aware reader の rollback` 節の本文と、その直後に `## local smoke` ヘッダが来る構造を保つ（`DurableReceiptEligibilityWiringTest.kt:58` がこの 2 ヘッダを境界に rollback runbook を assert するため、rollback 節と local smoke ヘッダの間に新しい `## ...` 節を挿入しない）
- [ ] 9.2 `docs/deploy.md` の setuid helper / privilege inventory gate / cleanup helper 委譲 / maintenance 制御の記述を新構成へ更新する
- [ ] 9.3 `docs/design.md:3567`（「fixed PID 1 が…UID 10002/10003 inventory を空に…launcher proxy」）を新構成の現在形へ更新する。supervisor / launcher proxy / UID 分離を load-bearing に記述しないこと
- [ ] 9.4 `docs/llm-obsidian-production-setup.md:39`（「supervisor cleanup acknowledgement…UID 10002/10003 の process inventory」）を新構成の現在形へ更新する
- [ ] 9.5 撤去した機能名・クラス名・command 名（`fukurou-runtime-supervisor` / `fukurou-mcp-launcher` / `fukurou-llm-agent-launcher` / `setuid` / `fd 3` / `fd 4` / `fd 5` / `mcp-runtime` / `llm-agent` / `request_supervisor`）で `docs/` と `README.md` を grep し、誤りになった記述が残っていないか確認する（CLAUDE.md の docs 現在形規約）

## 10. Stage 3 / validation と PR 明記

- [ ] 10.1 `make test` / `make detekt` / `make build` を実行する
- [ ] 10.2 PR description に password 供給経路の選択（env 直渡し `DB_PASSWORD`、bind mount 撤去）、UID 分離を appuser 1 UID へ統合した旨、maintenance 制御を DB flag + drain へ移した旨を明記する
- [ ] 10.3 PR description に「ドキュメント影響: あり（`docs/mcp-runtime.md`, `docs/deploy.md`, `docs/design.md`, `docs/llm-obsidian-production-setup.md`）」を書く
- [ ] 10.4 全 PR（Stage 1 / 2 / 3）完了後に change を archive する
