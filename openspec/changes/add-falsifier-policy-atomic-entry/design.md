## Context

A1 `add-falsifier-policy-authority-boundary` はdurable OFF authority、v2 fingerprint、public namespace guard、strict exact replayをinactive internal boundaryへ追加する。
exact resultがない場合は`AuthorizedNewMutationUnsupportedException`でfail closedとなり、production runnerは全entryでFalsifierとpublic broker pathを使う。

現行public entryは、MARKET相当entryとresting entryの双方でledger mutationとintent consumptionをまとめる。
InMemoryは`InMemoryDecisionRepository`のmutexからledger state write lockへ入り、FILL equity appendではさらに`InMemoryEquitySnapshotRepository`のprivate lockを取得する。
PostgreSQLは`risk_state`、`paper_account`、OPEN position / orderをlockするtransactionで書き込む。
ただしA1 authorized pathには、同じ原子境界でexact replay、consumed intent、flat predicateを解決して新規mutationするbackend capabilityがない。

full A2はA1 boundary接続、SafetyFloor preparation、両backend capability、concurrency testまで含めると1,250〜1,500行規模となり、1 PRの目安1,000行を超える。
このchangeはA2aとしてbackend capabilityとその直接testだけを扱う。
blocker反映後のA2aは1,000行を実装gateとし、超える場合は同じcontractをbackend別stackへ分ける。
A1 boundary / SafetyFloor接続はA2b、runtime activationはBに分ける。

## Goals / Non-Goals

**Goals:**

- InMemory / PostgreSQLに同じ意味のmodule-internal atomic entry capabilityを追加する
- atomic section内の順序をexact replay、Missing時のintent検証、flat predicate、mutation / consumptionに固定する
- MARKET相当entryとLIMIT / STOP resting entryを既存paper writer semanticsで保存する
- 同一requestと競合requestをsingle mutationへ収束させる
- rejection、rollback可能failure、commit outcome不明をtypedに区別する
- A2a単独deployでproduction behaviorを変えない

**Non-Goals:**

- A1 `AuthorizedFalsifierPolicyBoundary`からcapabilityを呼ぶこと
- authorized commandのvalidation、market data取得、fill / TTL plan作成、SafetyFloor接続
- runner permit propagation、Falsifier skip、runtime activation flag
- status、terminal cause、completion event、NO_TRADE / outcome mapping
- public `Broker`、`PlaceOrderCommand`、MCP schemaの変更
- CONDITIONAL、shadow、evaluation、live trading
- DB schema migrationまたはdata backfill

## Decisions

### 1. full A2をbackend A2aとconnection A2bに分割する

A2aはbackend capabilityを追加するが、`PaperBroker.authorizedFalsifierPolicyBoundary`へ注入しない。
A1の`Missing -> AuthorizedNewMutationUnsupportedException`とunsupported backend behaviorをそのまま維持する。
capabilityはmodule-internal testから直接呼び、実装と並行性をproduction接続前に独立検証する。

A2bはA1 authority / fingerprint検証と既存`PaperBrokerTradeDelegate`のcommand validation、market preparation、SafetyFloor、cash / symbol / price contractを通したrequestをcapabilityへ渡す。
BはA2b完了後にだけrunnerを接続する。

full A2を一つのchangeにする案は、backend concurrencyとrunner-facing failure semanticsを同時にreviewするdiffが1,000行を超えるため採用しない。
A2aでA1 boundaryの`Missing`だけをcapabilityへ接続する案も、SafetyFloorを通さない一時的なproduction-reachable経路を作るため採用しない。

### 2. capabilityはprepared mutation requestとtyped outcomeだけを扱う

broker packageにmodule-internal `AuthorizedAtomicPaperEntryBackend`を置き、既存の次のrequestを包むsealed inputを受ける。

- `IntentConsumingMarketEntryFillRequest`
- `IntentConsumingRestingEntryOrderRequest`

