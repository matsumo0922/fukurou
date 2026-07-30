## 1. Internal capability contract

- [ ] 1.1 module-internal `AuthorizedAtomicPaperEntryBackend`、MARKET / resting request、`Exact` / `Created` resultを追加し、public repository / broker contractへ露出しない
- [ ] 1.2 v2 prefix、non-null command intent、command / consumption intent一致を副作用前に検証する
- [ ] 1.3 replay-indeterminate、intent-missing、intent-consumed、account-not-flat、backend-unavailable、outcome-indeterminateをtyped failureとして追加する
- [ ] 1.4 A1 `AuthorizedFalsifierPolicyBoundary`へcapabilityを注入せず、`Missing`が従来の`AuthorizedNewMutationUnsupportedException`を返すことを維持する

## 2. InMemory atomic backend

- [ ] 2.1 `InMemoryDecisionRepository`へdecision mutex内でexact replayを優先できるnon-suspend internal commit helperを追加する
- [ ] 2.2 decision mutexからledger write lockの順で、strict replay、Missing時のintent検証、flat predicateを実行するInMemory adapterを追加する
- [ ] 2.3 `OPEN position == 0 AND BUY (OPEN / PENDING_CANCEL) order == 0`をlocked stateから判定し、protective SELLを除外する
- [ ] 2.4 既存locked writer semanticsを再利用してMARKET相当entryまたはLIMIT / STOP resting entryとconsumptionを同じcritical sectionでpublishする
- [ ] 2.5 fallible処理をpublish前へ寄せ、before-image rollbackによりfailure時にentry / account / equity snapshot / consumptionのpartial stateを残さない

## 3. PostgreSQL atomic backend

- [ ] 3.1 Exposed backendにinternal capabilityを実装し、既存`risk_state -> paper_account -> OPEN positions -> OPEN / PENDING_CANCEL orders`のlock順を再利用する
- [ ] 3.2 同じtransaction内にstrict exact replay readerを追加し、`Exact` / `Missing` / `Ambiguous`をintent / flat判定より先に解決する
- [ ] 3.3 `Missing`後にintent存在 / consumptionとflat predicateを読み、既存write policyを通してMARKET / resting mutationとconsumptionを一括commitする
- [ ] 3.4 rollback確認済みstorage failureとcommit acknowledgement不明をtypedに分け、transaction failureでpartial rowを残さない
- [ ] 3.5 schema migration、index、data backfillを追加せず既存table / unique constraintで実装する

## 4. Replay, failure, and concurrency tests

- [ ] 4.1 InMemoryでMARKET / restingの新規`Created`、consumed / non-flatでも優先される`Exact`、ambiguous replay、intent missing / consumed、protective SELL除外をtestする
- [ ] 4.2 InMemoryで同一request、同一intentの別request、別intent、MARKET対restingをbounded並行実行し、1 mutation / 1 consumptionとtyped loser結果をtestする
- [ ] 4.3 InMemoryのpre-commit failureでledger / account / equity snapshot / consumptionが残らないことをtestする
- [ ] 4.4 PostgreSQLでMARKET / restingの`Created` / `Exact`、ambiguous replay、intent / flat / write policy rejectionとtransaction rollbackをintegration testする
- [ ] 4.5 PostgreSQLの独立connectionで同一request、同一intentの別request、別intent、MARKET対restingを並行実行し、paper account lockがzero-row raceを直列化することをtestする
- [ ] 4.6 commit acknowledgement不明をtyped outcome-indeterminateとし、同一v2 retryがcommit済みresultを`Exact`で回復することをbounded fault testする
- [ ] 4.7 public `Broker` / `PlaceOrderCommand` / MCP schema不変、A1 `Missing` fail-closed、production runnerがOFFでもFalsifierを起動する回帰testを維持する

## 5. Documentation and validation

- [ ] 5.1 `docs/mcp-runtime.md`をA2a capabilityがinactive、A1 boundary未接続、new mutation未有効、A2b / Bまでactivation禁止である現在形へ更新する
- [ ] 5.2 変更したcapability / class / failure名で`docs/`とREADMEをgrepし、stale記述を更新する
- [ ] 5.3 OpenSpec strict validation、関連InMemory / PostgreSQL test、admission isolation regression、detekt、buildをexact HEADで実行する

## Deferred

A2bでA1 authority / fingerprint boundaryと既存command preparation / SafetyFloorをatomic capabilityへ接続する。
Bでruntime activation precondition、runner permit propagation、Falsifier skip、durable status / outcome-unknown mappingを実装する。
CONDITIONAL、shadow、evaluation、live tradingは対象外である。
