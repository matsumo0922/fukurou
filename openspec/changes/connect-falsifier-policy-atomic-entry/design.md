## Context

A1 `AuthorizedFalsifierPolicyBoundary` は durable policy authority と v2 fingerprint を検証し、InMemory の strict replay が `Exact` なら保存済み result を返す。
`Missing` は `AuthorizedNewMutationUnsupportedException` で fail closed になり、PostgreSQL は A1 replay reader に未接続である。

A2a は InMemory / PostgreSQL の `AuthorizedAtomicPaperEntryBackend` を実装し、stable replay、intent availability、flat predicate、paper mutation、intent consumptionを一つの lock / transactionで確定する。
ただし A2a は attempt-local `AuthorizedAtomicEntryCreationProposal` を入力に取る storage primitiveであり、command validation、market preparation、SafetyFloor、symbol / price / cash contractを実行しない。
production `OneShotLlmRunner` は現在も全 entry で Falsifier と public `Broker` pathを使う。

A2b は A1 と A2a の間だけを接続する。
public/MCP contractとproduction runner behaviorを変えず、B が明示的にinternal pathを呼ぶまで到達不能に保つ。

## Goals / Non-Goals

**Goals:**

- durable authority / fingerprint検証後、stable identityだけのstrict replayをfresh market data、SafetyFloor、prepared IDより先に実行する
- `Missing` の場合だけ既存place pathと同じcommand preparation / SafetyFloorを通してA2a creation proposalを作る
- A2a backendのatomic `Exact` / `Created`を返し、typed failureを意味変換せず伝播する
- storage affinityを証明したInMemory / PostgreSQLだけをinternal boundaryへ接続し、未知またはmismatch backendはfail closedにする
- live scope sessionが存続するnormal concurrencyで同じstable requestをpreflightからterminal resultまで直列化し、false rejection auditと余分なHARD_HALT sweepを抑える
- public command、MCP schema、runner behavior、paper truthを維持する

**Non-Goals:**

- production runnerからinternal authorized pathを呼ぶこと
- Falsifier skipまたはpermit propagation
- runtime activation flag、active snapshot precondition、status / terminal cause / completion event / outcome mapping
- `OutcomeIndeterminate`の運用上のretry / recovery policy
- public/MCP schema、DB schema、SafetyFloor規則、paper fill semanticsの変更
- PostgreSQL session loss / failoverを越えるcross-process preparation / auditの完全直列化、durable fencing token、新table / column / migration
- scope lossに対するdurable infrastructure attribution、strategy evaluation / metric exclusion
- `CONDITIONAL_V1`、shadow、evaluation、live trading

## Decisions

### 1. early storage rootとconfig-bound runtime bundleを分離する

public `PaperBroker` constructorはauthorized capabilityを自動解決せず、常にunsupportedで構築する。
既存の任意`HikariDataSource` / `ExposedDatabase` pairを受け取るpublic `TradingRuntimeFactory.connectedPostgres(dataSource, database)`も、両者の生成元を証明できないためauthorized capabilityを注入しない。
production compositionとauthorized pathを明示的に検証するtestは`TradingRuntimeFactory`配下のroot / bundle factoryだけを使う。
これにより型がInMemory / Exposedであるだけの任意repository mixをauthorized pathへ接続しない。

publicだがproduction-safeなroot factoryは一つのowned `HikariDataSource`だけを入力に取り、database引数を受けず、early opaque `PostgresStorageRoot`を生成する。
rootは同じDataSourceから作る`ExposedDatabase`、stable scope connection factory、physical connection eviction controller、stable identityそのものをkeyにするprocess-local cancellable mutex registryだけを所有する。
active DB runtime configとclockの解決前にSafetyFloor、paper execution、max drawdown、ledger writer、decision / policy repository、backendを作ってはならない。

