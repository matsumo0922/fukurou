## Context

`submit_decision` のproduction経路は、MCP processからowner-only Unix socketを通り、app processの `LlmDecisionSubmissionGateway` が `DecisionRepository` を呼ぶ。gateway envelopeはすでに server-owned invocation、phase、phase manifest、effective invocation hashを照合する一方、repository callではphaseが失われ、`ExposedDecisionRepository` は呼び出しごとにrandomなdecision / TradePlan / TradeIntent IDを生成する。MCP handlerにもgateway不在時のdirect repository fallbackが残り、requestの `invocation_id` がserver contextを上書きできる。

既存 `decisions.invocation_id` はnullableでphaseを持たない。2026-07-17にproductionをread-onlyで棚卸しし、非NULL `invocation_id` の重複は0件だった。ただしlegacy rowからphaseを確定できないため、既存rowへphaseを推測backfillしたり、`decisions` 自体へ `(invocation_id, phase)` unique constraintを追加したりしない。

この変更はMCP、gateway、repository、PostgreSQL bootstrapを跨ぎ、response loss・並行実行・migrationを扱うため、独立反証を必須とする。

## Goals / Non-Goals

**Goals:**

- server-owned `(invocationId, phase)` ごとに新規decision side effectを最大1回へ制限する。
- 同じcanonical business payloadのretryへ、最初にcommitしたdecision / TradePlan / TradeIntent IDを返す。
- changed payloadをtyped conflict、完了結果を再構成できないauthorityをtyped UNKNOWNとしてfail closedにする。
- decision、付随entity、terminal evidence、authority completionを単一transactionでcommitする。
- production decision submissionからdirect repository fallbackとcaller-owned invocation identityを除去する。
- legacy historyを変更せずadditive migrationとold-reader rollback compatibilityを維持する。

**Non-Goals:**

- legacy decisionのdedupe、phase推測、backfill、rewrite。
- falsificationやact toolの冪等化、汎用action engine、owner lease、periodic/startup recovery。
- multi-replica coordinator、root deploy script、NAS bootstrap、OpenAPI/UI拡張。
- ambiguousなaction resultの自動再実行。

## Decisions

### 1. 新規authority tableを唯一のstrict keyにする

（ユーザー確認済み）新しい `decision_submission_authorities` は次を保持する。

```text
invocation_id + phase  PRIMARY KEY
payload_schema_version
payload_hash
state                 PENDING | COMPLETED
decision_id
trade_plan_id
trade_intent_id
created_at
completed_at
```

通常経路は1 transaction内でauthorityを `INSERT ... ON CONFLICT DO NOTHING` し、同じkeyのrowを `FOR UPDATE` で読む。winnerだけが既存のdecision insert処理とterminal evidence associationを実行し、result IDsを設定して `COMPLETED` にする。transaction途中の `PENDING` は外部から可視化されず、失敗・process crashではauthorityを含む全mutationがrollbackする。conflict側はwinnerのcommit/rollbackまでPostgreSQL unique arbitrationで待機し、commit後は同じresultを読む。retry loopや別transactionのclaimは作らない。

winner判定はauthority INSERTのrows-affectedだけを正本とする。並走winnerがrollbackした場合、unique arbitration後のloser INSERTが1行を作成してwinnerへ昇格する。事前SELECTの結果から勝敗を推測しない。既存 `COMPLETED` のsame-payload retryは `insertDecisionSubmission` より前でshort-circuitし、decision-by-IDのexact reconstructionだけを行う。したがってopportunity episode close/create、identity failure、dedupe shadow observationを含む既存の付随副作用も再実行しない。

既存 `decisions` へcolumnやunique constraintを足す案は、legacy phaseを推測しないとkeyを構成できないため採用しない。advisory lockだけで直列化する案も、response lossを越えるdurable result authorityを残せないため採用しない。

### 2. repositoryへserver-owned authorityを明示して渡す

（agent 仮決め）`DecisionSubmissionAuthority(invocationId, phase)` を追加し、phase-awareなrepository overloadをPostgreSQLとin-memory実装の両方に持たせる。gatewayはbinding済みのinvocation/phaseからauthorityを構築し、submissionのnullable identityやrequest bodyをkeyの正本にしない。

現行manifestの `invocationId` と `DecisionRunContext.decisionRunId` はproduction composition rootで同じ値から生成される。authority keyはdecision run IDを意味し、gateway bindingのmanifest invocation IDと完全一致することを起動時・gateway testで固定する。将来この2値を分離する変更は、authority key再設計なしには許可しない。

terminal evidence captureの有効・無効にかかわらず、gateway経由decisionはphase-aware overloadを通る。capture有効時はevidence validation、decision/entity insert、evidence/link/coverage insert、authority completionを同一transactionに置く。duplicate retryは既存resultだけを返し、evidenceやcoverageも追加しない。

既存のphaseなし `submitDecision` はtest fixtureや非gatewayの内部組み立て用compatibility boundaryとして残すが、production MCP handlerは invocation phase が存在するときgatewayを必須とする。production call-site inventoryは `FukurouMcpServer.handleSubmitDecision` の1箇所であり、static/route testでdirect fallback不在を固定する。

1つの `(invocationId, phase)` はterminal decision最大1件を表す。現行gatewayが1 launchにつき1接続・1 requestで終了することと合わせてinvariantを構成し、将来gatewayをmulti-request化する場合もこのkeyを変えず、2件目のchanged decisionはconflictにする。

### 3. versioned canonical business payloadで一致を判定する

