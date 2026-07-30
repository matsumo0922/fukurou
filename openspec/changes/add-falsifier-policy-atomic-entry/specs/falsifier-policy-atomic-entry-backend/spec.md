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
BUY entryが厳密1件、intent ID一致、non-null trade group、同じclient request IDの全orderが同一trade groupの場合だけ`Exact`を返さなければならない（MUST）。

#### Scenario: consumed intentとexact result

- **WHEN** exact result、consumed intent、open positionまたはopen resting entryが存在する同じrequestを再実行する
- **THEN** capabilityはintentとflat stateを拒否理由にせず`Exact` resultを返しmutationを行わない

#### Scenario: MARKET exact replay

- **WHEN** v2 IDに1件のFILLED BUY entry、同一trade groupのprotective SELL、position、executionが存在する
- **THEN** capabilityは関連するorder / position / executionを一つの`Exact` resultとして返す

#### Scenario: resting exact replay

- **WHEN** v2 IDに1件のOPEN BUY resting entryが存在しintentとtrade groupが一致する
- **THEN** capabilityはそのOPEN orderを`Exact` resultとして返す

#### Scenario: ambiguous replay

- **WHEN** BUY entryが複数、intent不一致、trade group欠損、または別trade groupのrowが同じv2 IDに混在する
- **THEN** capabilityはtyped replay-indeterminate failureを返しintent、ledger、accountを変更しない

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

#### Scenario: mutation前のfailure

- **WHEN** replay / intent / predicate / write policy検証またはpre-commit writerが失敗する
- **THEN** transactionまたはstaged in-memory updateはrollbackしentryとconsumptionを残さない

#### Scenario: commit acknowledgement不明

- **WHEN** PostgreSQL commitの成否をcallerが確定できない
- **THEN** capabilityはtyped outcome-indeterminate failureを返し、callerは同じv2 requestのexact replayでのみ結果を回復できる

#### Scenario: indeterminate retry

- **WHEN** outcome-indeterminate後に同じv2 requestをretryしcommit済みrowが存在する
- **THEN** capabilityは新規mutationより先に`Exact` resultを返す

### Requirement: backendごとの排他順序を固定する

InMemory capabilityはdecision mutexからledger write lockの順に取得し、exact replay、intent検証、flat predicate、staged mutation、consumption publishを両lockの内側で完了しなければならない（MUST）。
PostgreSQL capabilityは既存ledger mutation lock順を使用し、同じtransactionでexact replay、intent検証、flat predicate、mutation、consumptionを完了しなければならない（MUST）。

#### Scenario: InMemory stress

- **WHEN** InMemory backendで同一request、同一intentの別request、別intentを反復して並行実行する
- **THEN** deadlockせず各競合はexact / consumed / non-flatの契約どおり一意に収束する

#### Scenario: PostgreSQL stress

- **WHEN** 独立connectionのPostgreSQL transactionで同じ競合組合せを反復する
- **THEN** zero-row predicateのphantom insertを許さず一件だけをcommitする

### Requirement: schemaとproduction semanticsを移行しない

A2aはDB schema migration、runtime config、runner status、terminal cause、completion event、Falsifier skip、CONDITIONAL / shadow behaviorを変更してはならない（MUST NOT）。

#### Scenario: deploy

- **WHEN** A2aを既存schemaへdeployする
- **THEN** DDLまたはdata backfillなしで起動し、capabilityはinactiveである

#### Scenario: rollback

- **WHEN** A2bより前にA2a codeをrollbackする
- **THEN** ledger、intent、foundation policy decision/eventを削除または書換えずA1 replay-only behaviorへ戻る
