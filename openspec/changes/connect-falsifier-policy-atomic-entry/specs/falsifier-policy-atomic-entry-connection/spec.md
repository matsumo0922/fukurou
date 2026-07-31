## ADDED Requirements

### Requirement: authorized capabilityはinternal runtime factoryだけがstorage affinityを証明する

public `PaperBroker` constructorはauthorized capability unsupportedでなければならない（MUST）。
任意のDataSource / `ExposedDatabase` pairを受け取るpublic `connectedPostgres` factoryもauthorized capability unsupportedでなければならない（MUST）。
database引数を受けないproduction-safe factoryはPostgreSQLで単一のowned DataSourceからearly opaque `PostgresStorageRoot`として`ExposedDatabase`、stable scope connection factory、connection eviction controller、stable-identity keyed process-local cancellable mutex registryだけを生成しなければならない（MUST）。
active DB runtime configとclockの解決後、root overloadはrootからledger / decision / policy decision repositoryとbackendをconfig-bound componentとして同時生成しなければならない（MUST）。
early rootはconfig-bound repository / backendを生成してはならず（MUST NOT）、same rootからruntimeをrebuildするときは新しいresolved config / clockでbound componentだけを再生成し、root-owned mutex registryを維持しなければならない（MUST）。
PostgreSQL stable scopeのdedicated connectionとeviction controllerはroot所有の同じDataSourceだけを使わなければならない（MUST）。
internal `TradingRuntime` compositionはこのroot-bound component setまたは同じruntime InMemory repository instancesを共有する場合だけcapabilityを注入しなければならない（MUST）。
affinity mismatchはdurable authority readとstrict replayより前にtyped unsupportedとし、public lookupへfallbackしてはならない（MUST NOT）。

#### Scenario: public constructorとExact ledger

- **WHEN** public constructorで作った`PaperBroker`のledgerにstable identityと一致する`Exact` lifecycleが存在する
- **THEN** authorized placeはreplayを行わずtyped unsupportedを返す

#### Scenario: PostgreSQL database mismatch

- **WHEN** ledgerとdecisionまたはpolicy decision repository、backend、scope connection / eviction controllerのいずれかがopaque root外のdatabaseまたはDataSource由来である
- **THEN** internal factoryはcapabilityを注入せず、persisted replayが`Exact` / `Missing`のどちらでもtyped unsupportedを返す

#### Scenario: public connectedPostgres pair

- **WHEN** callerが接続済みDataSourceと`ExposedDatabase`をpublic `connectedPostgres`へ渡す
- **THEN** runtimeは構築できるがauthorized capabilityはunsupportedであり、pairのobject identity一致だけでは有効化しない

#### Scenario: production fromEnvironment

- **WHEN** production `fromEnvironment -> postgres(config)`が一つのowned DataSourceを生成する
- **THEN** root factoryとresolved config-bound compositionは同じrootからinactive authorized capabilityを注入する
- **AND** runnerのFalsifier / public broker wiringは変更されない

#### Scenario: Application database resources

- **WHEN** `Application.createApplicationDatabaseResources`がconfigured DataSourceを生成する
- **THEN** `ApplicationDatabaseResources`はsingle-DataSource-only factoryのopaque rootを保持し、read-only DataSource / database getterを既存serviceへ渡す
- **AND** active DB runtime config解決前にconfig-bound repository / backendを作らない

#### Scenario: daemon scheduler runtime

- **WHEN** `LlmDaemonSchedulerWorker.createLlmLaunchRuntimeComponents`がproduction daemon runtimeを構築する
- **THEN** componentはDataSource / database pair overloadではなく`ApplicationDatabaseResources`由来rootとresolved config / clockを使う
- **AND** runnerは従来どおりFalsifierとpublic broker pathを使う

#### Scenario: manual launch runtime

- **WHEN** `createManualLlmLaunchService`がmanual runtimeを構築する
- **THEN** `ManualLlmLaunchRuntime`は同じApplication rootとresolved config / clockを渡しpair overloadを使わない
- **AND** manual runnerのFalsifierとpublic broker behaviorは変わらない

#### Scenario: scope DataSource mismatch

