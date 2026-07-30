## ADDED Requirements

### Requirement: atomic entry capability は internal かつ inactive である

systemはauthorized paper entry用のmodule-internal backend capabilityをInMemoryとPostgreSQLに提供しなければならない（MUST）。
A1 `AuthorizedFalsifierPolicyBoundary`、public `Broker`、MCP、production runnerはこのcapabilityを呼んではならない（MUST NOT）。

#### Scenario: A2a deploy後のauthorized Missing

- **WHEN** A1 authorized boundaryがvalid authority / fingerprintに対してexact replay `Missing`を受け取る
- **THEN** boundaryは従来のtyped `authorized new mutation unsupported`を返しcapabilityを呼ばない

#### Scenario: public contract

- **WHEN** change前後のpublic `Broker`、`PlaceOrderCommand`、MCP input schemaを比較する
- **THEN** capability、permit、atomic requestのfieldまたはmethodは追加されていない

#### Scenario: production runner

- **WHEN** production runnerがOFF foundation permitを持つENTERを処理する
- **THEN** runnerは従来どおりFalsifierとpublic broker pathを実行しstatus / outcome mappingを変えない

### Requirement: exact replay はatomic sectionの最初に解決する

capabilityはbackendの排他境界を取得した後、intent consumption、flat predicate、新規mutationより先にv2 client request IDのauthorized exact replayを解決しなければならない（MUST）。
同じclient request IDのBUY entryが厳密1件、intent ID一致、non-null trade groupの場合だけcandidateとしなければならない（MUST）。
同じclient request IDのSELLは、BUY entryと同じtrade groupかつentry positionへ直接linkするprotective STOPだけを許可し、その他のSELLまたは2件目のBUYがあれば`Ambiguous`としなければならない（MUST）。
resultは同じclient request IDのentry / protective STOP、それらを直接参照するposition / executionだけから復元し、同じtrade groupの別request rowを集約してはならない（MUST NOT）。

#### Scenario: consumed intentとexact result

- **WHEN** exact result、consumed intent、open positionまたはopen resting entryが存在する同じrequestを再実行する
- **THEN** capabilityはintentとflat stateを拒否理由にせず`Exact` resultを返しmutationを行わない

#### Scenario: MARKET exact replay

- **WHEN** v2 IDに1件のFILLED BUY entry、同一trade groupのprotective SELL、position、executionが存在する
- **THEN** capabilityはrequest-scoped entry / protective STOPと直接linkするposition / executionだけを`Exact` resultとして返す

#### Scenario: resting exact replay

- **WHEN** v2 IDに1件のOPEN BUY resting entryが存在しintentとtrade groupが一致する
- **THEN** capabilityはそのOPEN orderを`Exact` resultとして返す

#### Scenario: 同じrequest IDのcloseまたはreduce

- **WHEN** BUY entryと同じv2 IDにnon-protective close / reduce SELL orderが存在する
- **THEN** capabilityはSELLをprotective STOPと誤認せずtyped replay-indeterminate failureを返す

#### Scenario: 同じrequest IDのADD_LONG

- **WHEN** 同じv2 IDに2件目のBUY / ADD_LONG orderが存在する
- **THEN** capabilityはどちらかを選ばずtyped replay-indeterminate failureを返す

#### Scenario: 別requestの同一trade group lifecycle

- **WHEN** original entryと同じtrade groupに異なるclient request IDのADD_LONG、close、executionが存在する
- **THEN** original replayはそれらをorder / position / execution resultへ集約しない

#### Scenario: malformed protective row

- **WHEN** request-scoped SELLがSTOP以外、position link欠損、entryと別position、または別trade groupである
- **THEN** capabilityはtyped replay-indeterminate failureを返しintent、ledger、accountを変更しない

#### Scenario: risk-reducing public command

- **WHEN** public close / update protection / cancelがreserved v2 prefixをaudit client request IDに持つ
- **THEN** systemはprefixだけを理由にrisk-reducing commandを拒否せず、非protective rowが同じIDへ作られた場合のentry replayを`Ambiguous`にする

#### Scenario: concurrent同一request

- **WHEN** 同じintent、command、v2 client request IDの2 callが並行する
- **THEN** 片方だけが`Created`となり他方はcommit後のstateを`Exact`として返し、entryとconsumptionは各1件だけ存在する

