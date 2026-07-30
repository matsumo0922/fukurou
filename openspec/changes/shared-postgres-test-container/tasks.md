# Tasks

stacked PR で 4 段に分ける。各段は前段の reviewer approve を待って着手する。

## PR1: 共有 test fixture の新設と重複解消

対応する受け入れ条件: 「`TestPostgresConnection.kt` の 3 module 重複が解消され、共通部が共有 fixture から参照されている」

- [x] `trading/src/testFixtures/kotlin/me/matsumo/fukurou/trading/testing/` に共有 helper を新設する
  - [x] `BoundedTestPostgresContainer` と timeout 定数（`connectTimeout` 10s / `loginTimeout` 30s / `socketTimeout` 300s）
  - [x] `retryTransientTestPostgresConnection`（現在 `trading` のみが持つ）
  - [x] `withJdbcQueryParameters`（現在 `mcp` のみが持つ）
  - [x] Docker 可用性判定 helper（PR2 で使う。PR1 では定義のみ）
- [x] `trading/build.gradle.kts` に `testFixturesApi(libs.testcontainers.postgresql)` を追加する
  - 現在 Testcontainers は `testImplementation` にしかなく、`testFixtures` の compile classpath に無いため `PostgreSQLContainer` の import が解決しない
  - `BoundedTestPostgresContainer` の superclass に型が露出するので `testFixturesImplementation` ではなく `testFixturesApi` を使う（前例: `mcp-core/build.gradle.kts:15-17`）
  - `commons-compress` の CVE constraint（`trading/build.gradle.kts:24-31`）が fixture 経由の consumer にも効くことを確認する
  - test source も Testcontainers を直接 import するため、`testImplementation` の明示宣言は残す（推移解決に依存させない）
- [x] helper に serial 実行前提であることをドキュメントコメントで明記する
  - `pg_current_wal_insert_lsn()` と `pg_locks` は database per test では隔離されないこと（cluster-global）を併記する
- [x] `mcp/build.gradle.kts` に `testImplementation(testFixtures(project(":trading")))` を追加する
- [x] `trading` / `fukurou` / `mcp` の `TestPostgresConnection.kt` 3 本を削除し、参照を共有 helper に切り替える
- [x] `internal` 可視性が module 境界を越えられない点に対処する（共有 helper は `public`、必要なら利用側で alias）
- [x] full validation（`make test` + `make detekt`）を実行し、挙動が変わっていないことを確認する

## PR2: Docker 不在時の silent pass 廃止

対応する受け入れ条件: 「Docker 不在時にテストが silent pass しない」「Docker 可用性の判定 helper が 1 箇所に集約され、ファイルごとの別名コピーが無い」

- [x] 9 個の別名 helper を PR1 の共有 helper に置き換える
  - [x] `fukurou`: `applicationMigrationDockerAvailable`（`ApplicationMigrationFailureTest.kt:304`）、`receiptDockerAvailable`（`PidRegistrationReceiptPersistenceTest.kt:115`）、`isDockerAvailable`（`DatabaseRecoveryPoolCompositionTest.kt:112`）、`reportDockerAvailable`（`EvaluationReportPersistenceTest.kt:130`）、`monitoringDockerAvailable`（`MonitoringRepositoryPostgresTest.kt:76`）、`isDockerAvailable`（`OpsRouteTest.kt:2323`）
  - [x] `trading`: `isDockerAvailable`（`PostgresPersistenceIntegrationTest.kt:13268`、`TtlShorteningReplayIntegrationTest.kt:363`、`TailFactSheetIntegrationTest.kt:389`）
  - [x] `DockerClientFactory` 直接参照（`DatabaseColdStartTest.kt:37`、`OneShotRunnerMainTest.kt:61`、`FukurouMcpServerTest.kt:1368,2323`）
