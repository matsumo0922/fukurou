## 1. Internal capability contract

- [ ] 1.1 module-internal `AuthorizedAtomicPaperEntryBackend`、MARKET / resting request、`Exact` / `Created` resultを追加し、public repository / broker contractへ露出しない
- [ ] 1.2 v2 prefix、non-null command intent、command / consumption intent一致を副作用前に検証する
- [ ] 1.3 request-scoped replayをBUY entry厳密1件と直接linkするprotective STOPだけに限定し、close / reduce / ADD_LONGと別requestの同一trade group rowを`Exact`へ集約しない
- [ ] 1.4 replay-indeterminate、intent-missing、intent-consumed、account-not-flat、backend-unavailable、outcome-indeterminateをtyped failureとして追加する
- [ ] 1.5 A1 `AuthorizedFalsifierPolicyBoundary`へcapabilityを注入せず、`Missing` fail-closedとpublic place / previewのv2 guardを維持し、close / update / cancelへblanket prefix guardを追加しない

## 2. InMemory atomic backend

- [ ] 2.1 `InMemoryDecisionRepository`へdecision mutex内でexact replayを優先できるnon-suspend internal commit helperを追加する
- [ ] 2.2 decision mutexからledger write lockの順で、strict replay、Missing時のintent検証、flat predicateを実行するInMemory adapterを追加する
- [ ] 2.3 `OPEN position == 0 AND BUY (OPEN / PENDING_CANCEL) order == 0`をlocked stateから判定し、protective SELLを除外する
- [ ] 2.4 orders / positions / executions、account / updatedAt、decision / lineage auxiliary、eligibility / queue / source map、market cursorを含むledger before-imageを取得・完全restoreするinternal protocolを追加する
- [ ] 2.5 `InMemoryEquitySnapshotRepository`とintent consumptionsを同じcritical sectionでsnapshot / replaceできるinternal before-image APIを追加する
- [ ] 2.6 既存locked writer semanticsを再利用してMARKET相当entryまたはLIMIT / STOP resting entryをpublishし、その後にconsumptionをappendする
- [ ] 2.7 ledger / equity publish後・consumption append前のtest fault seamを追加し、failure時に全before-imageを両lock内で完全restoreする

## 3. PostgreSQL atomic backend

- [ ] 3.1 Exposed backendにinternal capabilityを実装し、MARKET / eligibilityなしrestingを`risk_state -> paper_account -> positions -> orders`でlockする
- [ ] 3.2 eligibility付きrestingを`session advisory -> market_data_sessions FOR UPDATE / verify -> risk_state -> paper_account -> positions -> orders`でlockし、ledger後のsession取得を禁止する
- [ ] 3.3 同じtransaction内にrequest-scoped strict exact replay readerを追加し、`Exact` / `Missing` / `Ambiguous`をintent / flat判定より先に解決する
- [ ] 3.4 `Missing`後にintent存在 / consumptionとflat predicateを読み、既存write policyを通してMARKET / resting mutationとconsumptionを一括commitする
- [ ] 3.5 mutation transactionを`maxAttempts=1`とbody-completed markerで囲み、rollback確認済みpre-body / pre-commit failureをUnavailable、body完了後またはrollback不明をOutcomeIndeterminateに分類する
- [ ] 3.6 OutcomeIndeterminate直後に`maxAttempts=1`のfresh strict readbackを一度だけ実行し、`Exact`なら回復、unavailable / `Missing` / `Ambiguous`ならindeterminateを維持する
- [ ] 3.7 schema migration、index、data backfillを追加せず既存table / unique constraintで実装する

## 4. Replay, failure, and concurrency tests

- [ ] 4.1 InMemory / PostgreSQLでrequest-scoped protective STOPだけが`Exact`となり、同じIDのclose / reduce / ADD_LONG / malformed STOPが`Ambiguous`となることをtestする
- [ ] 4.2 同一trade groupの別request ADD_LONG / close / executionをoriginal replay resultへ集約しないことを両backendでtestする
- [ ] 4.3 InMemoryでMARKET / restingの新規`Created`、consumed / non-flatでも優先される`Exact`、intent missing / consumed、protective SELLのflat除外をtestする
- [ ] 4.4 InMemoryで同一request、同一intentの別request、別intent、MARKET対restingをbounded並行実行し、1 mutation / 1 consumptionとtyped loser結果をtestする
- [ ] 4.5 InMemoryのledger / equity publish後・consumption前faultで全ledger field、auxiliary map、market cursor、equity snapshot、consumptionがbefore-imageと一致することをtestする
- [ ] 4.6 PostgreSQLでMARKET / restingの`Created` / `Exact`、intent / flat / write policy rejectionとtransaction rollbackをintegration testする
- [ ] 4.7 PostgreSQLの独立connectionで同一request、同一intentの別request、別intent、MARKET対restingを並行実行し、paper account lockがzero-row raceを直列化することをtestする
- [ ] 4.8 eligibility付きauthorized restingと`applyMarketEvent`をdeterministic barrierで交差させ、sessionからledgerの順でdeadlock / reverse acquisitionなく完了することをtestする
- [ ] 4.9 pre-body / pre-commit rollbackをUnavailable、commit成功ACK lossとfresh readback成功を`Exact`、readback unavailable / `Missing` / `Ambiguous`をOutcomeIndeterminateとしてtestする
- [ ] 4.10 attempt counterでmutation bodyとfresh readbackが各1回だけ実行され、whole-transaction自動retryがないことをtestする
- [ ] 4.11 public close / update / cancelのreserved prefixがrisk-reducing availabilityを維持し、nonprotective same-ID rowがreplayを`Ambiguous`にすることをtestする
- [ ] 4.12 public `Broker` / `PlaceOrderCommand` / MCP schema不変、A1 `Missing` fail-closed、production runnerがOFFでもFalsifierを起動する回帰testを維持する

## 5. Documentation and validation

- [ ] 5.1 `docs/mcp-runtime.md`をA2a capabilityがinactive、A1 boundary未接続、new mutation未有効、A2b / Bまでactivation禁止である現在形へ更新する
- [ ] 5.2 変更したcapability / class / failure名で`docs/`とREADMEをgrepし、stale記述を更新する
- [ ] 5.3 OpenSpec strict validation、関連InMemory / PostgreSQL test、admission isolation regression、detekt、buildをexact HEADで実行する
- [ ] 5.4 human-authored implementation diffが1,000行を超える場合はA2a contractを保ったbackend別stackへ分割し、A2b接続を同じPRへ混ぜない

## Deferred

A2bでA1 authority / fingerprint boundaryと既存command preparation / SafetyFloorをatomic capabilityへ接続する。
Bでruntime activation precondition、runner permit propagation、Falsifier skip、durable status / outcome-unknown mappingを実装する。
CONDITIONAL、shadow、evaluation、live tradingは対象外である。
