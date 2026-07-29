## Why

`make test` は 13分31秒（#312 実測の `./gradlew test` 単体）かかり、その大半を PostgreSQL container の起動が占める。`PostgresPersistenceIntegrationTest` は `runPostgresTest`（`trading/src/test/kotlin/me/matsumo/fukurou/trading/persistence/PostgresPersistenceIntegrationTest.kt:13032-13055`）でテスト1本ごとに container を start/stop しており、このファイル単独で 220 回、repository 全体で約 248 回の container 起動が直列に走る。worktree を並列に開けると Docker daemon とメモリが枯渇し、テストが不安定になる。

同時に、Testcontainers を使う 12 ファイルは Docker 不在時に `println` + `return` で抜けるため、テストフレームワーク上は pass 扱いになり skip 件数すら出ない。CI で Docker が壊れても全テストが緑になる。可用性判定 helper は 9 個の別名関数としてファイルごとに複製されている。

## What Changes

- Testcontainers PostgreSQL の共有 test helper を `trading` の `testFixtures` に集約し、`fukurou` / `trading` / `mcp` に重複する `TestPostgresConnection.kt` 3 本を解消する
- Docker 不在時の silent pass を廃止し、`assumeTrue` による skip 報告に統一する。9 個の別名 helper を共有 helper 1 個に置き換える
- `PostgresPersistenceIntegrationTest` の container ライフサイクルを per-test から **class 単位の共有 container + database per test** に変更する。テストごとに `CREATE DATABASE` して繋ぎ替え、終了時に `DROP DATABASE` する
- `ALTER SYSTEM` と `container.logs` に依存する 1 テストのみ、per-test container を維持する
- replay 系 3 ファイル（`TtlShorteningReplayIntegrationTest` / `TailFactSheetIntegrationTest` / `OneShotRunnerMainTest`）も同じ database per test 方式へ統一する

**BREAKING** なし（test source のみの変更。production コードと production の JDBC configuration には触れない）。

## Capabilities

### New Capabilities

なし。

### Modified Capabilities

- `postgres-test-connection-bounds`: fixture の所在（module-local → 共有 testFixtures）、container ライフサイクル（per-test → 共有 container + database per test）、Docker 不在時の扱い（silent pass → skip 報告）を変更する。既存の timeout 境界と retry semantics は維持する

## Impact

### 変更するコード

- `trading/src/testFixtures/kotlin/me/matsumo/fukurou/trading/testing/` — 共有 helper の新設
- `trading/src/test/.../TestPostgresConnection.kt` / `fukurou/src/test/.../TestPostgresConnection.kt` / `mcp/src/test/.../TestPostgresConnection.kt` — 削除または共有 helper への委譲
- `mcp/build.gradle.kts` — `testImplementation(testFixtures(project(":trading")))` の追加
- Testcontainers を起動する test 12 ファイル — Docker 判定の置換
- `trading/src/test/.../PostgresPersistenceIntegrationTest.kt` — container ライフサイクルと `PostgresTestContext` の接続経路
- replay 系 3 ファイル — 同上

### 変更しないもの

- production コード全般（`src/main` 配下）
- `admissionHealthIsolationRegressionTest` 2 タスクとその対象 suite
- process-global シングルトンの設計（`LlmExecutionAdmissionHealth` 等）
- `web`（vitest）、CI workflow 構成

### 依存関係

`fukurou` と `mcp` はいずれも `trading` に依存するため、共有 helper を `trading` の `testFixtures` に置けば 3 module 全部から参照できる。新規 module は不要。
