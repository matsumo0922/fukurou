## Why

Issue #207 の OFF 実験を production path へ進めるには、A1 の authority / fingerprint / strict replay と A2a の atomic backend を、既存の command preparation / SafetyFloor を迂回せずに一つの internal broker path として接続する必要がある。
A2a までは backend が inactive で A1 `Missing` が常に fail closed になるため、本 change は runtime activation より前の A2b 接続だけを行う。

## What Changes

- A1 authorized place boundary は authority と fingerprint を検証し、stable identity だけの strict replay を fresh preparation より先に実行する
- public `PaperBroker` constructor と任意の`connectedPostgres(dataSource, database)` pairを受けるfactoryは authorized capability unsupported のままとし、database引数を受けないproduction-safe factoryだけがearly opaque `PostgresStorageRoot`を生成する
- rootは同じDataSource由来の`ExposedDatabase`、scope connection factory / eviction controllerだけを所有し、active DB runtime configとclock解決後にroot overloadがledger / decision / policy repository、backendをconfig-bound bundleとして構成する。InMemoryは同じruntime repository instancesを要求し、mismatchは`Exact` / `Missing`にかかわらずtyped unsupportedにする
- `ApplicationDatabaseResources`はrootを保持してread-only DataSource / database getterを他serviceへ渡し、daemon schedulerとmanual launchはresolved configごとにroot overloadから`TradingRuntime`を構築する。shared runtimeはrootをcloseせずApplication shutdownがworkers終了後に一度だけcloseし、standalone runtimeは所有rootを一度だけcloseする
- backendはstable request execution scopeを提供し、PostgreSQLはdedicated sessionが存続するnormal concurrency、InMemoryはprocess内mutexの存続中に同じrequestを直列化する。PostgreSQL session loss / failoverを完全直列化とは扱わない
- PostgreSQL stable request lockはblocking APIを使わず、既存global / market-sessionのsingle-bigint advisory key familyと分離したtwo-int `pg_try_advisory_lock`を固定namespace `1179994962`とstable request hash32で30秒のmonotonic deadlineまで50ms間隔pollする
- 30秒deadlineはHikari connection borrow前に開始し、borrowと各try callをremaining budgetのquery / network timeoutで拘束する。cancel / SQL failure / response loss / timeout restore failureはconnectionをevictし、unlockは独立5秒cleanup deadlineで失敗時にabort / evictする
- PostgreSQLはSafetyFloor / DB-visible preparation side effect前、A2a backend invocation前、non-mutation terminal return前にacquisitionと無関係な独立5秒monotonic deadlineで同じdedicated sessionのheartbeat / ownershipを確認する。remaining query / network timeout、running statement cancel、timeout restore failure時のevictをacquisition / unlockと同様に適用し、loss / unknownはtyped unavailableで成功・NO_TRADE・未commitへ変換せず、backend confirmed result後のscope lossはpaper truthとstrategy evaluationを覆わない
- `Exact` は保存済み result をそのまま返し、`Ambiguous` / read failure は mutation へ進めず typed failure を維持する
- `Missing` の場合だけ、既存 public place path と同じ command validation、market preparation、SafetyFloor、symbol / price / cash contract、MARKET / resting proposal preparation を実行する
- preparation 後は InMemory / PostgreSQL の A2a `AuthorizedAtomicPaperEntryBackend` を呼び、backend の `Exact` / `Created` を `PaperTradeResult` へ変換する
- A1 preflight 後の競合により backend が `Exact` を返した場合は、fresh proposal の ID、resolved trade group、MARKET / resting subtype、fill / TTL / eligibilityを比較しない
- A2a backendはinitial replayが`Missing`の後にsubtype / session lock hintだけを読み、ledger / session lock後のsecond replayも`Missing`の場合だけfull proposal validationとfresh proposalの使用へ進む
- entry mutationを行わないterminal result / failureの直前にfresh strict replayを行い、`Exact`を優先し、`Missing`だけ元のterminalを返し、`Ambiguous` / read failureをindeterminateにする。ただしSafetyFloor enforcement / sweepがthrowした場合は元failureを正としてreadback failureをsuppressする
- backend 未対応、replay unavailable / ambiguous、atomic outcome indeterminate を `NO_TRADE`、確定失敗、または安全な再試行成功へ変換せず fail closed にする
- public `Broker` / `PlaceOrderCommand` / MCP schema、public v2 namespace guard、production runner、Falsifier 実行、runtime activation / status / outcome mapping を変更しない
- docs は A2b internal path が接続済みだが B まで production から到達不能である現在形へ更新する

## Capabilities

### New Capabilities

- `falsifier-policy-atomic-entry-connection`: A1 strict replay、既存 broker preparation / SafetyFloor、A2a atomic backendを接続する inactive internal broker path

### Modified Capabilities

なし。

## Impact

- `AuthorizedFalsifierPolicyBoundary` の storage affinity / stable request scope / strict replay / `Missing` continuation
- `TradingRuntimeFactory` のopaque `PostgresStorageRoot`、config-bound runtime bundle、`PaperBroker` capability injection、shutdown ownership
- `PaperBroker` の authorized place preparation とnon-mutation terminal readback
- InMemory / PostgreSQL の stable request scope、two-int advisory namespace、connection eviction、strict replay reader adapter、`AuthorizedAtomicPaperEntryBackend`
- authorized boundary / broker / backend integration test
- `docs/mcp-runtime.md`

DB schema migration、public API、MCP wire schema、runner wiring、runtime configは変更しない。
B は runtime activation precondition、runner permit propagation、Falsifier skip、durable status / outcome mappingを扱う。