active config / clock解決後、root overloadのruntime factoryがrootのdatabaseとscope infrastructureからledger writer / repository、decision repository、policy decision repository、A2a backendをconfig-bound bundleとして同時生成する。
runtimeを同じrootと新しいresolved configでrebuildする場合はconfig-bound componentを新規作成し、root、DataSource、database、scope connection factory / evictor、process-local mutex registryを再作成しない。
opaque root / bundleのconstructorはfactory外から呼べず、個別componentの差し替えまたは別DataSource / databaseとの再結合を許さない。
stable request scopeのdedicated connectionも必ずroot所有の同じDataSourceから取得し、eviction controllerもそのDataSourceだけを操作する。
object identityの事後比較だけで任意pairを承認する案は、DataSource ownershipとscope connection / eviction先を証明できないため採用しない。

actual productionは`Application.createApplicationDatabaseResources`でDataSourceと`ExposedDatabase` pairを別々に保持し、`LlmDaemonSchedulerWorker.createLlmLaunchRuntimeComponents`のdaemon / manual pathからpublic pair-based `connectedPostgres`を呼ぶ。
A2bは`createApplicationDatabaseResources`でsingle-DataSource-only root factoryを呼び、`ApplicationDatabaseResources`がrootを保持する形へ移す。
`ApplicationDatabaseResources.dataSource / database`はrootのread-only getterとして既存readiness、bootstrap、routes、monitoring、maintenance serviceへ渡し、scope connection / eviction / mutex infrastructureやauthorized componentを個別に外へ公開しない。
active DB runtime configとclock解決後、daemon schedulerと`ManualLlmLaunchRuntime` / `createManualLlmLaunchService`はpairではなく同じrootを`createLlmLaunchRuntimeComponents`へ渡し、root overloadの`TradingRuntimeFactory.connectedPostgres`でconfig-bound runtimeを作る。

Applicationでrootを共有する各`TradingRuntime.close()`は自身のconfig-bound resourceだけを閉じ、rootを閉じない。
daemon schedulerは各loop generationが`TradingRuntime`を含むcloseable runtime bundleを一つ所有し、generation bodyを`try`、bundle closeを`finally`に置く。
config refreshまたは通常loop継続でgenerationを作り直す場合も、旧generationのbound runtimeをcloseし終えてから次generationを構築する。
worker shutdownはscheduler jobをcancelした後、`System.nanoTime()`を使うcode-owned 30秒monotonic termination deadlineまでjob終了をawaitする。
job終了はin-flight runのfinallyがgeneration bundle / bound runtimeをcloseし終えたことまでを含み、Applicationはdaemon worker terminationとmanual launch resource closeの両方が成功した後だけshared rootを一度closeする。
termination deadlineに到達した場合はtyped shutdown failureを返し、shared rootをcloseせず、in-flight runのcommit outcomeを未commit、`NO_TRADE`、または確定failureへ読み替えない。
このfail-closed timeoutではroot ownershipをApplicationに残し、worker terminationを後で再確認できる状態を保つ。
testはscheduler regenerationで旧bound close後に次generationが開始する順序、shutdown中のin-flight runがfinallyへ到達するまでroot closeが始まらないこと、termination timeout時にrootがopenでshutdown failureだけが返ることをbarrierで固定する。
standalone production `TradingRuntimeFactory.postgres(config)`は自分でrootを作り、そのruntime closeがconfig-bound resourceの後にowned rootを一度だけcloseする。
root closeはidempotentであり、shared runtime closeとApplication shutdownまたはstandalone failure cleanupが重なってもDataSourceを二重closeしない。

既存public `connectedPostgres(dataSource, database)`と任意pairを使うtest / embedding pathはruntime自体を構築できるがauthorized unsupportedのままとする。
production compositionのpair pathをroot overloadへ移しても、production runnerはA2bでinternal pathを呼ばずFalsifier / public broker behaviorを維持する。

InMemory factoryはledger、decision、policy decision repositoryが同じruntimeで作ったinstance identitiesであり、backendもそのledger / decision instanceを保持することを検証する。
affinity proofがない、または一つでもmismatchする場合はcapability全体をdurable authority readより前にtyped unsupportedにし、`Exact`を返せそうなledgerがあってもauthority / replayを開始しない。
したがってmismatchは`Exact` / `Missing`の双方で同じfail-closed resultになる。

