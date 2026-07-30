## ADDED Requirements

### Requirement: public contract と runner behavior は不変である

systemはpublic `PlaceOrderCommand`、preview/placeの`Broker` interface、MCP wire schemaを変更してはならない（MUST NOT）。
production runnerはinternal authorized boundaryを呼ばず、全entryで従来どおりFalsifierを実行しなければならない（MUST）。

#### Scenario: public API surface

- **WHEN** change前後のpublic command、broker signature、MCP tool input schemaを比較する
- **THEN** permitまたはauthorized envelopeのfieldは追加されていない

#### Scenario: OFF foundation permit

- **WHEN** production runnerがcanonical OFF ENTER permitを確立する
- **THEN** runnerはFalsifierを起動し、従来のpublic broker pathだけを使う

#### Scenario: runner completion

- **WHEN** production runnerがentry flowを完了または失敗する
- **THEN** status、terminal cause、completion event、no-trade/outcome mappingはchange前と同じである

### Requirement: authorized envelope はinternal-onlyである

broker packageはpublic commandとfoundation permitを束縛するinternal `AuthorizedPreviewOrder` / `AuthorizedPlaceOrder` envelopeとinternal boundaryを提供しなければならない（MUST）。
permitまたはenvelopeをpublic APIへ露出してはならない（MUST NOT）。

#### Scenario: authorized preview

- **WHEN** module内部のtest callerがcommandとpermitをauthorized preview envelopeに束縛する
- **THEN** internal boundaryはdurable authorityを検証してからpreviewを実行する

#### Scenario: authorized place

- **WHEN** module内部のtest callerがcommandとpermitをauthorized place envelopeに束縛する
- **THEN** internal boundaryはauthority、fingerprint、exact replayの順に処理する

#### Scenario: MCP caller

- **WHEN** MCP callerがpreview/place requestを送る
- **THEN** callerはpermitまたはauthorized envelopeを指定できない

### Requirement: durable authority を全identityでpre-lookup検証する

internal boundaryはintent IDでdurable decision/eventを読み、decision ID、intent ID、action、policy、required/reason codes、runtime config version/hashがpermitと完全一致する場合だけauthorityを受理しなければならない（MUST）。
この検証をexisting result lookupより先に行わなければならない（MUST）。

#### Scenario: canonical OFF ENTER

- **WHEN** permitとdurable decision/eventが`OFF_V1 / ENTER / required=false / POLICY_OFF`の全identityで一致する
- **THEN** internal boundaryはfingerprint検証へ進む

#### Scenario: identity mismatch

- **WHEN** decision/eventがpartial、属性不一致、permit不一致、またはcanonical OFF ENTER以外である
- **THEN** systemはresult lookupとmutationを行わずtyped authority-indeterminate failureを返す

#### Scenario: repository unavailable

- **WHEN** durable decision/eventを読み取れない
- **THEN** systemはresult lookupとmutationを行わずtyped authority-unavailable failureを返す

#### Scenario: authority failureの意味

- **WHEN** authority validationがexisting result lookup前に失敗する
- **THEN** typed failureはresult不存在またはNO_TRADEを断定しない

### Requirement: v2 fingerprint はcommandとauthorityへcanonicalに束縛する

authorized placeはnormalized command business fieldsとpermit全identityを、`schemaVersion="falsifier-authority-v1"`とfield insertion orderを固定したcanonical JSONへ符号化しなければならない（MUST）。
nullableは`JsonNull`、stringはJSON escapeされた`JsonPrimitive`、BigDecimalはnormalized `toPlainString`のcanonical string `JsonPrimitive`として表現しなければならない（MUST）。
whitespaceなしJSON serializationのUTF-8 bytesのSHA-256を`runner-place-v2-<hash>`として使用しなければならない（MUST）。
authority検証後、existing result lookup前にfingerprintを再計算してclient request IDと完全一致することを要求しなければならない（MUST）。

#### Scenario: exact fingerprint

- **WHEN** intent、business fields、permit identity、v2 client request IDが完全一致する
- **THEN** internal boundaryはexisting result lookupへ進む

#### Scenario: business payload mismatch

- **WHEN** intent、数量、価格、STOP/TP、time stop、trade group、またはcanonical thesisがfingerprintと異なる
- **THEN** systemはexisting resultを返さずmutationも行わない

#### Scenario: authority fingerprint mismatch

- **WHEN** decision ID、policy attributes、またはruntime config identityがfingerprintと異なる
- **THEN** systemはexisting resultを返さずmutationも行わない

#### Scenario: nullと文字列null

- **WHEN** nullable fieldがJSON nullのcommandと、同じfieldが文字列`"null"`のcommandを符号化する
- **THEN** canonical JSONとv2 fingerprintは異なる

#### Scenario: 改行とJSON metacharacter

- **WHEN** string fieldが改行、quote、backslash、brace、comma、colonを含む
- **THEN** `JsonPrimitive`のJSON escapeによりfield boundaryを変えず一意に符号化される

#### Scenario: canonical decimal

