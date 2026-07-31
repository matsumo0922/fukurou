## 1. Storage affinity and backend scope

- [ ] 1.1 public `PaperBroker` constructorをauthorized capability unsupportedに保ち、capability型またはaffinity tokenをpublic APIへ露出しない
- [ ] 1.2 public `connectedPostgres(dataSource, database)` pair overloadをauthorized unsupportedに保ち、object identity一致だけでcapabilityを有効化しない
- [ ] 1.3 database引数を受けないproduction-safe factoryにsingle owned DataSourceから`ExposedDatabase`、scope connection factory / evictor、stable-identity keyed cancellable mutex registryだけを生成するearly opaque `PostgresStorageRoot`を追加する
- [ ] 1.4 active DB runtime config / clock解決後のroot overloadでledger / decision / policy repositoryとbackendをconfig-bound componentとして同時生成し、same root rebuildはnew configでbound componentだけを再生成してroot-owned mutex registryを維持する
- [ ] 1.5 `Application.createApplicationDatabaseResources`をroot生成へ移し、`ApplicationDatabaseResources`がrootを保持してread-only DataSource / database getterを既存readiness / bootstrap / routes / monitoring / maintenanceへ渡す
- [ ] 1.6 daemon schedulerとmanual launchのruntime input / `createLlmLaunchRuntimeComponents`をpairから同じApplication root + resolved config / clock overloadへ移し、各scheduler loop generationが`TradingRuntime`を含むcloseable bundleを所有してfinallyで一度closeする
- [ ] 1.7 worker closeはjob cancel後のgeneration finally / bound runtime close完了をcode-owned 30秒monotonic deadlineでawaitし、worker terminationとmanual resource closeの成功後だけApplication rootをcloseし、timeoutはtyped shutdown failure + root非closeでin-flight commit outcomeを読み替えない
- [ ] 1.8 standalone `postgres(config)`はowned rootを生成し、runtime closeがbound resource後にrootを一度だけcloseする
- [ ] 1.9 InMemory runtime factoryはledger / decision / policy repository / backendが同じruntime repository instancesを共有することを検証する
- [ ] 1.10 public / pair-based constructor、custom backend、PostgreSQL root / scope DataSource、InMemory identity mismatchをauthority / replay前のtyped unsupportedにしpublic lookupへfallbackしない
- [x] 1.11 `AuthorizedAtomicPaperEntryBackend`へstable identity strict replayとstable request execution scopeを追加し、scope acquisition failureをpreparation前typed unavailableにする
- [ ] 1.12 InMemory scopeとPostgreSQL rootにcancellable stable-identity keyed coroutine mutex registryを実装し、holder / waiter参照がなくなった同一entryだけをcancellation / release後に安全に除去する
- [x] 1.13 repo内two-int advisory usageを再grepしてnamespace `1179994962`が未使用であることを確認し、stable request ID SHA-256先頭4 byteのsigned hash32とtwo-int try-lock / unlock SQLを実装する
- [ ] 1.14 PostgreSQLは30秒deadlineをlocal mutex wait前に開始し、`local identity mutex -> dedicated borrow -> two-int advisory`の順でremaining budgetを使い、same-request local waiterがconnectionをborrowしないdeadline-aware acquisitionを実装する
- [ ] 1.15 各try callへremaining budget由来の`Statement.queryTimeout` / `Connection.networkTimeout`をarmし、original timeout restore failureをroot evictorへ送る
- [ ] 1.16 cancellation時はcleanup executorでrunning `Statement.cancel`を試み、borrow / SQL failure / query timeout / response loss / outcome不明をroot evictorで非再利用にする
- [ ] 1.17 definite false timeoutとtimeout restore成功時だけtyped unavailable + 通常pool returnにする
- [ ] 1.18 acquired scopeは独立5秒cleanup deadlineとquery / network timeoutでunlockし、false / stall / throw / response loss / restore failureをabort / evictする
- [ ] 1.19 取得順をlocal identity mutex -> dedicated borrow -> stable request two-int advisory -> market session single-bigint advisory -> market session row -> risk state -> account -> positions -> ordersに固定し、local mutexをdedicated cleanup後まで保持してexternal I/O中はstable scope以外のlockを保持しない
- [ ] 1.20 scope acquisition時のbackend PIDを保持し、SafetyFloor / DB-visible side effect前、A2a invocation前、non-mutation terminal前に毎回独立5秒monotonic deadlineとremaining query / network timeoutで`pg_backend_pid()` / exact granted lock heartbeatを行う
- [ ] 1.21 heartbeat cancellationはrunning `Statement.cancel`を試み、stall / loss / unknown / timeout restore failureはpre-mutation typed unavailable + abort / evict、SafetyFloor throwはprimary + suppressed、confirmed backend result後はpaper truthと通常のstrategy evaluationを維持してcleanupだけevictする
- [ ] 1.22 heartbeatと別connection上のterminal readback / backend invocation間のcheck-use gapをA2b residualとして維持し、non-mutation terminalをdurable `NO_TRADE` / commit不存在へ変換せずBのterminal mappingへstage-outする