public constructorでrepositoryのruntime typeだけを見てadapterを推測する案は、別database / 別InMemory repositoryのauthorityとledgerを混ぜ得るため採用しない。
public constructorへinternal capabilityやaffinity tokenを公開する案も採用しない。

### 2. A2a backendはstable request execution scopeとstrict replay readerを提供する

`AuthorizedAtomicPaperEntryBackend` はatomic commitに加え、stable request execution scopeと`AuthorizedAtomicEntryIdentity`だけを受け取るstrict replay readをmodule-internalに提供する。
InMemory adapterはledgerの既存classifierへ委譲し、PostgreSQL adapterはfresh `maxAttempts=1` transactionでA2aと同じclassifier / backend固有STOP identityを使う。
readは`Exact / Missing / Ambiguous`を値として返し、storage failureを`Result.failure`として返す。

A1専用readerとA2a backendを別々に解決する案は、PostgreSQLのstrict replay実装とbackend selectionを二重化するため採用しない。
public `PaperLedgerHistoryRepository.findPlaceOrderResultByClientRequestId`へのfallbackはstrict lifecycle shapeを失うため採用しない。

### 3. stable request scopeを全preflight / preparation / finalizationの最上位に置く

capability support / affinity、authority / fingerprint検証、stable identity構築の後、backend scopeを取得してからstrict preflightを開始する。
scopeはinitial replay、`Missing` preparation、A2a final replay / commit、non-mutation terminal readbackが完了するまで保持する。
PostgreSQLのserialization guaranteeはdedicated JDBC sessionとsession advisory lockが存続するnormal concurrencyに限定し、その範囲では同じstable request IDだけを直列化して異なるrequestを直列化しない。
mid-scope session loss、database failover、network partition後のsession再接続を越えるpreparation / audit完全直列化は保証しない。
losing typed-unavailable attemptとduplicate preparation / auditだけをinfrastructure residualとして報告できるが、confirmed ledger tradeのstrategy evaluation対象をscope lossだけで変更しない。
durable attribution / metric exclusion、新schema / durable fencing tokenはA2bに追加しない。
scope取得failureはticker、SafetyFloor、audit、fresh IDより前にtyped unavailableとして返す。

InMemoryはstable identity-keyed coroutine `Mutex`を使う。
PostgreSQLもroot-owned registryのstable identity-keyed coroutine `Mutex`を、dedicated connection borrowより前のprocess-local admissionとして使う。
PostgreSQLの同一rootからconfig-bound runtimeをrebuildしてもregistryを共有するため、同一processのsame-request waiterはgeneration / backend instanceを越えてconnectionをborrowしない。
両registryはwaiter / holderを参照countし、cancellationまたはrelease後に同じentryであることを確認して未使用entryを除去する。
PostgreSQLの取得順は`local identity mutex -> dedicated connection borrow -> two-int advisory lock`であり、local mutexはdedicated unlock / eviction完了後まで保持してouter `finally`で解放する。
異なるstable identityは別mutex entryを使い、hash32 collisionがない限りdedicated connectionとadvisory lockを並行取得できる。
PostgreSQL integration testは現行`maximumPoolSize=4`に対して4件以上のsame-request callをbarrierで交差させ、holder以外がconnectionをborrowせず、holderのstrict replay、preparation、A2a transaction用connectionが枯渇しないことを確認する。
別processのsame-request callは各processのlocal admission後にtwo-int advisory lockで直列化し、異なるrequestの並行性も同じtest群で維持する。