- **WHEN** backend repositoryと同じdatabaseを使うがstable scope dedicated connectionまたはeviction controllerが別DataSourceを使う
- **THEN** capabilityはauthority / replay前にtyped unsupportedとなる

#### Scenario: active DB configはenvironmentと異なる

- **WHEN** Application root生成後にactive DB runtime configがenvironment defaultと異なる値へ解決される
- **THEN** ledger、SafetyFloor関連writer、backendはactive DB configとresolved clockから構成されenvironment defaultで早期固定されない

#### Scenario: same root runtime rebuild

- **WHEN** 同じApplication rootでactive configを更新してruntime componentをrebuildする
- **THEN** 新config-bound repository / backendを作りroot、DataSource、database、scope connection factory / evictor、process-local mutex registryを再生成またはcloseしない

#### Scenario: InMemory repository mismatch

- **WHEN** ledger、decision、policy decision、backendのいずれかが同じruntimeで作ったinstanceと異なる
- **THEN** internal factoryはcapabilityを注入せず、persisted replayが`Exact` / `Missing`のどちらでもtyped unsupportedを返す

#### Scenario: affinity一致

- **WHEN** internal factoryが全repositoryとbackendのsame storage identityを証明する
- **THEN** authorized boundaryはそのcapabilityだけをauthority、scope、replay、commitに使用する

### Requirement: Application scheduler generationはbound runtime終了後だけshared rootを解放する

daemon schedulerの各loop generationは`TradingRuntime`を含むcloseable runtime bundleを所有し、normal completion、config regeneration、failure、cancellationの全経路でgeneration `finally`からbound runtimeを一度closeしなければならない（MUST）。
worker shutdownはscheduler jobをcancelした後、`System.nanoTime`によるcode-owned 30秒monotonic deadlineまでjob terminationをawaitしなければならない（MUST）。
job terminationはin-flight runのgeneration `finally`とbound runtime closeの完了を含まなければならない（MUST）。
Applicationはdaemon worker terminationとmanual launch resource closeが成功した後だけshared rootをcloseしなければならない（MUST）。
termination timeout時はtyped shutdown failureを返し、shared rootをcloseしてはならず（MUST NOT）、in-flight commit outcomeを未commit、`NO_TRADE`、または確定failureへ変換してはならない（MUST NOT）。
shared `TradingRuntime.close()`はbound resourceだけをcloseし、Application rootをcloseしてはならない（MUST NOT）。

#### Scenario: scheduler generation regeneration

- **WHEN** daemon loopがresolved config更新または次iterationのためruntime generationを作り直す
- **THEN** 旧generationのfinallyが所有するbound `TradingRuntime`を一度closeし終えてから次generationを構築する
- **AND** shared rootはgeneration間で維持される

#### Scenario: Application shutdown ownership

- **WHEN** shutdownがdaemon workerをcancelしmanual launch resourceをcloseする
- **THEN** worker job terminationと各bound runtime closeを確認した後だけApplicationがshared rootを一度closeする

#### Scenario: shutdown during in-flight run

- **WHEN** Application shutdownがA2a commitを含み得るin-flight daemon run中に開始する
- **THEN** workerはcancel後もgeneration finally / bound runtime closeまでjob terminationをawaitし、その完了前にrootをcloseしない

#### Scenario: worker termination timeout

- **WHEN** scheduler jobがcancel後30秒のmonotonic deadline内にterminationしない
- **THEN** Application shutdownはtyped failureを返しshared rootをopenのまま所有する
- **AND** in-flight runを`NO_TRADE`、未commit、または確定failureとして報告しない

#### Scenario: standalone shutdown ownership

- **WHEN** standalone `postgres(config)` runtimeをcloseする
- **THEN** runtimeはbound resourceの後にowned rootを一度だけcloseする

### Requirement: authorized replayはfresh preparationより先に判定する

systemはstorage affinityを確認し、durable authorityとv2 fingerprintを検証した後、stable request scope内でstable command identityだけを使うstrict replayをticker、symbol rules、orderbook、SafetyFloor、cash、fresh ID、TTL、realtime eligibilityより先に実行しなければならない（MUST）。
authorityまたはfingerprintが不一致の場合はreplayとmutationを行ってはならない（MUST NOT）。

#### Scenario: exact retryとmarket data failure

