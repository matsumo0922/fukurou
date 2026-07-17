## Context

`DefaultLlmOutputParser` は Codex 0.142.5 の `turn.completed.usage` から `input_tokens`、`cached_input_tokens`、`output_tokens`、`reasoning_output_tokens` を保存するが、`totalCostUsd` は null のままである。`LlmInvocationAuditor` は同じ event の `details.configuredModel` に request/runtime config由来のmodel identityを保存する。一方、`ExposedEvaluationRepository` はusageだけを読み、`EvaluationMath.summarizeLlmCosts` はprovider申告costだけを合計するため、Codex phaseはtokenが揃っていてもunpricedになる。

公式OpenAI pricingの2026-07-17確認値では、standard APIの`gpt-5.5`は1M tokensあたりuncached input USD 5、cached input USD 0.5、output USD 30である。Codex CLIはsubscription経由であり、この換算値は請求額ではない。

## Goals / Non-Goals

**Goals:**

- Issue #189 のCodex token cost DoDを、既存audit eventからのread-time集計で満たす。
- provider申告cost、static catalog由来のAPI list-price estimate、subscription実費を混同しない。
- model/token attributionが不十分なphaseを0 USDに変換しない。
- `/evaluation/costs`の既存fieldとbounded queryを維持する。

**Non-Goals:**

- OpenAI pricingのnetwork取得、自動更新、過去価格履歴の再現
- ChatGPT/Codex subscription実費やquota残量の推定
- audit event、DB schema、既存履歴のbackfill
- Claude向け独自static price catalog
- evaluation report artifact/Web UIへの追加表示
- Issue #189のreal-provider deploy smoke

## Decisions

### 1. evaluation read modelでconfigured modelを結合する

`SELECT_LLM_PHASE_USAGE_SQL`の既存payloadから`details.configuredModel`を読み、`LlmPhaseUsageFact`へnullableに追加する。DB columnやaudit payloadを増やさず、既存eventをそのまま評価する。（ユーザー確認済み: PR-3をcost reporting境界に限定）

`configuredModel`がない`CLI_DEFAULT`や、catalogにexact matchしないalias/modelは推測しない。Codex CLIの現在のdefaultが`gpt-5.5`でも、保存factがmodelを証明しない限りunpricedにする。（agent 仮決め）

### 2. static catalogはexact `gpt-5.5` standard priceだけを持つ

`trading` evaluation packageにversioned catalogを置き、version、as-of date、official source URL、per-million ratesを公開用metadataへ投影する。初期entryは`gpt-5.5`のuncached input 5、cached input 0.5、output 30 USDとする。（agent 仮決め）

価格はselected periodのevent発生日に関係なく、このbuildが持つcatalogで参考換算する。これはhistorical invoiceではなくcurrent catalog equivalentであり、response metadataとdocsで明記する。価格履歴を導入していないため、過去請求の再現を保証しない。（agent 仮決め）

### 3. token式はcached inputとreasoning outputを二重加算しない

Codexの`input_tokens`はcached tokenを含むため、`uncachedInput = inputTokens - cacheReadInputTokens`とする。`output_tokens`はreasoning tokenを含み、`reasoning_output_tokens`は内数なのでoutput rateを`outputTokens`へ一度だけ適用する。

全必須値が非null/非負で、`cacheReadInputTokens <= inputTokens`の場合だけ換算する。矛盾、overflowを伴う集計、unknown modelはunpricedとしてcoverageへ残す。金額計算と合計は`BigDecimal`で行い、途中でbinary floating pointを使わない。

### 4. cost sourceをadditive fieldで分離する

既存`knownCostUsd`はprovider structured outputが報告したcostの合計として維持する。新規fieldは次の意味を持つ。

- `apiListPriceEquivalentUsd`: provider-reported costがあればそれを使い、欠落したexact Codex phaseだけcatalog estimateで補った合計
- `catalogEstimatedCostUsd`: static catalogで算出した部分だけの合計
- `apiListPriceCoveredPhaseCount`: 上記equivalentに含まれるphase数
- `unpricedPhaseCount`: provider-reported costもcatalog estimateもないphase数
- `subscriptionActualCostUsd`: subscription実費を観測できないため常にnull
- `pricingCatalog`: version、as-of、basis、source URL

provider別responseにもequivalent/estimate/coverageを追加し、model別token responseにはcatalog estimateをnullableで追加する。既存fieldの削除・renameはしない。（agent 仮決め）

### 5. production routeから証明する

pure calculation testに加え、`ExposedEvaluationRepository`のevent payload projection testと`GET /evaluation/costs` route testで、保存済みconfigured model + Codex usageがpublic responseのestimateへ到達することを証明する。route-local `.describe {}`、OpenAPI snapshot、generated TypeScript typesも更新する。

## Risks / Trade-offs

- [Catalogが将来の公式価格とdriftする] → version/as-of/sourceをresponseへ返し、unknown/new modelをunpricedにする。価格更新は明示的なcode reviewを要する。
- [current catalogを過去eventへ適用して請求額と誤認する] → field名を`Equivalent`/`Estimated`にし、subscription actualを別nullable fieldで返し、historical invoiceではないとdocsへ明記する。
- [configured model欠落でproductionの一部Codex usageが価格化されない] → default modelを捏造せずcoverageとして可視化する。model明示はruntime config側で行う。
- [provider-reported costとcatalog estimateを二重計上する] → phase単位でprovider-reportedを優先し、catalogはcost欠落時だけ適用する。
- [wire contractがclient snapshotとdriftする] → route test、committed OpenAPI snapshot、generated TypeScript typeの差分検査を同じtaskに含める。

## Migration Plan

additive read-model/API変更のためDB migrationはない。rollbackは旧imageへ戻すだけで、既存eventとclientのlegacy fieldはそのまま読める。

## Open Questions

なし。価格値は実装時に公式pricing pageと再照合し、差異があれば設計artifactを先に更新する。
