# Design: 共有 PostgreSQL test container

## Context

`PostgresPersistenceIntegrationTest`（15,730 行 / `@Test` 220 個）は `runPostgresTest` でテスト 1 本ごとに `postgres:16-alpine` container を start/stop する。repository 全体の container 起動は約 248 回で、うち 220 回（89%）がこのファイルに集中する。

読み取り調査（clean context の独立 agent が実施）で判明した制約が設計を規定する。

- `TradingPersistenceBootstrap.ensureSchema()` は `SchemaUtils.createMissingTablesAndColumns` を毎回実行し、正常な既存 schema の上でも成功する（`trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/TradingPersistenceBootstrap.kt:1372,1392-1439`）。同一 DB で 3 回連続 bootstrap して index 数が一定であることを既存テストが確認している（`PostgresPersistenceIntegrationTest.kt:1710-1731`）
- **migration 前の状態を必要とするテストが 7 件ある**。bootstrap 前に legacy table を手動 `CREATE TABLE` する、あるいは bootstrap 前の `verifySchema()` 失敗を要求する（`:1710-1719`, `:4440-4450`, `:5230-5247`, `:5285-5306`, `:5367-5399`, `:5718-5749`, `:9135-9169`）
- **schema を破壊したまま終了するテストが 13 件以上ある**。column / table の DROP、cleanup されない trigger・function・sequence、invalid index、`CHECK(false)` constraint が残る（`:1673-1687`, `:2298-2304`, `:2481,2517-2523`, `:4363-4374`, `:5414-5459`, `:5463-5500`, `:5504-5553`, `:5797-5805`, `:6986`, `:9660,9724-9728`, `:10685-10701`, `:11061-11084`）
- instance-global な副作用は `ALTER SYSTEM SET log_statement = 'ddl'` + `pg_reload_conf()` の 1 件のみで、`RESET` されない（`:5251-5252`, helper `:13746-13752`）。`CREATE DATABASE` / `CREATE ROLE` / `CREATE EXTENSION` / `pg_terminate_backend` / replication slot は存在しない
- `container.logs` を使うのは 1 テスト・2 call site。logging 有効化後の offset から DDL 文を数え `assertEquals(6, ...)` する（`:5230`, `:5251-5252`, `:5280-5281`, matcher `:13755-13768`）
- `HikariDataSource` は全 call site で `.use` されており、pool leak は見つかっていない（base factory `:13277-13292`、fault factory `:11426-11442`）

## Goals / Non-Goals

### Goals

- `PostgresPersistenceIntegrationTest` の container 起動を 220 回から 2 回（共有 1 + 例外 1）に減らす
- Docker 不在時にテストが silent pass せず、skip として報告される
- Testcontainers helper の 3 module 重複を解消する
- 既存 220 テストの意味（何を検証しているか）を変えない

### Non-Goals

- テストの分割・削除・統合。行数削減は本 change の目的ではない
- production コードの変更
- process-global シングルトンの設計変更
- テスト実行の並列化（`maxParallelForks` の引き上げ）。共有 container は serial 実行を前提とする
- `ALTER SYSTEM` の復元（container 再利用の範囲を超えるため、該当テストを隔離する方針を採る）

## Decisions

### D1: database per test を採用する（共有 container 内で `CREATE DATABASE`）

各テストの開始時に共有 container 上で一意名の database を作成し、その database を指す JDBC URL で接続する。テスト終了時に接続を全て閉じてから `DROP DATABASE`。

**却下した代替案**

- **全 table TRUNCATE**: 不可。migration 前の legacy table を手動作成する 7 テストが既存 table と衝突し、DROP された column / table・残存 trigger・invalid index は TRUNCATE で復元できない
- **schema per test（`CREATE SCHEMA` + `search_path`）**: 非推奨。`TradingPersistenceBootstrap` の obsolete trigger 除去が `to_regclass('public.$table')` と public 固定（`TradingPersistenceBootstrap.kt:2093-2104`）で、test schema では誤った table を参照する。また `tradingDatabaseConfig()`（`:11448-11453`）が container の default JDBC URL へ新規接続するため、全接続経路への `search_path` 伝播が必要になり侵襲的
- **per-test container の維持**: 現状。所要時間と資源競合が解決しない

**採用理由**: 各テストは現状と同じ「空の public schema に `ensureSchema()` を適用する」構造をそのまま得る。schema 破壊・残存 trigger・追加 schema・統計を database 単位でまとめて破棄でき、220 テスト分の FK 順序を考慮した TRUNCATE リストや修復処理が不要になる。

### D2: `PostgresTestContext` に test database の JDBC URL を保持させる