- **WHEN** authority、fingerprint、stable identityに一致するpersisted lifecycleが`Exact`でありmarket data sourceが利用不能である
- **THEN** systemはmarket dataとfresh proposalを参照せずpersisted resultを返す

#### Scenario: replay Missing

- **WHEN** authorityとfingerprintが一致しstrict replayが`Missing`を返す
- **THEN** systemは初めてauthorized creation preparationへ進む

#### Scenario: replay Ambiguous

- **WHEN** strict replayが`Ambiguous`を返す
- **THEN** systemはcommand preparation、SafetyFloor、atomic mutation、intent consumptionを行わずtyped indeterminate failureを返す

#### Scenario: replay unavailable

- **WHEN** strict replay storage readが失敗する
- **THEN** systemはresult不存在を断定せずmutationへ進まない

### Requirement: stable request scopeはlive ownership中のnormal concurrencyを直列化する

backendはlive scope ownershipが存続するnormal concurrencyで、同じstable client request IDをinitial replay前からterminal result / failure後まで直列化するexecution scopeを提供しなければならない（MUST）。
scope取得failureはpreparation前のtyped unavailableでなければならない（MUST）。
異なるstable request IDは同じscope keyであることだけを理由にauthorityまたはresultを共有してはならない（MUST NOT）。
PostgreSQL scopeはfixed namespace `1179994962`とstable request hash32を使うtwo-int `pg_try_advisory_lock`だけを使用し、blocking `pg_advisory_lock`を使わず、既存global / market sessionのsingle-bigint key familyと分離しなければならない（MUST）。
PostgreSQL scopeはroot-owned stable-identity keyed cancellable mutexをdedicated connection borrow前に取得し、`local identity mutex -> dedicated borrow -> two-int advisory lock`の順を守らなければならない（MUST）。
process-local mutexはsame rootのconfig generation / backend instance間で共有し、holder / waiter refcountをcancellationとreleaseの両方でcleanupし、同じentryに参照がなくなった場合だけregistryから除去しなければならない（MUST）。
acquisitionはprocess-local mutex wait前に`System.nanoTime`でcode-owned 30秒deadlineを開始し、local wait、borrow、各try callをremaining budgetで拘束し、false応答後は最大50msのcancellation-aware pollを行い、timeoutをpreparation前typed unavailableにしなければならない（MUST）。
same-request local waiterはmutex取得前にdedicated connectionをborrowしてはならず（MUST NOT）、異なるstable identityはhash32 collisionまたは別のDB lock競合がない限り並行できなければならない（MUST）。
各try callはremaining budgetから`Statement.queryTimeout`と`Connection.networkTimeout`をarmし、original timeout restoreまで確認しなければならない（MUST）。
InMemory / PostgreSQL mutex acquisitionはcoroutine cancellationへ従わなければならない（MUST）。
PostgreSQL session loss / failoverを越えるcross-process preparation / audit完全直列化を保証してはならず（MUST NOT）、新schemaまたはdurable fencing tokenを追加してはならない（MUST NOT）。

#### Scenario: InMemory same request

- **WHEN** 同じstable requestの2 callがInMemoryで並行する
- **THEN** identity-keyed coroutine mutexにより後続callは先行callのterminalまでpreflightを開始しない

#### Scenario: PostgreSQL cross-process same request

- **WHEN** 同じstable requestの2 callが別processからPostgreSQLへ並行する
- **THEN** 各processのlocal admission後にtwo-int advisory lockがcross-process barrierとなり、dedicated sessionとlockが存続する間、後続callは先行callのterminalまでpreflightを開始しない

#### Scenario: PostgreSQL same-process pool admission

- **WHEN** maximum pool sizeが現行値4で、4件以上のsame-request callを同一root内のbarrierで交差させる
- **THEN** holderだけがdedicated connectionをborrowし、全local waiterはconnectionをborrowせずidentity mutexで待つ
- **AND** holderのstrict replay、preparation、A2a transactionが使う別connectionをwaiterが枯渇させない

#### Scenario: PostgreSQL different request concurrency

- **WHEN** 異なるstable identityのcallを同一rootから並行開始する
- **THEN** callは異なるlocal mutex entryを使い、same-request local admissionだけを理由に相互待機しない