（agent 仮決め）payload schema v1は、保存されるdecision semanticsを型付きJSONへ投影し、object keyを固定順、decimalを数値同値のplain string、nullable値を明示nullとしてSHA-256化する。対象はaction、close ratio、setup tags、probability/R/cost、tool evidence ID列、fact check、self review、理由、不足データ、NO_TRADE条件、entry intent、TradePlanとinvalidation predicatesである。listの順序は現在の保存契約どおり保持し、fact check / self reviewはJSON object key順だけを正規化する。

`invocationId`、`llmProvider`、`promptHash`、`systemPromptVersion`、`marketSnapshotId`、phase manifest、socket path、raw audit/log/exception/secretはbusiness payload hashへ含めない。identityとphaseはauthority key、terminal evidenceは既存のserver-trusted evidence tableを正本とし、secret/pathを新しいhash projectionへ取り込まない。

同じkeyでschema versionまたはhashが異なれば `DecisionSubmissionConflictException`。hashが一致してもstateが `COMPLETED` でない、result IDが欠ける、またはIDからdecision / plan / intentをexactに再構成できなければ `DecisionSubmissionUnknownException` とし、新しいentityを作らない。

raw request bytesのhash案はJSON key順・decimal表記・caller metadataの差で同じdecisionをconflictにするため採用しない。actionだけのhash案は理由やplan/intent変更を黙って既存resultへ折り畳むため採用しない。

### 4. caller identityは一致検証し、server contextを保存する

（agent 仮決め）MCP requestに `invocation_id` があれば `DecisionRunContext.decisionRunId` と完全一致を要求する。省略または一致時も、`DecisionSubmission.invocationId` は必ずserver contextから設定する。不一致はDBやgatewayへ到達する前に `invalid_request` として拒否する。

schemaから即時削除する案は既存LLM prompt/clientとの互換性を不要に壊すため採用しない。request値を無視する案はspoof attemptを観測不能にするため採用しない。

### 5. typed conflict/UNKNOWNをgatewayとMCPまで保つ

（agent 仮決め）app gatewayは repository exceptionをstable response code `DECISION_SUBMISSION_CONFLICT` / `DECISION_SUBMISSION_UNKNOWN` に写像する。clientはcodeをtyped exceptionへ復元し、MCPは `decision_submission_conflict` / `decision_submission_unknown` error typeを返す。他のvalidation/persistence failureは従来どおりgeneric submission rejectionに閉じ、DB詳細やpayloadを露出しない。

UNKNOWNをgeneric retryable failureへ落とす案はLLMがside effectを再実行し得るため採用しない。

decision recordのconflict/UNKNOWNやgateway不在rejectは、`close_position` / `update_protection` 等の既存risk-reducing act toolをgateしない。RISK_REDUCTION_ONLYのaction制限と実際のrisk reductionは既存broker側のidempotency/CASを使って継続し、decision記録の失敗を理由に保護処理まで拒否しない。

## Risks / Trade-offs

- [canonical projectionのfield漏れでchanged payloadをsame扱いする] → 保存対象fieldを列挙するgolden testと、各fieldを1つずつ変更してhashが変わるfinite inventory testを置く。
- [concurrent insertでduplicate entityが生じる] → PostgreSQL primary key arbitrationと `FOR UPDATE` を使い、2 connection/barrier testで全callerのresult IDとrow countを確認する。
- [winner rollback中のloserが勝敗を誤認する] → authority INSERTのrows-affectedでのみwinnerを決め、winner abort後にloserがfresh winnerへ昇格するbarrier testを置く。
- [terminal evidenceだけ重複する] → winner判定とevidence insertを同じtransaction helperへ置き、response-loss retry後のevidence/link/coverage row countを検証する。
- [retryでopportunity episodeやshadow observationが再実行される] → completed authorityを `insertDecisionSubmission` の前でshort-circuitし、全付随tableのrow countを検証する。
- [legacy rowが新authorityと衝突する] → authority tableを空で追加し、legacy `decisions` を参照・backfillしない。既存同一invocation rowはstrict keyのseedに使わない。
- [旧image rollback後にdirect fallbackが再び有効になる] → schema/authority rowは削除せず、rollback時はLLM launch gateをOFF、active runをdrainし、gateway対応imageへ戻るまでdecision submissionを再開しない。
- [authority tableの異常なPENDING/参照不整合] → UNKNOWNでfail closedし、自動修復・再実行を行わない。read-only調査後に別判断とする。
- [payload schema versionを跨ぐin-flight retry] → version不一致はtyped conflictとしてfail closedにし、deploy/rollback前にactive runをdrainする。旧version payloadを新versionとして再hashしない。

## Migration Plan

1. （ユーザー確認済み）productionの既存 `decisions.invocation_id IS NOT NULL` 重複inventoryが0件であることをPR evidenceへ記録する。
2. bootstrapで空の `decision_submission_authorities` をadditiveに作成してからruntime/gatewayを起動可能にする。legacy rowを読み書きせず、既存tableへunique constraintを追加しない。MCP roleはこのtableを直接利用しないため `MCP_REQUIRED_TABLES` へwrite/read prerequisiteとして追加せず、app-role bootstrapとintegration testをschema availabilityの正本にする。
3. targeted schema/concurrency/gateway/MCP testsとfull validationを通し、新imageを通常deployする。root-installed artifactとNAS bootstrapは変更しない。
4. rollback時は新tableとrowを保持する。LLM launch gateをOFFにしてactive runをdrainし、旧image上ではdecision-capable LLMを再開しない。gateway対応imageへforward後、UNKNOWN/conflictがないことをread-only確認して再開する。
5. 実装が既存 `decisions` のconstraint/backfillへ変わる場合だけ、merge直前に2回目のproduction duplicate inventoryを必須化する。現在の新規空table設計では1回目のinventoryがmigration gateを満たす。

## Open Questions

なし。agent仮決めは独立falsifierとPR reviewerの重点確認対象にし、PRの「人間に確認してほしいこと」へ転記する。
