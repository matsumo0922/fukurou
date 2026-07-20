## Context

Issue #192 は Epic #180 の B1 であり、統計的な成績証明ではなく、現行 `PAPER_WS_V1` が production の通常運転と代表的な2障害を通っても paper truth を壊さないことを future-only に実証する。

現行実装には必要な永続境界がすでにある。

- `ProtectionReconciler.runMarketEventLoop()` は WebSocket session ごとに `market_data_sessions` を開始し、transport failure を `markDisconnected()` と `applyGapImpact()` へ渡した後に再接続する。
- `ExposedMarketDataIntegrityRepository` は `market_data_gaps` を作り、gap 時点の resting BUY order を取消し、open order、open position、関連 decision run を `evaluation_exclusions` へ保存する。
- `ProtectionReconcilerWorker` の startup bootstrap は `recoverStaleSession()` を呼び、前 process が残した `CONNECTED` session を `PROCESS_RESTART` gap にし、未適用 impact も同じ transaction で完了する。
- `ExposedPaperLedgerWriter` は realtime event の durable receipt を execution authority として検証し、execution へ `source_session_id`、`source_sequence`、取引所時刻、socket 受信時刻、side、price、size を保存する。
- `ExposedEvaluationRepository` は `evaluation_exclusions` に存在する position を closed-trade 母集団から外し、API は active epoch + `CURRENT` を既定 scope として exclusion summary を返す。
- production の同一 JVM は WebSocket と複数の GMO REST client を持ち、5秒周期の reconciler REST polling も同じ `api.coin.z.com:443` を使う。host上の PID、remote endpoint、4-tuple安定性、byte countではWebSocket socketを一意に証明できないため、network/socket推定は使わない。WebSocket切断はapplicationが所有するactive session identityへ一時的なone-time ops seamを通じて指示する。

この change は既存 contract を新しい runtime mechanism で置き換えない。production 操作と観測の境界だけを仕様化し、結果を secret-free evidence として固定する。

## Goals / Non-Goals

**Goals:**

- resting entry 中の WebSocket transport 切断を1回だけ行い、gap impact、取消、除外、再接続を同じ session/gap identity で追跡する。
- open position 中の process restart を1回だけ行い、stale session recovery、position/decision-run 除外、新 session での realtime 管理再開を追跡する。
- 各 arm の前後で revision、account epoch、runtime config identity、container/process/session/injection identity、対象 entity、receipt、execution、gap、exclusion、CURRENT evaluation scope を照合する。
- hard fail と観測不能を分離し、どちらも成功へ読み替えない。
- fill 件数や相場 regime を完了条件にせず、通常 soak を継続可能な状態へ戻す。

**Non-Goals:**

- 恒久的に利用可能な fault-injection API、runtime config、DB table、dashboard、alert、root-installed helper を残すこと。
- stale、overflow、reorder、複数回反復の障害 matrix を実施すること。
- gap 中の市場履歴から execution を再構成すること、既存 row を backfill/rescale すること。
- closed trade 数、profit factor、勝率、regime coverage を Issue #192 の完了条件にすること。
- production で hard fail が見つかった場合に、この change 内で application code を修正すること。
- change-local な1回限りの operator procedure を main OpenSpec capability として永続化すること。

## Decisions

### 1. 2 arm を直列化し、各 arm を immutable identity へ束縛する

（agent 仮決め）`implementation-evidence.md` は `WS-DISCONNECT` と `PROCESS-RESTART` の2 arm を持つ。各 arm は次を mutation 前に保存する。

- production revision、immutable image digest、container ID/開始時刻
- active account epoch ID、active runtime config version ID/hash、`PAPER` mode
- current market-data session ID、last processed sequence、latest receipt admission ordinal
- unresolved market-data gap 数
- 対象 order/position/decision-run の ID、status、epoch、execution semantics、作成/更新時刻
- active `llm_runs` / launch reservation、runtime config mutation、deploy/backup/restore maintenance の有無
- active epoch、runtime config、paper account baseline の一致と、次回 backup/restore systemd timer が arm の最大実行窓外にあること

