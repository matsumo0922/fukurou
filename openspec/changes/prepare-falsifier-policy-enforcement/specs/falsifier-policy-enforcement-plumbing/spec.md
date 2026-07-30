## ADDED Requirements

### Requirement: public contract と production behavior は不変である

system は public `PlaceOrderCommand`、preview/place の `Broker` interface、MCP wire schema を変更せず、authorized authorityをbroker package内のinternal boundaryだけで扱わなければならない（MUST）。
production runnerはこのchangeでinternal boundaryを呼んではならず（MUST NOT）、全entryにfresh `APPROVED`を要求しなければならない（MUST）。

#### Scenario: public API surface

- **WHEN** change 前後のpublic command、broker signature、MCP tool input schemaを比較する
- **THEN** permitまたはauthorized envelopeのfieldはpublic surfaceに追加されていない

#### Scenario: OFF foundation decision

- **WHEN** production runnerが`OFF_V1 / ENTER` decisionとpermitを確立する
- **THEN** runnerは従来どおりFalsifierを起動し、public broker pathだけを使う

#### Scenario: non-OFF entry

- **WHEN** runnerがALWAYS_ON、CONDITIONAL、またはADD_LONG entryを処理する
- **THEN** fresh `APPROVED` gateの挙動は変わらない

### Requirement: authorized envelope は internal-only である

broker packageはpublic commandとfoundation permitを束縛するinternal `AuthorizedPreviewOrder` / `AuthorizedPlaceOrder` envelopeとinternal broker boundaryを提供しなければならない（MUST）。
runnerのinternal permit型またはenvelopeをpublic APIへ露出してはならない（MUST NOT）。

#### Scenario: internal authorized preview

- **WHEN** module内部のcallerがcommandとcanonical foundation permitを`AuthorizedPreviewOrder`に束縛する
- **THEN** internal boundaryはdurable authority検証後にpreviewを実行する

#### Scenario: internal authorized place

- **WHEN** module内部のcallerがcommandとcanonical foundation permitを`AuthorizedPlaceOrder`に束縛する
- **THEN** internal boundaryはauthority、fingerprint、replay/atomic mutation gateの順で処理する

#### Scenario: MCP caller

- **WHEN** MCP callerがpreview/place requestを送る
- **THEN** callerはauthorized envelopeまたはpermitを指定できない

### Requirement: durable OFF authority を全 identity で検証する

authorized boundaryはintent IDでdurable policy decision/eventを読み、decision ID、intent ID、action、policy、required/reason codes、runtime config version/hashがpermitと完全一致する場合だけauthorityを受理しなければならない（MUST）。

#### Scenario: canonical OFF ENTER authority

- **WHEN** permitとdurable decision/eventが`OFF_V1 / ENTER / required=false / POLICY_OFF`の全identityで一致する
- **THEN** authorized boundaryはfingerprint検証へ進む

#### Scenario: partialまたはmismatch

- **WHEN** decision/eventが片側欠損、属性不一致、permit不一致、またはOFF ENTER以外である
- **THEN** systemはresult lookupとledger mutationを行わずtyped indeterminate failureを返す

#### Scenario: policy repository unavailable

- **WHEN** durable decision/eventを読み取れない
- **THEN** systemはresult lookupとledger mutationを行わず、注文なしを断定しないtyped authority-unavailable failureを返す

### Requirement: v2 namespace はauthorized OFF place専用である

authorized placeはnormalized command business fieldsとpermit全identityのcanonical SHA-256を`runner-place-v2-<hash>`として使用しなければならない（MUST）。
systemはauthorityとfingerprintを既存result lookupより前に新規/replay双方で検証しなければならない（MUST）。

#### Scenario: exact authorized request

- **WHEN** permit、durable decision、normalized command、v2 client request IDが完全一致する
- **THEN** systemは既存result lookupへ進む

#### Scenario: commandまたはauthority mismatch

- **WHEN** intent、数量、価格、STOP/TP、time stop、canonical thesis、またはpermit identityがv2 fingerprintと異なる
- **THEN** systemは既存resultを返さず新規mutationも行わない

#### Scenario: permitなしの未使用v2 ID

