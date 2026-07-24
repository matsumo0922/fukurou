## Why

MCP subprocess 専用の PostgreSQL role `fukurou_mcp` は single-owner の paper trading 環境で実質的な資産を保護しておらず、migration ごとの GRANT 追従、provision script、deploy 手順の保守コストだけを増やしている。Epic #286 の簡素化方針に従い、MCP の DB identity を既存の application role へ一本化する。

## What Changes

- MCP launch manifest の `dbUser` に application user（`DB_USER` / `POSTGRES_USER` と同じ値）を書き、専用 MCP role への依存を撤去する。
- `FUKUROU_MCP_DB_USER` と `DEFAULT_MCP_DATABASE_USER` を削除する。
- `scripts/deploy/provision-fukurou-mcp-role` と `scripts/deploy/sql/mcp-role.sql` を削除し、Docker image・root deploy executor・DB helper の3箇所で同期する payload manifest、foundation SQL の `mcp_role` GRANT、関連 contract/self-test から専用 role の追従処理を除去する。
- MCP isolation canary の manifest 生成を application user に追従させる。
- dedicated-role integration test を、application role で既存 MCP tool matrix と submission gateway 経路が成立する回帰テストへ置き換える。
- `fukurou_mcp` role を前提とする runtime・deploy・design の記述と `.env.example` を現在の構成へ更新する。
- production DB に残る `fukurou_mcp` role は自動削除せず、既存 ownership を application role へ移し、ACL dependency を除去してから role を削除する owner 手順を最終 cutover PR の migration note として提示する。
- `submit_decision` は引き続き submission gateway 経由とし、MCP tool の永続化経路と paper truth の意味を変更しない。

## Capabilities

### New Capabilities

- なし。

### Modified Capabilities

- `llm-cli-invocation-contract`: MCP subprocess が専用 PostgreSQL role ではなく application role で接続し、submission gateway を永続化境界として維持する契約を明記する。

## Impact

- `trading` module の production / canary MCP manifest 生成と関連テスト
- production compose と `.env.example` の MCP DB user 設定
- deploy role provision script / SQL、Dockerfile・root deploy executor・DB helper の payload manifest、foundation GRANT、deploy contract/self-test
- `mcp` module の dedicated-role integration test と MCP tool matrix coverage
- `docs/deploy.md`、`docs/mcp-runtime.md`、`docs/design.md` および README の関連記述
- production database の owner 向け手動 migration note
- 2 PR 構成: additive な application-role regression coverage、続いて runtime/deploy/docs の cutover と専用 role 資産削除