arm は直列に行い、前 arm が復旧・判定まで終わる前に次 armへ進まない。preflight と mutation の間に対象 entity が fill/cancel/close された場合は injection 前なら待機へ戻し、injection 後ならその arm を `INVALID` として停止する。自動再注入はしない。

対象以外への影響を小さくするため、`WS-DISCONNECT` は `OPEN` の resting BUY entry が1件以上かつ open position 0件、`PROCESS-RESTART` は open position が1件以上かつ `OPEN` / `PENDING_CANCEL` の resting BUY entry 0件のときだけ実施する。preflight inventory は対象を固定するだけであり、authoritative affected inventory は対象 gap の `evaluation_exclusions` と `impact_applied_at` 直後の order/position/run state から再構成する。preflight から impact までに追加 entity が生じた場合は全件を evidence に残し、arm を `INVALID` として次 armへ進めない。

（反証反映済み・要ユーザー確認）各 mutation 直前に、操作、最大停止/復旧窓、preflight対象、予想される `orders` mutation、不可逆な `evaluation_exclusions`、Epic #181 の評価母集団への影響を提示し、owner の明示 go/no-go を得る。承認は arm ごとに行い、設計承認や falsifier 通過を production mutation の承認へ読み替えない。

### 2. WebSocket 切断は application-owned session への一時的 one-time seam とする

（ユーザー確認済み・反証後の人間判断）`GmoPublicWebSocketMarketEventStream` は現在の `GmoMarketEventSession` を `AtomicReference` で保持し、session開始時に登録、通常closeまたはterminal claim時に同一identityをcompare-and-clearする。sessionは自身のJDK `WebSocket` と `GmoWebSocketListener` を1:1で保持する。`Application.module`でapplication-scoped holderを作り、先に構築するroutingと後で開始するworkerへ同じholderを渡し、worker compositionで生成したstreamの切断interfaceをholderへ設定する。process-global mutable singletonは作らない。fault controllerはoperatorが渡した `expectedSessionId` とactive session IDが厳密に一致する場合だけ、session-local one-shot CASの後に `WebSocket.abort()` を呼び、abortが正常returnした同じmethod内でtyped `InjectedWebSocketDisconnectException` をlistenerの既存 `sendTerminalFailure` boundaryへ渡す。これにより実socketを閉じ、productionのlistener terminal claim、message channel failure、`session.receive()`、`recordMarketDataGap()`というdownstream経路を即時に通す。REST client、他session、container network、public origin、PostgreSQL path、application processには触れない。session不一致、active session不在、既処理のinjection IDはmutationなしのtyped conflictにする。

このarmが保証するのは「実WebSocket socketをlocal abortし、production listenerのterminal-failure boundary以降を通したときのpaper semantics」である。GMO peerやkernelが自然にlistener callbackをdispatchする部分は合成せず、検証済みとも主張しない。proving testはabort単独がchannel終端を保証しないこと、combined abort-and-terminal操作が`DISCONNECTED`を即時に1回だけ届けること、通常の`onError`/`onClose`も同じprivate terminal claimを使うことを固定する。

seamは常設しない。`FUKUROU_ISSUE_192_WS_FAULT_ENABLED` はdefault `false`のtemporary deployment flagとし、false時はcontrollerとrouteを構築しない。trueの検証revisionだけ `POST /ops/issue-192/ws-disconnect` をOpenAPIから`.hide()`して登録する。requestは `injectionId`、`expectedSessionId`、固定purpose `ISSUE_192_WS_DISCONNECT`、owner承認を結びつけたreasonを持つ。外部入口は既存Cloudflare Accessの `/ops/*` policyで保護する。個人が単独で使うhobby systemであるため、専用token、capability file、Dockerfile変更、OpenAPI operation、WebUIは追加しない。内部processからrouteを呼べる残余リスクは、短期間の有効化、exact session照合、後述のdurable one-shot gateで影響を1回の切断に限定したうえで受容する。