## 2. Strict replay and proposal access order

- [ ] 2.1 authorized boundaryをaffinity -> authority -> fingerprint -> stable identity -> stable request scope -> strict replayの順にし、unsupportedをauthority read前、`Exact`をfresh preparation前に返す
- [ ] 2.2 initial replay `Ambiguous` / read failureはpreparationとmutationへ進めず、`Missing`だけscopeを保持してauthorized creation continuationへ渡す
- [x] 2.3 A2a backend initial replayをstable identity-onlyにし、`Exact` pathがproposalのsubtype、session、command、fresh ID、fill、TTL、eligibilityへ一切依存しないようにする
- [x] 2.4 initial `Missing`後はsubtype / realtime session lock hintだけを読み、必要なsession / ledger lockを取得してsecond strict replayを実行する
- [x] 2.5 full `requireCreationProposalValid`、fresh entity ID / resolved group検査、business identity、fill / TTL / eligibility validationをsecond replay `Missing`後へ移す
- [x] 2.6 initial / second `Exact`はfresh proposalとpersisted ID / lifecycle / subtypeを比較せず、`Created`だけがvalidated proposalをmutationへ使う

## 3. Broker preparation and terminal finalization

- [ ] 3.1 public placeとauthorized creationが既存command validation、market preparation、SafetyFloor enforcement / observation、symbol / price / cash contractを共有するhelperへ整理する
- [ ] 3.2 MARKET / crossing LIMITは既存fill / position / STOP / executionとconsumptionからA2a MARKET proposalを構築する
- [ ] 3.3 non-crossing LIMIT / STOPは既存TTL / realtime eligibility / queue metadataとconsumptionからA2a resting proposalを構築する
- [ ] 3.4 SafetyFloor rejectionとpreparation failureはA2a commit前に止め、stable scope内fresh replayが`Exact`ならpersisted result、`Missing`なら元terminal、`Ambiguous` / read failureならtyped indeterminateを返す
- [ ] 3.5 SafetyFloor enforcement / violation audit / HARD_HALT sweepがthrowした場合は元failureをprimaryに保ち、fresh readback failure / ambiguityをsuppressed evidenceにする
- [ ] 3.6 A2a `Exact` / `Created`のresultを返し、intent / flat / write policy / unavailable / replay failureと`OutcomeIndeterminate`を変換・retryせず伝播する

## 4. Affinity, scope, replay, and safety tests