各requestのcommandはnon-null intent ID、reserved `runner-place-v2-` client request ID、consumptionと一致するintent IDを必須とする。
capabilityはpermitを受け取らずauthorityを再検証しない。
permit / durable decisionの検証責務はA1 boundaryに残し、A2bが検証済みrequestだけを渡す。

successは`AuthorizedAtomicEntryResult.Exact`と`Created`を区別する。
どちらも`PaperTradeResult`を保持し、call側がmessage文字列やconsumed stateからreplayを推測しない。
capability未実装backendはA1と同じくproduction接続されず、A2bで明示的にfail closedにする。

public `PaperLedgerRepository`へmethodを追加する案は、authorized storage primitiveを通常broker surfaceへ広げるため採用しない。
permitをbackend requestへ含める案は、storage layerにpolicy repository責務を重複させるため採用しない。

### 3. atomic section内はexact replayを必ず最初に解決する

backendは排他境界を取得した後、次の順序で処理する。

1. command / consumptionの構造identityを検証する
2. v2 client request IDのstrict authorized replayを同じlock / transactionから読む
3. `Exact`ならconsumed intentとflat stateを見ずに返す
4. `Ambiguous`ならtyped replay-indeterminateでrollbackする
5. `Missing`の場合だけintentの存在と未消費を検証する
6. flat predicateを検証する
7. existing paper write policyを検証する
8. MARKETまたはresting mutationとintent consumptionをcommitする

構造identityは副作用前に確認するが、ledger上のreplay判定よりconsumed / flat判定を前へ出さない。
同一requestの初回成功後はintentがconsumedでaccountもnon-flatになるため、replayが後だと正規retryを拒否してしまう。

A1 readerをlock外で一度呼んでからnew mutationだけtransactionへ渡す案は、並行callが双方`Missing`を観測できるため採用しない。
A2a capabilityはtransaction-local / lock-local replay helperを両backendに持つ。
A1 boundaryへのreader registrationはA2aでは変更せず、A2bがconnection全体を設計する。

### 4. exact replayはrequest subtypeごとの完全なdirect-link shapeだけを復元する

replay helperはsealed request subtypeとprepared IDを受け取り、`client_request_id == requested v2 ID`のBUY entryをanchorとして読む。
requested v2 ID、prepared entry / position / stop / execution ID、trade groupのいずれにもrequest-correlated artifactがない場合だけ`Missing`である。
BUY entryが欠けてもprepared IDまたはdirect linkで他artifactが見つかる場合、same-ID rowが存在するのにBUY entryがない場合、BUY entryが複数の場合、または唯一のentryがrequestのintent ID、trade group ID、entry order ID、symbol / modeと一致しない場合は`Ambiguous`とする。
一部artifactが欠けた状態をnew mutation可能な`Missing`へ落とさない。

`IntentConsumingMarketEntryFillRequest`の`Exact`は、既存writerがflat accountから作る次の完全bundleに固定する。

- requestと一致するFILLED BUY entryが厳密1件
- entryの`positionId`とrequestのposition IDに一致し、同じtrade groupに属するpositionが厳密1件
- そのposition IDとtrade groupへ直接linkする`side=SELL / orderType=STOP`のprotective STOPが厳密1件
- entry order IDとposition IDへ直接linkするBUY executionが厳密1件
- requestが持つentry / position / stop / execution IDと保存IDが一致する

entry、position、protective STOP、executionの欠損、duplicate entry / STOP / execution、intent / trade group / order / position link不一致は全て`Ambiguous`であり、`Missing`または部分的な`Exact`にしない。
MARKET相当requestにはcrossing LIMITをprepared fillへ変換した入力も含まれるため、`orderType == MARKET`ではなくsealed request subtypeと`status == FILLED`でshapeを選ぶ。

protective STOPのclient request IDはbackend間で同じではない。
InMemoryの`toProtectiveStopOrder()`はentryと同じclient request IDを保存するが、PostgreSQLの`insertProtectiveStopOrder()`は`orders.client_request_id`のpartial unique indexに合わせてNULLを保存する。
そのためSTOPをsame-ID SELLとして探索せず、entryから確定したposition IDとtrade group、SELL / STOP roleで一意に探索する。
InMemoryではsame-IDであることも整合条件として検証できるが、PostgreSQLではNULLであることを正常shapeとする。

