## Context

Issue #187 の DoD (b) と (f) を、最終 handoff で承認された B+F 一つの PR として実装する。現行 `DecisionExecutionLifecycle` は open position があればそれだけを close し、併存する resting BUY を残す。現行 `PaperBrokerSafetyGate.sweepOpenRisk` は order ごとの cancel と position ごとの close を別 transaction で反復する。Exposed ledger の各 mutation と market-event fill は、`risk_state -> paper_account -> OPEN positions(id順) -> OPEN/PENDING_CANCEL orders(id順)`の共通 lock orderをすでに使う。

position は `trade_group_id` を持ち、entry order は `trade_group_id` と `intent_id`、intent は canonical `thesis_id` を持つ。したがって同一 thesis 判定は、caller の snapshot や表示文字列ではなく、ledger transaction 内の persisted linkage を正本にできる。

本変更は safety、DB hot path、runner/reconciler/persistence の cross-layer change なので、実装前に独立 falsifier を通す。

## Goals / Non-Goals

**Goals:**

- （ユーザー確認済み）full EXIT と同一 thesis pending BUY invalidation を一つの ledger transaction にする。
- （ユーザー確認済み）EXIT と fill のraceを既存 lock order/status CASで直列化し、commit順にかかわらずEXIT後のsame-thesis open riskを0にする。
- （ユーザー確認済み）HARD_HALT cleanupを同じatomic primitiveで実行し、sticky haltをdurable retry markerとしてstartup/periodic passから収束させる。
- （ユーザー確認済み）linkageやmarket inputが曖昧ならmutationを作らず、HARD_HALT/UNKNOWNを維持する。
- （ユーザー確認済み）Exposedとin-memoryの意味を揃え、本番entrypointからの配線をtestで証明する。

**Non-Goals:**

- 旧C0/C1のgeneration token、queue/journal/member、汎用recovery engineを復活させること。
- 全crash pointを列挙した多段saga、owner lease、multi-replica coordination。
- unrelated thesisの注文取消、legacy linkageの推測backfill、既存履歴のrewrite。
- past priceや後着market historyからpaper executionを遡及生成すること。
- root-installed script、NAS bootstrap、MCP/OpenAPI、production configの変更。

## Decisions

### 1. 一つの repository primitive で SAME_THESIS と ALL_OPEN_RISK を扱う

（agent 仮決め）`PaperLedgerMutationRepository` に atomic risk-exit requestを追加する。requestは実行理由、audit context、transaction外で一つに固定したcausal `PaperSimulationContext`、純粋なfill simulator、およびscopeを持つ。position IDやsizeをcaller snapshotから列挙しない。

- `SAME_THESIS(targetPositionId)`: full EXIT用。target positionのcanonical thesisをtransaction内で解決し、transaction時点で同じthesisに属する全open positionをcloseし、同じthesisのrisk-increasing pending BUYを取消す。
- `ALL_OPEN_RISK`: HARD_HALT用。transaction lock時点の全risk-increasing open orderを取消し、全open positionをcloseする。

Exposed実装は一回のDB transaction、in-memory実装は一回のmutex critical sectionで、cancel、close order/execution、position、linked protective STOP、accountをまとめて更新する。HARD_HALTがsame primitiveを使うことで、close/cancel semanticsを二重実装しない。

代替案の「既存`cancelOrder`/`closePosition`をbrokerから順に呼ぶ」は、途中commitとresponse lossでpartial cleanupを作るため不採用とする。positionごとの小さなsagaも今回の個人利用向けscopeを超える。

### 2. causal market contextを渡し、lock後の最新全量からfillを計算する

（agent 仮決め）brokerは一つのcausal `TickSnapshot`/simulation contextと純粋なpaper fill simulatorをrequestへ渡す。repositoryはlock取得後にsame-thesisまたはall-riskの最新open position集合と各full sizeを確定し、そのsizeからSELL fillを計算する。

market/orderbook I/OをDB transaction内へ持ち込まず、事前に固定した同一market observationだけを計算へ使う。同時fillが先にcommitしてposition sizeやsame-thesis position集合を変えても、EXITは古いsizeを適用せずlocked state全体をcloseする。HARD_HALTでtrustworthyなtickを得られない場合はcleanupを実行せずsticky haltとUNKNOWNを残す。後から取得したhistorical priceによる埋め合わせはしない。

