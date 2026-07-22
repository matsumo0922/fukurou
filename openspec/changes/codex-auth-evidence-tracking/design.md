## Context

PR #296（issue #291）は `LlmInvocationAuditor.isSafeCodexLifecycleFailure()` に3条件のゲートを実装し、Codex の raw output（redactor 経由）を「process の事実だけから決まる3カテゴリ（`PROCESS_EXIT`/`PROCESS_TIMEOUT`/`CLEANUP`）」に限って安全に記録できるようにした。3条件は次の通り。

1. `failure.category` が `PROCESS_EXIT`/`PROCESS_TIMEOUT`/`CLEANUP`
2. adapter（`DefaultLlmOutputParser`）が Codex の出力から failure を一切検出していない（`cliErrorReported == false`）
3. stderr に Codex の既知認証失敗文言（`CODEX_STDERR_AUTH_FAILURES`）が含まれない

issue #295 は、issue #282 の実際の production 障害（`OUTPUT_CONTRACT`/`SCHEMA_DRIFT`）がこの3カテゴリに含まれないため、同じ障害が再発しても診断できないという limitation に対応する。`OUTPUT_CONTRACT`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`UNKNOWN_PROVIDER_FAILURE` は Codex の出力テキストを解釈して分類されるカテゴリであり、`DefaultLlmOutputParser.parseCodex()` の先勝ち方式（`providerCategory = providerCategory ?: message?.knownCompatibilityFailureCategory() ?: UNKNOWN_PROVIDER_FAILURE`）により、認証 evidence が別カテゴリの陰に隠れて raw output に混入するリスクがある。

## Goals / Non-Goals

**Goals:**

- primary category の先勝ち解決とは独立に、「認証関連 evidence を出力中（stdout/stderr のいずれか）に一度でも観測したか」を追跡する
- その追跡結果が false の場合に限り、`OUTPUT_CONTRACT`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`UNKNOWN_PROVIDER_FAILURE` の raw output を安全に監査記録へ残せるようにする
- #282 と同形の `OUTPUT_CONTRACT`/`SCHEMA_DRIFT` 障害（起動失敗・非 JSON stdout）で raw output が記録されることを回帰テストで証明する

**Non-Goals:**

- `DefaultLlmOutputParser` の先勝ち方式カテゴリ決定ロジック自体の撤廃（互換性への影響が大きいため、独立追跡を「追加」する形に留める）
- `AUTHENTICATION` カテゴリの raw output 非保持方針の変更（引き続き最も保守的に扱う）
- `SecretRedactor` を出力解析型に作り替えること
- Claude provider の audit 挙動の変更
- 新しい認証失敗文言パターンの追加・強化（`CODEX_STDERR_AUTH_FAILURES` の内容自体は変更しない）

## Decisions

### D1: evidence 追跡フィールドは `LlmProviderFailure` ではなく `ParsedLlmOutput`/`LlmInvocationResult` に置く

**帰属**: agent 仮決め（issue #295 本文は `LlmProviderFailure` モデルへの追加を候補として挙げていたが、コード調査の結果この形は成立しないため変更した）

issue #295 の「やること」は `DefaultLlmOutputParser`（または `LlmProviderFailure` モデル）の拡張を候補として挙げているが、実装を検証した結果 `LlmProviderFailure` への追加は機能しない。理由: `isSafeCodexLifecycleFailure()` の既存条件1（lifecycle category）は `cliErrorReported == false`、つまり **adapter が failure を一切検出していない**ケースに限定される。このとき `DefaultLlmOutputParser.parseCodex()` は `ParsedLlmOutput.providerFailure = null` を返し、`LlmInvocationResult.providerFailure` も null になる。`LlmProviderFailure` インスタンス自体が存在しないため、そこに evidence フラグを載せても lifecycle 経路（条件1）には運べない。

`LlmInvocationAuditor.primaryProviderFailure()` は lifecycle category を検出すると `lifecycleFailure(category)` で auditor 側から新規に `LlmProviderFailure` を合成するが、これは parser 由来ではないため、parser が計算した evidence フラグを引き継げない。

よって `ParsedLlmOutput`（parser の戻り値）と `LlmInvocationResult`（invoker の戻り値）に `authEvidenceObserved: Boolean = false` を追加し、`providerFailure` の有無に関わらず常に運ばれるようにした。`LlmPhaseAuditSignals` はこれを `invocationResult?.authEvidenceObserved ?: false` として読む。

**代替案として検討したもの**:
- `LlmProviderFailure` へ追加 → 上記の理由で lifecycle 経路（既存条件3 の置き換え）に対応できないため却下
- 新しい別クラス（`LlmCodexAuditEvidence` 等）を新設して `ParsedLlmOutput` に持たせる → フィールド1個のためだけに型を増やすのは過剰。`Boolean` で十分

### D2: evidence の判定範囲は「turn.failed/error イベントの message」＋「stdout/stderr 全文の既知文言 contains 検査」

**帰属**: agent 仮決め

`parseCodex()` のイベントループ内で `turn.failed`/`error` の message を `knownCompatibilityFailureCategory()` で判定し、結果が `AUTHENTICATION` であれば first-win で他カテゴリが確定済みでも `authEvidenceObserved = true` にする。

