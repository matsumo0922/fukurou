## ADDED Requirements

### Requirement: Testcontainers PostgreSQL connections are time bounded

Issue #245 の受け入れ条件として、repository の Testcontainers PostgreSQL fixture が生成する全 JDBC URL は、connection establishment と socket read の有限 timeout を MUST 設定する。

#### Scenario: Every test consumer receives bounded JDBC settings

- **WHEN** Testcontainers PostgreSQL fixture が HikariCP、Exposed、`DriverManager`、または production composition test へ JDBC URL を渡す
- **THEN** URL は `connectTimeout` が 10 秒以下、`socketTimeout` が 30 秒以下の正の整数値をそれぞれちょうど 1 個含む

#### Scenario: Consumer uses a stricter timeout

- **WHEN** 接続失敗を検証する consumer が既定値より短い timeout を必要とする
- **THEN** URL helper は既存 query parameter を key 単位で上書きし、重複 key や複数の `?` を生成しない

#### Scenario: Authentication response stops arriving

- **WHEN** PostgreSQL JDBC connection が socket を確立した後、authentication response を受信できない
- **THEN** driver は設定した socket timeout 以内に例外を返し、test worker は無期限に停止しない

#### Scenario: First pool initialization hits a transient socket failure

- **WHEN** `runPostgresTest` の test body 開始前に最初の DataSource construction が socket timeout または connect failure で失敗する
- **THEN** fixture は接続を 1 回だけ再試行し、成功すれば test body を 1 回だけ実行する

#### Scenario: Retry does not mask a persistent or non-network failure

- **WHEN** 2 回目の DataSource construction が失敗する、または最初の失敗が retryable socket/connect cause を持たない
- **THEN** fixture は失敗を呼び出し元へ伝播し、test body を実行しない

#### Scenario: Wrong-password assertion rejects URL configuration failures

- **WHEN** MCP integration test が誤った password で PostgreSQL 接続失敗を検証する
- **THEN** test は任意の例外ではなく invalid-password SQLSTATE `28P01` を確認する

### Requirement: Production connection semantics remain unchanged

Issue #245 の non-goal として、変更は test source の Testcontainers fixture に限定し、production の JDBC configuration と retry semantics を MUST 変更しない。

#### Scenario: Production application is built

- **WHEN** application または trading runtime の production DataSource を構築する
- **THEN** 本 change による timeout parameter や retry は追加されない
