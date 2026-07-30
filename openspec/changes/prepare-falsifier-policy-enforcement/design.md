## Context

Foundation は entry intent ごとに `FalsifierPolicyDecision` と canonical event を durable 保存し、canonical `OFF_V1 / ENTER / required=false / POLICY_OFF` decision から internal `FalsifierPolicyPermit` を作る。
現行の public `Broker` は `PlaceOrderCommand` を preview/place の両方に受け取り、MCP caller と runner が同じ boundary を使う。
permit を public command に追加すると MCP contract または public API へ authority が漏れ、既存 result lookup を先に行うと permit のない caller に replay result を返し得る。

また、flat の判定を broker の事前 snapshot だけで行うと、同時 resting order、resting fill、market entry の間で TOCTOU が残る。
in-memory ledger は state write lock、PostgreSQL ledger は `paper_account` を含む共通 mutation row lock を既に持つため、flat invariant は各 backend の mutation linearization point で検証する。

この change は authority plumbing を inactive のまま構築する。
production runner は internal authorized boundary を呼ばず、Falsifier を必ず実行する。

## Goals / Non-Goals

**Goals:**

- public command、`Broker` interface、MCP wire schema を変えずに authorized preview/place path を用意する
- permit と durable decision/event の全 identity を mutation/replay 前に検証する
- v2 namespace の spoof と payload/authority を変えた replay を拒否する
- exact replay を intent consumption より優先し、同一 retry を冪等に返す
- flat invariant を market/resting の双方で backend の mutation lock/transaction 内に強制する
- authority を確認できない状態を「注文なし」と断定しない typed failure にする

**Non-Goals:**

- production runner から authorized boundary を呼ぶこと
- Falsifier skip、runtime enforcement flag、policy activation
- runner の durable outcome-unknown status / terminal / event mapping
- `CONDITIONAL_V1` 判定、shadow/evaluation、live trading

## Decisions

### 1. public boundary と internal authorized boundary を分離する

public `PlaceOrderCommand`、`Broker.previewOrder(PlaceOrderCommand)`、`Broker.placeOrder(PlaceOrderCommand)` と MCP schema は変更しない。
broker package に次の module-internal 型と boundary を置く。

- `AuthorizedPreviewOrder`: public command と foundation permit を束縛する envelope
- `AuthorizedPlaceOrder`: public command と foundation permit を束縛する envelope
- authorized preview/place を受ける internal broker boundary

envelope は internal のまま foundation permit を保持し、public interface の parameter/return type には使わない。
runner package の internal permit を public API へ昇格させる案と wire DTO に permit を追加する案は、authority を外部 contract に漏らすため採用しない。

本 change の production `OneShotLlmRunner` は authorized boundary を呼ばない。
runner は全 policy/action で従来の public boundaryと fresh `APPROVED` gate を使うため、deploy だけで Falsifier skip は起きない。

### 2. internal boundary は permit と durable decision の全 identity を先に検証する

authorized boundary は command の intent ID で `FalsifierPolicyDecisionRepository` を読み、repository が decision と canonical event の整合を確認した結果を使う。
次の全 identity が permit と完全一致する場合だけ OFF authority を受理する。

- decision ID / intent ID
- action が `ENTER`
- policy が `OFF_V1`
- `required=false`
- reason codes が `POLICY_OFF` のみ
- runtime config version ID / hash

検証は preview/place の両方で行う。
欠損、partial state、属性不一致、repository unavailable は ledger mutation を開始しない。
ただし place の既存 result lookup より前に失敗するため、過去の exact result が存在しないとは断定せず、typed authority-unavailable/indeterminate failure を返す。
この failure 自体は runner の `NO_TRADE` を意味しない。

durable decision だけから暗黙に permit を作る案は、MCP caller が既存 intent ID を知るだけで authority を得るため採用しない。

### 3. canonical fingerprint と v2 namespace を internal OFF path に予約する

authorized place は normalized command business fields と permit の全 identity を canonical projection にし、SHA-256 から `runner-place-v2-<hash>` を導出する。
projection は intent ID、symbol、side/order type、size、price、trade group、protective STOP、TP、estimated win probability、time stop、canonical thesis ID と、permit の全 identity を含む。
数値/null の canonicalization は preview normalized content と同じ business表現を再利用する。
自由文 reason と tool/audit metadata は authority を変えないため除外する。

internal boundary の順序は次に固定する。

1. permit と durable decision/event の全 identity を検証する
2. command から v2 fingerprint を再計算し、client request ID と完全一致することを検証する
3. ledger の既存 result を client request ID で lookup する
4. exact result があればそのまま返す
5. result がない場合だけ intent consumption と新規 mutation gate へ進む

