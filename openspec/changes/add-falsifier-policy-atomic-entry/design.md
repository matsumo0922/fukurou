## Context

A1 `add-falsifier-policy-authority-boundary` はdurable OFF authority、v2 fingerprint、public namespace guard、strict exact replayをinactive internal boundaryへ追加する。
exact resultがない場合は`AuthorizedNewMutationUnsupportedException`でfail closedとなり、production runnerは全entryでFalsifierとpublic broker pathを使う。

現行public entryは、MARKET相当entryとresting entryの双方でledger mutationとintent consumptionをまとめる。
InMemoryは`InMemoryDecisionRepository`のmutexからledger state write lockへ入り、PostgreSQLは`risk_state`、`paper_account`、OPEN position / orderをlockするtransactionで書き込む。
ただしA1 authorized pathには、同じ原子境界でexact replay、consumed intent、flat predicateを解決して新規mutationするbackend capabilityがない。

full A2はA1 boundary接続、SafetyFloor preparation、両backend capability、concurrency testまで含めると1,250〜1,500行と見積もられ、1 PRの目安1,000行を超える。
このchangeはA2aとしてbackend capabilityとその直接testだけを扱う。
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

### 4. flatはopen positionとfill可能なBUY orderの不存在で定義する

flat predicateは次のANDで固定する。

- `PositionStatus.OPEN`のpositionが0件
- `side == BUY`かつ`status in (OPEN, PENDING_CANCEL)`のorderが0件

`PENDING_CANCEL`は取消完了前にfillし得るためriskとして数える。
protective STOP / take-profitのSELL orderは単独ではriskを増やさないため数えない。
exact replayはこのpredicateより先なので、replay対象自身が作ったOPEN position / BUY orderは拒否理由にならない。

intent consumptionだけをsingle-entry gateにする案は、異なるintentの並行entryを防げないため採用しない。
SafetyFloor snapshotだけでflatを判定する案は、snapshot取得後のraceを閉じないため採用しない。

### 5. InMemoryはdecision mutexからledger write lockの順で一つのcommit sectionを作る

InMemory adapterは`InMemoryDecisionRepository`と`InMemoryPaperLedgerRepository`の組合せだけを受理する。
lock順は既存public entryと同じ`decision mutex -> ledger state write lock`に固定する。
ledger write lock内でstrict replayとflat predicateを読み、decision mutex内のintent存在 / consumed stateを使う。
MARKET / resting updateとconsumption appendが完了するまで両lockを解放しない。

既存`consumeIntentAfterLedgerWrite`はledger callbackより前にconsumedを拒否するため、そのまま使わない。
新しいinternal helperはdecision mutex内でexact replay結果を優先できる非suspend commit callbackを提供する。
callbackはledger write lock内で、intent未消費確認、staged ledger publish、consumption appendを一続きに実行する。

MARKET updateは既存locked helperとdomain変換を再利用する。
fallible validationとresult組立てをstate publishより前へ寄せ、test fault seamはpublish直前だけに置く。
publish開始後に外部I/Oを行わず、entry rows、account projection、equity snapshot、consumptionを同じcritical sectionで追加する。
unexpected failureではbefore-imageを復元し、entryまたはconsumption片側だけを残さない。

ledger lockからdecision mutexを取る逆順案は、public entryとのdeadlockを作るため採用しない。
ledgerとdecisionを別々にcommitして補償削除する案は、paper truthを書換えるため採用しない。

### 6. PostgreSQLはpaper account rowをglobal entry serialization pointにする

Exposed backendは既存`lockPaperLedgerMutationRows()`のauthority順を維持する。

1. `risk_state`
2. singleton `paper_account`
3. OPEN positionsをID順
4. OPEN / PENDING_CANCEL ordersをID順

zero-row predicateへの同時insertはposition / order row lockだけでは防げない。
全risk-increasing entry writerが先にsingleton `paper_account`をlockする既存契約を使い、2つ目のtransactionを待機させる。
READ COMMITTEDでは待機後のreplay / predicate queryが先行commitを観測する。

同じtransactionでstrict replay query、intent存在 / consumption query、flat predicate、write policy、entry write、consumption insertを実行する。
MARKETは既存`insertEntryFill`、restingは既存`insertEntryOrder`を再利用する。
intent consumptionのunique indexを最後の防御として維持し、transaction rollbackによりpartial rowを残さない。