### Requirement: Missing後だけintentを検証する

exact replayが`Missing`の場合、capabilityは同じatomic section内でcommandのintentが存在し未消費であることを検証しなければならない（MUST）。
intent欠損と既消費を別のtyped failureとして返さなければならない（MUST）。

#### Scenario: consumedだがresultなし

- **WHEN** v2 exact resultがなくintentが既にconsumedである
- **THEN** capabilityはtyped intent-consumed failureを返しflat predicateとmutationへ進まない

#### Scenario: intent欠損

- **WHEN** commandのintent IDに対応するdurable intentが存在しない
- **THEN** capabilityはtyped intent-missing failureを返しconsumptionとledger rowを作らない

#### Scenario: 同一intentの異なるrequest

- **WHEN** 同じintentに異なるv2 fingerprintの2 callが並行する
- **THEN** 最初のcallだけが`Created`となり、他方はexact replayではなくtyped intent-consumed failureを返す

### Requirement: flat predicate はmutationと同じatomic sectionで判定する

新規entryは`OPEN position count == 0 AND risk-increasing open entry order count == 0`の場合だけ許可しなければならない（MUST）。
risk-increasing open entry orderは`side=BUY`かつ`status in (OPEN, PENDING_CANCEL)`とし、protective SELLを数えず、`PENDING_CANCEL`をfill可能なriskとして数えなければならない（MUST）。
predicateの読み取り、entry mutation、intent consumptionは同じin-memory lockまたはPostgreSQL transaction内で行わなければならない（MUST）。

#### Scenario: open position

- **WHEN** exact replayがなく1件以上のOPEN positionがある
- **THEN** capabilityはtyped account-not-flat failureを返しintentを消費しない

#### Scenario: open resting entry

- **WHEN** exact replayがなくOPENまたはPENDING_CANCELのBUY entry orderがある
- **THEN** capabilityはtyped account-not-flat failureを返し新規entryを作らない

#### Scenario: protective orderのみ

- **WHEN** OPEN positionがなくOPEN protective SELLだけがある
- **THEN** protective SELLだけを理由にflat predicateを拒否しない

#### Scenario: 別intentの並行entry

- **WHEN** flat accountへ異なるintent / v2 IDの2 callが並行する
- **THEN** 片方だけが`Created`となり他方はtyped account-not-flat failureを返し、失敗側intentは未消費のままである

#### Scenario: MARKETとrestingの競合

- **WHEN** flat accountへMARKET相当entryとresting entryを並行実行する
- **THEN** 種別にかかわらず一方だけが`Created`となり、他方は同じflat predicateで拒否される

### Requirement: MARKETとrestingは既存paper semanticsでcommitする

capabilityは既存`MarketEntryFillRequest` / `RestingEntryOrderRequest`とpaper writerを再利用しなければならない（MUST）。
MARKET相当entryはFILLED BUY、position、protective STOP、execution、account / equity updateを、resting entryはOPEN BUY、TTL、market eligibility、queue metadataを既存semanticsどおり保存し、それぞれintent consumptionと同時commitしなければならない（MUST）。

#### Scenario: MARKET create

- **WHEN** exact replayがなくintentが未消費でaccountがflatなvalid MARKET相当requestをcommitする
- **THEN** capabilityは既存paper fill semanticsのentry / position / protective STOP / execution / account更新とconsumptionを一度だけ保存する

#### Scenario: resting create

- **WHEN** exact replayがなくintentが未消費でaccountがflatなvalid LIMITまたはSTOP resting requestをcommitする
- **THEN** capabilityは既存paper resting semanticsのorder / TTL / eligibility metadataとconsumptionを一度だけ保存する

#### Scenario: backend write policy rejection

- **WHEN** HARD_HALTまたはpaper baseline不一致がrisk-increasing writeを拒否する
- **THEN** capabilityは既存write policy failureを保持しentryとconsumptionを保存しない

### Requirement: capability はSafetyFloor bypassとして有効化されない

A2a capabilityはprepared paper mutationだけを受け取るinternal storage primitiveであり、production entry surfaceとして有効化してはならない（MUST NOT）。
既存production entryはcommand validation、market preparation、SafetyFloor、cash、symbol、price contractを通る経路を維持しなければならない（MUST）。

