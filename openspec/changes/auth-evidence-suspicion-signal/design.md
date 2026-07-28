## Context

`LlmInvocationAuditor.invokeAndAudit()` は phase 監査を組み立てる際、`LlmPhaseAuditSignals` に 2 つの認証関連フラグを載せる。

- `authFailureSuspected`: 運用通知シグナル。`true` のとき監査 payload に `authFailureSuspected="true"` を書き、`LLM_CLI_AUTH_FAILURE_RUNBOOK_MESSAGE` を human logger へ出す。`/ops` の provider 集計（`MonitoringRepository`）はこの field を authentication-failure カウントとして読む
- `authEvidenceObserved`: raw output 抑止シグナル。`DefaultLlmOutputParser.parseCodex()` が primary category の先勝ち解決と独立に追跡し、`isSafeCodexLifecycleFailure()` が `true` のとき無条件で raw output を落とす

現状 `authFailureSuspected` は `providerFailure?.category == AUTHENTICATION` だけで決まる。2026-07-23 の障害では refresh token 失効の stderr が出ていたにもかかわらず、stdout が JSONL contract を満たさなかったため primary category が `OUTPUT_CONTRACT` に解決され、この式が false のまま推移した。`authEvidenceObserved` は既に「primary category と独立の認証 evidence」を表現する変数として存在するが、運用通知側へ接続されていない。

## Goals / Non-Goals

**Goals:**

- 認証 evidence が観測された invocation を、primary category を問わず `authFailureSuspected=true` として監査に残す
- 今回の実障害の stderr 文言（`Failed to refresh token` / `refresh_token_reused` / `token_expired`）を既知 evidence として検出できるようにする
- 既存の raw output 抑止規則（fail-closed 方向）を弱めない

**Non-Goals:**

- 通知チャネル（Discord 等）の新設
- `/ops/llm-auth` の status 判定強化（別 issue）
- `AUTHENTICATION` カテゴリの raw output 公開
- primary category 解決ロジック（`CODEX_STDERR_AUTH_FAILURES` の完全一致判定、`knownCompatibilityFailureCategory()` の分類表）の変更
- Claude 側の evidence 追跡の新設（`parseClaude()` は `authEvidenceObserved = false` を維持する）

## Decisions

### D1: `authFailureSuspected` に `authEvidenceObserved` を OR で加える（ユーザー確認済み。issue 本文が式を明示）

```kotlin
authFailureSuspected = providerFailure?.category == LlmProviderFailureCategory.AUTHENTICATION ||
    (invocationResult?.authEvidenceObserved ?: false)
```

代替案として「`authEvidenceObserved` を primary category へ昇格させる（`OUTPUT_CONTRACT` を `AUTHENTICATION` に書き換える）」を検討したが採らない。primary category は fail-closed の分岐（`PROVIDER_ADAPTER_FAILURE_CATEGORIES` による例外化、raw output 可否）に使われており、category を書き換えると contract 上の failure 種別の意味と、`AUTHENTICATION` 固有の disclosure 規則の両方が動く。今回必要なのは観測面だけなので、観測シグナルの導出だけを変える。

### D2: OR を無条件にし、invocation の成否で条件付けしない（agent 仮決め）

`authEvidenceObserved` は invocation が成功した場合（`providerFailure == null`）にも true になりうる。この場合も `authFailureSuspected=true` になり、`/ops` の authentication-failure カウントに false positive が乗る余地がある。

それでも無条件の OR を採る理由:

1. issue 本文が式を明示している
2. 「evidence が観測されたのに、別の解決結果に隠れて通知が立たない」ことがこの issue の是正対象であり、成否という別の解決結果で再びゲートすると同じ形のバグを持ち込む
3. false positive のコストは診断フラグの過剰報告に限られ、資金・paper truth・fail-closed の挙動には影響しない（`authFailureSuspected` は disclosure を一切広げない）

代替案「`authEvidenceObserved && providerFailure != null` に絞る」は受け入れ条件を満たすが、上記 2 の理由で採らない。false positive が運用で問題になった場合は follow-up で絞る。

### D3: 追加文言は evidence 集合（部分一致）だけに入れる（agent 仮決め）

`Failed to refresh token` / `refresh_token_reused` / `token_expired` は `CODEX_KNOWN_AUTH_EVIDENCE_TEXTS` にのみ追加し、`CODEX_STDERR_AUTH_FAILURES`（stderr 全体との完全一致で primary category を `AUTHENTICATION` に確定させる集合）には入れない。実障害の stderr は他の診断行と混在しており完全一致しないため、後者に入れても発火しない。加えて後者へ入れると primary category の解決が変わり、Non-Goals に反する。

`CODEX_KNOWN_AUTH_EVIDENCE_TEXTS` は現在 `CODEX_STDERR_AUTH_FAILURES + setOf(...)` として構成されているため、追加分は既存 `setOf(...)` 側へ並べる。

### D4: `authFailureSuspected` は disclosure を広げない（ユーザー確認済み。issue の「やらないこと」）

`isSafeCodexLifecycleFailure()` は引き続き `authEvidenceObserved` だけを見る。`authFailureSuspected` を disclosure 判定に混ぜない。結果として、追加した 3 文言を含む Codex invocation は raw output が記録されなくなる。認証障害の診断は `authFailureSuspected=true` と failure category で足りるため、このトレードオフを受容する。

## Risks / Trade-offs

- [追加文言により、認証と無関係だが文言を含む Codex 出力の raw output が失われる] → `token_expired` 等は認証文脈以外で Codex が出す蓋然性が低い。失われるのは診断用の raw output だけで、failure category と typed failure は残る
- [成功した invocation で `authFailureSuspected` が立ち、`/ops` の authentication-failure カウントが過大になる] → D2 の判断として受容。診断フラグのみで fail-closed 挙動には影響しない。人間確認事項として PR に転記する
- [`authFailureSuspected=true` は `noDecisionAuditReason()` で `proposer_missing_decision` を返す分岐に入るため、decision 未保存時の no-trade 理由の分布が変わる] → いずれも `proposer_missing_decision` は「判断が保存されなかった」という同じ事実を指し、原因の説明が `proposer_no_tool_calls` より正確になる方向の変化。paper truth の意味は変わらない
- [`authEvidenceObserved` は既知文言の一致判定に過ぎず、未知の認証 evidence を検出しない] → 既存 spec が明記している限界をそのまま引き継ぐ。今回の変更は検出範囲を狭めない