PostgreSQLはopaque root所有DataSourceから借りたdedicated physical connectionでstable client request IDから導出したsession advisory lockを取得する。
repo内のadvisory SQLをgrepした現在状態ではglobal trading lock、market session lock、decision lockはsingle-bigint APIを使い、two-int APIの使用はない。
stable request lockはPostgreSQLで別key spaceとなるtwo-int API `pg_try_advisory_lock(namespaceInt, requestHashInt)` / `pg_advisory_unlock(namespaceInt, requestHashInt)`だけを使い、blocking `pg_advisory_lock`を使わない。
`namespaceInt`はrepo予約値`1179994962`（`0x46554B52`）、`requestHashInt`はstable client request IDのUTF-8 SHA-256先頭4 byteをbig-endian signed Intとして固定する。
実装時にtwo-int usageを再grepし、同じnamespaceが導入されていた場合は本change内で一意な固定値へ設計artifactとcodeを同時更新する。
hash32 collisionはstable request同士を余分に直列化するだけで、authorityやmutationを共有しない。
既存single-bigint keyと同じ64-bit bit patternを強制作成してもPostgreSQL key familyが異なるため相互blockせず、global / market lock保持中のstable scope取得で自己deadlockしない。

scope acquisitionはcode-owned `30秒` timeoutと`50ms` poll intervalを使う。
30秒は既定`runner.perRunTimeout=180秒`の6分の1でOneShot hard deadline budget内に収まり、DB競合で1 run全体を使い切らない最小のbounded waitである。
値はA2bのKDoc / code定数とし、新runtime configまたは運用調整面を追加しない。
deadlineはprocess-local identity mutexを待ち始める前に`System.nanoTime()`から作り、monotonic elapsed timeだけで判定してwall clockを使わない。
local mutex wait、Hikari connection borrow、advisory pollは同じremaining acquisition budgetに従い、local wait中にdeadlineへ到達したcallはconnectionを一度もborrowせずpreparation前typed unavailableを返す。
connection borrow自体もremaining budgetを超えないdeadline-aware root connection factoryで行い、borrow timeout / cancellation後に遅れて返ったconnectionはpoolへ渡さずcleanupする。

borrow後は既存`PersistenceTransactionTimeouts`と同じbudget patternで、各`pg_try_advisory_lock`直前にremaining budgetを再計算する。
`Statement.queryTimeout`はremaining millisを秒へ切り上げ、`Connection.networkTimeout`はremaining millis以下に設定する。
original network timeoutをscope infrastructureが保持し、各try call後またはscope開始前のfinallyでrestoreする。
restoreがthrowまたは結果不明ならphysical connectionをevictし、取得済みlockがあればsession closeで解放する。
各pollでtry-lockがfalseならremaining deadline以下のcancellation-aware `delay(50ms)`を行い、30秒到達でpreparation前typed unavailableを返す。

suspend callerのcancellation時は現在実行中の`Statement.cancel()`をscope専用cleanup executorで試みる。
cancel resultにかかわらずtry responseはunknownとしてconnectionをevictし、network timeoutまたはconnection abortにより同期JDBC callもboundedに終了させる。
serverでlock取得後にresponseを失った場合もsession close / evictionがlockを解放するため、connectionをpoolへ戻さない。
InMemory / PostgreSQL identity mutex waitもcoroutine cancellationへ従い、`NonCancellable` waitまたはprocess-lifetime blockingを行わない。

dedicated connectionのacquisition outcomeを次に分類する。

- 全try callが`acquired=false`を返しnetwork timeout restoreにも成功したままmonotonic deadlineへ到達: lock非取得が確定しているためtyped unavailableを返し、connectionは通常close / pool returnしてよい
- borrow / cancellation、SQL failure、query timeout、response loss、timeout restore failureなどlock取得またはconnection stateが不明: root evictorでphysical connectionを非再利用にしてから元のcancellationまたはtyped unavailableを返す
- `acquired=true`: scopeを開始し、terminalのfinallyで同じtwo-int keyをunlockする

