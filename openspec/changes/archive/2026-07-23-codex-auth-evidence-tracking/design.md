## Context

PR #296（issue #291）は `LlmInvocationAuditor.isSafeCodexLifecycleFailure()` に3条件のゲートを実装し、Codex の raw output（redactor 経由）を「process の事実だけから決まる3カテゴリ（`PROCESS_EXIT`/`PROCESS_TIMEOUT`/`CLEANUP`）」に限って安全に記録できるようにした。3条件は次の通り。

1. `failure.category` が `PROCESS_EXIT`/`PROCESS_TIMEOUT`/`CLEANUP`
2. adapter（`DefaultLlmOutputParser`）が Codex の出力から failure を一切検出していない（`cliErrorReported == false`）
3. stderr に Codex の既知認証失敗文言（`CODEX_STDERR_AUTH_FAILURES`）が含まれない

issue #295 は、issue #282 の実際の production 障害（`OUTPUT_CONTRACT`/`SCHEMA_DRIFT`）がこの3カテゴリに含まれないため、同じ障害が再発しても診断できないという limitation に対応する。

## Falsification Round 1（gpt-5.6-sol high、clean context）

初回設計案は独立反証で4件の blocking 反例が見つかった。以下は反証内容と、各反例に対する処置。

### Finding 1（blocking, 設計修正で解消）

初回案の evidence 文字列 scan は `CODEX_STDERR_AUTH_FAILURES`（stderr 専用の複合文言2件）だけを対象にしていたため、`knownCompatibilityFailureCategory()` が `AUTHENTICATION` に分類する "Not logged in" / "Invalid authentication credentials" が非 JSON raw stdout に単独で現れるケース（#282 の起動失敗と同形）を検出できなかった。→ D2 で scan 対象文言集合を拡張して解消（下記）。

### Finding 2（blocking, security。issue スコープとの trade-off）

`UNKNOWN_PROVIDER_FAILURE` は定義上「既知パターンに一切マッチしなかった」任意テキストであり、有限の既知文言リストでは「認証 evidence が不存在であること」を証明できない。この不完全性は `OUTPUT_CONTRACT` カテゴリ（非 JSON の raw garbage である #282 のケースを含む）にも構造的に存在する。#296 では `OUTPUT_CONTRACT`/`UNKNOWN_PROVIDER_FAILURE` の raw output は一切公開しない設計だったため、この残存リスクは #296 には存在しなかった新規リスクである（design.md 初版の「#296 から変わらない」という記述は誤りだった）。

**処置**: main（propose 担当）がこの run 内でユーザーに直接 `AskUserQuestion` を投げ、確認を得た（**帰属: ユーザー確認済み**）。監査可能性のため、質問と回答をそのまま以下に転記する。

