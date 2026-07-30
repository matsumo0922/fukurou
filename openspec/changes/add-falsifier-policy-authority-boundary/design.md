## Context

Foundation は entry intent ごとに `FalsifierPolicyDecision` と canonical event を durable 保存し、canonical `OFF_V1 / ENTER / required=false / POLICY_OFF` decision から internal `FalsifierPolicyPermit` を作る。
現行の public `Broker` は `PlaceOrderCommand` を preview/place の両方に受け取り、MCP caller と runner が同じ boundary を使う。
permit を public command に追加すると authority が外部 contract に漏れ、既存 result lookup を authority 検証より先に行うと permit のない caller に replay result を返し得る。

本 change は authority/replay boundary だけを inactive に追加する。
authorized new mutation は常に fail closed とし、backend atomic entry は A2、runner の使用開始は B に分ける。

## Goals / Non-Goals

**Goals:**

- public command、`Broker` interface、MCP wire schema を変えずに authorized preview/place boundary を用意する
- permit と durable decision/event の全 identity を result lookup より前に検証する
- v2 fingerprint を normalized command と authority に束縛する
- public/MCP caller による未使用・既存 v2 ID の spoof を result lookup 前に拒否する
- exact authorized replay を consumed intent より優先して返す
- authorityを確認できない状態と、新規mutation未対応をtyped failureで区別する

**Non-Goals:**

- authorized new order mutation
- atomic flat predicate、in-memory/PostgreSQL concurrency
- production runner から authorized boundaryを呼ぶこと
- Falsifier skip、runtime activation、runner status/outcome mapping
- conditional/shadow/evaluation、live trading

## Decisions

### 1. public boundary と internal authorized boundary を分離する

public `PlaceOrderCommand`、`Broker.previewOrder(PlaceOrderCommand)`、`Broker.placeOrder(PlaceOrderCommand)`、MCP input schema は変更しない。
broker package に module-internal `AuthorizedPreviewOrder` / `AuthorizedPlaceOrder` envelope と internal broker boundary を置く。
envelope は public command と foundation permit を保持するが、public interface の parameter/return type には使わない。

runner packageのpermitをpublicへ昇格する案とwire DTOへopaque tokenを追加する案は、callerにauthority surfaceを公開するため採用しない。
本changeのproduction `OneShotLlmRunner` はinternal boundaryを参照せず、全entryで従来のFalsifierとpublic broker pathを使う。

### 2. durable decision/event の全 identity を最初に検証する

internal boundaryはcommandのintent IDで`FalsifierPolicyDecisionRepository`を読み、repositoryがdecisionとcanonical eventの整合を確認した結果を使う。
次の全identityがpermitと完全一致する場合だけauthorityを受理する。

- decision ID / intent ID
- actionが`ENTER`
- policyが`OFF_V1`
- `required=false`
- reason codesが`POLICY_OFF`のみ
- runtime config version ID / hash

preview/placeの双方でこの検証を行う。
欠損、partial state、属性不一致はtyped authority-indeterminate failure、repository read failureはtyped authority-unavailable failureを返す。
いずれも既存 result lookup と mutation より前であるため、「注文がない」または`NO_TRADE`を意味しない。

durable decisionだけからpermitを再発行する案は、intent IDを知るcallerへauthorityを与えるため採用しない。

### 3. v2 fingerprint をcommandとpermitへcanonicalに束縛する

authorized placeはnormalized command business fieldsとpermit全identityのcanonical projectionをSHA-256にし、`runner-place-v2-<hash>`をclient request IDとする。
projectionはintent ID、symbol、side/order type、size、price、trade group、protective STOP、TP、estimated win probability、time stop、canonical thesis IDとpermit全identityを含む。
数値/nullは既存preview normalized contentと同じbusiness表現を使い、自由文reasonとtool/audit metadataは除外する。

internal place boundaryの順序は次に固定する。

1. durable authorityの全identityを検証する
2. commandからfingerprintを再計算し、v2 client request IDと完全一致することを検証する
3. ledgerのexisting resultをclient request IDでlookupする
4. exact resultがあれば返す
5. resultがなければtyped `authorized new mutation unsupported` failureを返す

resultがない場合はintent consumptionを評価せず、backendへmutation要求を渡さない。
A2で新規mutationを追加するときにだけ、exact lookup後へconsumed intentとatomic flat gateを追加する。

### 4. public path はv2 namespaceをpre-lookupで予約する

public preview/place pathは`runner-place-v2-` prefixをinternal authorized path専用として拒否する。
placeでは既存 result lookup より前に拒否するため、permitのないpublic/MCP callerは正しい既存IDでもresultを受け取らない。
未使用IDでもpreview/mutationへ進まない。
既存のfresh approval pathとclient request namespaceは変更しない。

public pathでresult lookup後にprefixを検証する案は、既存resultをauthorityなしで返すため採用しない。

### 5. exact replay はintent consumptionより優先する

authority/fingerprintがexactなら、existing result lookupをintent consumed判定より先に置く。
初回成功はintentを消費するため、consumed判定を先にすると正規retryが失敗するからである。

A1は新規mutationを行わないが、test fixtureでseedしたexact v2 resultとconsumed intentを使って順序を固定する。
resultが一意に復元できない、lookup自体が失敗する、またはcommand/authorityが異なる場合はexact replayとして返さない。

### 6. A1はproduction semanticsを変えない

production runnerはinternal boundaryに未接続であり、OFF foundation permitがあってもFalsifierを起動する。
runner status、terminal cause、completion event、no-trade/outcome mappingは変更しない。
`OFF_V1`と`CONDITIONAL_V1`のproduction activation禁止をdocsに維持する。

後続A2 `add-falsifier-policy-atomic-entry`は、in-memory/PostgreSQLのmutation lock/transaction内で`open positions == 0 AND risk-increasing open entry orders == 0`を検証し、authorized market/resting new mutationを追加する。
後続Bはruntime enforcement flag default false、active snapshot precondition、permit propagation、Falsifier skip、durable outcome-unknown mapping、exact retry recoveryを扱う。

## Risks / Trade-offs

- [internal boundaryが誤ってproductionから呼ばれる] → runner wiringを追加せず、OFFでもFalsifierが起動する回帰testを置く
- [repository停止時に既存resultがある] → pre-lookupでtyped unavailableを返し、result不存在やno-tradeを断定しない
- [consumed intentがexact retryを阻害する] → authority/fingerprint検証後、existing result lookupをconsumptionより先に固定する
- [public callerがv2 IDを観測する] → public preview/placeはresult lookup前にreserved prefixを拒否する
- [A1が不完全なnew mutationを許す] → exact resultがなければ専用typed failureで必ずfail closedにする

## Migration Plan

1. internal envelope、authority validator、fingerprint、public v2 guard、exact replay-only pathをdeployする。
2. public/MCP schemaとrunner Falsifier behaviorが不変であることを確認する。
3. A2がatomic backend capabilityを実装するまでauthorized new mutationは常にfail closedにする。
4. Bがactivation/outcome mappingを実装するまでrunnerをinternal boundaryへ接続しない。
5. rollbackはcodeだけを戻し、foundation decision/eventやseed済みledger recordを削除・書換えしない。

## Open Questions

なし。