- [x] 共有 fixture に `requireTestDocker()` を追加し、`assumeTrue` 呼び出しを 1 箇所に集約する
- [x] `DockerClientFactory` の参照が共有 fixture 1 箇所だけになったことを確認する
- [x] `println` + `return` / `return@runBlocking` を `assumeTrue` に置き換える
- [x] Docker 不在環境で skip 件数が test report に出ることを確認する（一時 probe で `assumeTrue(false)` を通し、`skipped=1` を JUnit XML で確認。probe は削除済み）
- [x] guard の契約テストを追加する（`dockerGuardRaisesAssumptionFailureWhenDaemonIsUnavailable` / `dockerGuardMatchesDaemonAvailability`）
- [x] full validation を実行する

## PR3: PostgresPersistenceIntegrationTest の container 共有化

対応する受け入れ条件: 「container 起動回数が per-test から共有に変わり、220 回から大幅に減っている」「共有化後も 220 テストが個別に pass し、テスト間でデータが漏れないことを確認できる」「`make test` の所要時間を変更前後で計測し、記録する」

- [ ] 変更前の `make test` 所要時間を計測して記録する
- [ ] container を test class 単位で 1 個共有する構造に変える
- [ ] `runPostgresTest` を database per test に変える
  - [ ] test ごとに一意名の database を `CREATE DATABASE` する
  - [ ] その database を指す JDBC URL で base DataSource を作る
  - [ ] test 終了時に `DROP DATABASE <name> WITH (FORCE)` で破棄する（残存接続を強制切断する。`TradingRuntimeFactory.postgres()` が所有する pool は test から close できず、`runtime.close()` が `finally` 外にある箇所が 7 件あるため）
  - [ ] `DROP DATABASE` を実行する admin 接続は対象 database 以外へ接続し、autocommit で実行する
- [ ] `PostgresTestContext` に test database の JDBC URL を保持させ、補助 factory を全て切り替える（**最重要チェックポイント**）
  - [ ] `createDataSource(connectionInitSql)`（`:11414-11416`）
  - [ ] `createDeadlineDataSource()`（`:11418-11424`）
  - [ ] `createRecoveryCommitFaultDataSource()`（`:11426-11442`）
  - [ ] `tradingDatabaseConfig()`（`:11448-11453`）
  - [ ] test 本体に `container.jdbcUrl` の直接参照が残っていないことを grep で確認する
- [ ] `bootstrap_addsNullableClaimColumnsWithoutBackfillOrTableRewrite`（`:5230`）を専用 container で実行する経路に分離する（`ALTER SYSTEM` と `container.logs` 依存のため）
- [ ] 220 テスト全件の pass を確認する
- [ ] cleanup が実際に行われたことを確認する（「220 個の database を作って 1 つも DROP しない」実装でも全件 pass するため、pass だけでは cleanup の証拠にならない）
  - [ ] class 終了時点で container 上に test database が残っていないことを確認する
  - [ ] `DROP DATABASE` が例外を投げずに完了することを確認する
  - [ ] テストが例外で中断した場合も database が破棄されることを確認する（意図的に失敗させる一時テストか、helper の単体検証で確認する）
- [ ] 変更後の `make test` 所要時間を計測し、変更前と比較して記録する
- [ ] container 起動回数が 220 → 2 になったことを確認する
- [ ] full validation を実行する

## PR4: replay 系 3 ファイルの container 共有化

対応する受け入れ条件: PR3 と同じ（container 起動回数の削減）

本 change の必須スコープ。delta spec が replay 3 class の共有化を MUST として規定しているため、省略すると change を完了できない。

- [ ] `TtlShorteningReplayIntegrationTest`（`runReplayTest`、`:331-349`）を database per test に変える
- [ ] `TailFactSheetIntegrationTest` を同様に変える
- [ ] `OneShotRunnerMainTest` を同様に変える
- [ ] container 起動回数が 17 → 3 になったことを確認する
- [ ] full validation を実行する

## 全 PR 共通の確認

- [ ] `admissionHealthIsolationRegressionTest` 2 タスクが従来どおり pass する
- [ ] production コード（`src/main` 配下）に変更が無い
- [ ] worktree を 2 つ以上並べて同時に `make test` を実行し、Docker 資源競合で落ちないことを確認する（PR3 完了後）