> **質問**: 「既知文言リストでは『認証 evidence 不存在』を完全には証明できないという残存リスクを、どの範囲で受容する？」
>
> 選択肢:
> 1. `OUTPUT_CONTRACT`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED` のみ、`UNKNOWN_PROVIDER_FAILURE` は除外（推奨として提示）
> 2. issue の明示スコープどおり4カテゴリ全部
> 3. 全面後退：構造化イベント経由の `RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED` のみ（`OUTPUT_CONTRACT` も除外し、#282 の受け入れ条件1を満たさなくなる案）
>
> **回答**: 「issue の明示スコープどおり4カテゴリ全部」

この回答により、`CODEX_SAFE_OUTPUT_INTERPRETED_FAILURE_CATEGORIES` は `OUTPUT_CONTRACT`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`UNKNOWN_PROVIDER_FAILURE` の4カテゴリ全部を含む方針で確定した。この残存リスクは「Risks / Trade-offs」に明記する。

### Finding 3（blocking, 設計修正で解消）

`authEvidenceObserved: Boolean = false` という default 値は、「scan した結果 evidence が無かった」と「そもそも scan されていない（field が未設定のまま）」を区別できず、security gate の否定証拠が fail-open になる。将来 `LlmInvoker` の新しい実装やテスト double がこの field を指定し忘れると、暗黙に「安全」として扱われてしまう。→ D5 で default を撤廃し、全構築箇所で明示必須にすることで解消（下記）。

### Finding 4（blocking, 検証可能性）

初回 tasks.md は、#282 と同形の受け入れ条件1・2を hand-built double（`ConfigurableAuditLlmInvoker`）だけで満たせる書き方だった。実際に `DefaultLlmOutputParser` → `LlmInvoker` → `LlmInvocationAuditor` という production の配線を経由して #282 相当のケースが正しく扱われることを証明するテストが必須化されていなかった。→ D4 で `ShellLlmInvoker`（実装、`outputParser = DefaultLlmOutputParser()` 既定）に fake `ProcessRunner` を組み合わせた production-wiring テストを必須化することで解消（下記）。

### Non-blocking

`authEvidenceObserved = true` で raw output を非公開にしても、primary category が `AUTHENTICATION` でなければ `authFailureSuspected`（運用ログ通知のトリガー）は false のままになる。仕様が「非公開」だけを要求する範囲では blocking ではないが、独立追跡の運用上の終端（人間への通知）が欠けている。本 change のスコープでは対応せず、follow-up として報告する。

## Falsification Round 2（同一 falsifier による round 1 修正の再反証）

round 1 の4件の修正を適用した版を同じ falsifier に再反証させた結果、Finding 1・3（`ParsedLlmOutput`/`LlmInvocationResult` の範囲）・4 は解消と確認された。新たに2件の blocking が見つかった。

### Blocking A（blocking, spec correctness/security。設計修正で解消）

delta spec のシナリオ title・THEN 節が「no authentication evidence is present anywhere」「remaining fail-closed」といった、実装（既知の固定文言との一致判定）が実際に保証する範囲より強い表現になっており、本文中の残存リスク記述（Finding 2 の帰結）と矛盾して読める。→ 該当する4箇所のシナリオ title を「no known authentication-evidence text is observed」系の表現に統一し、`UNKNOWN_PROVIDER_FAILURE` シナリオの THEN 節から「fail-closed」を raw output 保持と並置しない形（trading-decision の fail-closed と audit exposure の条件を別々に記述）へ書き換えて解消した。本 design.md の Goals も同様に「安全に」という無限定の表現を「既知 evidence 文言の不在という条件の範囲内で」に修正した。

### Blocking B（blocking, fail-open upgrade path。設計修正で解消）

`LlmPhaseAuditSignals.authEvidenceObserved: Boolean = false`（tasks 3.1 の当初案）が default を持っていたため、`ParsedLlmOutput`/`LlmInvocationResult` で確立した fail-closed 不変条件が、security gate 直前のこの集約構造体で再び崩れうるという指摘。`LlmPhaseAuditSignals` は `private data class` で構築箇所は現状1箇所のみだが、将来の変更でその1箇所が `authEvidenceObserved` の明示を省略してもコンパイルが通ってしまう。→ D5 を拡張し、`LlmPhaseAuditSignals.authEvidenceObserved` からも default を撤廃することで解消した（他のフィールドは対象外、理由は D5 参照）。

### Finding 2 の監査可能性

falsifier から、「ユーザー確認済み」という帰属を teammate 経由の伝言としてではなく、質問と回答そのものを読める形で示してほしいという指摘があった。→ Finding 2 のセクションに `AskUserQuestion` の質問文・選択肢・実際の回答をそのまま転記した。

## Falsification Round 3（Blocking A・B・Finding 2 は解消確認、新規 Blocking C）

round 2 の修正を同じ falsifier に再反証させた結果、Blocking A・B・Finding 2 は解消と確認された。新たに1件の blocking が見つかった。

### Blocking C（blocking, fail-open upgrade path。設計修正で解消）

round 2 で「`cliErrorReported`/`authFailureSuspected`/`cleanupFailed`/`providerFailure` は security gate の否定証拠として直接使われないため default を維持してよい」と判断したが、この判断のうち `cliErrorReported` に関する部分が事実誤りだった。`isSafeCodexLifecycleFailure()` の lifecycle 経路は `!auditSignals.cliErrorReported` を直接安全条件として使っており、default `false` のまま構築箇所で明示が省略されると誤って安全側に倒れ、#296 の condition 2 が閉じた反例（adapter が output text から failure を検出しているのに lifecycle category が primary として選ばれる複合ケース）が再現する。→ D5 を再拡張し、`cliErrorReported` も default を撤廃することで解消した。最終的に default を撤廃するのは `authEvidenceObserved` と `cliErrorReported` の2フィールド。

## Goals / Non-Goals

**Goals:**

- primary category の先勝ち解決とは独立に、「既知の認証関連 evidence 文言（`CODEX_KNOWN_AUTH_EVIDENCE_TEXTS`）を出力中（stdout/stderr のいずれか）に一度でも観測したか」を追跡する（既知文言との一致判定であり、任意の未知 secret の不存在を証明するものではない。Risks 参照）
- その追跡結果が false の場合に限り、`OUTPUT_CONTRACT`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`UNKNOWN_PROVIDER_FAILURE` の raw output を、既知 evidence 文言の不在という条件の範囲内で監査記録へ残せるようにする
- #282 と同形の `OUTPUT_CONTRACT`/`SCHEMA_DRIFT` 障害（起動失敗・非 JSON stdout）で raw output が記録されることを、production の配線を経由したテストで証明する
- evidence 追跡の否定結果（`authEvidenceObserved == false`）を fail-closed な形（default なし、明示必須）で扱う

**Non-Goals:**

- `DefaultLlmOutputParser` の先勝ち方式カテゴリ決定ロジック自体の撤廃（互換性への影響が大きいため、独立追跡を「追加」する形に留める）
- `AUTHENTICATION` カテゴリの raw output 非保持方針の変更（引き続き最も保守的に扱う）
- `SecretRedactor` を出力解析型に作り替えること
- Claude provider の audit 挙動の変更
- 新しい認証失敗文言パターンの追加・強化（既知文言の集合は Finding 1 で拡張した範囲に留める）
- 「既知文言に一致しない任意テキストに未知の secret が含まれていないこと」の完全な保証（Finding 2 参照。有限の文字列一致では原理的に不可能であり、issue #295 のスコープでは対応しない）

## Decisions

### D1: evidence 追跡フィールドは `LlmProviderFailure` ではなく `ParsedLlmOutput`/`LlmInvocationResult` に置く

**帰属**: agent 仮決め（issue #295 本文は `LlmProviderFailure` モデルへの追加を候補として挙げていたが、コード調査の結果この形は成立しないため変更した。falsifier のレビューでもこの配置自体は妥当と確認された）

issue #295 の「やること」は `DefaultLlmOutputParser`（または `LlmProviderFailure` モデル）の拡張を候補として挙げているが、実装を検証した結果 `LlmProviderFailure` への追加は機能しない。理由: `isSafeCodexLifecycleFailure()` の既存条件1（lifecycle category）は `cliErrorReported == false`、つまり **adapter が failure を一切検出していない**ケースに限定される。このとき `DefaultLlmOutputParser.parseCodex()` は `ParsedLlmOutput.providerFailure = null` を返し、`LlmInvocationResult.providerFailure` も null になる。`LlmProviderFailure` インスタンス自体が存在しないため、そこに evidence フラグを載せても lifecycle 経路（条件1）には運べない。

`LlmInvocationAuditor.primaryProviderFailure()` は lifecycle category を検出すると `lifecycleFailure(category)` で auditor 側から新規に `LlmProviderFailure` を合成するが、これは parser 由来ではないため、parser が計算した evidence フラグを引き継げない。

よって `ParsedLlmOutput`（parser の戻り値）と `LlmInvocationResult`（invoker の戻り値）に `authEvidenceObserved: Boolean` を追加し、`providerFailure` の有無に関わらず常に運ばれるようにした。`LlmPhaseAuditSignals` はこれを `invocationResult?.authEvidenceObserved ?: false` として読む（`invocationResult` 自体が null になるのは起動失敗など invocation が完了しなかったケースのみで、その場合 `isSafeCodexLifecycleFailure()` に到達する前に別経路で failure 処理される）。

### D2: evidence の判定範囲は「turn.failed/error イベントの message」＋「stdout/stderr 全文の既知文言 contains 検査」（Finding 1 で拡張）

`parseCodex()` のイベントループ内で `turn.failed`/`error` の message を `knownCompatibilityFailureCategory()` で判定し、結果が `AUTHENTICATION` であれば first-win で他カテゴリが確定済みでも `authEvidenceObserved = true` にする。

加えて、イベントループ終了後に次の **`CODEX_KNOWN_AUTH_EVIDENCE_TEXTS`**（`CODEX_STDERR_AUTH_FAILURES` の2文言 ∪ `knownCompatibilityFailureCategory()` が `AUTHENTICATION` に分類する2文言 "Not logged in"/"Invalid authentication credentials"）を **stdout と stderr の両方**に対して `.contains()`（部分一致）で検査する。

```kotlin
internal val CODEX_KNOWN_AUTH_EVIDENCE_TEXTS = CODEX_STDERR_AUTH_FAILURES + setOf(
    "Not logged in",
    "Invalid authentication credentials",
)
```

`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED` に分類される文言（"Session limit reached" 等）は意図的にこの集合へ含めない。これらは新設の output-interpreted 経路そのものが公開対象とするカテゴリであり、その分類文言自体を「evidence として公開をブロックする文言」に含めると、当該カテゴリが常に自己矛盾でブロックされてしまうため。

`.contains()`（部分一致）を採用したのは #296 の condition 3 と同じ安全側判定を踏襲するため（parser 内部の `stderrAuthFailure`＝主 category 決定用の判定は `trimEnd().. in set` の完全一致だが、これは「認証失敗と断定する」ための厳格な判定であり、evidence 追跡（「疑わしきは記録しない」）とは目的が異なるため意図的に区別する）。

**残る限界（Finding 2、Risks 参照）**: `CODEX_KNOWN_AUTH_EVIDENCE_TEXTS` は固定の既知文言集合であり、これに一致しない任意テキスト（未知の secret、OAuth device code、rotation 後の token 等）を検出できない。この限界はユーザー確認済みの残存リスクとして受容する。

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
- output-interpreted 経路（新設、`OUTPUT_CONTRACT`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`UNKNOWN_PROVIDER_FAILURE`）は `cliErrorReported` を要求しない。これらのカテゴリは定義上すべて adapter 由来（`cliErrorReported` は必然的に true）であり、意味のある追加条件にならないため
- `authEvidenceObserved` チェックを関数冒頭でどちらの経路よりも先に評価することで、「lifecycle category にせよ output-interpreted category にせよ、認証 evidence が独立に観測されていれば無条件で記録しない」という最優先の否定条件を明示する
- 旧 condition 3（stderr 直接 inspect）は `authEvidenceObserved` に統合されるため、この関数は `processResult: ProcessRunResult` を引数に取らなくなる（呼び出し元1箇所を更新）

### D4: 既存テストの更新 ＋ production-wiring テストの必須化（Finding 4 で追加）

`LlmInvocationAuditorTest.kt` の `ConfigurableAuditLlmInvoker` は実際の `DefaultLlmOutputParser` を通さず `LlmInvocationResult` を直接組み立てるテスト double であり、`authEvidenceObserved` を明示パラメータとして受け取れるよう拡張する。既存テスト `invokeAndAudit_omitsRawOutputWhenStderrCarriesKnownAuthSignatureAlongsideProcessExit` はこの double に `authEvidenceObserved = true` を明示的に渡す形へ更新する。

これに加えて、#282 の受け入れ条件（AC1・AC2）は **production の配線を経由したテスト**で証明する。`ShellLlmInvoker` は `outputParser: LlmOutputParser = DefaultLlmOutputParser()` を既定値に持つため、`processRunner` だけを固定 `ProcessRunResult` を返す fake に差し替えれば、実際の `DefaultLlmOutputParser.parseCodex()` を経由した `LlmInvocationResult` を得られる（`ShellLlmInvokerTest.kt` の既存パターンと同じ手法）。この `ShellLlmInvoker` インスタンスをそのまま `LlmInvocationAuditor.invokeAndAudit()` の `llmInvoker` 引数に渡すことで、parser → invoker → auditor の一続きを実コードで検証する。

- #282 同形 fixture（非 JSON stdout、既知認証文言なし）→ production 配線経由で raw output が記録されることを証明
- 同じ fixture 形状で stdout に既知認証文言を埋め込んだケース → production 配線経由で raw output が記録されないことを証明

`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`UNKNOWN_PROVIDER_FAILURE` については、parser 単体テスト（`DefaultLlmOutputParserTest.kt`、実 parser を直接呼ぶため既に production の parsing ロジックを証明している）と `ConfigurableAuditLlmInvoker` 経由の auditor テストの組み合わせで十分と判断し、production-wiring テストは #282 が直接対応する `OUTPUT_CONTRACT` に限定する（issue の受け入れ条件1が名指しするのがこのカテゴリのため）。