`IntentConsumingRestingEntryOrderRequest`はrequest subtype、expected order ID、LIMIT / STOP order typeによりMARKET相当requestと区別する。
OPEN restingの`Exact`は、requestと一致するOPEN BUY entryが厳密1件、`positionId == null`、direct-linked position / protective STOP / executionが0件である単一order shapeとする。
PENDING_CANCEL / CANCELED / REJECTEDへ進んだ未約定restingも、同じentry anchorにfill artifactがない場合は現在statusの単一order resultとして復元する。
resting entryがmarket eventでFILLEDへ進んでいる場合はMARKET requestへ読み替えず、resting requestのentry ID / intent / trade group / order typeを維持したまま、position、protective STOP、BUY executionが各1件のcomplete FILLED bundleを要求する。
resting requestはfill時に生成されるstop / execution IDを入力に持たないため、それらはdirect linkと一意性で確定する。

FILLED resultのorder IDsはanchor entryと一意なdirect-linked protective STOP、position IDsは一意なdirect-linked position、execution IDsはentry / positionへ一意にdirect-linkするexecutionだけに限定する。
未約定resting resultはanchor entryだけを含む。
同じtrade groupでも異なるclient request IDの後続ADD_LONG、close / reduce、protection update、またはそれらのexecutionを走査・集約しない。

backend-neutralなinternal classifierへrow collectionを渡すunit testでは、same-ID close / reduce / ADD_LONG、duplicate BUY / STOP / execution、missing artifact、link mismatchをsyntheticに作る。
PostgreSQLは`WHERE client_request_id IS NOT NULL`のunique indexによりentryと同じnon-null IDの2件目を保存できないため、同じmalformed rowをintegration fixtureで無理に作らない。
PostgreSQL integrationでは2件目のnon-null same-ID insertがunique violationになることを確認し、NULL client request IDのduplicate direct-linked STOP、duplicate execution、欠損artifact、different-ID / direct-link mismatchなどDB上でreachableなcorruptionを検証する。

A1のInMemory replay readerも、A1 commandと保存rowから検証できる同じcomplete-bundle shape rulesへ寄せる。
A2aはA1 boundaryをcapabilityへ接続しないが、FILLED BUYだけのfixtureを`Exact`にせず`Ambiguous`としてfail closedにする。
A1の正規FILLED replay fixtureはentry、position、protective STOP、executionを全てseedし、既存のvalid exact replayだけを維持する。

trade group全体をresultへ集約する案は、初回entry後のlifecycle mutationをoriginal requestのresultへ混ぜるため採用しない。
same client request IDだけでprotective STOPを特定する案はPostgreSQLのNULL identityと矛盾するため採用しない。

public `close_position`、`update_protection`、`cancel_order`へreserved v2 prefixのblanket guardは追加しない。
これらはrisk-reducing / protection availabilityを担うため、client request IDだけを理由に拒否しない。
synthetic classifierでentryと同じv2 IDのnon-protective rowを受けた場合は`Ambiguous`となり、reachableなPostgreSQL public pathではpartial unique indexが2件目のnon-null IDを拒否する。
public `place_order` / previewの既存reserved prefix guardは維持する。

### 5. flatはopen positionとfill可能なBUY orderの不存在で定義する

flat predicateは次のANDで固定する。

- `PositionStatus.OPEN`のpositionが0件
- `side == BUY`かつ`status in (OPEN, PENDING_CANCEL)`のorderが0件

`PENDING_CANCEL`は取消完了前にfillし得るためriskとして数える。
protective STOP / take-profitのSELL orderは単独ではriskを増やさないため数えない。
exact replayはこのpredicateより先なので、replay対象自身が作ったOPEN position / BUY orderは拒否理由にならない。