- **WHEN** BigDecimal business fieldを符号化する
- **THEN** 既存normalized contentと同じ`toPlainString`のcanonical decimal stringがJSON string valueとして使われる

#### Scenario: schemaまたはfield orderが異なる

- **WHEN** schema versionまたはobject field insertion orderが`falsifier-authority-v1`契約と異なる
- **THEN** authorized boundaryはそのserializationをv1 fingerprintとして扱わない

### Requirement: public v2 spoof はpre-lookupで拒否する

public preview/place pathは`runner-place-v2-` namespaceをinternal authorized path専用として扱い、existing result lookup、preview、mutationより前に拒否しなければならない（MUST）。

#### Scenario: unused v2 ID

- **WHEN** public/MCP callerが未使用のv2 IDをpermitなしで送る
- **THEN** systemはpreview、result lookup、mutationを行わず拒否する

#### Scenario: existing v2 ID

- **WHEN** public/MCP callerが正しい既存v2 IDをpermitなしで送る
- **THEN** systemはexisting result lookup前に拒否しresultを返さない

### Requirement: authorized replay reader はExactを厳密に復元する

systemは既存public repository lookup semanticsを変更せず、authorized replay専用internal reader/capabilityを使用しなければならない（MUST）。
readerは同じclient request IDのBUY entry candidateが厳密1件、candidate intent IDがcommand intent IDと一致、candidate trade group IDがnon-null、全related rowsが同一trade groupに属する場合だけ`Exact`を返さなければならない（MUST）。

#### Scenario: BUY entryとprotective SELL

- **WHEN** 同じclient request IDに1件のBUY entryと同じtrade groupのprotective SELLが存在する
- **THEN** readerはprotective SELLの同居を許可してExact resultを返す

#### Scenario: BUY candidateなし

- **WHEN** 同じclient request IDにBUY entry candidateが存在しない
- **THEN** readerは`Missing`を返す

#### Scenario: BUY candidate複数

- **WHEN** 同じclient request IDにBUY entry candidateが複数存在する
- **THEN** readerは`Ambiguous`を返す

#### Scenario: wrong intent

- **WHEN** 唯一のBUY candidateのintent IDがcommand intent IDと異なる
- **THEN** readerは`Ambiguous`を返す

#### Scenario: trade group欠損または不一致

- **WHEN** BUY candidateのtrade group IDがnull
- **THEN** readerは`Ambiguous`を返す

#### Scenario: 別groupのrelated row

- **WHEN** 同じclient request IDのrelated rowにBUY candidateとは別のtrade groupが含まれる
- **THEN** readerは`Ambiguous`を返す

#### Scenario: unsupported reader

- **WHEN** ledger backendがauthorized replay reader capabilityを実装していない
- **THEN** internal boundaryはpublic lookupへfallbackせずtyped fail-closedを返す

### Requirement: exact replay はconsumed intentより優先する

authorized placeはauthority/fingerprint検証後にinternal readerを呼び、`Exact` resultがあればintent consumed状態にかかわらず返さなければならない（MUST）。

#### Scenario: seeded exact replay

- **WHEN** exact v2 resultとconsumed intentが存在し、同じcommandとauthorityでauthorized placeをretryする
- **THEN** systemはexisting resultを返しmutationを行わない

#### Scenario: replay lookup failure

- **WHEN** internal readerが失敗する、`Ambiguous`を返す、またはresultを一意に復元できない
- **THEN** systemはexact replayとみなさずtyped indeterminate failureを返す

#### Scenario: non-exact retry

- **WHEN** commandまたはauthorityがv2 fingerprintと異なる
- **THEN** consumed状態にかかわらずexisting resultを返さない

### Requirement: authorized new mutation はA1でfail closedである

authorized placeはauthority/fingerprint検証後にexact existing resultがない場合、backend capability未実装のtyped failureを返し、新規order、position、execution、intent consumptionを作ってはならない（MUST NOT）。

#### Scenario: replay readerがMissing

- **WHEN** authorityとfingerprintはvalidだがauthorized replay readerが`Missing`を返す
- **THEN** systemはtyped `authorized new mutation unsupported` failureを返しledgerを変更しない

#### Scenario: unconsumed intent

- **WHEN** validなunconsumed intentとauthorityがあるがexisting resultがない
- **THEN** systemはintentを消費せず新規mutationを行わない

#### Scenario: consumed intentだがresultなし

- **WHEN** validなconsumed intentとauthorityがあるがexisting resultがない
- **THEN** systemはbackend mutationへ進まず同じtyped unsupported failureを返す

### Requirement: atomic entryとactivationは後続changeまでdeferする

systemはこのchangeでatomic flat predicate、backend authorized new mutation、runtime enforcement flag、runner permit propagation、Falsifier skip、durable outcome-unknown mappingを実装してはならない（MUST NOT）。

#### Scenario: A1 deploy後

- **WHEN** authority/replay boundaryをproductionへdeployする
- **THEN** authorized new mutationはfail closedで、production entry behaviorは変化しない