#### Scenario: PostgreSQL holder timeout

- **WHEN** 別connectionがsame-request two-int lockをcode-owned 30秒deadlineまで保持し全try callが`false`を返す
- **THEN** waiterはwall clockに依存せずpreparation前typed unavailableを返す
- **AND** lock非取得とnetwork timeout restoreが確定したdedicated connectionは通常close / pool returnしてよい

#### Scenario: Hikari borrow stall

- **WHEN** dedicated connection borrowが30秒acquisition deadlineのremaining budgetを使い切る
- **THEN** systemはpreparation前typed unavailableを返し、遅れてborrowされたconnectionをpoolへ戻さずcleanupする

#### Scenario: try-lock stall

- **WHEN** `pg_try_advisory_lock`の同期JDBC callがstallする
- **THEN** remaining budget由来のquery / network timeoutで30秒deadline内に終了し、outcome不明としてconnectionをevictする

#### Scenario: PostgreSQL cancellation while polling

- **WHEN** waiterがtry-lockのfalse応答後のpoll delay中またはSQL outcome不明時にcancelされる
- **THEN** cleanup executorでrunning `Statement.cancel`を試み、pollを停止し、outcome不明なphysical connectionをroot evictorで非再利用にしてcancellationを伝播する

#### Scenario: try response loss after acquisition

- **WHEN** serverがtwo-int lockを取得した後にclientがtry responseを失う
- **THEN** systemは取得結果をunknownとしてphysical connectionをevict / closeしsession終了でlockを解放する

#### Scenario: process-local cancellation while waiting

- **WHEN** InMemoryまたはPostgreSQLのsame-request mutexを待つcallがrunnerからcancelされる
- **THEN** waiterはscopeまたはdedicated connectionを取得せずcancellationを伝播し、holder / waiter refcountと未使用entryをcleanupしてprocess-lifetime blockを残さない

#### Scenario: scope acquisition failure

- **WHEN** stable request mutexまたはPostgreSQL advisory lockを取得できない
- **THEN** systemはticker、SafetyFloor、audit、fresh proposalを参照せずtyped unavailableを返す

#### Scenario: advisory hash collision

- **WHEN** 異なるstable request IDが同じPostgreSQL advisory keyへcollisionする
- **THEN** callは余分に直列化されるが各自のauthority、fingerprint、strict replay、mutation検証を別々に実行する

#### Scenario: global single-bigint forced collision

- **WHEN** global trading lockがtwo-int namespace / hashと同じ64-bit bit patternのsingle-bigint advisory keyを保持する
- **THEN** stable requestのtwo-int lockは相互blockせず同じcall chainで自己deadlockしない

#### Scenario: market session single-bigint forced collision

- **WHEN** market session lockがtwo-int namespace / hashと同じ64-bit bit patternのsingle-bigint advisory keyを保持する
- **THEN** stable requestのtwo-int lockは相互blockせず所定lock順で取得できる

### Requirement: PostgreSQL stable request lockはdedicated connectionで確実に解放する

PostgreSQL stable request scopeはopaque storage root所有DataSourceのdedicated physical connectionで`pg_try_advisory_lock(namespaceInt, requestHashInt)`をbounded pollし、external market / SafetyFloor I/O中は取得済みstable lock以外のmarket session / ledger lockを保持してはならない（MUST NOT）。
取得順はprocess-local identity mutex、dedicated connection borrow、stable request advisory、realtime market session advisory、market session row、ledger locksでなければならない（MUST）。
normal return、failure、cancellationの全経路でmain acquisition deadlineと独立したcode-owned 5秒monotonic cleanup deadlineを使い、remaining budget由来のquery / network timeoutをarmして同じtwo-int keyのunlockをfinallyから試みなければならない（MUST）。
unlockがfalse、stall、throw、response loss、timeout restore failure、または結果不明の場合は`Connection.abort`を試み、root所有eviction controllerでphysical connectionをpoolへ再利用せずclose / evictしなければならない（MUST）。
process-local identity mutexはdedicated unlockまたはeviction完了後にouter `finally`で解放しなければならない（MUST）。
acquisition中のSQL failure、query timeout、cancellation、response lossで取得結果が不明の場合も同じevictionを行わなければならない（MUST）。

