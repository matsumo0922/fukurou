## 1. PR 1 — Application-role regression coverage

- [x] 1.1 `McpDatabaseRoleIntegrationTest` の fixture/helper を整理し、専用 role の既存 assertion を変更せずに application role で production bootstrap/server path を起動できる additive scenario を作る
- [x] 1.2 application role の scenario で既存 MCP required tool matrix が成立し、`submit_decision` / `submit_falsification` が submission gateway 経由で永続化されることを検証する
- [ ] 1.3 PR 1 の関連 MCP integration test、`make test`、`make detekt` を実行し、本番配線を変更していないことを確認する
- [ ] 1.4 PR 1 description に「ドキュメント影響: なし」と、OpenSpec change は最終 cutover PR 完了まで archive しないことを記載する

## 2. PR 2 — MCP database identity cutover

- [ ] 2.1 `OneShotLlmRunner.mcpServerConfig()` が `DB_USER` を manifest の `dbUser` に設定するよう変更し、`FUKUROU_MCP_DB_USER` と `DEFAULT_MCP_DATABASE_USER` を削除する
- [ ] 2.2 `McpIsolationCanaryArtifacts` の manifest 生成を `DB_USER` に追従させる
- [ ] 2.3 `docker-compose.prod.yml` と `.env.example` から `FUKUROU_MCP_DB_USER` を削除し、MCP password・manifest・submission socket の既存配線は変更しない
- [ ] 2.4 runner / manifest fixture を application user に更新し、production decision-run の manifest が app と同じ DB identity を持つことを回帰テストで検証する

## 3. PR 2 — Dedicated role provisioning removal

- [ ] 3.1 `scripts/deploy/provision-fukurou-mcp-role` と `scripts/deploy/sql/mcp-role.sql` を削除する
- [ ] 3.2 `Dockerfile` の db-helper-manifest stage から `mcp-role.sql` の `COPY` と hash list entry を削除する
- [ ] 3.3 `scripts/deploy/deploy-fukurou` から `MCP_ROLE_SQL` と installed DB helper manifest の `mcp-role.sql` entry を削除する
- [ ] 3.4 `scripts/deploy/fukurou-deploy-db` から MCP role env/default、`mcp-role.sql` payload manifest entry、foundation SQL の `--set=mcp_role` 引数を削除する
- [ ] 3.5 `scripts/deploy/sql/deploy-foundation-v1.sql` から MCP role への GRANT 追従を削除する
- [ ] 3.6 `ReleaseDeployFoundationContractTest`、deploy executor self-test、`deploy-db-selftest`、`deploy-postgres-selftest` から MCP role payload / SQL fixture / env / CREATE ROLE / GRANT argument assertion を削除し、Dockerfile・root deploy executor・DB helper に共通する残存3-file manifest contract を検証する
- [ ] 3.7 `McpDatabaseRoleIntegrationTest` から provision / privilege repair / future object / forbidden-write role-boundary fixture と assertion を削除し、PR 1 で追加した application-role tool matrix / gateway regression を正本として残す
- [ ] 3.8 `McpPersistenceSchemaVerifier` と readiness test に専用 role / ACL 前提がないことを確認し、該当がない場合は変更しない

## 4. PR 2 — Documentation and migration note

- [ ] 4.1 `docs/deploy.md` から MCP role provision、権限マトリクス、rollout / DR の専用 role 手順を削除または application role 構成の現在形へ書き直す
- [ ] 4.2 `docs/mcp-runtime.md` の DB role 分離説明と test 説明を application role + submission gateway 境界へ書き直し、`docs/design.md` の stale な `fukurou_mcp` 前提を除去する
- [ ] 4.3 README と docs を `FUKUROU_MCP_DB_USER`、`fukurou_mcp`、`provision-fukurou-mcp-role`、`mcp-role.sql` で検索し、専用 role の残存記述を除去する
- [ ] 4.4 PR 2 description に「ドキュメント影響: あり（対象ファイル）」と、`pg_shdepend` / active session のcluster preflight、`BEGIN; REASSIGN OWNED ...; DROP OWNED ...; DROP ROLE ...; ROLLBACK;` dry-run、同transactionの`COMMIT`版、別database dependency検出時は変更せず停止する条件、自動実行しない理由、適用タイミングを記載する
- [ ] 4.5 `docs/deploy.md` に、rollback SHAの`deploy-fukurou`・`fukurou-deploy-db`・foundation/index/`mcp-role.sql`をexact配置して旧markerを再生成してから旧imageを起動し、role cleanup後は旧provisionも再実行するrollback手順を記載する

## 5. PR 2 — Verification and archive

- [ ] 5.1 MCP manifest / runner / isolation canary / application-role tool matrix の関連テストを実行し、application role での起動と gateway 経由 tool call を確認する
- [ ] 5.2 `ReleaseDeployFoundationContractTest`、`deploy-db-selftest`、`deploy-postgres-selftest` を実行し、Dockerfile・root deploy executor・DB helper の残存3-file manifest と foundation install を確認する
- [ ] 5.3 disposable PostgreSQL のapplication DBと別databaseにrole ownership / ACL dependencyを作り、cluster preflightが別database dependencyを検出すること、dry-run transactionの`DROP ROLE` failureがapplication DBを変更しないこと、別database cleanup後のfinal transactionがroleを削除してownershipを保持することを検証する
- [ ] 5.4 4-file旧artifact set→3-file新candidateと、3-file新artifact set→4-file旧candidateの両方向について、exact set + marker再生成なら検証が通り、混在setではfail-closedになるrollback manifest self-testを追加・実行する
- [ ] 5.5 `make test`、`make detekt`、`make build` を実行する
- [ ] 5.6 active source/docs 全体を `FUKUROU_MCP_DB_USER`、`DEFAULT_MCP_DATABASE_USER`、`fukurou_mcp`、`provision-fukurou-mcp-role`、`mcp-role.sql`、`mcp_role` で検索し、archived OpenSpec と意図した PR migration note 以外の残存を確認する
- [ ] 5.7 PR 2 完了後に OpenSpec change を一度だけ archive する