temporary `docker-compose.prod.yml` はflagだけをcontainerへ渡す。verification前にNAS `.env` でflagをtrueにして検証revisionをdeployする。cleanupはseamとcompose flagを削除したrevisionをdeployし、routeが404であることを確認してからNAS `.env` entryを削除する。追加・撤去の各deployは通常のsigned bundle、quality、infrastructure-gap contractを通す。

controllerはglobal trading lockを取得しない。controller-local `Mutex` でrequestを直列化し、request shape、final preflight対象、active session一致などmutationなしで拒否できる全条件をbounded read-only queryで先に確定する。one-shot authorityは`command_event_log`全走査ではなく、requested event ID `588ce39f-90ec-4479-9430-f22a6d0356a9` と executed event ID `0367f844-595a-4ed7-8480-43a1d3e5df6c` のprimary-key lookup最大2回で判定する。起動時と各request時にどちらかが存在すればinjection IDを問わずglobal consumedとして全requestをmutationなしのtyped conflictにする。lookupがtimeout、DB unavailable、conflicting type/payloadなどでcomplete truthを返せなければfail closedのtyped unavailableとし、routeはabortを呼ばない。これがtable sizeに依存せずarm 2 restart後にも残るauthoritative one-shot gateである。

固定IDが両方不存在の場合だけ、requested fixed IDでsecret-free auditをdurable appendする。requested/executed両eventの`toolName`は固定値`issue192-ws`、`clientRequestId`は各event IDと同じbare UUIDとし、`injectionId`と`expectedSessionId`はpayloadへ保存して既存`ManifestPersistencePolicy.validateCommandEvent`を通す。active sessionのcompare-and-abort-and-terminalが成功した場合だけexecuted fixed IDを追記する。duplicate primary key、response喪失、retry、process restartではfixed-ID gateにより再実行せず既存状態を返す。requested audit後・abort前のsession race/CAS failure/process crash、またはabort後・executed audit前の失敗は`UNKNOWN`でarmを恒久終了し、audit rowを削除せず再注入しない。人間判断で再計画できるのはrequested auditがまだ存在しないpre-audit rejectionだけである。read-only preflightとabortはDB/network跨ぎでatomicにできないため、その間のentity state変化はpost evidenceで`INVALID`にし、trading lockやrisk-reducing operationを待たせない。

owner承認後、operatorはfinal preflightで固定したsession IDと新規injection IDを1回送る。typed exception classとmessageは固定し、messageを`issue-192 injected websocket disconnect`として`toGapReason()`の`sequence gap`部分一致を避ける。成功responseまたはdurable executed auditの時刻から、preflight sessionに紐づくgapと `impact_applied_at` をbounded pollする。combined abort-and-terminal operationの対象gap reasonは`DISCONNECTED`だけを受理する。gapはsession identityでjoinし、自然callbackがterminal claimを先取した場合もあるためdetailのexception classはPASS条件にしない。`TRANSPORT_LIVENESS_LOST`、`DATABASE_FAILURE`、`INVALID_MESSAGE`、`SEQUENCE_GAP`その他のreason、またはtarget以外のgapは全件を保存してarmを`INVALID`または`UNKNOWN`とし、復旧確認後に停止する。silentに期待reasonへ正規化せず再注入しない。`evaluation_exclusions.reason`は同じ `MarketDataGapReason.name` と一致させる。

待機上限は active `gmoPublic.websocketTransportLivenessTimeout` + 2 × active reconnect backoff + 30秒とし、5分を hard cap とする。active値を取得不能、計算不能、または算出値が5分を超える場合は注入しない。abort要求後は、PASS/INVALID/UNKNOWNのどの候補でも、通常運転の復旧確認が完了するまでarmを終了しない。