#### Scenario: cancellation

- **WHEN** initial replay、preparation、backend commitのいずれかでcallがcancelされる
- **THEN** cleanupはcancellationで中断されずsession advisory unlockを試みる

#### Scenario: unlock failure

- **WHEN** `pg_advisory_unlock`がfalseを返す、throwする、または結果を確認できない
- **THEN** systemはdedicated physical connectionをpoolへ戻さずclose / evictしてsession lockを解放する

#### Scenario: unlock stall

- **WHEN** main acquisition deadlineの残りがなく、`pg_advisory_unlock`の同期JDBC callがstallする
- **THEN** systemは独立5秒cleanup deadlineのquery / network timeoutでcallをboundし、結果不明ならabort / evictする

#### Scenario: network timeout restore failure

- **WHEN** try-lockまたはunlock後にoriginal `Connection.networkTimeout`をrestoreできない
- **THEN** systemはconnection stateをunknownとして通常pool returnせずevictする

#### Scenario: try-lock SQL failure

- **WHEN** `pg_try_advisory_lock`がSQL failure、query timeout、またはresponse lossで取得結果を確定できない
- **THEN** systemはphysical connectionをpoolへ戻さずroot evictorで非再利用にしpreparationへ進まない

#### Scenario: external I/O

- **WHEN** same-request scope内でticker、orderbook、SafetyFloorを実行する
- **THEN** systemはstable request advisory lockだけを保持し、realtime session / ledger lockをまだ取得しない

#### Scenario: lock order

- **WHEN** eligibility付きresting entryをcommitする
- **THEN** 取得順はlocal identity mutex、dedicated borrow、stable request advisory、market session advisory、market session row、risk state、account、positions、ordersとなる

### Requirement: PostgreSQL scope ownershipはmutationとterminalの境界で再確認する

PostgreSQL pathはscope acquisition時のbackend session identityを保持し、SafetyFloor / DB-visible preparation side effectの直前、A2a backend invocation直前、non-mutation terminal return直前に同じdedicated sessionのbounded heartbeatを行わなければならない（MUST）。
各heartbeatは開始時に`System.nanoTime`からcode-owned 5秒の独立monotonic deadlineを作り、scope acquisitionの30秒deadlineまたはそのremaining budgetを再利用してはならない（MUST NOT）。
heartbeat queryは独立5秒deadlineのremaining budget由来の`Statement.queryTimeout` / `Connection.networkTimeout`をarmし、cancellation時はcleanup executorでrunning `Statement.cancel`を試み、original timeout restoreまで確認しなければならない（MUST）。
heartbeatは`pg_backend_pid()`が初期値と一致し、`pg_locks`にcurrent backendのexact two-int namespace / request hashを持つgranted advisory lockが存在することを確認しなければならない（MUST）。
loss、PID mismatch、SQL failure、timeout、stall、cancel、response loss、timeout restore failureをownership unknownとしてtyped unavailableへ変換し、acquisition / unlockと同様にroot evictor / abortを行わなければならない（MUST）。
scope lossを成功、`NO_TRADE`、未commitへ変換してはならない（MUST NOT）。
A2bはscope lossだけを理由にconfirmed `Exact` / `Created` ledger tradeをstrategy evaluation対象から除外または変更してはならない（MUST NOT）。
losing typed-unavailable attemptとduplicate preparation / auditはinfrastructure residualとして報告できるが、durable infrastructure attribution / metric exclusionはA2bで要求しない。
heartbeat成功は別connection上のterminal readbackまたはbackend invocationまでsession ownershipをfenceせず、check-use gapにより後続processがcommitし得ることをA2bのresidualとして扱わなければならない（MUST）。
A2bはこのgap後のnon-mutation terminalをdurable `NO_TRADE`またはcommit不存在の証明へ変換してはならず（MUST NOT）、durable status / terminal mappingとcaller reconciliationをBへstage-outしなければならない（MUST）。

#### Scenario: heartbeat after slow preparation

- **WHEN** live dedicated sessionとgranted lockを維持したpreparationがscope acquisition開始から30秒を超えた後にheartbeatを開始する
- **THEN** systemは新しい独立5秒deadlineでownershipを確認し、expired acquisition deadlineを理由に失敗しない

