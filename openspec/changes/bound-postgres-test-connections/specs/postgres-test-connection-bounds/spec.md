## ADDED Requirements

### Requirement: Testcontainers PostgreSQL connections are time bounded

Issue #245 の受け入れ条件として、repository の Testcontainers PostgreSQL fixture が生成する全 JDBC URL は、connection establishment と socket read の有限 timeout を MUST 設定する。

#### Scenario: Every test consumer receives bounded JDBC settings

- **WHEN** Testcontainers PostgreSQL fixture が HikariCP、Exposed、`DriverManager`、または production composition test へ JDBC URL を渡す
- **THEN** URL は `connectTimeout=10` と `socketTimeout=30` を含む

#### Scenario: Authentication response stops arriving

- **WHEN** PostgreSQL JDBC connection が socket を確立した後、authentication response を受信できない
- **THEN** driver は設定した socket timeout 以内に例外を返し、test worker は無期限に停止しない

### Requirement: Production connection semantics remain unchanged

Issue #245 の non-goal として、変更は test source の Testcontainers fixture に限定し、production の JDBC configuration と retry semantics を MUST 変更しない。

#### Scenario: Production application is built

- **WHEN** application または trading runtime の production DataSource を構築する
- **THEN** 本 change による timeout parameter や retry は追加されない