SERIALIZABLEへ全writerを変更する案は、既存transaction全体のretry semanticsを広げるため採用しない。
advisory lockを新設する案は、既存paper account lockと二重のauthorityになるため採用しない。

### 7. capabilityは既存paper semanticsを再利用するがSafetyFloorの代替にしない

MARKET相当requestは既存どおりFILLED BUY order、OPEN position、protective STOP、execution、account / equity snapshotを作る。
crossing LIMITはA2bのpreparationでMARKET相当requestになり、non-crossing LIMITとSTOPはresting requestになる。
resting requestは既存TTL、market eligibility、queue snapshot metadataを保存する。
HARD_HALT、paper baseline、execution lineageはbackend write policyを再利用してtransaction内で再検証する。

5不変条件全体の判定は既存SafetyFloorの責務であり、A2a capability単独では代替しない。
そのためA2aをpublic / runnerへ接続せず、A2bは既存SafetyFloorを通したprepared request以外を渡せない構造にする。
新しい簡略SafetyFloorやOFF専用の例外規則は追加しない。

### 8. failureはrejection、unavailable、outcome-indeterminateを分ける

typed failureは少なくとも次を区別する。

- replay ambiguous / corrupt: `AuthorizedAtomicEntryReplayIndeterminateException`
- intent missing: `AuthorizedAtomicEntryIntentMissingException`
- intent consumed: `AuthorizedAtomicEntryIntentConsumedException`
- account non-flat: `AuthorizedAtomicEntryNotFlatException`
- transaction開始前またはrollback確認済みstorage failure: `AuthorizedAtomicEntryUnavailableException`
- commit acknowledgementを確定できないfailure: `AuthorizedAtomicEntryOutcomeIndeterminateException`

deterministic rejectionとrollback確認済みfailureはentry / consumptionを残さない。
outcome-indeterminateはresult不存在またはNO_TRADEを意味せず、同じv2 requestのretryだけがexact replayで回復できる。
A2aはこの型をrunner status / terminal causeへmappingしない。
durable mappingと運用復旧はBで扱う。

generic `IllegalStateException`だけを返す案は、consumed、non-flat、storage不明をcall側が文字列判定することになるため採用しない。
commit不明を安全なfailureとして扱う案は、実際にはcommit済みのentryを見落とすため採用しない。

### 9. concurrency matrixを両backendで同じにする

test matrixはMARKET / resting双方について次を固定する。

- 同じintent / 同じv2 ID: `Created` 1件 + `Exact` 1件
- 同じintent / 異なるv2 ID: `Created` 1件 + intent-consumed 1件
- 異なるintent / 異なるv2 ID: `Created` 1件 + account-not-flat 1件
- MARKET対resting: `Created` 1件 + account-not-flat 1件
- exact result + consumed + non-flat: `Exact`
- pre-commit failure: entry 0件、consumption 0件
- commit acknowledgement不明後の同一retry: commit済みなら`Exact`

InMemoryはbounded coroutine stress、PostgreSQLは独立connection / transactionのintegration testを使う。
反復回数はraceを観測できる小さな固定値とし、新しい汎用chaos frameworkは追加しない。

## Risks / Trade-offs

- [A2a capabilityを誤ってproductionから呼ぶ] → A1 boundaryへ注入せず、runner / public call graph不変testを置く
- [同一requestがconsumed / non-flatで拒否される] → lock / transaction内のstrict replayを最初に固定する
- [zero-row predicateを2 transactionが通過する] → 全entry writerが取得するsingleton paper account rowで直列化する
- [InMemoryのlock順が逆転する] → decision mutexからledger write lockだけを許可し、concurrency testでdeadlockを検出する
- [InMemory failureがpartial stateを残す] → fallible処理をpublish前へ寄せ、before-image rollbackとpre-commit fault testを置く
- [capabilityがSafetyFloor bypassになる] → A2aをinactiveに保ち、A2bで既存preparation / SafetyFloor pathだけへ接続する
- [PENDING_CANCELをflatと誤認する] → fill可能なBUYとしてpredicateに含める
- [commit不明をNO_TRADE扱いする] → typed outcome-indeterminateに限定しrunner mappingをBへdeferする
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