### 3. canonical thesis linkageはDB内で完全に解決する

（ユーザー確認済み）SAME_THESIS transactionは次を検証する。

1. target positionがOPENで、trade groupが一つある。
2. target groupに属するentry orderのintentからnon-null canonical thesisがちょうど一つ得られる。
3. risk-increasing open/pending-cancel BUYはそれぞれ一つのintentとnon-null canonical thesisへ解決できる。
4. target thesisと一致する注文だけを取消し、異なるthesisの注文を維持する。

missing/null/multiple/contradictory linkage、stale target、scope外のtarget混入はtyped exceptionでtransaction全体をrollbackする。callerが渡すtrade group/thesis文字列をauthorityにしない。

代替案のtrade-group一致だけでは、同一thesisのrevision/add-longが別groupになった場合にlate entryを残すため不採用とする。thesis表示文の一致はcanonical identityではないため使わない。

### 4. 既存risk-state-first lock orderとstatus CASを維持する

（ユーザー確認済み）atomic primitiveは既存`lockPaperLedgerMutationRows()`を最初に使う。market-event fill、placement、従来close/cancelと同じ順序で全OPEN position/orderをlockした後に再解決する。UPDATEはOPEN/PENDING_CANCEL等の期待statusを条件にし、更新件数不一致をrollbackする。

HARD_HALT stateはcleanupより先に既存serviceでdurable commitする。以後のrisk-increasing mutationは同じrisk rowを先にlockして拒否される。cleanupはその後の別transactionだが、ledger側の全cancel/closeは一transactionであり、sticky haltがtransaction前失敗とresponse-loss後retryを区別せず安全側へ保つ。

### 5. risk_stateに最小のUNKNOWN/SAFE cleanup evidenceを追加する

（agent 仮決め）`risk_state`にadditiveなcleanup stateを追加し、中間phaseを増やさず`UNKNOWN`と`SAFE`だけを表す。新規HARD_HALT transitionはUNKNOWNを保存する。すでにHARD_HALT/SAFEなら重複setだけではUNKNOWNへ戻さないが、SAFEをcleanup skipの根拠にはしない。各cleanup attemptは必ずrisk-state-first lock下でopen riskをreadbackし、riskがあればUNKNOWNへ戻してcleanupを続ける。atomic cleanup transactionは全mutation後にopen position 0かつrisk-increasing order 0を同じlock内で再確認できた場合だけSAFEを保存する。

transaction commit後に応答を失っても、次passはSAFEをreadbackで再検証し、empty target setをidempotent successとして扱ってduplicate executionを作らない。DB結果を確定できないfailureではSAFEへ昇格せず、既存reconciler failure auditとsticky HARD_HALT/UNKNOWNを維持する。manual resumeはrisk-state-firstの同じtransactionでSAFEとopen position 0/risk-increasing order 0を再検証し、どちらかを満たさなければUNKNOWNへ戻して拒否する。resume成功時はcleanup evidenceを非HARD_HALT状態へ戻す。

### 6. retryは既存reconciler loop内の専用cleanup attemptへ限定する

（agent 仮決め）production `ProtectionReconcilerWorker` のbootstrap完了後かつWebSocket接続前に、cleanup evidenceの値にかかわらずHARD_HALTのときだけ動くboundedな専用cleanup attemptを一度実行する。attemptの第一手はatomic open-risk readbackとし、SAFEかつopen risk 0ならmutationなしで終了する。`reconcileOnce(STARTUP_FULL)`や`FULL_TICK_EXECUTION`をproduction WebSocket起動へ追加せず、REST tickからentry fill/protective executionを作らない。

WebSocket接続失敗/backoff branchでも、既存loopの一部かつ既存`tradingLock`内で同じbounded cleanup attemptを実行する。接続済みsessionのperiodic safety maintenanceとevent passも、各passでsticky HARD_HALTを読み、cleanup stateがSAFEでもatomic readbackを行って再検証する。kill criterionとdrawdown transitionも同じbroker operationへ接続する。新worker、新scheduler、新leaseは作らない。

cleanup attemptはまずatomic readbackを行い、open position 0かつrisk-increasing order 0ならmarket tickなしでもSAFEを保存する。open positionがありtrustworthyな現在tickを取得できない場合だけmutationせずUNKNOWNを維持する。order cancelだけなら価格入力を要求しない。