### D5: `authEvidenceObserved` は default なしのフィールドとする（Finding 3 で追加、Finding 3 再反証の Blocking B で `LlmPhaseAuditSignals` にも適用範囲を拡張）

**帰属**: agent 仮決め

`ParsedLlmOutput.authEvidenceObserved` と `LlmInvocationResult.authEvidenceObserved` はどちらも default 値を持たない `Boolean` とする。これにより全ての構築箇所（`parseClaude()`、`contractFailure()`、`parseCodex()`、`LlmInvoker.kt` の `LlmInvocationResult` 構築、既存テストの `ParsedLlmOutput`/`LlmInvocationResult` 直接構築箇所）がコンパイル時にこの field の明示を強制される。

再反証（round 2）で、`LlmInvocationAuditor.kt` 内部の集約構造体 `LlmPhaseAuditSignals.authEvidenceObserved` に `= false` の default が残っていることが blocking として指摘された。`LlmPhaseAuditSignals` は `private data class` で現時点の構築箇所は `invokeAndAudit()` 内の1箇所のみだが、default を残す限り、将来この構造体を構築する箇所が追加・変更されたときに `authEvidenceObserved` の明示を省略してもコンパイルが通ってしまい、`ParsedLlmOutput`/`LlmInvocationResult` 側で確立した fail-closed 不変条件が security gate 直前の1箇所で再び崩れうる。この指摘を受け、`LlmPhaseAuditSignals.authEvidenceObserved` も default を撤廃する。