復旧後は同じ process/container開始時刻と同じnetwork attachmentのまま新しい market-data session が `CONNECTED` になり、その session の durable receipt が1件以上保存され、対象 gap の `recovered_at` が非nullになるまで待つ。旧 target resting order は `MARKET_DATA_GAP` で取消済みかつ execution 0件でなければならない。preflight boundaryからrecoveryまでに作られた `market_data_gaps` はpreflight session上の対象1件だけでなければPASSにしない。追加gapは全件記録し、armを`INVALID`として次armを止める。

network detachはWebSocketが別networkから生存し得るため不採用とする。host socket destroyは同一JVM・同一host:portの定常REST接続とWebSocketを識別できず、`nsenter` / `ss -K` / PID意味論にも依存するため不採用とする。network-wide `iptables` / `nftables` はhost上の別workloadやRESTまで巻き込むため不採用とする。WebSocket endpointのruntime config変更はprocess restartと追加config epochを混ぜるため不採用とする。恒久fault endpointもrisk-increasing surfaceを残すため不採用とし、検証後にseamを削除する。

### 3. Process restart は同一 container/image の planned restart とする

（agent 仮決め）`PROCESS-RESTART` arm は対象 open position、active session、container ID、image digest、開始時刻を保存した後、`fukurou-ktor` を1回だけ restartする。image replacement、deploy、runtime config activate/rollback、PostgreSQL restart は同時に行わない。

restart 後は container ID と image digestが不変、開始時刻だけが前進していることを確認する。旧 session には `PROCESS_RESTART` gap、`impact_applied_at`、対象 position/decision-run の exclusion が存在し、新 session は旧 session と異なる ID で `CONNECTED` にならなければならない。最初の post-restart durable receipt と `recovered_at` を確認して realtime 管理の再開を確定する。

対象 position は open のままでも、新 session の realtime receipt に基づく protective execution で closed になってもよい。closed になった場合、execution は新 session/sequence の receipt と一致しなければならない。REST price、gap 中の時刻、旧 session の未処理 event を根拠にした execution は受理しない。

restart 後に readiness が5分以内に戻らない、image/container identity が変わる、または stale session recovery が完了しない場合、再restartで結果を上書きしない。既存の reviewed deploy recovery手順で通常運転を復旧し、復旧後の `CONNECTED` session、receipt、unresolved gap 0、readiness/public connectivityを確認してからarmを `INVALID` または `HARD_FAIL` として停止する。

### 4. Lineage は execution と durable receipt の完全 join で判定する

（agent 仮決め）各 arm の preflight timestamp から recovery evidence timestamp までに新規作成された `PAPER_WS_V1` execution を全件列挙し、次を照合する。

- account epochとruntime config hashが記録されている。
- `source_session_id`、`source_sequence`、`source_exchange_at`、`source_received_at`、side、price、sizeが全て非nullである。
- `(source_session_id, source_sequence)` に対応する `paper_market_event_receipts` がちょうど1件あり、source timestamp、socket observed timestamp、side、price、sizeがexecution evidenceと一致する。
- source session は `market_data_sessions` に存在し、その event は order/position の causal eligibility boundaryを満たす。

execution が0件の場合も total=0 として記録し、「fill pathを観測した」とは記載しない。ただし対象 resting orderにexecutionがないこと、post-recovery receiptが存在すること、違反executionが0件であることは別々に判定する。fill件数を要求しない Issue #192 の境界を維持しつつ、空集合をpositive fill evidenceへ偽装しない。

### 5. Gap impact と CURRENT KPI 非混入を DB と API の二面で確認する