scope開始時に取得した`pg_backend_pid()`をsession identityとして保持する。
PostgreSQL pathは次の三点で、同じdedicated connectionへbounded heartbeatを行う。
各heartbeatは開始時に`System.nanoTime()`からcode-owned 5秒の独立monotonic deadlineを作り、scope acquisitionの30秒deadlineやそのremaining budgetを再利用しない。
そのためpreparationが30秒を超えてacquisition deadlineが過去になっていても、live session / lockは新しい5秒budgetで確認できる。
各queryにはheartbeatのremaining budget由来の`Statement.queryTimeout` / `Connection.networkTimeout`をarmし、実行中のcancellationではscope専用cleanup executorで`Statement.cancel()`を試みる。
SQL stall、cancel、response loss、またはoriginal network timeout restore failureはacquisition / unlockと同様にownership unknownとしてroot evictor / abortへ送る。
heartbeatは`pg_backend_pid()`が初期PIDと一致し、`pg_locks`にcurrent backendのexact two-int namespace / request hashを持つ`granted` advisory lockが存在することを同じqueryで確認する。
lock countを変えるreentrant try-lockやownership gapを作るunlock/relockは使わない。

1. SafetyFloor enforcement、violation / margin audit、HARD_HALT sweepなど最初のDB-visible preparation side effectの直前
2. A2a backend invocationの直前
3. SafetyFloor rejectionまたはpreparation failureなどnon-mutation terminalをreturnする直前

heartbeatのSQL failure、timeout、PID mismatch、response lossはscope ownership unknownである。
backend invocation前ならtyped unavailableを返しA2a mutationへ進まず、non-mutation terminal前なら元のrejection / failureを成功、`NO_TRADE`、未commitへ確定せずtyped unavailableを返す。
ただしSafetyFloor enforcement / HARD_HALT sweep自体がthrowした場合は既存carve-outどおり元failureをprimaryに保ち、heartbeat failureをsuppressed evidenceにする。
いずれもroot evictor / abortでsessionを非再利用にする。

A2a backendが`Exact` / `Created`のconfirmed resultを返した後にheartbeatまたはcleanupでscope lossを検出しても、paper ledgerのconfirmed truthを覆ってfailureへ変換しない。
resultを返しつつsessionをevictし、そのledger tradeを通常どおりstrategy evaluation対象に保つ。
confirmed commit後にprocess crashしても成功を消さず、durable metric exclusion / infrastructure attributionはBまたは別changeへstage-outする。
mid-preparation session lossにより別processがscopeを取得した場合、preparation、SafetyFloor evaluation、audit、HARD_HALT sweepの重複はinfrastructure residualとして許容する。
A2a backendのtransaction内strict replay / flat predicate / intent consumptionによりentry mutationとconsumptionは最大1に保ち、scope lossを成功、`NO_TRADE`、未commitの根拠に使わない。

heartbeatは観測であってfencingではないため、成功応答と別connection上のterminal readbackまたはA2a backend invocationの間にcheck-use gapが残る。
このgapでdedicated sessionを失うと、別processがadvisory lockを取得し、先行callのnon-mutation terminal readback / return後にcommitすることがある。
A2a transactionはmutation / consumption最大1を維持するが、A2bは先行のrejection / failureをdurable `NO_TRADE`または「commit不存在」の証明に変換しない。
このresidualのdurable status / terminal mappingとcaller reconciliationは後続Bのactivation contractへstage-outする。

testはproduction定数をruntime overrideせず、module-internalのnanoTime / delay / timeout seamで短いdeadlineを使う。
dedicated connectionはscope中にlock保持だけを担当し、market / SafetyFloor external I/OとA2a transactionは別connectionを使う。
stable scope lock以外のledger / market session lockをexternal I/O中に保持してはならない。

lock順は`stable request session advisory -> realtime market session advisory -> market_data_sessions row -> risk_state -> paper_account -> positions -> orders`とする。
stable request lockはledger / realtime session lockより最上位であり、後段から再取得しない。