**round 3 の再反証で追加指摘（Blocking C）**: 上記の「`cliErrorReported` は security gate の否定証拠として直接使われないため default を維持してよい」という判断が事実誤りだった。`isSafeCodexLifecycleFailure()` の lifecycle 経路は `!auditSignals.cliErrorReported` を直接安全条件として使っており（#296 の condition 2 に相当。adapter が output text から failure を検出しているのに lifecycle category が primary として選ばれる複合ケースへの防御）、`cliErrorReported` が default `false` のまま構築箇所で明示を省略されると `!cliErrorReported` が誤って `true` となり、#296 が閉じたはずの反例が再現する。よって `cliErrorReported` も `authEvidenceObserved` と同じ理由で default を撤廃する。

最終的に default を撤廃するのは `authEvidenceObserved` と `cliErrorReported` の2フィールドのみとする。`authFailureSuspected`（運用ログ通知のみに使用され、raw output 記録可否には関与しない）と `cleanupFailed`（監査 payload の情報表示にのみ使用）は gate 判定に使われないため default を維持する。`providerFailure` は `isSafeCodexLifecycleFailure()` 内で `auditSignals.providerFailure?.category` として参照されるが、null の場合は `when` 式が `else -> false`（非公開側）に倒れるため、default `null` のままでも構造的に fail-closed であり撤廃不要。

