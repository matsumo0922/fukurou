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
- test body 実行後の side effect を再実行する retry や test 全体の再実行。

## Decisions

### [agent 仮決め] Testcontainers の URL parameter を bounded base container で一元設定する

各 Gradle module の test source に module-local な `BoundedTestPostgresContainer` を置き、すべての PostgreSQL fixture はこの基底 class を継承する。基底 class は construction 時に `withUrlParam` で既定の `connectTimeout=10`、`loginTimeout=30`、`socketTimeout=300` を設定する。container が生成する URL 自体へ設定するため、HikariCP、Exposed、`DriverManager`、runtime config の全 consumer に同じ上限が伝播する。fixture ごとの helper 呼び出しを不要にし、適用忘れを構造的に防ぐ。

既存 MCP wrong-password test の `2/2` 秒は、既定より厳しい risk-reducing timeout として維持する。ただし query string の再連結は行わず、既存 parameter map を parse して key 単位に上書きし、値を再 encode する test URL helper を使う。同 test は「例外が出た」だけでなく PostgreSQL の invalid-password SQLSTATE `28P01` を確認し、URL 設定エラーによる偽陽性を拒否する。

代替の「各 `createDataSource` へ個別設定」は直接 `DriverManager` や Exposed に渡す経路を漏らすため採用しない。「production の DataSource factory を変更」は issue の明示的な non-goal に反するため採用しない。

### [agent 仮決め] connection establishment と通常 query の timeout を分離する

localhost の Testcontainers TCP connect は 10 秒、PostgreSQL JDBC connection establishment 全体は `loginTimeout=30` で打ち切る。PostgreSQL JDBC 42.7.13 の `loginTimeout` は接続試行を別 thread で実行し、deadline 到達時に attempt を abandon/cancel するため、Issue #245 の authentication read hang を query semantics から分離して制限できる。

`socketTimeout` は認証後の query read にも継続して効く。full/targeted validation では 30 秒と 60 秒の両方が正当な大規模集計と競合したため、query read は 300 秒で有限化する。値は module 内の bounded base container が所有し、contract test が既定 URL parameter と「consumer 固有の値はこの上限以下」を固定する。

### [agent 仮決め] `runPostgresTest` の初期接続だけを最大 3 attempts にする

full validation で timeout 自体は有限に発動したが、同じ pool 初期化が 2 attempts とも connection failure になる事象を 72.9 秒の test report で観測した。そこで `runPostgresTest` が test body を開始する前の DataSource construction に限り、`SocketTimeoutException`、`ConnectException`、または connection attempt failure を表す SQLSTATE `08001` を cause chain に持つ失敗を最大 2 回再試行する。cause chain に non-null SQLSTATE があれば network cause より優先し、全 SQLSTATE が `08001` の場合だけ再試行する。3 回目の失敗、authentication failure（class `28`）、connection rejection（`08004`）、protocol error（`08P01`）、非 connection SQL error、test body 内の失敗はそのまま伝播する。

全 fixture や test 全体の retry は side effect の再実行と systematic failure の隠蔽を招くため採用しない。この境界なら 1 回目の pool は構築に失敗しており、database mutation と test assertion はまだ開始されていない。

### [ユーザー確認済み] OpenSpec を repo-local に導入する

ユーザーの明示許可に基づき `openspec init --tools codex,claude` の成果物を同じ PR に含める。以後の仕様変更は `openspec/changes/` で提案し、merge 後に archive して `openspec/specs/` へ同期する。

## Risks / Trade-offs

- [30 秒以内でも connection establishment が連続失敗し得る] → 最大 3 attempts 後は成功へ偽装せず、有限の infrastructure failure として可視化する。
- [retry が systematic failure を隠す] → retryable cause を socket timeout / connect failure に限定し、最大 3 attempts、test body 開始前だけに適用する。
- [新しい PostgreSQL fixture が timeout 設定を持たない] → fixture 定義を module-local な `BoundedTestPostgresContainer` の継承に限定し、module ごとの contract test で基底 class の既定値を固定する。
- [timeout parameter が既存 query を打ち切る] → connection establishment は `loginTimeout=30` で分離して直接制限し、query read の `socketTimeout` は実測で失敗した 60 秒から 300 秒へ広げる。full validation で既存 query との両立を確認する。

## Migration Plan

1. test-only bounded base container と contract test を各対象 module に追加する。
2. finite inventory の全 PostgreSQL fixture を bounded base container へ移行する。
3. targeted test と full validation を実行する。
4. merge 後、後続 run で change を archive する。

Rollback は bounded base container への移行と OpenSpec 導入差分の revert で完了し、production migration は不要である。

## Open Questions

なし。
