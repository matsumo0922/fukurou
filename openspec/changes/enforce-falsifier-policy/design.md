## Context

Foundation は entry intent ごとに `FalsifierPolicyDecision` と canonical audit event を保存し、`OFF_V1 / ENTER / required=false / POLICY_OFF` の全 identity を持つ `FalsifierPolicyPermit` を runner 内で再構築する。
現行 runner は permit の有無にかかわらず Falsifier を起動し、`PlaceOrderCommand`、SafetyFloor、PaperBroker は permit を参照しない。

本 change は policy enforcement を一つの paper order authority boundary として導入する。
permit を単なる runner 分岐にせず、durable decision、internal command、SafetyFloor、broker replay identity の全てに束縛する。

## Goals / Non-Goals

**Goals:**

- canonical `OFF_V1 / ENTER` permit がある場合だけ Falsifier を省略する
- permit を MCP wire に露出せず runner の internal command path に限定する
- SafetyFloor と PaperBroker が durable policy decision の全 identity を独立に検証する
- preview/place 間の position 変化と、別 caller による v2 request ID の偽装を fail closed にする
- paper commit の可能性がある failure を no-trade と誤分類しない
- intent consumption と他の SafetyFloor rule を変更しない

**Non-Goals:**

- `CONDITIONAL_V1` の risk、regime、recent-loss 判定
- `ADD_LONG` の OFF bypass
- rejected-intent shadow、policy 間の評価集計、実験期間の判定
- `OFF_V1` / `CONDITIONAL_V1` の production activation
- live trading

## Decisions

### 1. policy foundation を entry flow の唯一の分岐入力として渡す

`establishEntryPolicyFoundation` が返した decision/permit を後続 entry flow へ明示的に渡す。
permit が canonical `OFF_V1 / ENTER` の場合だけ Falsifier phase を起動せず、fresh falsification record や Falsifier phase observation を作らない。
`ALWAYS_ON_V1`、`CONDITIONAL_V1`、`OFF_V1 / ADD_LONG`、permit 欠損は従来どおり fresh `APPROVED` を必須とする。

runner 内の policy enum だけで分岐する案は、保存済み decision との不一致を見逃すため採用しない。
durable decision が `required=false` というだけで自動省略する案も、action/reason/config identity の欠損を許すため採用しない。

### 2. permit は internal command field としてのみ伝播する

`PlaceOrderCommand` に Kotlin 内部専用の authority field を追加し、runner が foundation から受け取った permit を preview/place command に設定する。
MCP の `preview_order` / `place_order` input、OpenAPI、JSON serializer には field を追加しない。
MCP から構築した command と既存 caller の command は permit を持たない。

permit は decision ID、intent ID、action、policy、required/reason codes、runtime config version ID/hash を保持する。
runner は permit と command intent/action が一致する場合だけ OFF path を構築する。

wire から opaque token を受ける案は caller に bypass capability を公開するため採用しない。
process-local Boolean だけを渡す案は durable authority の再読と fingerprint を構築できないため採用しない。

### 3. broker は durable decision を再読し、SafetyFloor context に verified authority を渡す

PaperBroker runtime に `FalsifierPolicyDecisionRepository` を配線する。
permit 付き command では、broker は intent ID で decision/event を読み、次の全 identity が permit と完全一致することを要求する。

- decision ID と intent ID
- action が `ENTER`
- policy が `OFF_V1`
- `required=false` と reason codes が `POLICY_OFF` のみ
- runtime config version ID/hash

repository failure、decision/event 欠損、partial state、不一致は ledger mutation 前に fail closed にする。
検証済み authority を `SafetyFloorContext` に渡し、SafetyFloor は permit、durable decision、command intent の一致を再確認して fresh `APPROVED` の代替とする。
fresh approval path では従来の `EntryIntentSafetySnapshot` を使う。

SafetyFloor が repository を直接読む案は pure evaluation を suspend I/O に変えるため採用しない。
broker が検証済み typed snapshot を作り、SafetyFloor がその identity と rule を評価する。

### 4. OFF authority は placement lock 内の ENTER 状態に限定する

OFF permit の place では、ledger が placement と intent consumption に使う同じ排他境界内で最新 open position を再確認し、0 件の場合だけ entry を許可する。
preview 時点で flat でも、place までに resting BUY が約定して open position が生じた場合は拒否する。
これにより proposer の `ENTER` が実行時に実質 `ADD_LONG` へ変化する TOCTOU を閉じる。