この順序により consumed intent の exact retry は既存 resultを返し、別 payload/authority は result lookup 前に拒否する。
SHA-256 の実用上の collision は identity として同一と扱う。

public place path は `runner-place-v2-` prefix を result lookup 前に拒否する。
permit のない public/MCP caller は正しい既存 ID でも未使用 ID でも resultを受け取らず、mutation も起こさない。
fresh approval path と既存 client request namespace は変更しない。

### 4. exact replay を consumption check より優先する

authorized place の authority/fingerprint が exact なら、既存 result lookup を intent consumed check より先に行う。
初回成功時に intent が atomic consume されるため、consumed check を先にすると正規 retry が拒否されるからである。

既存 result がなければ、消費済み intent は既存 rule で拒否する。
同じ v2 ID の result が複数または保存内容が壊れている場合は exact result とみなさず fail closed にする。

authority検証より replay を先にする案は permit のない caller へ result を漏らすため採用しない。

### 5. flat invariant は ledger backend の mutation linearization point に置く

authorized new mutation は次の predicate を同じ lock/transaction 内で確認する。

`open positions == 0 AND risk-increasing open entry orders == 0`

risk-increasing open entry order は未約定の BUY entry orderであり、保護 STOP、exit、cancel pending の risk-reducing order は flat を偽らない。
predicate は market entry fill と resting entry creation の双方に適用する。

in-memory backend は既存 state write lock 内で predicate、intent consumption、order/position write を連続実行する。
PostgreSQL backend は既存の ledger mutation lock orderで single `paper_account` rowを先に `FOR UPDATE` し、open positions/ordersをlock/readしてから同一 transaction で predicate、intent consumption、writeを行う。
market-event resting fill も同じ ledger mutation lockを取得するため、resting fill と authorized market entry が直列化される。

authorized mutation 用の internal ledger capability を in-memory / PostgreSQL が実装する。
未対応 backend では authorized path を mutation 前に fail closed にし、read-check-write fallback は持たない。

broker 外の事前 snapshotだけで判定する案と、positionだけを検査して open BUY orderを無視する案は、同時 entry raceを閉じないため採用しない。

### 6. inactive plumbing と activationを stacked changeに分離する

本 PR A は authority/fingerprint/atomic predicate を実装して test するが、production runner から internal boundary へ permitを渡さない。
`OFF_V1` と `CONDITIONAL_V1` の production activation禁止を docs に維持する。

後続 PR B は次を一つの activation change として扱う。

- code-owned runtime enforcement flag（default `false`）
- production active config snapshotが activation条件を満たすことの検証
- runner の permit propagation と canonical OFF ENTERだけの Falsifier skip
- `ToolCompletionAuditFailedException(executed=true)` と authority-unavailableを durable `OUTCOME_UNKNOWN` status / terminal / event へ mapping
- exact retry による recovery と、outcome unknown時に no-tradeを作らない契約

plumbing と runner skipを一つの大きな diffで実装する案は、public/private boundary、atomic ledger race、outcome semanticsを同時にreviewさせるため採用しない。

## Risks / Trade-offs

- [internal authorized boundary が誤って production から呼ばれる] → 本 change では runner wiringを追加せず、behavior regression testで全 entryのFalsifier invocationを固定する
- [permitは正しいが repositoryが一時停止する] → mutation/result lookupを行わず typed indeterminate failureを返し、no-trade semanticsは後続 runner mappingまで付与しない
- [consumed intentがexact retryを阻害する] → authority/fingerprint検証後、existing result lookupをconsumption checkより先に置く
- [同時 resting orderがflat判定をすり抜ける] → positionとrisk-increasing open entry orderを同じbackend mutation boundary内で検査する
- [resting fillとmarket entryが競合する] → in-memory write lock / PostgreSQL account-row mutation lockで直列化する
- [public callerがv2 IDを観測する] → public pathは既存 result lookup前にnamespaceを拒否する
- [実装が1,000行を超える] → internal boundary/fingerprint/namespaceをPR A1、backend atomic predicateをPR A2へstack分割し、A1ではauthorized new mutationをfail closedのままにする

## Migration Plan

1. inactive internal boundary、v2 public guard、in-memory/PostgreSQL atomic capabilityをdeployする。
2. public/MCP schemaとrunnerのfresh Falsifier behaviorが不変であることを確認する。
3. `OFF_V1` / `CONDITIONAL_V1` はproduction activateしない。
4. 後続PR Bでflag/precondition/outcome mappingを揃えてからOFF runner pathを有効化する。
5. rollbackはcodeだけを戻し、foundation decision/event、order、executionを削除・書換えしない。

## Open Questions

なし。