scopeはnormal return、failure、cancellationの全経路で`finally`からunlockを試みる。
unlockはmain 30秒acquisition deadlineを再利用せず、code-owned 5秒の独立monotonic cleanup deadlineを使う。
cleanup executor上でremaining budget由来のquery / network timeoutをarmし、cancellationで中断せず同じtwo-int keyをunlockする。
unlockがfalse、stall、throw、response loss、network timeout restore failure、または5秒内に結果不明の場合、`Connection.abort(cleanupExecutor)`を試みた上でphysical connectionをpoolへ再利用せずclose / evictしてsession終了によりlockを解放する。
unlock成功とtimeout restore成功を両方確認した場合だけ通常close / pool returnする。
unlock failureを通常pool returnで済ませる案はcross-process lock leakでproductionを停止し得るため採用しない。

### 4. boundaryはauthority / fingerprint / scoped stable replayまでを所有する

authorized placeの順序を次に固定する。

1. capability supportとstorage affinity proofを確認し、不成立ならauthority read前にtyped unsupportedを返す
2. durable decision/eventとpermitの全identityを検証する
3. v2 fingerprintをcommandとpermitから再計算して検証する
4. commandからstable `AuthorizedAtomicEntryIdentity`を構築・検証する
5. stable request scopeを取得する
6. scope内でbackendのstrict replay readを実行する
7. `Exact`なら保存済み`PaperTradeResult`を返す
8. `Ambiguous`またはread failureならtyped fail-closedを返す
9. `Missing`の場合だけscopeを保持したままPaperBrokerのauthorized creation continuationを呼ぶ

これによりexact retryはticker、symbol rules、orderbook、SafetyFloor、cash read、fresh UUID、TTL、realtime sessionを参照しない。
現在のcommand validationをstrict replayより先に置く案は、既にcommit済みのexact retryを現在のdynamic input availabilityで失敗させるため採用しない。
authorityまたはfingerprintが不一致なら、既存resultがあってもreaderを呼ばないA1のpre-lookup境界を維持する。

### 5. `Missing` continuationは既存place preparationを一つのhelperとして再利用する

`PaperBrokerTradeDelegate` はpublic placeとauthorized creationが共有できるpreparation helperを持つ。
authorized continuationはpublic v2 namespace guardとpublic idempotency lookupを再実行せず、次を既存順序・既存実装で行う。

1. `PlaceOrderCommand` validation
2. ticker / symbol rules / account / position / intentを使う`PreparedPlaceOrder`構築とtrade group解決
3. `SafetyFloor` enforcementとmargin observation
4. symbol rule / entry price contract
5. MARKETまたはcrossing LIMITのfill simulationとcash validation
6. 非即時LIMIT / STOPのcash validation、TTL、realtime eligibility / queue metadata構築
7. fresh `TradeIntentConsumptionRequest`とMARKET / resting creation proposal構築

SafetyFloor rejectionは従来の`accepted=false` resultを返し、atomic backendを呼ばない。
HARD_HALT sweep、violation audit、margin observationも既存helperの順序を変えない。
MARKET / resting writeを既存`PaperBrokerEntryIntentConsumer`へ渡す案はflat predicateとintent consumptionをA2a atomic sectionから分離するため採用しない。
SafetyFloorやfill simulationをA2a storage primitiveへ移す案はexternal I/Oをtransactionへ持ち込み、既存lock orderを崩すため採用しない。

entry mutationを行わずterminalになるSafetyFloor rejection、validation / market / symbol / price / cash / eligibility failureでは、stable scopeを保持したままfresh strict replayを一度だけ行う。
fresh replayが`Exact`ならpersisted resultを優先し、`Missing`なら元のterminal result / failureを返し、`Ambiguous`またはread failureならresult不存在を断定せずtyped indeterminateを返す。
これはA2a外のwriter、process crash recovery、将来のcall path誤接続に対するdefense-in-depthである。

SafetyFloor enforcementまたはHARD_HALT sweep自体がthrowした場合は例外の意味とrisk-reduction failureを失わないため、その元failureを常にprimaryとする。
fresh readbackは元failureを成功へ変えず、readbackの`Ambiguous` / failureは元failureへsuppressed evidenceとして追加する。
SafetyFloor failureをgeneric replay-indeterminateで覆う案は採用しない。

### 6. atomic backendは二段replayの間にproposalを必要最小限しか参照しない