- [ ] 4.1 public constructor、public `connectedPostgres` pair、PostgreSQL root / scope DataSource mismatch、InMemory affinity mismatchについて、ledger replayが`Exact` / `Missing`の双方でもtyped unsupportedとなりauthority / reader / preparation / mutationを呼ばないことをtestする
- [ ] 4.2 Application early rootがactive DB config解決前にbound componentを作らず、daemon / manualがactive configとclockでroot overloadを使い、env defaultとの差分とrunner / Falsifier behavior不変をcomposition testする
- [ ] 4.3 same root / new active config rebuildがnew bound componentだけを作ってroot / local mutex registryを維持し、scheduler regenerationが旧generation bound runtimeをfinallyでcloseしてから次generationを作ることをtestする
- [ ] 4.4 InMemory same-request barrierで後続preflightが先行terminalまでmutex待機し、異なるrequestは並行でき、cancelled waiterが直ちに終了してregistry entryが安全にcleanupされることをtestする
- [ ] 4.5 PostgreSQL pool maximum 4で4件以上のsame-request callをbarrier交差させ、holder以外がborrowせずstrict replay / preparation / A2a transaction用connectionを枯渇させないこと、別processはadvisoryで直列化し異なるrequestは並行できることをtestする
- [ ] 4.6 global / market session single-bigint lockにnamespace / hashと同じ64-bit bit patternを強制し、stable request two-int lockと相互blockまたは自己deadlockしないことをintegration testする
- [ ] 4.7 module-internal短縮deadline seamでlocal mutex wait / Hikari borrow stall / holder継続中のtry-lock false pollが共通monotonic acquisition deadlineでtimeoutし、local waiterは非borrow、definite false + timeout restore成功時だけconnectionを通常pool returnできることをtestする
- [ ] 4.8 try-lock stall / SQL failure / query timeout / response lossとcancellation中の`Statement.cancel`をtestし、root connectionが再利用されずserver取得済みlockもsession closeで解放されることをtestする
- [ ] 4.9 acquired scopeのnormal / failure / cancellation unlock、独立5秒cleanupでのstall / false / throw / response loss / network timeout restore failureをtestしunknown connectionをabort / evictする
- [ ] 4.10 eligibility付きresting scopeとmarket eventをbarrierで交差させ、local mutex -> dedicated borrow -> stable advisory以降の所定順を守り、stable scope以外をexternal I/O中に保持せずdeadlockしないことをtestする
- [ ] 4.11 backend initial `Exact`へinvalid / throwing相当proposalを渡しproposal非参照、initial Missing -> lock -> second `Exact`でもfull proposal validation非実行を両backendでtestする
- [ ] 4.12 second `Missing`の場合だけbusiness mismatch、fresh ID collision、invalid fill / TTL / eligibilityがvalidation failureになることを両backendでtestする
- [ ] 4.13 同じrequestの2 callをbarrierで交差させ、先行commit後の後続がinitial `Exact`となりfalse rejection audit / SafetyFloor evaluation / HARD_HALT sweep / fresh ID生成を追加しないことをtestする
- [ ] 4.14 non-mutation terminal fresh replayの`Exact / Missing / Ambiguous / unavailable` matrixと、SafetyFloor / sweep throwがprimaryでreadback failureをsuppressするmatrixをtestする
- [ ] 4.15 SafetyFloor rejection、preparation failure、intent missing / consumed、non-flat、HARD_HALT / baseline rejection、backend unavailableがentry / consumptionを残さないことをtestする
- [ ] 4.16 PostgreSQL `OutcomeIndeterminate`がA2bでNO_TRADE / 確定failureへ変換されず、mutation retryが増えないことをtestする
- [ ] 4.17 public Broker / PlaceOrderCommand / MCP schema不変、public v2 pre-lookup guard、risk-reducing command、OFFでもFalsifierを起動するrunner回帰testを維持する
- [ ] 4.18 SafetyFloor side effect前、A2a invocation前、non-mutation terminal前のPID / `pg_locks` heartbeat success / loss / stall / cancel / timeout restore failure matrixをtestし、毎回独立5秒budget、30秒超のslow preparation後のlive session success、SafetyFloor throw primaryを確認する
- [ ] 4.19 PostgreSQL dedicated backend sessionをmid-preparationで強制終了して別processを開始し、duplicate preparation / auditをinfra residualとして許容しつつentry mutation / consumption各最大1、成功 / NO_TRADE / 未commit偽装なしをtestする
- [ ] 4.20 scope loss後もconfirmed `Exact` / `Created`とconfirmed commit後process crashのledger tradeが通常のstrategy evaluation対象に残り、losing unavailable attempt / duplicate preparation / auditだけをinfra residualとして報告し、新schema / fencing tokenを追加しないことを確認する
- [ ] 4.21 scheduler shutdown中のin-flight runをbarrierで止め、worker cancel後にgeneration finally / bound runtime closeをawaitしてからroot closeする順序と、30秒termination timeout時にtyped failureを返してrootをcloseせずcommit outcomeを不明化しないことをtestする
- [ ] 4.22 heartbeat成功後かつ別connection上のterminal readback / backend invocation前にdedicated sessionを失わせ、別process commit後もmutation / consumption最大1を保ち、A2b terminalをdurable `NO_TRADE` / commit不存在へ変換しないresidualをtestする

## 5. Documentation and validation

- [ ] 5.1 `docs/mcp-runtime.md`をA2b internal path接続済み、root-owned local admission + live session scope / heartbeat、generation-bound runtime closeとbounded root shutdown、check-use residualをdurable `NO_TRADE`へ変換しない境界、confirmed tradeのpaper truth / strategy evaluation維持、Bまでpublic/MCP/runnerから到達不能である現在形へ更新する
- [ ] 5.2 変更したcapability / class / failure名で`docs/`とREADMEをgrepし、stale記述を更新する
- [ ] 5.3 OpenSpec strict validation、関連InMemory / PostgreSQL test、admission isolation regression、detekt、buildをexact HEADで実行する
- [ ] 5.4 human-authored diffが1,000行目安を超える場合はbackend scope / affinity / replay contractを先行PR、broker preparation connectionを後続stack PRに分割し、Bを混ぜない