#### Scenario: A2a direct production call

- **WHEN** A2aだけがdeployされる
- **THEN** publicまたはrunnerからcapabilityへ到達するcall pathは存在しない

#### Scenario: 5 invariants

- **WHEN** capability実装を追加する
- **THEN** 最大損失、損切り必須、ナンピン禁止、最大ドローダウン停止、エクスポージャー上限を迂回するpublicまたはproduction pathは増えない

### Requirement: failure はpartial stateを成功またはNO_TRADEに変換しない

predicate rejection、intent rejection、writer failureはtyped failureを返し、entryとconsumptionの片方だけを残してはならない（MUST NOT）。
commit結果を確定できないstorage failureはtyped outcome-indeterminateとして扱い、result不存在、NO_TRADE、または安全なretry成功を断定してはならない（MUST NOT）。
PostgreSQL mutation transactionとfresh readback transactionは`maxAttempts=1`で実行し、whole-transactionを自動再実行してはならない（MUST NOT）。

#### Scenario: mutation前のfailure

- **WHEN** PostgreSQL transaction body開始前またはbody完了前に失敗しrollback完了を確認できる
- **THEN** capabilityはtyped unavailable failureを返しentryとconsumptionを残さない

#### Scenario: body完了後のcommit acknowledgement不明

- **WHEN** 全body statement完了marker設定後にcommitまたはacknowledgementが失敗する
- **THEN** capabilityはtransaction bodyを再実行せずtyped outcome-indeterminateとしてfresh exact readbackを一度だけ開始する

#### Scenario: commit成功ACK lossのreadback

- **WHEN** commitは成功したがacknowledgementを失い、fresh readbackがsame v2 resultを`Exact`で復元する
- **THEN** capabilityはmutationを再実行せず`Exact` successを返す

#### Scenario: readback unavailable

- **WHEN** outcome-indeterminate後のfresh exact readback自体が失敗する
- **THEN** capabilityは元のtyped outcome-indeterminate failureを維持する

#### Scenario: readback MissingまたはAmbiguous

- **WHEN** outcome-indeterminate後のfresh exact readbackが`Missing`または`Ambiguous`である
- **THEN** capabilityは未commitを断定せず元のtyped outcome-indeterminate failureを維持する

#### Scenario: transaction attempt count

- **WHEN** mutation bodyまたはfresh readbackがretry可能なDB errorを返す
- **THEN** 各transaction bodyの実行回数は1回を超えない

### Requirement: InMemory failure は全mutable stateをrestoreする

InMemory capabilityはexact replayが`Missing`でintent / flat検証を通過した後、mutation前に全mutable stateのbefore-imageを取得しなければならない（MUST）。
restore対象はorders、positions、executions、account / accountUpdatedAt、decision / lineage auxiliary maps、market eligibility / queue / source maps、market session cursor、equity snapshots、intent consumptionsを含まなければならない（MUST）。
before-image取得からledger / equity publish、consumption append、成功returnまたはcomplete restoreまでは、decision mutex、ledger write lock、equity snapshot lockを連続して保持しなければならない（MUST）。
ledger publish後・consumption append前を含むfailureでは、全3 lockを保持したままbefore-imageへ完全restoreしなければならない（MUST）。
restoreは同時にcommit済みのequity snapshotを削除してはならず（MUST NOT）、全equity mutationが共有するequity snapshot lockで並行commitを除外しなければならない（MUST）。
equity snapshot repositoryは同じprivate lock内でsnapshot / replaceを行うmodule-internalのnon-suspend exclusive transaction helperを提供しなければならない（MUST）。
exclusive helperのcallbackは外部I/O、suspend call、account source、またはledger lockを新たに取得する処理を実行してはならない（MUST NOT）。

#### Scenario: MARKET publish後のfault

- **WHEN** MARKET entryのledger / account / equity publish後かつintent consumption append前のfault seamが失敗する
- **THEN** orders、positions、executions、account、updatedAt、全auxiliary map、equity snapshots、consumptionsはcall前と完全一致する

#### Scenario: resting publish後のfault