live stable request scope中の同じA2b call同士は直列化されるが、session loss residualに依存しないようA2a backendは独立したstorage safety boundaryとして二段replayを維持する。
backend initial replayはstable identity以外を一切参照せず、requestにinvalid / stale proposalが同居していても`Exact`を返せる。
initial `Missing`後はMARKET / resting subtypeとrealtime session lock hintだけを読み、ledger / session lockを取得する。
full `requireCreationProposalValid`、fresh entity ID検査、resolved group、fill / TTL / eligibility validation、command business identity比較はlock取得後のsecond replayも`Missing`の場合だけ行う。

- initialまたはsecond replay `Exact`: persisted resultを返す。fresh command / order / position / STOP / execution ID、resolved trade group、MARKET / resting subtype、fill / TTL / eligibilityを検証・比較しない
- backend `Created`: backendがatomic commitしたresultを返す
- backend `Ambiguous`相当、intent missing / consumed、non-flat、write policy rejection、unavailable:元のtyped failureを返す
- `AuthorizedAtomicEntryOutcomeIndeterminateException`: commit成功・失敗のどちらも断定せず、そのまま返す

A2b側でcommitをretryする案、`OutcomeIndeterminate`を`NO_TRADE`または通常failureへ畳む案、fresh proposalとpersisted resultのID / subtype一致を要求する案は採用しない。

### 7. public pathとproduction runnerは接続しない

working pathは`PaperBroker.previewAuthorizedOrder` / `placeAuthorizedOrder`のmodule-internal surfaceだけに置く。
public `Broker.placeOrder` / `previewOrder`はreserved v2 prefixをpre-lookupで拒否し、close / update protection / cancelのrisk-reducing availabilityは維持する。
public `PlaceOrderCommand`とMCP wire DTOへpermitまたはbackend selectorを追加しない。

production `OneShotLlmRunner`はA2bでinternal surfaceを参照せず、`OFF_V1` permitがあってもFalsifierとfresh approvalを含む従来pathを使う。
Bがruntime activation、active config snapshot、permit propagation、Falsifier skip、status / outcome mappingを同じchangeで設計・実装する。

## Risks / Trade-offs