加えて、イベントループ終了後に `CODEX_STDERR_AUTH_FAILURES` の各文言を **stdout と stderr の両方**に対して `.contains()`（部分一致）で検査する。#296 の既存 condition 3 は stderr のみを対象にしていたが、issue #282 が示す「起動失敗で stdout が空・非 JSON になるケース」では、認証失敗の平文メッセージが JSON でラップされずそのまま stdout に出る可能性を排除できない。issue #295 の文言「出力中に一度でも観測したか」は stdout/stderr のどちらかに限定していないため、両方を検査範囲に含めるのが安全側の解釈と判断した。

`.contains()`（部分一致）を採用したのは #296 の condition 3 と同じ安全側判定を踏襲するため（parser 内部の `stderrAuthFailure`＝主 category 決定用の判定は `trimEnd().. in set` の完全一致だが、これは「認証失敗と断定する」ための厳格な判定であり、evidence 追跡（「疑わしきは記録しない」）とは目的が異なるため意図的に区別する）。

### D3: `isSafeCodexLifecycleFailure()` を2経路の disjunction に再構成する

```kotlin
private fun isSafeCodexLifecycleFailure(
    request: LlmInvocationRequest,
    auditSignals: LlmPhaseAuditSignals,
): Boolean {
    if (request.provider != LlmProvider.CODEX) return false
    if (auditSignals.authEvidenceObserved) return false

    return when (auditSignals.providerFailure?.category) {
        in CODEX_SAFE_LIFECYCLE_FAILURE_CATEGORIES -> !auditSignals.cliErrorReported
        in CODEX_SAFE_OUTPUT_INTERPRETED_FAILURE_CATEGORIES -> true
        else -> false
    }
}
```

- lifecycle 経路（条件1）は既存の `cliErrorReported == false` 要件をそのまま維持する（#296 の condition 2 が守っていた「adapter-derived failure が lifecycle category に上書きされる複合ケース」への防御は evidence 追跡だけでは代替できないため）
- output-interpreted 経路（新設）は `OUTPUT_CONTRACT`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`UNKNOWN_PROVIDER_FAILURE` に対して `cliErrorReported` を要求しない。これらのカテゴリは定義上すべて adapter 由来（`cliErrorReported` は必然的に true）であり、意味のある追加条件にならないため
- `authEvidenceObserved` チェックを関数冒頭でどちらの経路よりも先に評価することで、「lifecycle category にせよ output-interpreted category にせよ、認証 evidence が独立に観測されていれば無条件で記録しない」という最優先の否定条件を明示する
- 旧 condition 3（stderr 直接 inspect）は `authEvidenceObserved` に統合されるため、この関数は `processResult: ProcessRunResult` を引数に取らなくなる（呼び出し元の1箇所を更新）

### D4: 既存テストの `ConfigurableAuditLlmInvoker` は `authEvidenceObserved` を明示指定できるようにする

**帰属**: agent 仮決め

`LlmInvocationAuditorTest.kt` の `ConfigurableAuditLlmInvoker` は実際の `DefaultLlmOutputParser` を通さず `LlmInvocationResult` を直接組み立てるテスト double であり、`authEvidenceObserved` のデフォルト値（false）のままでは既存テスト `invokeAndAudit_omitsRawOutputWhenStderrCarriesKnownAuthSignatureAlongsideProcessExit`（stderr に既知認証文言があるケース）が意図した状態を再現できなくなる。この double に `authEvidenceObserved: Boolean = false` パラメータを追加し、該当テストで明示的に `true` を渡す形に更新する。

加えて、`DefaultLlmOutputParser.parseCodex()` が実際にこの複合ケース（成功 event stream + stderr 既知認証文言）で `authEvidenceObserved = true` を返すことを証明する end-to-end テストを `LlmInvocationAuditorTest.kt` 側に別途追加する（production call path の証明として、hand-built fixture だけでなく実際の parser 配線を通す）。

## Risks / Trade-offs

- [Risk] stdout/stderr の contains 検査を広げたことで、既知認証文言が業務データ（会話内容や market data）に偶然含まれ、false positive で `OUTPUT_CONTRACT`/`UNKNOWN_PROVIDER_FAILURE` の raw output が不必要に非公開になるケースがありうる → Mitigation: false positive は「本来見えるはずの診断情報が見えない」という運用上の不便に留まり、secret 漏洩方向の安全性は損なわない。安全側に倒す設計として許容する
- [Risk] `CODEX_STDERR_AUTH_FAILURES` の文言は Codex CLI のバージョンに依存する固定文字列であり、CLI 側の文言変更で evidence 追跡が漏れる可能性は #296 から変わらず残る → Mitigation: 既存の残存リスクと同一であり、本 change のスコープでは対処しない（`adapterSchemaVersion` によるバージョンピン止めで検知する既存の仕組みに委ねる）
- [Trade-off] `isSafeCodexLifecycleFailure()` のシグネチャ変更（`processResult` 引数の削除）は呼び出し元1箇所の更新を伴う。影響範囲は限定的だが、既存の3条件ゲートを丸ごと書き換えるため、レビューでは新旧の等価性（lifecycle 経路が #296 と同じ結果を返すこと）を重点確認する必要がある

## Migration Plan

- schema 変更なし。`authEvidenceObserved` はデフォルト `false` の追加フィールドであり、既存の呼び出し箇所（Claude path、lifecycleFailure 合成箇所）は影響を受けない
- ロールバック: 本 change の commit を revert すれば #296 の3条件ゲートに戻る。DB マイグレーションやランタイム設定の変更は伴わない

## Open Questions

（なし。issue #295 の受け入れ条件は本設計で全て充足する）