#### Scenario: loss before SafetyFloor side effect

- **WHEN** initial replay後かつSafetyFloor enforcement / audit / HARD_HALT sweep前のheartbeatでsession lossを検出する
- **THEN** systemはside effectとA2a mutationへ進まずtyped unavailableを返しsessionをevictする

#### Scenario: loss before backend invocation

- **WHEN** preparation完了後かつA2a backend invocation直前のheartbeatでsession lossまたはownership unknownを検出する
- **THEN** systemはbackendを呼ばずtyped unavailableを返し、未commitまたは`NO_TRADE`を断定しない

#### Scenario: loss before non-mutation terminal

- **WHEN** SafetyFloor rejectionまたはpreparation failureを返す直前のheartbeatでsession lossを検出する
- **THEN** systemは元terminalを確定せずtyped unavailableを返す

#### Scenario: SafetyFloor failure carve-out

- **WHEN** SafetyFloor enforcement / HARD_HALT sweepがthrowしterminal heartbeatも失敗する
- **THEN** systemはSafetyFloor側failureをprimaryに保ちheartbeat failureをsuppressed evidenceとしてsessionをevictする

#### Scenario: scope loss after confirmed backend result

- **WHEN** A2a backendが`Exact`または`Created`のconfirmed resultを返した後にscope lossまたはcleanup failureを検出する
- **THEN** systemはconfirmed paper truth resultをfailureへ変換せず返しsessionをevictする
- **AND** confirmed ledger tradeを通常どおりstrategy evaluation対象に保つ

#### Scenario: process crash after confirmed commit

- **WHEN** confirmed commit後かつcallerへのresponse完了前またはcleanup中にprocessがcrashする
- **THEN** durable ledger tradeは成功として残り、scope lossだけを理由に通常のstrategy evaluation対象から除外されない

#### Scenario: forced session termination mid-preparation

- **WHEN** PostgreSQL backend sessionをinitial `Missing`後のpreparation中に強制終了し別processが同じrequestを開始する
- **THEN** duplicate preparation / auditはinfrastructure failure residualとして記録できる
- **AND** A2a strict replay / transactionによりentry mutationとintent consumptionは各最大1件である
- **AND** scope lossをstrategy success、`NO_TRADE`、未commitへ変換しない

#### Scenario: heartbeat check-use gap before terminal readback

- **WHEN** non-mutation terminal直前のheartbeatが成功した後、別connection上のfresh readbackまたはreturnまでにdedicated sessionを失い、別processが同じrequestをcommitする
- **THEN** 先行callのterminalは後続commit不存在を証明せず、A2bはdurable `NO_TRADE`へ変換しない
- **AND** A2a backendはentry mutation / intent consumptionを各最大1に保ち、durable terminal reconciliationはBが扱う

#### Scenario: heartbeat check-use gap before backend invocation

- **WHEN** backend invocation直前のheartbeat成功後かつ別connection上のA2a transaction開始前にdedicated sessionを失い、別processも同じrequestを開始する
- **THEN** A2a strict replay / transactionが`Exact`または単一`Created`へ収束し、A2bはsession heartbeatだけからcommit outcomeを推定しない

### Requirement: Missing continuationは既存broker preparationとSafetyFloorを維持する

strict replayが`Missing`の場合、systemは既存public place pathと同じcommand validation、market preparation、SafetyFloor enforcement / observation、symbol rule、entry price、cash、fill simulation、TTL、realtime eligibilityのsemanticsを使わなければならない（MUST）。
SafetyFloorを通過したMARKET / crossing LIMITは既存paper fill request、非即時LIMIT / STOPは既存resting requestからA2a creation proposalを作らなければならない（MUST）。

#### Scenario: SafetyFloor rejection

- **WHEN** `Missing`後の既存SafetyFloorがentryを拒否する
- **THEN** systemは既存のrejected `PaperTradeResult`とaudit / HARD_HALT semanticsを維持し、A2a commitとintent consumptionを行わない

#### Scenario: MARKET preparation

- **WHEN** validなMARKETまたはcrossing LIMITが既存SafetyFloorとcash contractを通過する
- **THEN** systemは既存simulated fill、position、protective STOP、execution、consumption requestを含むMARKET proposalをA2aへ渡す