intent consumptionだけをsingle-entry gateにする案は、異なるintentの並行entryを防げないため採用しない。
SafetyFloor snapshotだけでflatを判定する案は、snapshot取得後のraceを閉じないため採用しない。

### 6. InMemoryはcomplete before-image restoreを採用する

InMemory adapterは`InMemoryDecisionRepository`と`InMemoryPaperLedgerRepository`の組合せだけを受理する。
lock順は`decision mutex -> ledger state write lock -> equity snapshot lock`に固定する。
ledger write lock内でstrict replayとflat predicateを読み、decision mutex内のintent存在 / consumed stateを使う。
全3 lockを取得してからbefore-imageを取得し、MARKET / resting update、equity publish、consumption append、成功returnまたはcomplete restoreが完了するまで解放しない。

既存`consumeIntentAfterLedgerWrite`はledger callbackより前にconsumedを拒否するため、そのまま使わない。
新しいinternal helperはdecision mutex内でexact replay結果を優先できる非suspend commit callbackを提供する。
`InMemoryEquitySnapshotRepository`には、private equity lockを取得してsnapshot stateを扱うmodule-internalのnon-suspend exclusive transaction helperを追加する。
callbackはledger write lockからequity helperへ入り、intent未消費確認、before-image取得、ledger / equity publish、consumption append、成功またはrestoreを一続きに実行する。
equity lock保持中のcallbackはsuspendせず、外部I/O、`EquitySnapshotRecorder`、account source、またはledger lockを新たに取得する処理を呼ばない。
publicな`append`、`appendDailyIfAbsent`、`findAll`とinternal snapshot / replaceは同じprivate equity lockを共有する。

既存locked writerは複数のmutable collectionをin-place更新するため、大きなstaged payloadへの全面置換ではなくcomplete before-image restoreを採用する。
exact replayが`Missing`でintent / flat検証を通過した後、全3 lockを保持したmutation前に次をsnapshotする。

- `orders`、`positions`、`executions`
- `accountSnapshot`、`accountUpdatedAt`
- decision / lineage auxiliaryである`decisionRunIdsByPositionId`、`thesisCandidatesByIntentId`
- `orderMarketEligibility`、`orderQueueConsumedBtc`、`positionMarketEligibility`
- `executionMarketSources`
- `marketSessionId`、`lastMarketSequence`
- `InMemoryEquitySnapshotRepository`の全equity snapshots
- `InMemoryDecisionRepository`の全intent consumptions

`decisionContextsByRunId`はimmutable、`accountStateBoundary`のrisk stateはこのentry pathでread-onlyなのでrestore対象外である。
それ以外のmutable fieldを暗黙に除外しない。

MARKET / resting updateは既存locked helperとdomain変換を再利用する。
test-only fault seamをledger stateとequity snapshotのpublish完了後、intent consumption append直前に置く。
このseamまたはそれ以降で例外が発生した場合、全3 lockを保持したままledger fields / collections、全auxiliary map、equity snapshots、intent consumptionsをbefore-imageへ完全復元してからfailureを返す。
equity lockはbefore-image取得前からrestore完了まで連続して保持されるため、restoreは同時にcommit済みのDAILY snapshotを消さない。
競合するDAILY appendはequity lockで待機し、restore後にcommitして残る。
restore APIはin-memory内部のsynchronous replace操作に限定し、restore中に外部I/Oや新しいfailure seamを作らない。
testは全対象のbefore / after snapshot一致を比較し、orderだけ消してaccountやequityを残す不完全rollbackを許さない。

現行`EquitySnapshotRecorder.recordDailyIfNeeded()`はaccount sourceでledger read lockを取得・解放してから`appendDailyIfAbsent`でequity lockを取るため、equity lockからledger lockへ戻るnested pathを持たない。
現行`InMemoryEquitySnapshotRepository`のequity lock保持区間もsnapshot collectionのread / writeだけで、ledgerを呼ばない。
新しいexclusive helperからaccount sourceやledger取得を呼ぶ経路は追加せず、この前提をcall graph確認とcross-testで固定する。
ledger lockからdecision mutex、またはequity lockからledger lockを取る逆順案は、public entryとのdeadlockを作るため採用しない。
ledgerとdecisionを別々にcommitして補償削除する案は、paper truthを書換えるため採用しない。
staged stateのsingle reference publishへ全面refactorする案は、既存repository layoutに対してscopeが大きいため採用しない。