補助 DataSource factory（`createDataSource(connectionInitSql)` / `createDeadlineDataSource()` / `createRecoveryCommitFaultDataSource()`、`:11414-11442`）と `tradingDatabaseConfig()`（`:11448-11453`）は現在 `container.jdbcUrl` を直接参照する。これらを context 保持の test database URL 経由に変える。

この変更を漏らすと、補助接続だけが共有 container の default database を指し、テスト間でデータが漏れる。**実装時の最重要チェックポイント**とする。

### D3: `ALTER SYSTEM` + `container.logs` 依存テストは per-test container に残す

`bootstrap_addsNullableClaimColumnsWithoutBackfillOrTableRewrite`（`:5230`）のみ従来の per-test container を維持する。

理由は 2 つ。`ALTER SYSTEM` は database ではなく instance の設定を変えるため database per test では隔離されない。`container.logs` は共有 container だと他 database の DDL が混入し、同じ column / index 名にマッチして `assertEquals(6, ...)` が壊れうる。

offset で過去ログを除外する現在の実装は serial 実行なら成立するが、instance 設定が他テストへ漏れる問題は残る。1 container の追加コストで両方を確実に回避できるため隔離を選ぶ。

### D4: Docker 判定は `assumeTrue` に統一する

`println` + `return` を `org.junit.Assume.assumeTrue` に置き換え、skip をテストレポートに出す。「Docker 必須にして失敗させる」案は却下する。ローカルで Docker を起動せずに unit テストだけ回す開発フローを壊すため。

skip 件数がレポートに出れば「CI で Docker が壊れても緑」は解消される。実行されたことの観測手段が所要時間しかない現状より改善する。

### D5: 共有 helper は `trading` の `testFixtures` に置く

`fukurou` と `mcp` はいずれも `trading` に依存するため、新規 module を作らずに 3 module から参照できる。`fukurou/build.gradle.kts:35` は既に `testImplementation(testFixtures(project(":trading")))` を持つ。`mcp/build.gradle.kts` に同じ 1 行を追加する。

集約対象は `BoundedTestPostgresContainer`、timeout 定数、`retryTransientTestPostgresConnection`（現在 trading のみ）、`withJdbcQueryParameters`（現在 mcp のみ）、Docker 可用性判定。

## Risks / Trade-offs

- **[補助接続の URL 差し替え漏れ]** → D2 の全 call site を実装時に列挙し、`container.jdbcUrl` の直接参照が test 本体に残っていないことを grep で確認する。レビューの重点確認対象とする
- **[テスト間のデータ漏れ]** → 各テストが独立 database を持つため構造的に防がれるが、`DROP DATABASE` 前に接続が残っていると失敗する。close 順序を helper に閉じ込め、テスト個別の後始末に依存しない
- **[共有 container の serial 前提]** → 現在 `trading` の `test` タスクは `maxParallelForks` 未指定（Gradle 既定 1）で serial（`trading/build.gradle.kts:33-35`）。将来並列化すると `container.logs` と WAL 観測（`:9316-9338`）が壊れうる。helper のドキュメントコメントに serial 前提を明記する
- **[`ensureSchema()` の非冪等パスの発見]** → 220 テストのうち一部が「fresh container だから通っていた」可能性は残る。database per test は fresh database を与えるため大半は影響しないが、実行して初めて分かる。PR3 の検証で全 220 テストの pass を確認する
- **[所要時間の改善が見込みより小さい]** → container 起動が支配的という推定が外れた場合。変更前後を計測して記録し、効果が出なければ PR4 以降の判断材料にする

## Migration Plan

stacked PR で 4 段に分ける。各 PR は独立して意味を持ち、前段の approve を待って次段に着手する。

1. **PR1（基盤）**: 共有 helper を `testFixtures` に集約。3 module の重複解消。挙動不変
2. **PR2（silent pass 廃止）**: PR1 の helper を使い、Docker 判定を `assumeTrue` に統一
3. **PR3（本命）**: `PostgresPersistenceIntegrationTest` を database per test 化。220 → 2 起動
4. **PR4（横展開）**: replay 系 3 ファイルを同方式に統一。17 → 3 起動

PR1 と PR2 を分ける理由は、PR2 が 12 ファイルに触れる機械的置換で、基盤の設計レビューと混ぜると diff が読みにくくなるため。

ロールバックは PR 単位の revert で可能。production コードに触れないため運用への影響はない。

## Open Questions

- PR4 の要否は PR3 の実測効果を見て判断する。PR3 で所要時間が十分改善し、replay 系 17 起動の寄与が誤差なら、PR4 は follow-up issue に落として本 change を 3 PR で閉じる（agent 仮決め。人間の確認事項として PR3 の description に記載する）
- `make test` の計測は同一マシン・同一条件で行うが、Docker のイメージキャッシュ状態に左右される。変更前後を連続実行して比較する
