## Why

Testcontainers PostgreSQL を使う test が、JDBC authentication 中の socket read に timeout を持たないため、Docker Desktop の接続単位の一時障害で無期限に停止する。Issue #245 で確認された full validation の停止を有限時間の失敗へ変換し、検証プロセスが自力で終了できるようにする。

## What Changes

- repository 内の Testcontainers PostgreSQL test fixture が生成する JDBC URL に有限の `connectTimeout` と `socketTimeout` を設定する。
- 同じ test process 内の全 JDBC consumer（HikariCP、Exposed、`DriverManager`、runtime config）が timeout 付き URL を受け取るよう、container の URL parameter を接続前に一元設定する。
- timeout 設定が container ごとに欠落しないことを Docker 不要の contract test と有限 inventory で検証する。
- production の JDBC 設定と接続 retry semantics は変更しない。
- OpenSpec を Codex / Claude Code 共通の repo-local 仕様管理として導入する。

## Capabilities

### New Capabilities

- `postgres-test-connection-bounds`: Testcontainers PostgreSQL を使う test 接続が有限時間で成功または失敗する契約。

### Modified Capabilities

なし。

## Impact

- `:trading`、`:fukurou`、`:mcp` の Testcontainers PostgreSQL fixture とその contract test。
- `.codex/`、`.claude/`、`openspec/` の OpenSpec workflow files。
- public API、production configuration、database schema、paper/live trading semantics への影響はない。