**代替案として検討したもの**: falsifier は `NOT_SCANNED`/`ABSENT`/`PRESENT` の tri-state enum を提案した。表現力は tri-state の方が高い（「scan を試みたが未実施」という中間状態を型で表現できる）が、本 change の実装範囲では `authEvidenceObserved` を計算する経路（Codex）と計算しない経路（Claude、明示的に `false`）の2択で全ケースを尽くせるため、Boolean + default なしで十分と判断した。将来 Codex 以外の provider で類似の evidence 追跡が必要になった場合は、その時点で tri-state 化を再検討する。

## Risks / Trade-offs

- [Risk・ユーザー確認済み] `CODEX_KNOWN_AUTH_EVIDENCE_TEXTS` は固定の既知文言集合であり、これに一致しない未知の secret（token rotation 後の値、OAuth device code、ファイルパス等）が `OUTPUT_CONTRACT`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`UNKNOWN_PROVIDER_FAILURE` の raw output に含まれていた場合、evidence 追跡はそれを検出できず監査記録に残る。特に `UNKNOWN_PROVIDER_FAILURE` と `OUTPUT_CONTRACT`（非 JSON raw garbage のケース）はカテゴリの性質上、既知パターンに一致しない任意内容を含みうる度合いが他の2カテゴリより高い。この risk は #296 の対象範囲（lifecycle 3カテゴリ）には存在しなかった新規リスクであり、独立反証（Finding 2）で指摘された。issue #295 の明示スコープどおり4カテゴリ全部を対象とする方針をユーザーに確認した上で受容する
- [Risk] `CODEX_KNOWN_AUTH_EVIDENCE_TEXTS` の文言は Codex CLI のバージョンに依存する固定文字列であり、CLI 側の文言変更で evidence 追跡が漏れる可能性が残る → Mitigation: `adapterSchemaVersion` によるバージョンピン止めで検知する既存の仕組みに委ねる（#296 から変わらず残る限定的なリスク）
- [Trade-off] `isSafeCodexLifecycleFailure()` のシグネチャ変更（`processResult` 引数の削除）と `authEvidenceObserved` の default 撤廃は、呼び出し元・テスト double の複数箇所を更新する必要がある。影響範囲は限定的だが、レビューでは新旧の等価性（lifecycle 経路が #296 と同じ結果を返すこと）を重点確認する必要がある

## Migration Plan

- schema 変更なし。`authEvidenceObserved` は新規フィールドだが default を持たないため、既存の呼び出し箇所は全てこの change の diff 内でコンパイルが通る形に更新する（別 PR での段階移行は不要）
- ロールバック: 本 change の commit を revert すれば #296 の3条件ゲートに戻る。DB マイグレーションやランタイム設定の変更は伴わない

## Open Questions

（なし。issue #295 の受け入れ条件と falsification round 1 の指摘は本設計で全て充足・解消する）