- **WHEN** public/MCP callerが未使用の`runner-place-v2-` IDをpermitなしで送る
- **THEN** public pathはresult lookupとmutation前に拒否する

#### Scenario: permitなしの既存v2 ID

- **WHEN** public/MCP callerが正しい既存`runner-place-v2-` IDをpermitなしで送る
- **THEN** public pathは既存result lookup前に拒否しresultを返さない

### Requirement: exact replay はintent consumptionより優先する

authorized placeはauthority/fingerprint検証後に既存resultを検索し、exact resultがあればintent consumed状態にかかわらず返さなければならない（MUST）。
既存resultがない場合だけconsumed intentを拒否しなければならない（MUST）。

#### Scenario: 初回成功後のexact retry

- **WHEN** 初回authorized placeがintentを消費した後、同じcommandとauthorityでretryする
- **THEN** systemは既存resultを返しledger mutationを増やさない

#### Scenario: consumedだがresultなし

- **WHEN** authority/fingerprintはvalidだが既存resultがなくintentが消費済みである
- **THEN** systemは新規mutationを拒否する

#### Scenario: replay stateが一意でない

- **WHEN** client request IDに対応するresultが一意に復元できない
- **THEN** systemはexact replayとみなさずfail closedにする

### Requirement: flat invariant はbackend mutation境界でatomicである

authorized new mutationは、in-memory/PostgreSQL各backendのmutation lock/transaction内でopen positionが0件かつrisk-increasing open entry orderが0件の場合だけmarket entry fillまたはresting entry creationを行わなければならない（MUST）。
未対応backendはauthorized mutationをfail closedにしなければならない（MUST）。

#### Scenario: 二つのresting OFF entryが競合する

- **WHEN** flat accountへ二つのauthorized resting entryを同時にplaceする
- **THEN** 一方だけがopen entry orderを作り、他方は同じatomic predicateで拒否される

#### Scenario: resting fillとmarket entryが競合する

- **WHEN** resting BUYのfillとauthorized market entryが同時にaccountを変更する
- **THEN** backend mutation boundaryが両操作を直列化し、positionと追加entryが同時成立しない

#### Scenario: open positionがある

- **WHEN** mutation lock/transaction内でopen positionが1件以上ある
- **THEN** market/restingいずれのauthorized new mutationも拒否される

#### Scenario: risk-increasing open entry orderがある

- **WHEN** mutation lock/transaction内で未約定BUY entry orderが1件以上ある
- **THEN** market/restingいずれのauthorized new mutationも拒否される

#### Scenario: risk-reducing orderだけがある

- **WHEN** open positionとrisk-increasing entry orderがなく、保護STOPまたはexit orderだけがある
- **THEN** flat predicateはそのorderだけを理由に拒否しない

#### Scenario: 未対応backend

- **WHEN** ledger backendがauthorized atomic capabilityを実装していない
- **THEN** brokerはread-check-write fallbackを使わずmutation前にfail closedにする

### Requirement: inactive plumbing はoutcome semanticsを変更しない

systemはこのchangeでrunnerのstatus、terminal cause、completion event、no-trade mappingを変更してはならない（MUST NOT）。
authority unavailable/mismatch failureは注文有無を確定しないtyped resultとしてinternal boundaryから返さなければならない（MUST）。

#### Scenario: authority failure

- **WHEN** authorized placeのauthorityをexisting result lookup前に確立できない
- **THEN** internal boundaryはmutationを行わず、NO_TRADEを意味しないtyped indeterminate failureを返す

#### Scenario: production runner

- **WHEN** production entry flowが完了または失敗する
- **THEN** runnerは本change前のpublic pathとstatus/terminal/event mappingを使用する

### Requirement: activation は後続changeまで禁止する

systemはruntime enforcement flag、runner permit propagation、Falsifier skip、durable `OUTCOME_UNKNOWN` mappingを後続activation changeまで導入してはならない（MUST NOT）。
`OFF_V1`と`CONDITIONAL_V1`のproduction activation禁止を維持しなければならない（MUST）。

#### Scenario: plumbing deploy後

- **WHEN** inactive plumbingをproductionへdeployする
- **THEN** active policyはALWAYS_ONのままでFalsifier behaviorは変化しない