- **WHEN** resting order / TTL / eligibility publish後かつintent consumption append前のfault seamが失敗する
- **THEN** order、eligibility、queue、lineage auxiliary、equity snapshots、consumptionsを含む全mutable stateはcall前と完全一致する

#### Scenario: restore中の可視性

- **WHEN** fault restoreを実行する
- **THEN** decision mutex、ledger write lock、equity snapshot lockはrestore完了まで解放されず、次のentry callはpartial stateを観測しない

#### Scenario: DAILY snapshotとの交差

- **GIVEN** `EquitySnapshotRecorder`がaccount sourceを完了してDAILY repository append直前のtest barrierで停止している
- **AND** authorized MARKETがledgerとFILL equity snapshotをpublishした後、consumption append前のfault seamで全3 lockを保持して停止している
- **WHEN** DAILY appendを開始してequity snapshot lockを待機させ、MARKET faultを発生させる
- **THEN** DAILY appendはequity snapshot lockで待機するかrestore完了後にcommitし、そのDAILY snapshotは残る
- **AND** failed MARKETのledger mutation、intent consumption、FILL equity snapshotは存在しない
- **AND** 両処理はdeadlock、timeout、equity snapshot lockからledger lockへのreverse acquisitionなく完了する

### Requirement: backendごとの排他順序を固定する

InMemory capabilityは`decision mutex -> ledger write lock -> equity snapshot lock`の順に取得し、exact replay、intent検証、flat predicate、before-image取得、ledger / equity mutation、consumption publish、成功またはrestoreを全3 lockの内側で完了しなければならない（MUST）。
equity snapshot lock保持中にledger lockを取得してはならず（MUST NOT）、`EquitySnapshotRecorder`はaccount sourceのledger read lockを解放してからDAILY appendのequity snapshot lockを取得しなければならない（MUST）。
PostgreSQL MARKET capabilityは`risk_state -> paper_account -> OPEN positions -> OPEN / PENDING_CANCEL orders`の順にlockしなければならない（MUST）。
realtime eligibility付きresting capabilityは`session advisory -> market_data_sessions row / verify -> risk_state -> paper_account -> positions -> orders`の順にlockし、ledger lock後にsession lockを取得してはならない（MUST NOT）。
同じtransactionでexact replay、intent検証、flat predicate、mutation、consumptionを完了しなければならない（MUST）。

#### Scenario: InMemory stress

- **WHEN** InMemory backendで同一request、同一intentの別request、別intentを反復して並行実行する
- **THEN** deadlockせず各競合はexact / consumed / non-flatの契約どおり一意に収束する

#### Scenario: InMemory equity lock graph

- **WHEN** InMemory authorized entryと`EquitySnapshotRecorder`のDAILY appendを決定的barrier付きで交差実行する
- **THEN** entryはdecision、ledger、equityの順を保ち、recorderはledger readを解放してからequityを取得し、reverse acquisitionなく完了する

#### Scenario: PostgreSQL stress

- **WHEN** 独立connectionのPostgreSQL transactionで同じ競合組合せを反復する
- **THEN** zero-row predicateのphantom insertを許さず一件だけをcommitする

#### Scenario: authorized restingとmarket event

- **WHEN** realtime eligibility付きauthorized restingと同じsessionの`applyMarketEvent`を決定的barrier付きで交差実行する
- **THEN** authorized restingはsessionからledgerの順、market eventはsession rowからledgerの順を保ち、reverse acquisition、deadlock、timeoutなく完了する

#### Scenario: eligibilityなしresting

- **WHEN** resting requestにrealtime market eligibilityがない
- **THEN** capabilityはsession advisory / rowを取得せずMARKETと同じledger lock順を使う

### Requirement: schemaとproduction semanticsを移行しない

A2aはDB schema migration、runtime config、runner status、terminal cause、completion event、Falsifier skip、CONDITIONAL / shadow behaviorを変更してはならない（MUST NOT）。

#### Scenario: deploy

- **WHEN** A2aを既存schemaへdeployする
- **THEN** DDLまたはdata backfillなしで起動し、capabilityはinactiveである

#### Scenario: rollback

- **WHEN** A2bより前にA2a codeをrollbackする
- **THEN** ledger、intent、foundation policy decision/eventを削除または書換えずA1 replay-only behaviorへ戻る