### 7. PostgreSQLはMARKETとrestingでlock graphを分ける

MARKET相当entryとrealtime eligibilityを持たないresting entryは、既存ledger mutation lock順を使う。

1. `risk_state`
2. singleton `paper_account`
3. OPEN positionsをID順
4. OPEN / PENDING_CANCEL ordersをID順

realtime eligibilityを持つresting entryは、既存`lockRestingOrderCreationRows()`と同じ順を使う。

1. session-scoped PostgreSQL advisory transaction lock
2. 対象`market_data_sessions` rowを`FOR UPDATE`でlockし、CONNECTED / sequenceを検証する
3. `risk_state`
4. singleton `paper_account`
5. OPEN positionsをID順
6. OPEN / PENDING_CANCEL ordersをID順
7. global market admission boundaryを読む

authorized restingだけがledger rowsを先に取り、後からsession advisory / rowを取る経路は作らない。
`applyMarketEvent`は既存どおり`market_data_sessions` rowからledger mutation rowsへ進み、後からsession advisory lockを取得しない。
したがってauthorized restingと`applyMarketEvent`の間にreverse acquisition cycleはない。

zero-row predicateへの同時insertはposition / order row lockだけでは防げない。
全risk-increasing entry writerが先にsingleton `paper_account`をlockする既存契約を使い、2つ目のtransactionを待機させる。
READ COMMITTEDでは待機後のreplay / predicate queryが先行commitを観測する。

同じtransactionでstrict replay query、intent存在 / consumption query、flat predicate、write policy、entry write、consumption insertを実行する。
MARKETは既存`insertEntryFill`、restingは既存`insertEntryOrder`を再利用する。
intent consumptionのunique indexを最後の防御として維持し、transaction rollbackによりpartial rowを残さない。

SERIALIZABLEへ全writerを変更する案は、既存transaction全体のretry semanticsを広げるため採用しない。
advisory lockを新設する案は、既存paper account lockと二重のauthorityになるため採用しない。
eligibility付きrestingでgeneric MARKET lock helperを先に呼ぶ案は、`applyMarketEvent`とのlock順を逆転させるため採用しない。

### 8. capabilityは既存paper semanticsを再利用するがSafetyFloorの代替にしない

MARKET相当requestは既存どおりFILLED BUY order、OPEN position、protective STOP、execution、account / equity snapshotを作る。
crossing LIMITはA2bのpreparationでMARKET相当requestになり、non-crossing LIMITとSTOPはresting requestになる。
resting requestは既存TTL、market eligibility、queue snapshot metadataを保存する。
HARD_HALT、paper baseline、execution lineageはbackend write policyを再利用してtransaction内で再検証する。

5不変条件全体の判定は既存SafetyFloorの責務であり、A2a capability単独では代替しない。
そのためA2aをpublic / runnerへ接続せず、A2bは既存SafetyFloorを通したprepared request以外を渡せない構造にする。
新しい簡略SafetyFloorやOFF専用の例外規則は追加しない。

### 9. PostgreSQL transactionは一度だけ実行しcommit不明をfresh readbackする

typed failureは少なくとも次を区別する。

- replay ambiguous / corrupt: `AuthorizedAtomicEntryReplayIndeterminateException`
- intent missing: `AuthorizedAtomicEntryIntentMissingException`
- intent consumed: `AuthorizedAtomicEntryIntentConsumedException`
- account non-flat: `AuthorizedAtomicEntryNotFlatException`
- transaction開始前またはrollback確認済みstorage failure: `AuthorizedAtomicEntryUnavailableException`
- commit acknowledgementを確定できないfailure: `AuthorizedAtomicEntryOutcomeIndeterminateException`

