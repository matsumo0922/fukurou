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

### 2. static catalogは証明可能なexact `gpt-5.5` standard priceだけを持つ

`trading` evaluation packageにversioned catalogを置き、version、as-of date、official source URL、適用上限、per-million ratesを公開用metadataへ投影する。初期entryは公式pricingが`gpt-5.5 (<272K context length)`に示すuncached input 5、cached input 0.5、output 30 USDとする。（agent 仮決め）

保存usageはphase内の複数requestを合算し、各requestのcontext lengthを保持しない。したがってphase合計inputが272,000未満なら各requestも上限未満であることを証明できるため基本価格を適用し、合計inputが272,000以上なら実際には全requestが上限未満でも保守的にunpricedとする。証明不能なlong-context倍率を推測しない。（agent 仮決め）

価格はselected periodのevent発生日に関係なく、このbuildが持つcatalogで参考換算する。これはhistorical invoiceではなくcurrent catalog equivalentであり、response metadataとdocsで明記する。価格履歴を導入していないため、過去請求の再現を保証しない。（agent 仮決め）

### 3. token式はcached inputとreasoning outputを二重加算しない

Codexの`input_tokens`はcached tokenを含むため、`uncachedInput = inputTokens - cacheReadInputTokens`とする。`output_tokens`はreasoning tokenを含み、`reasoning_output_tokens`は内数なのでoutput rateを`outputTokens`へ一度だけ適用する。

全必須値が非null/非負で、`cacheReadInputTokens <= inputTokens`かつ`reasoningOutputTokens <= outputTokens`の場合だけ換算する。reasoning countがnullの場合は内訳不明として許容するが、存在する矛盾値は拒否する。矛盾、overflowを伴う集計、unknown model、価格帯を証明できないphaseはlist-price専用coverageでunpricedとして残す。金額計算と合計は`BigDecimal`で行い、途中でbinary floating pointを使わない。

### 4. 既存cost sourceの意味を変えずadditive fieldで分離する

既存`knownCostUsd`と`unpricedPhaseCount`はprovider structured output基準のまま維持する。共有`LlmCostStats`の既存fieldをcatalog換算で変更しないため、EvaluationReport、Reflection、Web UIは従来どおり整合する。新規fieldは`/evaluation/costs`用のadditiveなlist-price projectionとして次の意味を持つ。

- `apiListPriceEquivalentUsd`: static catalogで換算できたCodex phaseだけの合計。provider-reported costとは合算しない
- `apiListPriceCoveredPhaseCount`: catalog換算に含まれるCodex phase数
- `apiListPriceUnpricedPhaseCount`: Codex usageはあるがmodel、token整合性、または価格帯を証明できず換算しなかったphase数
- `subscriptionActualCostUsd`: subscription実費を観測できないため常にnull
- `pricingCatalog`: version、as-of、basis、source URL

provider別responseにもlist-price equivalent/coverageを追加し、model別token responseにはlist-price equivalentをnullableで追加する。既存fieldの削除・rename・意味変更はしない。（agent 仮決め）

### 5. production routeから証明する

pure calculation testに加え、`ExposedEvaluationRepository`のevent payload projection testと`GET /evaluation/costs` route testで、保存済みconfigured model + Codex usageがpublic responseのestimateへ到達することを証明する。route-local `.describe {}`、OpenAPI snapshot、generated TypeScript typesも更新する。

## Risks / Trade-offs

- [Catalogが将来の公式価格とdriftする] → version/as-of/sourceをresponseへ返し、unknown/new modelをunpricedにする。価格更新は明示的なcode reviewを要する。
- [current catalogを過去eventへ適用して請求額と誤認する] → field名を`Equivalent`にし、subscription actualを別nullable fieldで返し、historical invoiceではないとdocsへ明記する。
- [configured model欠落でproductionの一部Codex usageが価格化されない] → default modelを捏造せずcoverageとして可視化する。model明示はruntime config側で行う。
- [provider-reported costとcatalog estimateを同じ金額basisと誤認する] → 合算fieldを作らず、既存provider costとlist-price equivalentを別々に返す。
- [phase集計からlong-context価格帯を判定できない] → 合計input自体が272,000未満のphaseだけ基本価格帯を適用し、それ以上は保守的にlist-price unpricedとする。
- [wire contractがclient snapshotとdriftする] → route test、committed OpenAPI snapshot、generated TypeScript typeの差分検査を同じtaskに含める。

## Migration Plan

additive read-model/API変更のためDB migrationはない。rollbackは旧imageへ戻すだけで、既存eventとclientのlegacy fieldはそのまま読める。

## Open Questions

なし。価格値は実装時に公式pricing pageと再照合し、差異があれば設計artifactを先に更新する。