#### Scenario: resting preparation

- **WHEN** validなnon-crossing LIMITまたはSTOPが既存SafetyFloorとcash contractを通過する
- **THEN** systemは既存TTLと必要なrealtime eligibility / queue metadataを含むresting proposalをA2aへ渡す

#### Scenario: preparation failure

- **WHEN** validation、market preparation、symbol / price / cash contract、またはrealtime eligibilityが失敗する
- **THEN** systemはA2a commitとintent consumptionを行わずfresh strict replayで`Missing`を確認した場合だけ元のfailureを返す

### Requirement: non-mutation terminalはscope内fresh replayで確定する

initial replayが`Missing`でentry mutationを行わずterminal result / failureへ進む場合、systemはstable request scopeを保持したままownership heartbeatを確認し、fresh strict replayを一度だけ行わなければならない（MUST）。
fresh replayが`Exact`ならpersisted resultを優先し、`Missing`なら元のterminalを返し、`Ambiguous`またはread failureならtyped indeterminateを返さなければならない（MUST）。
ただしSafetyFloor enforcementまたはHARD_HALT sweepがthrowした場合は元failureをprimaryとして維持しなければならない（MUST）。

#### Scenario: false rejection prevention

- **WHEN** initial `Missing`後にnon-mutation rejectionへ進むがfresh replayが`Exact`を返す
- **THEN** systemはrejectionではなくpersisted resultを返す

#### Scenario: terminal Missing

- **WHEN** validation failureまたはSafetyFloor rejected resultの直前のfresh replayも`Missing`である
- **THEN** systemは元のfailureまたはrejected resultを返す

#### Scenario: terminal replay indeterminate

- **WHEN** non-mutation terminal直前のfresh replayが`Ambiguous`またはread failureである
- **THEN** systemは元のterminalを確定せずtyped indeterminateを返す

#### Scenario: SafetyFloor enforcement failure

- **WHEN** SafetyFloor enforcement、violation audit、またはHARD_HALT sweepがthrowし、fresh replayも失敗または`Ambiguous`である
- **THEN** systemはSafetyFloor側の元failureをprimaryに保ちreadback failureをsuppressed evidenceとして追加する

#### Scenario: concurrent false rejection audit

- **WHEN** 同じstable requestの2 callをbarrierで交差させ、先行callがcommitしてから後続callがscopeを取得する
- **THEN** 後続callはinitial `Exact`を返しSafetyFloor rejection auditとHARD_HALT sweepを追加実行しない

### Requirement: backend内replayはpreflight後の競合を確定する

A2a backendはprepared proposalを受け取った後もatomic section内でstrict replayを最初に実行しなければならない（MUST）。
initial replayはstable identity以外を参照してはならない（MUST NOT）。
initial `Missing`後はMARKET / resting subtypeとrealtime session lock hintだけを参照して必要なlockを取得し、lock後のsecond replayも`Missing`の場合だけfull proposal validation、fresh ID / resolved group検査、fill / TTL / eligibility、business identity比較へ進まなければならない（MUST）。
initialまたはsecond replayが`Exact`の場合、attempt-local proposalを検証または比較してはならない（MUST NOT）。

#### Scenario: backend initial Exactとinvalid proposal

- **WHEN** backend initial replayが`Exact`でrequestにinvalid fresh ID、business mismatch、stale fill / TTL / eligibilityを持つproposalが同居する
- **THEN** systemはproposalのfieldへ依存せずpersisted resultを返す

#### Scenario: lock後Exactとinvalid proposal

- **WHEN** backend initial replayが`Missing`、subtype / session hintでlock取得後のsecond replayが`Exact`である
- **THEN** systemはfull proposal validation、fresh ID検査、fill / TTL / eligibility比較を行わずpersisted resultを返す

#### Scenario: backend Created

- **WHEN** initial / second replayがともに`Missing`でfull proposal、intent、flat predicate、write policyが新規entryを許可する
- **THEN** systemはA2aがatomic commitした`Created` resultを返す

#### Scenario: proposal subtype drift