- [active DB config解決前にenv/default configでbackendを固定する] → early rootはstorage infrastructureだけを持ち、resolved config / clockごとにbound componentを構築する
- [任意DataSource / database pairや別poolのscope connectionを混ぜる] → public / pair-based factoryをunsupportedにし、single owned DataSourceのopaque rootからbound componentを生成する
- [Application daemon / manualがpair pathを使いunsupportedになる] → `ApplicationDatabaseResources`がrootを保持しscheduler / manual inputsをroot overloadへ移す
- [shared runtime closeがrootを早期closeする] → scheduler generationはfinallyでbound runtimeをcloseし、worker termination確認後だけApplicationがrootを一度closeする
- [shutdown timeout中にroot closeがin-flight commitを切断する] → worker cancel後を独立30秒monotonic deadlineでawaitし、timeoutはtyped shutdown failure + root非closeとしてcommit outcomeを読み替えない
- [production composition移行でrunner behaviorが変わる] → runtime component identityだけを変え、runnerは引き続きFalsifier / public broker pathを使う回帰testを置く
- [pool size以上のsame-request waiterがdedicated connectionを先取りする] → root-owned local identity mutexをborrow前に取得し、holder / waiter refcount cleanupでwaiterをpool外に置く
- [normal concurrencyで同じrequestがpreparationを並行する] → local identity mutexとlive stable session scopeをpreflightからterminal readbackまで保持する
- [mid-scope session loss後に別processがpreparation / auditを重複する] → 3点heartbeatで窓を狭め、重複をinfrastructure residualとして報告し、A2a atomic backendでmutation / consumptionを最大1にする
- [scope lossをNO_TRADEまたは未commitと誤認する] → pre-mutationはtyped unavailable、confirmed backend result後はpaper truth resultと通常のstrategy evaluationを維持する
- [blocking advisory lockがrunner deadlineを使い切る] → two-int try-lockをmonotonic 30秒deadline / 50ms cancellation-aware pollで取得する
- [borrow / try-lock response lossで未把握lockをpoolへ返す] → remaining query / network budgetとStatement.cancelを使い、cancel / SQL failure / outcome不明はroot evictorでphysical connectionを非再利用にする
- [unlockがmain deadline消費後にstallする] → 独立5秒cleanup deadlineとabort / evictでsession closeをboundedにする
- [preparationがacquisition deadlineを超えた後にheartbeatを即timeoutする] → heartbeat開始ごとに独立5秒monotonic deadlineを作り、remaining query / network timeoutとcancel / evictを適用する
- [heartbeat成功後のcheck-use gapでsessionを失う] → A2a atomicityでmutation最大1を維持し、non-mutation terminalをdurable `NO_TRADE`へ変換せずBのterminal mappingへstage-outする
- [PostgreSQL advisory lockがconnection poolへ漏れる] → cancellationを含むfinallyでunlockし、unlock失敗 / 不明ならphysical connectionをclose / evictする
- [advisory key hashがcollisionする] → fixed two-int namespace内のstable request同士を余分に直列化するだけで、各callのauthority / fingerprint / replay / mutation検証は共有しない
- [global / market single-bigint keyと数値collisionして自己deadlockする] → stable scopeはreserved two-int key familyだけを使い、forced collision integration testで非干渉を固定する
- [external I/O中にledger lockを保持してdeadlockする] → stable request lockだけを保持し、market session / ledger lockはA2a final sectionまで取得しない
- [exact retry時にmarket dataまたはSafetyFloorが利用不能] → stable replayを全dynamic preparationより先に置く
- [non-mutation terminalと既存commitが競合する] → scope内fresh readbackでExactを優先し、Ambiguous / unavailableをindeterminateにする
- [SafetyFloor sweep failureをreadbackが覆う] → SafetyFloor failureをprimaryに保ちreadback failureはsuppressed evidenceにする
- [second replay Exactがstale proposalで拒否される] → subtype / session lock hint以外のproposal validationをsecond Missing後へ遅延する
- [preparation後にHARD_HALTまたはaccount stateが変化する] → A2aのtransaction内write policyとflat predicateを最終mutation gateとして維持する
- [SafetyFloor rejection後にintentを消費する] → rejected resultはproposal / backend commit前に返す
- [commit outcome不明を未commitと誤認する] → A2aの一回限りreadback結果を尊重し、A2bはoutcome failureを変換・retryしない
- [internal pathが早期にproductionへ有効化される] → runner wiring / runtime keyを追加せず、OFFでもFalsifierが動く回帰testを維持する
- [A2bが1,000行目安を超える] → backend scope / affinity / replay contractを先行PR、broker preparation connectionを後続stack PRに分割し、Bを混ぜない

## Migration Plan

1. single owned DataSourceからopaque PostgreSQL storage root、root-owned process-local admission、config-bound runtime bundle、JDBC-bounded two-int try-lock scope、strict replay read、proposal validation順序を追加する。
2. `ApplicationDatabaseResources`、generation-owned daemon scheduler、manual launch、standalone `postgres(config)`をroot overloadへ移し、bounded worker termination後だけshared rootをcloseし、public pair-based factoryはunsupportedに保つ。
3. authorized boundaryのscoped `Missing` continuation、non-mutation terminal readback、既存preparationからA2a proposalへの変換を追加する。
4. InMemory / PostgreSQL focused testでaffinity mismatch、scope cleanup、exact-first、false rejection barrier、SafetyFloor、typed failureを確認する。
5. internal pathが接続済みでもpublic/MCP/runnerから到達不能である状態をdeployする。
6. 後続Bでruntime activationとrunner outcome contractを別PRとして追加する。

rollbackはA2b codeだけを戻す。
A2bはschema/data migrationを行わず、rollback時にdurable policy decision、intent、paper ledgerを削除・書換えしない。

## Open Questions

なし。