（反証反映済み agent 決定）各 gap について `market_data_gaps` の reason、started/impact/recovered時刻を保存する。authoritative affected inventoryは対象gap IDに紐づく `evaluation_exclusions` の `ORDER`、`POSITION`、`DECISION_RUN` と、impact直後の対象order/position/run stateから再構成し、preflight inventoryとの差分を明示する。exclusionの `reason` は対象gapの `MarketDataGapReason.name` と一致しなければならない。preflight boundaryからrecoveryまでに追加gapまたは追加affected entityが生じたarmはPASSにしない。

`GET /evaluation/summary` は preflight で固定した active epoch IDと `CURRENT` cohort、arm を含む明示期間で取得し、resolved scopeとexclusion summaryを保存する。DBでは `ExposedEvaluationRepository` と同じ `NOT EXISTS evaluation_exclusions(entity_type='POSITION')` 条件により、影響 position が strategy-eligible closed trade集合に現れないことを確認する。positionがまだopenである場合は「closed KPI 非混入は未発生、durable exclusionは存在」と記録し、将来のclosed tradeを現在の成功証拠として捏造しない。

read-only rehearsalでactive epoch baseline、`paper_account.initial_cash_jpy`、active runtime `paper.initialCashJpy` の一致を事前に確認する。API/query failure、上限超過、scope mismatch、baseline mismatch、epoch/config identity変化は `UNKNOWN` とし、hard fail 0件には数えない。`UNKNOWN` が1項目でも残る arm は完了しない。

### 6. Verdict は `PASS` / `HARD_FAIL` / `INVALID` / `UNKNOWN` を分離する

（ユーザー確認済み）次のいずれかを1件でも観測した arm は `HARD_FAIL` とし、soak 継続・次 arm・Issue closeへ進まない。

1. gap 中の価格履歴、REST price、旧 session の未受理 eventから retroactive execution が作られる。
2. receipt/gap/exclusion/target entity の結果が欠けたまま、fill、cancel、close、recovery、KPI eligibilityのいずれかが正常として確定される。
3. exclusion対象の position/decision run が current strategy KPI のeligible母集団に混入する。

preflight競合、operator command failure、追加gap/affected entity、unrelated deploy/config/backup change、観測上限超過は system correctness の反例と断定せず `INVALID` または `UNKNOWN` とする。ただしPASS以外でも通常運転の復旧確認を必須とし、`CONNECTED` session、post-recovery receipt、unresolved gap 0、readiness/public connectivityの全てが揃うまでarmを終了しない。gap永続化retryが収束しない場合は既存のreviewed deploy recoveryへ移る。requested fixed auditより前にmutationなしで拒否された場合だけ、新しいowner判断で再計画できる。requested audit以後は実障害回数にかかわらず自動・手動とも再注入せず、Issue #192を`UNKNOWN`または`INVALID`として終了する。

2 arm が `PASS`、hard fail 0、unknown 0、通常 network/readiness/market-data状態へ復旧したときだけ Issue #192 の注入 DoD を満たす。通常 soak 自体はその後も継続し、closed trade数やregime数をこの verdictへ混ぜない。

## Risks / Trade-offs