- **WHEN** retry preparationのLIMIT crossing判定がpersisted lifecycle subtypeと異なるがinitialまたはsecond replayは`Exact`である
- **THEN** systemはpersisted lifecycleを正としproposal subtype不一致で拒否しない

#### Scenario: full validationはsecond Missing後

- **WHEN** subtype / session hintがlock取得に使われsecond replayも`Missing`である
- **THEN** systemはその時点で初めてcommand business identity、fresh entity ID、resolved group、fill / TTL / eligibilityを検証してmutation可否を判定する

### Requirement: typed failureの意味を接続層で変えない

systemはintent missing / consumed、non-flat、write policy rejection、backend unavailable、replay indeterminateを元のtyped failureとして返さなければならない（MUST）。
`AuthorizedAtomicEntryOutcomeIndeterminateException`を`NO_TRADE`、未commit、確定failure、または安全な再試行成功へ変換してはならず（MUST NOT）、A2bでcommitを再実行してはならない（MUST NOT）。

#### Scenario: outcome indeterminate

- **WHEN** A2a backendがfresh exact readbackでもcommit成否を確定できない
- **THEN** A2bは元のoutcome-indeterminate failureを一度だけ返しmutationを再実行しない

#### Scenario: non-flat rejection

- **WHEN** A2a atomic flat predicateがopen positionまたはrisk-increasing open entryを検出する
- **THEN** A2bはaccount-not-flat failureを返し`NO_TRADE` resultへ変換しない

#### Scenario: write policy rejection

- **WHEN** A2a transaction内write policyがHARD_HALTまたはpaper baseline不一致を拒否する
- **THEN** A2bは元のwrite policy failureを保持しentryとconsumptionを成功扱いしない

### Requirement: known paper backendだけをinternal pathへ接続する

systemはinternal `TradingRuntime` factoryがsame storage affinityを証明したInMemory repository setまたはExposed PostgreSQL repository setからのみauthorized backendを解決しなければならない（MUST）。
PostgreSQLのpreflight strict replayはA2aと同じclassifier / protective STOP identityをfresh `maxAttempts=1` transactionで使わなければならない（MUST）。
未知または不整合なrepository構成はpublic lookupへfallbackせずtyped unsupportedでfail closedにしなければならない（MUST）。

#### Scenario: InMemory backend

- **WHEN** `PaperBroker`が同じInMemory decision repositoryとInMemory ledger repositoryで構成される
- **THEN** authorized pathはその共有lockを使うA2a backendとstrict replay readerを解決する

#### Scenario: PostgreSQL backend

- **WHEN** `PaperBroker`がExposed PostgreSQL ledger repositoryで構成される
- **THEN** authorized pathはwriter adapterのstrict replayとatomic transactionを使う

#### Scenario: unsupported backend

- **WHEN** `PaperBroker`がpublic constructor、authorized capability未対応のcustom ledger、またはstorage affinity不整合なrepository setで構成される
- **THEN** authorized placeはpublic result lookupとmutationを行わずtyped unsupported failureを返す

### Requirement: A2bはproduction activationを変更しない

authorized connectionはmodule-internal `PaperBroker` surfaceだけに置かなければならない（MUST）。
public `Broker` / `PlaceOrderCommand` / MCP schema、public v2 pre-lookup guard、risk-reducing command、production runnerのFalsifier / fresh approval / public place behaviorを変更してはならない（MUST NOT）。

#### Scenario: public v2 caller

- **WHEN** publicまたはMCP callerがreserved v2 client request IDでpreview/placeを呼ぶ
- **THEN** systemはexisting result lookupより前に拒否しinternal authorized connectionへ到達させない

#### Scenario: risk-reducing command

- **WHEN** public close、update protection、cancelがreserved prefixをaudit IDに含む
- **THEN** systemはprefixだけを理由にrisk-reducing commandを拒否しない

#### Scenario: OFF permitとproduction runner

- **WHEN** production runnerがcanonical `OFF_V1 / ENTER` permitを確立する
- **THEN** A2bだけではrunnerは従来どおりFalsifierを実行しpublic broker pathだけを使う

#### Scenario: public contract

- **WHEN** A2b前後のpublic broker signature、command、MCP input schemaを比較する
- **THEN** permit、authorized envelope、backend selectorは追加されていない
