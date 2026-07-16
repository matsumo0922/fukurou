## Context

Issue #245 では Testcontainers PostgreSQL の container 自体が健全でも、macOS Docker Desktop の port proxy が返した stale socket を PostgreSQL JDBC が掴み、authentication response の read を無期限に待つ事象が 3 回観測されている。HikariCP の `connectionTimeout` は pool acquisition timeout であり、pool 初期化中の driver socket read を制限しない。

対象 inventory は `PostgreSQLContainer` を生成する test file 9 件（`:trading` 2 件、`:fukurou` 6 件、`:mcp` 1 件）である。これらは HikariCP、Exposed、`DriverManager`、production composition entrypoint へ `container.jdbcUrl` を渡す。

## Goals / Non-Goals

**Goals:**

- すべての Testcontainers PostgreSQL JDBC 接続で connect と socket read を有限時間に制限する。
- stale socket を掴んだ test worker が timeout 後に失敗し、Gradle が結果を回収できるようにする。
- production 接続へ影響しない test-only の変更として閉じる。

**Non-Goals:**

- Docker Desktop / port proxy 自体の復旧。
- production JDBC timeout / retry policy の変更。
- infrastructure failure を test 成功として扱う retry や test 再実行。

## Decisions

### [agent 仮決め] Testcontainers の URL parameter を接続元で一元設定する

各 Gradle module の test source に `PostgreSQLContainer` extension を置き、fixture construction 時に `withUrlParam` で `connectTimeout=10`、`socketTimeout=30` を設定する。container が生成する URL 自体へ設定するため、HikariCP、Exposed、`DriverManager`、runtime config の全 consumer に同じ上限が伝播する。

代替の「各 `createDataSource` へ個別設定」は直接 `DriverManager` や Exposed に渡す経路を漏らすため採用しない。「production の DataSource factory を変更」は issue の明示的な non-goal に反するため採用しない。

### [agent 仮決め] timeout 値は connect 10 秒、socket read 30 秒とする

localhost の Testcontainers 接続として connect 10 秒は十分な余裕を持ち、authentication を含む socket inactivity は 30 秒で打ち切る。値は module 内の test helper が所有し、contract test が URL parameter を固定する。

### [agent 仮決め] 自動 retry は導入しない

Issue #245 の必須目的は無期限 hang を有限の失敗へ変換することにある。retry は一時障害を吸収できる一方、全 fixture の side effect 境界と再初期化安全性を新たに設計する必要があり、systematic failure を遅延・隠蔽する。今回は timeout failure を Gradle へ返し、再試行は検証 orchestration の責務として stage-out する。

### [ユーザー確認済み] OpenSpec を repo-local に導入する

ユーザーの明示許可に基づき `openspec init --tools codex,claude` の成果物を同じ PR に含める。以後の仕様変更は `openspec/changes/` で提案し、merge 後に archive して `openspec/specs/` へ同期する。

## Risks / Trade-offs

- [30 秒以内でも full validation は一時障害で失敗し得る] → hang を成功へ偽装せず、有限の infrastructure failure として可視化する。
- [新しい PostgreSQL fixture が helper 適用を忘れる] → module ごとの contract test と review 時の `PostgreSQLContainer` finite inventory で検出する。
- [timeout parameter が既存 query を打ち切る] → PostgreSQL JDBC の `socketTimeout` は network read inactivity を対象とし、通常の長時間 query deadline の代用にはしない。既存 test の最長正当な無通信時間を超える 30 秒を採用する。
- [Codex の global prompt setup が sandbox 制約で失敗した] → repo-local `.codex/skills` は生成済みであり、OpenSpec CLI workflow は利用できる。global prompt installation は PR の correctness に含めない。

## Migration Plan

1. test-only helper と contract test を各対象 module に追加する。
2. finite inventory の全 PostgreSQL container construction に helper を適用する。
3. targeted test と full validation を実行する。
4. merge 後、後続 run で change を archive する。

Rollback は helper 適用と OpenSpec 導入差分の revert で完了し、production migration は不要である。

## Open Questions

なし。