- [seamが誤ったsessionまたは複数sessionを切断する] → application-owned active sessionをexpected IDでcompare-and-abortし、不一致、active不在、消費済みinjection IDはmutationなしのtyped conflictにする。REST transportをcontrollerへ渡さない。
- [request retryまたはarm 2 restart後に2回切断する] → controller-local Mutexと2つの固定primary-key audit IDによるbounded global gateで冪等化し、lookup不確実性はfail closed、response喪失やrestart後は既存結果を返す。requested audit後の失敗は`UNKNOWN`としてarmを焼き切り再注入しない。
- [local abortがlistener terminal callbackを起こさず150秒待つ] → socket abort成功後に同じsessionがproduction listenerのprivate terminal claimへtyped failureを1回渡すcombined operationとする。downstream boundaryは検証するがpeer/kernel callback dispatchは保証から外す。
- [controllerがtrading lockを塞ぐ] → controllerはglobal trading lockを一切取得せず、bounded read、audit、abortをlocal Mutexだけで直列化する。risk-reducing処理とのraceはpost evidenceで`INVALID`にし、safety処理を待たせない。
- [abortとresting fillが競合する] → mutation直前、impact直後、recovery後にtarget rowを再読する。gap開始前の正当fillはarmを`INVALID`、gap開始後の非causal fillは`HARD_FAIL`として分ける。
- [abort時に既受理tradeがchannel bufferへ残る] → terminal failureより前に処理され得るfuture-only receipt/executionを全件列挙し、gap開始前のcausal fillなら既知`INVALID`とする。buffer空をPASS根拠にせず、自動再注入しない。
- [restart直前にLLM runが開始する] → active run/reservationを直前に二重確認し、post evidenceにunrelated interrupted runがあればarmを`INVALID`にする。新しいlaunch subsystemは追加しない。
- [preflight後にbackup/restore timerが発火する] → 次回timer時刻と最大arm windowを照合し、重なる窓では実行しない。実行中にmaintenanceが始まった場合はarmを`INVALID`にして復旧確認を完了する。
- [open positionがrestart直後の最初のtradeでSTOP/TPに到達する] → close自体を失敗にせず、新sessionのdurable receiptとの完全joinを要求する。
- [影響positionがevidence window内にcloseせず、KPI anti-joinをpositiveに観測できない] → durable exclusionとcurrent API summaryを保存し、closed KPI非混入は未発生と明記する。fill/closeを完了条件にしない。
- [temporary seamがproductionに残る] → default-false flagなしではrouteを構築せず、2 armのevidence取得後にcontroller、route、compose flag、docsを削除したrevisionをdeployし、route不存在と通常reconnectを確認してからchangeを完了する。既存rowのread pathを壊さないようaudit enumは残す。
- [同一process内のcomponentがtemporary routeを直呼びする] → 個人単独のhobby systemでは専用credentialを追加せず残余リスクとして受容する。exact session照合とdurable one-shot gateにより影響は最大1回の切断に限定し、検証後ただちにrouteを削除する。
- [追加/撤去deployが別のPROCESS_RESTART exclusionを作る] → 両deployはresting BUY 0かつopen position 0を既定gateとし、満たせない場合はexact inventoryと不可逆影響への別owner承認を必須にする。各deploy gapをarm evidenceと混ぜない。
- [検証期間中の別deployでrevision/seamが変わる] → 個人運用としてverification deployからcleanup deployまで他のdeployを行わず、各preflightでrevision/imageを再照合する。72時間以内に次preconditionが現れなければcleanupを先行し、後日新changeとして再計画する。requested audit後は再注入せずcurrent verdictとcleanupへ進む。
- [gap/exclusionがEpic #181の母集団を恒久的に縮小する] → mutation直前にexact affected inventoryと影響をownerへ提示し、arm単位の明示go/no-goが無ければ実行しない。除外rowを削除して成績へ戻さない。

## Migration Plan

1. OpenSpec artifactsを検証し、clean-context falsifierで5ベクトル、scope、未検証前提を反証する。blockingはproduction操作前に設計へ反映し、同じfalsifierで再確認する。
2. minimal seam、combined abort-and-terminal、global one-shot audit gate、exact-session/idempotency/audit tests、default-false compose flag、deploy enable/disable手順を実装する。通常revisionではroute不在、検証revisionでもsession不一致・audit failure時にabortしないこと、controllerがglobal trading lockを取得しないことをproving testにする。
3. resting BUY 0かつopen position 0を確認し、NAS `.env` のtemporary flagをtrueにしてseam入りrevisionを通常signed quality/deploy gateでdeployする。flat gateを満たせない場合はdeploy固有のexact inventoryと不可逆exclusionへのowner承認を得る。cleanup完了または72時間の早い方まで個人運用で他のdeployを行わない。productionを変更しないread-only rehearsalで、revision、route有効状態、2 fixed audit primary keysの不存在とbounded latency、query failure時のfail-closed、active runtime値、baseline一致、timer窓、SQL/API evidence query、既存deploy recoveryを確認する。raw payloadはartifactへ保存しない。
4. 通常soakで `WS-DISCONNECT` preconditionを待ち、exact affected inventoryと不可逆な除外影響へのowner go/no-goを得てからexpected sessionへ1回だけ実行し、復旧・evidence・verdictを完了する。
5. 通常soakで `PROCESS-RESTART` preconditionを待ち、同様にarm固有のowner go/no-goを得てから1回だけ実行し、復旧・evidence・verdictを完了する。
6. `implementation-evidence.md` に2 armの有限inventory、query結果、hard fail 0、注入auditとtarget gap IDのjoin、preflight admission ordinal以後のreceipt全件と未dispatch residual、buffer先行event、peer/kernel callback dispatchなどresidual未観測事項を記録する。arm 2後は対象positionがopenのままでも完了できるため、cleanup deployはdeploy固有owner承認と追加exclusion inventory提示を既定とし、flat stateなら承認を省略できる。controller/routeとcompose flagを削除し、historical rowを読むaudit enumとactivity catalogのlabel/descriptionだけを残したrevisionをreview/deployする。route不存在と通常WebSocket再接続を確認後、NAS `.env` entryを削除し、Issue #192へ要約を返す。

abort armにnetwork rollbackは不要であり、applicationの通常reconnectを復旧経路とする。restart armはstate rollbackを行わず同一imageを起動し直す。どのverdictでも通常運転へ戻らない場合は既存deploy recoveryへ移り、ledger、gap、exclusion、execution historyを削除・backfillしない。seam削除はevidence rowを消さず、fault-injection入口だけを閉じる。

このdelta specはユーザーが明示的に要求した設計・反証用のchange-local acceptance contractである。1回限りのoperator procedureとtemporary seamをsystemの恒久MUSTにしないため、seam削除とproduction確認後、archive skillで明示的に `Archive without syncing` を選ぶ。delta specはarchive内の実験記録として保持し、main specsへ同期しない。

## Implementation Handoff

この節は Issue #192 の実装開始からcleanup PR完了までのHANDOFFとして使う。正本は同changeの`proposal.md`、本設計、delta spec、`tasks.md`であり、この節と食い違う場合はそれらのacceptance contractを優先する。

### Current state

- OpenSpec strict validationは通過している。
- clean-context Claude falsificationは5ベクトルを完了し、command-event identityのentropy誤検知を固定`toolName=issue192-ws`とbare UUIDで解消した。fresh Claudeのtargeted recheck結果はunresolved blocking 0、implementation possibleである。
- 実装、commit、production mutation、Issue commentは未実施である。
- change directoryは未commitであり、実装開始時はIssue #192専用branch/worktreeへこのdirectoryだけを載せる。他issueのbranchや差分を混ぜない。

### Delivery shape

deliveryは2 PRに限定する。production verificationを両PRの間に置くため1 PRには統合せず、個人用途に対して過分になるため3 PR以上には分割しない。

| PR | Scope | Expected diff |
|---|---|---:|
| PR 1 `feat: add temporary websocket fault injection seam` | OpenSpec、application-owned exact-session disconnect、combined abort-and-terminal、application-scoped holder、temporary hidden route/default-false flag、fixed-PK one-shot audit、proving tests、deploy/current-state docs | 約 `+1,100〜1,500 / -0〜50` lines、12〜18 files |
| PR 2 `chore: remove issue 192 fault injection seam` | 2 armの`implementation-evidence.md`、controller/route/flag/holderとtemporary testsの削除、route不存在と通常WebSocketの確認、historical audit enum/catalog stringsの維持 | 約 `+150〜250 / -350〜550` lines、8〜12 files |

見積りはreview対象の手書き差分であり、formatterやgenerated artifactによる機械差分を含めない。実装中にPR 1が`+1,500`行を大きく超える場合は、新しい汎用subsystem、恒久API、WebUI、追加security layerが混入していないかを先に確認する。受け入れ条件に必要なproving testの行数だけを理由に分割しない。

### PR 1 file scope

- `trading/.../GmoPublicWebSocketMarketEventStream.kt`: active session identity、session-local one-shot CAS、socket abort、既存listener terminal boundaryへのtyped failure。
- `fukurou/.../Application.kt`と`ProtectionReconcilerWorker.kt`: application-scoped holderをrouting構築とworker startupへ共有する。companion objectやprocess-global mutable singletonは使わない。
- `fukurou/.../OpsRoutes.kt`または同packageの小さな専用file: temporary route/controller、local `Mutex`、final preflight、fixed audit gate。既存巨大fileへ全ロジックを埋め込まず、routing wiringだけを残してcontrollerを分離してよい。
- `trading/.../CommandEvent.kt`とcommand-event persistence: requested/executed event type、2固定UUIDのprimary-key lookup。DB schemaとtable scanは追加しない。
- `docker-compose.prod.yml`: default-false flag 1件だけ。Dockerfile、secret mount、capability fileは変更しない。
- activity catalog/i18n: historical auditを読める最小label/description。操作UIは追加しない。
- tests: exact/stale session、abort-plus-terminal、natural terminal race、buffered receipt、duplicate/concurrent/restart/new-ID、requested-only burn、PK lookup fail-closed/no scan、`ManifestPersistencePolicy`、no global trading lock、REST非干渉、single gap/reconnect、route disabled/hiddenを対象とする。
- docs: temporary flag、deploy、rehearsal、cleanupを既存docsへ追記する。新しい恒久運用文書やdashboardは作らない。

PR 1はproduction faultを発生させず、route disabled状態のtestとread-only rehearsalまでで完了する。DB schema、runtime config model、LLM strategy、WebUI action、専用認証、恒久fault API、fill/regime目標はscope外である。

### Between the PRs

PR 1のmerge/deploy後、`tasks.md`の3章と4章を直列に進める。各armのmutation直前にexact inventoryと不可逆exclusionをownerへ提示し、arm固有の明示goが得られるまで停止する。設計承認、PR merge、Claude falsificationはproduction mutationの承認ではない。

`WS-DISCONNECT`を1回実行して復旧とverdictを閉じた後だけ、`PROCESS-RESTART`を1回実行する。requested fixed audit以後の失敗、`HARD_FAIL`、`INVALID`、`UNKNOWN`では再注入しない。通常運転の復旧を優先し、発見したapplication defectの修正は別OpenSpec/PRへ切り出す。

### PR 2 retention boundary

PR 2はtemporary controller、route、compose flag、application-scoped holder、注入専用testとdocsを削除する。一方、既存rowの`CommandEventType.valueOf`とactivity catalog readを壊さないため、実際に保存したcommand-event enum値、そのcatalog mapping、両localeのlabel/description、historical decoding testは残す。ledger、receipt、gap、exclusion、execution、audit rowを削除・backfillしない。

PR descriptionには両PRとも`ドキュメント影響: あり（対象ファイル）`を記載する。PR 2のdeployでroute 404、通常WebSocket reconnect、readiness/public connectivityを確認し、NAS `.env` entryを削除した後にIssue #192へsecret-free evidence summaryを返す。change archiveは`Archive without syncing`を選ぶ。

## Open Questions

production mutation自体は未承認である。各armのexact inventory、不可逆なevaluation exclusion、運用停止/復旧窓を提示した時点で、ownerがarm単位にgo/no-goを判断する。2障害、各1回、hard fail、fill件数を完了条件にしない設計境界はIssue #192とユーザー指示で確定している。