authorized PostgreSQL mutationとcommit outcome確認用readbackは、どちらも`exposedTransaction(maxAttempts = 1)`を明示する。
transaction bodyの外側に`bodyCompleted=false` markerを置き、全SQL mutationとconsumption insertが完了してlambdaがreturn可能になった時点だけtrueにする。
Exposedまたはdriverによるwhole-transaction自動retryを許可しない。

failure分類は次に固定する。

- body開始前のfailure、または`bodyCompleted=false`でrollback完了を確認できるbody / pre-commit failureは`Unavailable`
- `bodyCompleted=true`後のcommit / acknowledgement failure、またはrollbackを確認できないfailureは`OutcomeIndeterminate`

`OutcomeIndeterminate`を捕捉したら、同じv2 ID / intentでfresh transactionのstrict exact readbackを直ちに一度だけ実行する。
readbackも`maxAttempts=1`とする。
`Exact`ならmutationを再実行せず`AuthorizedAtomicEntryResult.Exact`として回復する。
readbackがunavailable、`Missing`、`Ambiguous`のいずれでも元の`OutcomeIndeterminate`を維持し、result不存在を断定しない。

deterministic rejectionとrollback確認済みfailureはentry / consumptionを残さない。
outcome-indeterminateはresult不存在またはNO_TRADEを意味せず、fresh readbackまたは後続の同一v2 retryだけがexact replayで回復できる。
A2aはこの型をrunner status / terminal causeへmappingしない。
durable mappingと運用復旧はBで扱う。

generic `IllegalStateException`だけを返す案は、consumed、non-flat、storage不明をcall側が文字列判定することになるため採用しない。
commit不明を安全なfailureとして扱う案は、実際にはcommit済みのentryを見落とすため採用しない。
commit不明時にtransaction bodyを自動再実行する案は、初回commit済みか不明な状態で二重mutationを試みるため採用しない。

### 10. replay / lock / failure matrixを両backendで固定する

test matrixはMARKET / resting双方について次を固定する。

- 同じintent / 同じv2 ID: `Created` 1件 + `Exact` 1件
- 同じintent / 異なるv2 ID: `Created` 1件 + intent-consumed 1件
- 異なるintent / 異なるv2 ID: `Created` 1件 + account-not-flat 1件
- MARKET対resting: `Created` 1件 + account-not-flat 1件
- exact result + consumed + non-flat: `Exact`
- MARKET/FILLEDのentry / position / direct-linked protective STOP / direct-linked execution各1件: `Exact`
- OPEN restingの単一entry、および後からFILLEDへ進んだrestingのcomplete bundle: subtypeに対応する`Exact`
- position / STOP / executionの欠損、重複、intent / trade group / direct-link不一致: `Ambiguous`
- synthetic classifier上の同じv2 IDのclose / reduce SELLまたは複数BUY / ADD_LONG: `Ambiguous`
- PostgreSQLの2件目のnon-null same-ID order: unique violation
- PostgreSQLのNULL-ID duplicate STOP、duplicate execution、different-ID / direct-link corruption: `Ambiguous`
- 同じtrade groupの別request ADD_LONG / close: original replay resultへ非集約
- InMemory ledger publish後・consumption前failure: 全mutable stateを完全restore
- InMemory failureと`EquitySnapshotRecorder` DAILY appendの交差: DAILYはequity lockで待機してrestore後にcommitし、failed FILLは残らない
- PostgreSQL rollback確認済みpre-commit failure: `Unavailable`
- PostgreSQL commit成功・ACK loss: fresh readbackで`Exact`
- PostgreSQL readback unavailable / `Missing` / `Ambiguous`: `OutcomeIndeterminate`

