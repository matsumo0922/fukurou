# postgres-test-connection-bounds Delta Specification

## MODIFIED Requirements

### Requirement: Testcontainers PostgreSQL connections are time bounded

Issue #245 の受け入れ条件として、repository の Testcontainers PostgreSQL fixture は共有 test fixture が提供する bounded base container を MUST 継承し、生成する全 JDBC URL に connection establishment と socket read の有限 timeout を設定する。large population test は socket timeout を test oracle にせず、同じ period に1件 scoped + 20,001件 scope外を作り、normal scoped aggregation と global oversized rejection を別々の assertion で bounded time に検証しなければならない。

#### Scenario: Every fixture inherits bounded connection settings

- **WHEN** test source に Testcontainers PostgreSQL fixture を定義する
- **THEN** fixture は共有 test fixture が提供する bounded base container を継承し、fixture ごとの timeout helper 呼び出しを必要としない

#### Scenario: Every test consumer receives bounded JDBC settings

- **WHEN** Testcontainers PostgreSQL fixture が HikariCP、Exposed、`DriverManager`、または production composition test へ JDBC URL を渡す
- **THEN** URL は `connectTimeout` が 10 秒以下、`loginTimeout` が 30 秒以下、`socketTimeout` が 300 秒以下の正の整数値をそれぞれちょうど 1 個含む

#### Scenario: Consumer uses a stricter timeout

- **WHEN** 接続失敗を検証する consumer が既定値より短い timeout を必要とする
- **THEN** URL helper は既存 query parameter を key 単位で上書きし、重複 key や複数の `?` を生成しない

#### Scenario: Authentication response stops arriving

- **WHEN** PostgreSQL JDBC connection が socket を確立した後、authentication response を受信できない
- **THEN** driver は設定した login timeout 以内に connection failure を返し、test worker は無期限に停止しない

#### Scenario: First pool initialization hits a transient socket failure

- **WHEN** `runPostgresTest` の test body 開始前に最初の DataSource construction が socket/connect cause または SQLSTATE `08001` の connection attempt failure で失敗する
- **THEN** fixture は接続を最大 2 回再試行し、成功すれば test body を 1 回だけ実行する

#### Scenario: Retry does not mask a persistent or non-network failure

- **WHEN** 3 回目の DataSource construction が失敗する、または最初の失敗が retryable SQLSTATE `08001` を持たない（SQLSTATE がない場合は socket/connect cause を持たない）
- **THEN** fixture は失敗を呼び出し元へ伝播し、test body を実行しない

#### Scenario: Wrong-password assertion rejects URL configuration failures

- **WHEN** MCP integration test が誤った password で PostgreSQL 接続失敗を検証する
- **THEN** test は任意の例外ではなく invalid-password SQLSTATE `28P01` を確認する

#### Scenario: One scoped trade remains queryable inside a global oversized population

- **WHEN** evaluation repository test が同じ period に1件 scoped + 20,001件 scope外を作る
- **THEN** scoped trade query は1件だけを返し、prior PnL aggregation は正常に成功する
- **AND** 同じ20,002件の global population に対する別の assertion は JDBC socket timeout 前に `EVALUATION_POPULATION_UNAVAILABLE:ENTITY_LIMIT` を返す

## ADDED Requirements

### Requirement: Testcontainers PostgreSQL helpers live in one shared fixture

Testcontainers PostgreSQL の共有 helper は `trading` の test fixture source set に MUST 1 箇所だけ存在し、`fukurou` / `trading` / `mcp` の test source はそれを参照する。同じ責務の helper を module ごとに複製してはならない。

#### Scenario: A module needs the bounded base container

- **WHEN** `fukurou` / `trading` / `mcp` のいずれかの test source が bounded PostgreSQL container を必要とする
- **THEN** 共有 test fixture の型を参照し、module-local な同名 helper を定義しない

#### Scenario: A module needs the JDBC query parameter override helper

- **WHEN** test source が既定より短い timeout などで JDBC URL の query parameter を上書きする
- **THEN** 共有 test fixture の helper を呼び、module-local な複製を定義しない

#### Scenario: A module needs the transient connection retry

- **WHEN** test source が test body 開始前の一時的な接続失敗を再試行する
- **THEN** 共有 test fixture の retry helper を呼び、module-local な複製を定義しない

### Requirement: Docker unavailability is reported as a skip

Testcontainers を起動する test は、Docker が利用できない場合に MUST skip として報告する。silent pass（テストが成功として集計され、skip 件数にも現れない状態）にしてはならない。Docker 可用性の判定は共有 test fixture の helper 1 個に集約し、module ごと・ファイルごとの別名 helper を定義してはならない。

#### Scenario: Docker daemon is unavailable

- **WHEN** Testcontainers を起動する test を Docker が利用できない環境で実行する
- **THEN** test は skip として報告され、test report の skip 件数に現れる
- **AND** test は成功として集計されない

#### Scenario: Docker daemon is available

- **WHEN** Testcontainers を起動する test を Docker が利用できる環境で実行する
- **THEN** test は container を起動して本体を実行し、skip されない

#### Scenario: A test needs to check Docker availability

- **WHEN** test source が Docker 可用性を判定する
- **THEN** 共有 test fixture の helper を呼び、ファイル固有の別名 helper を定義しない

### Requirement: Persistence integration tests share one container with a database per test

`PostgresPersistenceIntegrationTest` は container を test class 単位で MUST 共有し、test method ごとに専用 database を作成・破棄することで独立性を保つ。instance-global な設定変更または container log の内容に依存する test は、共有 container から隔離した専用 container で実行する。

#### Scenario: Test methods run against the shared container

- **WHEN** `PostgresPersistenceIntegrationTest` の test method を実行する
- **THEN** container は test class 全体で 1 個だけ起動し、method ごとに新しい database を作成する
- **AND** method 終了時にその database を破棄する

#### Scenario: A test leaves the schema damaged

- **WHEN** ある test method が column や table を削除する、または trigger・function・invalid index を残して終了する
- **THEN** 後続の test method はその影響を受けない database で開始する

#### Scenario: A test creates a legacy table before bootstrap

- **WHEN** ある test method が `ensureSchema()` 前に legacy table を手動作成して migration を検証する
- **THEN** その database には既存の table が無く、手動作成が既存 table と衝突しない

#### Scenario: A test changes instance-global settings or reads container logs

- **WHEN** test method が `ALTER SYSTEM` で instance 設定を変更する、または container log から DDL 文を数える
- **THEN** その test は共有 container ではなく専用 container で実行し、設定変更と log 混入が他 test に影響しない

#### Scenario: Auxiliary data sources target the test database

- **WHEN** test method が補助 DataSource（`connectionInitSql` 付き、deadline 用、fault 注入用）または production composition 用の database config を作る
- **THEN** それらは共有 container の default database ではなく、その test method 専用の database を指す

### Requirement: Replay integration tests share one container per class

`TtlShorteningReplayIntegrationTest` / `TailFactSheetIntegrationTest` / `OneShotRunnerMainTest` は container を test class 単位で MUST 共有し、test method ごとに専用 database を作成・破棄する。

#### Scenario: Replay test methods run against the shared container

- **WHEN** replay integration test の test method を実行する
- **THEN** container は test class 全体で 1 個だけ起動し、method ごとに新しい database を作成する
- **AND** method 終了時にその database を破棄する