`ADD_LONG` は policy にかかわらず fresh `APPROVED` を要求する。
OFF permit を resting order の将来の fill gate へ再適用せず、placement 時に intent を消費した後の fill 再評価は既存契約を維持する。

lock 外の position snapshot だけを使う案は preview/place 間の race を閉じないため採用しない。

### 5. v2 client request namespace を OFF permit 専用に予約する

OFF ENTER の place command は、normalized business fields と permit の全 identityを canonical projection にし、SHA-256 から `runner-place-v2-<hash>` を生成する。
business fields には少なくとも intent ID、symbol、side/order type、size、price、trade group、protective STOP、TP、estimated win probability、time stop、canonical thesis identity を含める。
自由文 reason と audit の非権限 field は fingerprint から除外し、数値と nullable field は既存 normalized preview 表現と同じ規則で canonicalize する。

broker は `runner-place-v2-` prefix を予約し、既存 result lookup より前に次を検証する。

1. internal permit が存在する
2. durable decision の全 identity が permit と一致する
3. command から再計算した canonical fingerprint が client request ID と一致する

検証は新規 mutation と既存 result replay の双方に適用する。
permit のない MCP caller は、正しい既存または未使用の v2 ID を渡しても拒否される。
別 intent、数量、価格、STOP/TP、time stop、policy authority は同じ結果を replay できない。
fresh approval path は既存 namespace を維持し、v2 ID を使わない。

既存 result lookup 後に authority を検証する案は、偽装 caller に replay result を返すため採用しない。
command ID だけを hash する案は business payload と policy authority を束縛しないため採用しない。

### 6. intent consumption と SafetyFloor の安全規則を維持する

OFF authority が置換するのは fresh falsification 条件だけである。
消費済み intent、intent/command payload 不一致、STOP、ナンピン禁止、最大 drawdown、risk、exposure、cash、EV、blackout、symbol rule の検証順と意味は維持する。
OFF permit の理由は policy bypass として記録し、実行していない Falsifier の `APPROVED` を注文理由や監査へ捏造しない。

ToolCallGuard に新しい pre-mutation event は追加しない。
mutation 前の authority 正本は既存の policy decision/event、mutation 後の相関は order の intent ID と fingerprinted client request ID とする。

### 7. failure は commit possibility を境界に分類する

policy repository の保存・読取、permit 照合、v2 fingerprint 検証が新規 ledger side effect 前に失敗した場合は fail closed の no-trade とする。
broker が place を commit した可能性がある後に ACK、completion audit、authority 再読が失敗し、既存結果の有無を確定できない場合は outcome unknown とする。
no-trade record を新規作成して「注文なし」と断定しない。

retry は durable policy decision/event と order の intent ID / fingerprinted client request ID から exact authority を再構築できる場合だけ既存 result を返す。
authority が復元できなければ、再 mutation も no-trade への縮退も行わない。

ToolCallGuard の事前 event を増やす案は、既存の policy decision/event と order identity で同じ監査目的を満たせるため採用しない。

## Risks / Trade-offs

- [internal field が別 caller から誤設定される] → broker が durable decision と v2 fingerprint を既存 lookup 前に再検証する
- [preview 後に position が発生する] → placement/intent consumption の排他境界内で open position 0 件を再検査する
- [repository outage が entry 中に発生する] → side effect 前は fail closed、commit possibility 後は outcome unknown に分離する
- [MCP caller が v2 ID を観測する] → permit は wire に存在せず、既存・未使用 ID の双方を pre-lookup で拒否する
- [OFF bypass が他の safety rule を迂回する] → fresh falsification の判定だけを verified authority で置換し、評価順と他 rule を維持する
- [実装 diff が 1,000 行を超える] → production contract と focused regression を優先し、超過見込みが確定した場合は「inactive authority plumbing」と「skip activation」に stacked 分割する。前者単独では Falsifier を省略しない

## Migration Plan

1. authority plumbing、SafetyFloor/Broker validation、v2 replay gate、runner skip を同じ deployable change として実装する。
2. default `ALWAYS_ON_V1` で既存 fresh approval path の回帰を確認する。
3. `OFF_V1` / `CONDITIONAL_V1` は本 change では production activate しない。
4. rollback は code を戻すだけとし、保存済み decision/event、order、execution を削除または書換えない。

## Open Questions

なし。