### 7. runnerのEXIT target解決はatomic operationへ委ねる

（agent 仮決め）`DecisionExecutionLifecycle`はpositionが一つならatomic full EXITを呼び、positionなしでresting entryが一つなら従来のdeterministic cancelを呼ぶ。複数position、複数order-only targetは従来どおりfail-closedとする。positionとresting BUYの併存をrunner側で択一にしない。

tool/audit上のoperationはatomic full EXITであることを表す安定名を使い、結果にはclose order/executionとcancel order IDsを含める。

canonical thesisを解決できないlegacy exposureは、ユーザー確認済みのfail-closed方針により通常EXITで推測closeしない。その場合もthesis分類を必要としないHARD_HALT `ALL_OPEN_RISK` cleanupと既存の明示的position close/REDUCEはrisk-reducing経路として残す。docsとtyped failureはこの安全なfallbackを示す。productionの一時的なopen exposure inventoryをdeploy前提にはせず、新規entryのidentity保存とmissing-linkage regression testで将来rowを閉じる。

### 8. in-memory linkageはentry時にcanonical identityをledgerへ投影する

（agent 仮決め）runnerがpersisted `TradeIntentRecord.identity.thesisId`から組み立てるentry command/requestへcanonical thesis IDを内部fieldとして渡し、in-memory ledgerは`intentId -> thesisId`をentry作成と同じcritical sectionで保存する。Exposed実装はcaller projectionをauthorityにせず、必ずDBの`trade_intents.thesis_id`を再解決する。

test fixtureはmissing/null/contradictory linkageを明示的にseedできる。trade-group一致をthesis一致の代替にしない。

## Risks / Trade-offs

- [market observationとlock後stateがずれる] → market observationは一つに固定し、target集合と全量sizeはtransaction内のlocked stateから確定する。
- [全open ledger row lockでtransactionが重くなる] → 現行lock範囲を広げず、外部I/Oをtransaction外に置く。個人利用single-symbol paper botの現行populationを前提とし、unboundedな新scan/APIを追加しない。
- [legacy rowにcanonical thesisがない] → backfillや推測をせず、そのEXITだけtyped fail-closedにする。HARD_HALTのALL_OPEN_RISKはthesis分類を必要としないためrisk reductionを継続できる。
- [cleanup transactionの結果が不明] → sticky HARD_HALTとdurable UNKNOWN/SAFEを正本にし、自動でSAFEと宣言しない。次passのidempotent read/operationで収束させる。
- [startupでmarket tickがない] → executionを捏造せずcleanupを保留し、periodic/realtime input取得後に再試行する。halt中のentryは継続して拒否する。
- [一transactionのHARD_HALT cleanupが大きくなる] → 汎用paging/sagaは追加せず、想定scopeを大きく超えることが実装時に判明したらSTOP条件として扱う。

## Migration Plan

1. `risk_state`へadditive cleanup stateを追加し、fresh/upgrade、旧reader互換、UNKNOWN中resume拒否を実装する。legacy非HARD_HALT rowは非対象、legacy HARD_HALT rowはUNKNOWNとして安全側に読む。
2. repository interfaceとExposed/in-memory実装を同じcommitで追加する。
3. runner EXIT、broker HARD_HALT、ProtectionReconciler startup/periodic配線を切り替える。
4. targeted unit/integration/barrier race/crash-boundary testを追加し、既存の「EXIT後にresting BUYが1件残る」期待を0へ反転する。
5. prompt、README、`docs/design.md`、`docs/mcp-runtime.md`を現在形で更新する。
6. application bootstrapによるnullable additive schema upgradeで通常deployする。history backfill、destructive migration、NAS bootstrapは行わない。cleanup evidenceはHARD_HALT中だけ意味を持つ。旧reader rollback中にresume/haltされて`RUNNING + UNKNOWN`や`HARD_HALT + stale SAFE`が残っても、新readerはHARD_HALT中の各attemptとmanual resumeでopen riskをatomic readbackし、riskがあればUNKNOWNへ戻す。rollback時もcolumn/値を削除しない。

## Open Questions

なし。実装中に汎用recovery layer、destructive migration、root artifact変更、またはcurrent mainと核心前提の矛盾が必要になった場合は、設計を膨らませずHANDOFFのSTOP条件へ移る。