InMemoryはbounded coroutine stress、PostgreSQLは独立connection / transactionのintegration testを使う。
反復回数はraceを観測できる小さな固定値とし、新しい汎用chaos frameworkは追加しない。
eligibility付きauthorized restingと`applyMarketEvent`のcross-testは、test-only barrierで各lock到達点を固定し、両処理がtimeout / deadlockなく完了することとreverse acquisitionがないことを決定的に検証する。
InMemoryのequity cross-testは、まず`EquitySnapshotRecorder`のaccount sourceを完了させてtest barrierでDAILY repository append直前に停止し、次にauthorized MARKETをledger / FILL equity publish後・consumption前のfault barrierで停止する。
その後DAILY appendを開始してequity lock待機を確認し、faultを発生させてcomplete restoreを完了する。
DAILY appendがrestore後にcommitして残ること、failed MARKETのledger / consumption / FILL equityが存在しないこと、timeout / deadlockとequityからledgerへのreverse acquisitionがないことを検証する。
transaction wrapper testはattempt counterでmutation bodyとreadbackが各最大1回であることを検証する。

## Risks / Trade-offs

- [A2a capabilityを誤ってproductionから呼ぶ] → A1 boundaryへ注入せず、runner / public call graph不変testを置く
- [同一requestがconsumed / non-flatで拒否される] → lock / transaction内のstrict replayを最初に固定する
- [zero-row predicateを2 transactionが通過する] → 全entry writerが取得するsingleton paper account rowで直列化する
- [restingとmarket eventでdeadlockする] → advisory→session row→ledger rowsに固定し、reverse acquisitionなしのdeterministic cross-testを置く
- [InMemoryのlock順が逆転する] → decision mutex→ledger write lock→equity snapshot lockだけを許可し、call graph確認とconcurrency testでdeadlockを検出する
- [InMemory failureがpartial stateを残す] → 全mutable stateのcomplete before-image restoreとpublish後/consumption前fault testを置く
- [restoreが同時commit済みDAILY snapshotを消す] → equity lockをbefore-image取得からsuccess / restore完了まで連続保持し、DAILY appendとのdeterministic cross-testを置く
- [FILLED BUYだけを成功済みrequestと誤認する] → position / STOP / executionを各1件要求し、欠損・重複・link不一致をAmbiguousにする
- [PostgreSQLのSTOPをsame-IDで探索して欠損扱いする] → backend固有client request IDではなくposition ID・trade group・STOP roleのdirect linkで特定する
- [DB uniqueで作れないmalformed fixtureに依存する] → synthetic classifier testとreachable PostgreSQL integration corruptionを分ける
- [trade groupの後続lifecycleをreplayへ混ぜる] → result membershipをrequest-scoped entry anchorとdirect linkに限定し、close / ADD_LONG fixturesを置く
- [reserved prefix guardがrisk-reducing操作を止める] → close/update/cancelへblanket guardを追加せず、same-ID malformed shapeはclassifierでAmbiguous、PostgreSQL duplicate non-null IDはunique violationにする
- [capabilityがSafetyFloor bypassになる] → A2aをinactiveに保ち、A2bで既存preparation / SafetyFloor pathだけへ接続する
- [PENDING_CANCELをflatと誤認する] → fill可能なBUYとしてpredicateに含める
- [commit不明をretryして二重mutationする] → maxAttempts=1、bodyCompleted marker、fresh exact readbackだけに限定する
- [readback Missingを未commitと誤認する] → Missing / Ambiguous / unavailableを全てoutcome-indeterminateに維持する
- [diffが再び1,000行を超える] → A2aはbackend capability、direct test、inactive docsだけに限定しA2b要素を入れない

## Migration Plan

1. schema migration / backfillなしでInMemory / Exposed internal capabilityとdirect testを追加する。
2. A1 boundaryが`Missing`でfail closedのまま、public/MCP schema、runner Falsifier behavior、status / outcome mappingが不変であることを確認する。
3. A2aをinactive deployし、production call graphからcapabilityへ到達しないことを確認する。
4. 後続A2bで既存SafetyFloor / paper preparation pathとA1 boundaryを接続する。
5. A2bの検証後、Bでruntime activation precondition、permit propagation、Falsifier skip、durable outcome mappingを追加する。
6. A2b前のrollbackはA2a codeだけを戻し、ledger、intent consumption、foundation decision/eventを削除または書換えない。

## Open Questions

なし。
