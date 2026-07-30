## Context

`docker-compose.prod.yml` は `services.ktor.image` と `services.ktor.environment.FUKUROU_LLM_IMAGE_DIGEST` の両方で `${FUKUROU_IMAGE_REFERENCE:?...}` を参照する。compose は subcommand の種類に関係なく compose file 全体を interpolate するため、`stop` でもこの変数を要求する。

executor 内で `docker compose` を呼ぶ箇所は 2 つある。

1. `drain_launches()` の強制停止（`stop -t 30 ktor`）— natural deadline 内に active launch が 0 にならない場合だけ通る条件付き経路
2. `compose_cutover()` の `up -d --no-build`

`export FUKUROU_IMAGE_REFERENCE` を行う `bind_image_reference()` は現在 2 からしか呼ばれない。1 は `start_new_pause()` → `drain_launches()` の経路で 2 より前に実行されるため、強制停止に入った deploy だけが interpolation error で落ちる。issue #329 の失敗はこの経路である。

`drain_launches()` の呼び出し元は `start_new_pause()` のみで、その中で `MARKER_EXPECTED_DIGEST="${CANDIDATE_DIGEST}"` が代入され `persist_paused_state` まで済んでいる。つまり bind に必要な値は既に確定しており、欠けているのは export だけである。

**前提・証跡（2026-07-30 実測）**: root operator が production NAS 上で `sudo /usr/local/libexec/fukurou-deploy-db active-launch-count` を read-only 実行し、結果は `0` だった。既存の paused-state は drain 済みであり、acknowledge → fresh deploy で復旧する既存 runbook 手順（`ensure_open_gap_for_acknowledged_attempt` の active count 0 gate）がこの Migration Plan の前提として成立することを確認済み。これにより、未 drain な既存 paused-state からの復旧手順追加は本 change の scope に含めない。

## Goals / Non-Goals

**Goals:**

- 強制 drain 経路が `docker compose` を呼ぶ時点で `FUKUROU_IMAGE_REFERENCE` が bind 済みであることを保証する
- compose に渡る値が candidate の `repository@sha256:digest` と一致し、cutover 後の digest 検証と同じ値であり続けることを保証する
- 静的 contract test で「最初の `docker compose` 呼び出しより前に `bind_image_reference` が呼ばれる」ことを固定する

**Non-Goals:**

- drain timeout の調整、強制停止順序の変更、automatic rollback の追加
- compose の immutable digest 必須条件の緩和
- drain の runtime 挙動を再現する新規テスト harness（compose や DB を起動する selftest）の追加
- executor 全体の compose 呼び出し抽象化（後述の Decision 2 で棄却）

## Decisions

### Decision 1: bind を強制停止の直前に置き、`compose_cutover()` の bind は残す

`drain_launches()` の `docker compose stop` を実行する直前で `bind_image_reference` を呼ぶ。`bind_image_reference()` は変数の export だけを行う冪等な関数であり、`compose_cutover()` から再度呼ばれても副作用はない。

代替案と棄却理由:

- **`start_new_pause()` の冒頭（`MARKER_EXPECTED_DIGEST` 代入直後）で bind する**: runtime としては正しいが、`start_new_pause()` は executor 本文で `drain_launches()` より後に定義されている。本 change が追加する順序 assertion は executor 本文の静的なテキスト順序を見るため、この配置では「最初の `docker compose` より前に bind 呼び出しが現れる」が成立しない。将来 bind の呼び出し元だけが移動して compose 呼び出しが取り残される事故も、この配置では検出できない。
- **`deploy_compose()` で `pull_candidate()` の直後に `CANDIDATE_DIGEST` から bind する**: bind の値の出所が paused-state marker から deploy 引数に変わる。acknowledged pause を adopt する経路では marker が正本であるべきで、bind の意味を二重化したくない。

「使う直前に bind する」という配置は、compose 呼び出しと bind の距離が縮まるぶん、呼び出し順の入れ替えに対して壊れにくい。

### Decision 2: compose 呼び出しを共通 wrapper に括り出さない

`compose()` のような wrapper で全 compose 呼び出しを包めば invariant を構造的に保証できるが、timeout 値が呼び出しごとに異なり（45 秒 / 120 秒）、既存 contract test が `compose_cutover()` 本文に `docker compose` の literal が現れることを前提にしている。issue の scope は「既存 contract test を維持する」ことを含むため、wrapper 化は本 change では採らない。順序 assertion が同じ invariant を静的に守る。

### Decision 3: `MARKER_EXPECTED_DIGEST` の未設定は `set -u` に委ねる

executor は `set -Eeuo pipefail` で動く。`bind_image_reference` を marker 未設定の経路から呼ぶと `set -u` が即座に失敗させる。既存の `bind_image_reference()` も同じ前提で書かれており、追加の空文字ガードは入れない（`MARKER_EXPECTED_DIGEST` の値は `validate_digest` を通った `CANDIDATE_DIGEST`、もしくは `validate_paused_state_json` を通った marker 由来に限られる）。

### Decision 4: 順序 assertion は境界抽出（missing-delimiter 検知付き）+ 実行行の厳密マッチ + 呼び出し件数 assert で行う

executor 全体に対する素朴な `indexOf("bind_image_reference")` は、`bind_image_reference() {` という**関数定義行**（312 行目）にもマッチするため、実際の呼び出しの有無を証明しない。未修正の executor でも定義行（312）は最初の `docker compose`（340、`drain_launches` 内）より前にあるので、この naive assertion は false green を返す（初回反証の blocking 2）。

この初版修正として「関数本文の境界抽出 + 非空性 assert + `contains` による行フィルタ」を設計したが、2 回目の反証で以下 2 つの false green 反例が見つかった。

**反例 A（missing delimiter fallback）**: Kotlin の `substringAfter`/`substringBefore` はデフォルトで delimiter が見つからない場合「元の文字列（または残り全文）」を返す。関数の開始行や終了 anchor の文字列がリファクタで変わった場合（例: `drain_launches() {` → `function drain_launches {`）、`substringAfter` は executor 全体を返してしまい、その中に 312 行目の定義行が再び含まれる。「本文の非空性」を assert しても、fallback の戻り値も非空なので検知できない。

**反例 B（`contains` は invocation を証明しない）**: 実行行に `bind_image_reference` という文字列が含まれることは、それが呼び出し文であることを意味しない。`usage()` の heredoc や `printf '%s\n' 'bind_image_reference'` のような文字列リテラル行も、コメント行フィルタ（`#` 始まり判定）をすり抜けて「呼び出しあり」と誤判定され得る。同様に `docker compose` という文字列も、真の invocation 行以外に紛れ込む余地がある。

反例 A への対応として `missingDelimiterValue` に NUL 区切りのセンチネル文字列を使う版を検討したが、3 回目の反証で次の指摘を受けた。

- センチネルが raw NUL byte を含む場合、`file` コマンドが design.md を `data` と判定し `git diff --no-index` が `Binary files differ` を返すため、GitHub 上で通常のテキストレビューができなくなる（reviewability 上のブロッカー）
- `startMarker` が「最初の単なる substring occurrence」を採用するだけでは、実関数より前にデコイのコメント（例: `# drain_launches() {`）が存在すると誤った occurrence を anchor にしてしまい、本来の関数本文を取り違える

この 2 点を踏まえ、`missingDelimiterValue` センチネルより単純で reviewable な、**anchor の一意性を明示的に assert する** `indexOf` ベースの抽出に変更した。しかし 4 回目の反証で、`executor.split(startMarker).size - 1` による occurrence カウントは**部分文字列としての出現数**であって、関数宣言行そのものの出現数ではないという反例が見つかった。`drain_launches() {` という文字列は `undrain_launches() {` のような別関数の宣言行にも部分文字列として含まれるため、そのような decoy 関数を `drain_launches` の実宣言より前に置くと、`split` ベースの一意性チェックを回避したまま誤った occurrence を anchor にできる（3 回目の decoy コメントの反例と同型だが、コメントではなく別関数宣言による回避）。

これを踏まえ、`startMarker` の一意性判定を**行全体の厳密一致**（正規表現の `^...$` を `MULTILINE` で適用）に変更する。部分文字列一致を一切使わないため、`undrain_launches() {` のような接頭辞違いの宣言行はマッチしない。

```kotlin
private fun uniqueLineStart(source: String, exactLine: String): Int {
    val matches = Regex("(?m)^${Regex.escape(exactLine)}$").findAll(source).toList()
    assertEquals(1, matches.size, "expected exactly one line \"$exactLine\", found ${matches.size}")
    return matches.single().range.first
}

private fun extractFunctionBody(executor: String, startMarker: String, endMarker: String): String {
    val bodyStart = uniqueLineStart(executor, startMarker) + startMarker.length
    val bodyEnd = executor.indexOf(endMarker, bodyStart)
    assertTrue(bodyEnd >= 0, "end marker not found after $startMarker: $endMarker")
    return executor.substring(bodyStart, bodyEnd)
}

val drainLaunches = extractFunctionBody(executor, "drain_launches() {", "\n}\n\ngap_event_payload_hash")
val composeCutover = extractFunctionBody(executor, "compose_cutover() {", "\n}\n\nresume_launches_idempotently")
```

`startMarker`（例: `drain_launches() {`）が executor 中で**行全体として**厳密に 1 回だけ現れることを assert することで、decoy コメントにも decoy 関数宣言にも回避されない。`endMarker` が見つからない場合は `indexOf` が `-1` を返すため `bodyEnd >= 0` の assert で確実に検知する（missing delimiter fallback の反例 A を、NUL センチネルなしで同等に閉じる）。この方式はプレーンな ASCII 文字列だけで完結し、diff がテキストとして正常にレビューできる。

行の判定は `contains` ではなく、トリム後の行に対する**厳密一致・正規表現**で行う。

```kotlin
private fun executableLines(body: String): List<String> = body
    .lineSequence()
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .toList()

private val COMPOSE_INVOCATION = Regex("""^timeout \d+ docker compose(?=\s|$)""")

private fun bindCallIndex(lines: List<String>): Int = lines.indexOf("bind_image_reference")
private fun composeInvocationIndices(lines: List<String>): List<Int> =
    lines.indices.filter { COMPOSE_INVOCATION.containsMatchIn(lines[it]) }
```

- `bind_image_reference` の呼び出し文は executor 上 `  bind_image_reference`（引数なし、トリムすると識別子そのもの）なので、`line == "bind_image_reference"` の厳密一致で判定する。heredoc やコメントの文字列表現はトリムしても識別子そのものとは一致しないため誤判定しない。
- `docker compose` の実 invocation は現状 2 箇所とも `timeout <N> docker compose ...` という共通の先頭パターンを持つ。当初 `\b`（word boundary）で `compose` の直後を区切る案を検討したが、6 回目の反証で `docker compose-fake` や `docker compose.old` のような**別コマンド名**にも `\b` が成立してマッチしてしまう反例が見つかった（`-`/`.` は非単語文字なので `compose` との間に word boundary が立つ）。`(?=\s|$)`（直後が空白または行末であることを要求する先読み）に変更し、`compose` という単語そのものの直後でだけ区切られるようにする。

この 2 つのユーティリティを使い、各関数本文で `bindCallIndex(...) >= 0` かつ `bindCallIndex(...) < composeInvocationIndices(...).first()`、`composeInvocationIndices(...).size == 1` を assert する設計にしたが、5 回目の反証で次の false green が見つかった。

**反例 C（`<` は条件分岐による無効化を検出しない）**: `if false; then`／`bind_image_reference`／`fi`／`timeout <N> docker compose ...` のように、`bind_image_reference` を条件分岐で囲んで実行されないようにしても、`executableLines` はトリム後の各行（`if false; then`、`bind_image_reference`、`fi`、compose 行）をすべて実行行として残す。`bindCallIndex` は分岐の中にある `bind_image_reference` 行を見つけ、`composeInvocationIndices` はその後の compose 行を見つけるため、両者の index 比較（`<`）は無条件で成立してしまう。これは heredoc/quote のような意図的難読化ではなく、将来 feature flag や条件付き処理を挿入する通常のリファクタで起こり得る。

Decision 1 はもともと「`docker compose stop` を実行する直前で `bind_image_reference` を呼ぶ」という**隣接配置**を採用している。この設計意図をそのまま assertion に反映し、`<`（順序が前であること）ではなく**隣接**（`bind_image_reference` の直後の実行行が対象の `docker compose` invocation であること）を要求するよう変更する。

```kotlin
private fun bindCallIndex(lines: List<String>): Int = lines.indexOf("bind_image_reference")
private fun composeInvocationIndex(lines: List<String>): Int =
    lines.indexOfFirst { COMPOSE_INVOCATION.containsMatchIn(it) }
```

各関数本文で `bindCallIndex(...) >= 0`、`composeInvocationIndex(...) >= 0`、かつ `composeInvocationIndex(...) == bindCallIndex(...) + 1` を assert する（`bind_image_reference` の直後の実行行が `docker compose` invocation であることの直接的な証明）。`if false; then ... fi` のような分岐や、`fi`/`done`/`return` などの制御構文が bind と compose の間に挟まると、実行行としてカウントされてしまい隣接インデックスがずれるため、この assertion は fail する。これにより反例 C を閉じる。

`composeInvocationIndices(...).size == 1`（各関数内で invocation が厳密に 1 件）の assertion は維持する。executor 全体（`executableLines(executor)`）でも `docker compose` invocation の総数が厳密に 2 件であることを count assert し、2 関数それぞれの 1 件ずつと合計が一致することを保証する（反例 B の一部）。

**反例 D（bind 行自体が shell continuation で条件化されている場合、隣接判定だけでは検出できない）**: 6 回目の反証で、`false &&`（または `true ||`、パイプ `|`）で終わる行の直後に `bind_image_reference` を単独行で置くと、`executableLines` 上は bind 行と compose 行が依然隣接するため `composeInvocationIndex == bindCallIndex + 1` は成立するが、実際には bind が `&&`/`||`/`|` の右辺として条件付き・別 subshell 実行になり、親 shell の環境変数 export が効かない。この codebase 自身が `[[ "${reservation_count}" == "0" ]] && return 0`（`drain_launches()` 本文）のように `&&` の短絡評価イディオムを使っているため、これは heredoc/quote のような意図的難読化ではなく、実際に起こり得る継続行のパターンである。

これを踏まえ、`bind_image_reference` の**直前**の実行行が継続演算子（`&&`、`||`、単独または末尾の `|`、`|&`、行末 `\`）で終わっていないことも assert する。

```kotlin
private val LINE_CONTINUATION = Regex("""(&&|\|\|?|\|&|\\)\s*(#.*)?$""")

private fun bindIsUnconditional(lines: List<String>, bindIndex: Int): Boolean {
    val previousLine = lines.getOrNull(bindIndex - 1) ?: return true
    return !LINE_CONTINUATION.containsMatchIn(previousLine)
}
```

`bindCallIndex - 1` が範囲外（bind が本文の先頭実行行）の場合は継続元が存在しないため無条件に true とする。`false &&`/`true ||`/`printf x |` のように継続演算子で終わる行が bind の直前にある場合、`bindIsUnconditional` は false を返すため assertion が fail する。これにより反例 D を閉じる。

7 回目の反証で、継続演算子の直後に inline comment（例: `false && # feature gate`）が付くと、当初の `LINE_CONTINUATION`（行末が継続演算子そのものであることを要求）は検知できないという追加反例が見つかった。Bash は inline comment があっても継続演算子としての意味を保持するため、これは heredoc/quote のような意図的難読化ではなく、通常の説明コメントで起こり得る。上記の正規表現に `(#.*)?` を加え、継続演算子の直後に任意で `#` から始まるコメントが続く場合も継続とみなすよう修正済み（quote 内の `#` を字句的に区別する完全な lexer 化は行わない。残存リスクとして許容する）。

各関数本文の最終 assertion は次の 4 条件の組み合わせになる: `bindCallIndex(...) >= 0`、`composeInvocationIndex(...) >= 0`、`composeInvocationIndex(...) == bindCallIndex(...) + 1`、`bindIsUnconditional(lines, bindCallIndex(...))`。

検証対象の Scenario 別に見ると:

- `drainLaunches` 本文内：`composeInvocationIndices` が厳密に 1 件、`bindCallIndex >= 0`、`composeInvocationIndex == bindCallIndex + 1`（隣接）
- `composeCutover` 本文内：`composeInvocationIndices` が厳密に 1 件、`bindCallIndex >= 0`、`composeInvocationIndex == bindCallIndex + 1`（隣接）
- executor 全体：`composeInvocationIndices` が厳密に 2 件（上記 2 箇所の合計と一致することの相互検証）

`drain_launches` 本文内の既存順序（`assert_application_pid_zero` → `interrupt-active-launches` → drain ループ）を確認する assertion も、各 marker 文字列の `indexOf` が `-1` を返さないこと（`>= 0`）を先に assert してから順序比較する（先頭 marker 欠落時に `-1 < positive` が偶然 true になる回避策）。

**残存リスク（意図的に受容し、これ以上の設計変更を行わない）**: 3 回目の反証は、`: <<'TEXT'` heredoc や複数行の quote された文字列の本文に `bind_image_reference` と `timeout <N> docker compose` をこの順で埋め込めば、実行行フィルタ（トリム + `#` 除外のみ）が shell の字句的構造（heredoc、quote）を解析しないため、実際には何も実行されない箇所を「呼び出しあり」と誤判定し得ることを指摘した。

この反例は、テストを欺くために executor のコードを意図的に難読化することを要求する。通常の開発でうっかり起きる回帰の形ではない。この `ReleaseDeployFoundationContractTest.kt` の既存 assertion（`executor keeps a straight digest pinned cutover flow` 等）はすべてプレーンな `indexOf`/`contains`/`substringAfter` によるテキスト一致であり、bash の字句解析やコメント判定以上の防御を一切行っていない。本 change のためだけに shell lexer 相当の解析を持ち込むことは、この既存 contract test ファイル全体の検証水準から見て不釣り合いであり、issue #329 の Scope 外（「drain の runtime 挙動を再現する新規テスト harness は追加しない」）とも整合しない。

よって、この heredoc/quote による字句的回避は non-blocking の残存リスクとして受容する。実際に踏まれるのは、この静的 assertion を欺く目的で executor のコードを意図的に難読化した場合のみであり、そのような変更は通常の code review でも独立に検出される。

## Risks / Trade-offs

- **強制停止経路そのものは本 change でも自動テストされない** → 順序 assertion は「bind が compose より前にある」ことだけを静的に保証する。runtime の検証は merge 後の fresh deploy が担う。drain の runtime harness は issue で明示的に scope 外。
- **bind が 2 箇所に増える** → 呼び出し箇所が増えると片方だけ消える将来変更の余地が残る。順序 assertion が「最初の compose より前に bind がある」ことを守るため、強制停止側の bind を消すと test が落ちる。
- **静的テキスト assertion の脆さ** → 関数の定義順を入れ替えると assertion の意味が変わる。executor は 1 file の bash script で定義順が実行経路と概ね一致しており、既存 contract test も同じ手法を採っている。手法の是非は本 change の範囲外とする。

## Migration Plan

コード変更に data migration はない。merge 後の運用手順は既存 runbook（`docs/deploy.md`）に従う。

1. merge 後、root-owned executor を runbook の手順で review 済み revision に更新する
2. paused-state を直接削除せず、`--acknowledge-paused-state <deployment-id>` で acknowledge する
3. fresh deploy を実行し、production revision / health / running image digest が deploy target と一致することを確認する
4. launch maintenance が resume され、既存の OPEN gap が同じ gap ID で CLOSE されることを確認する

rollback: 本 change は executor の 1 行追加とテスト追加のみで、旧 revision の executor に戻しても DB や marker の互換性に影響しない。

## Open Questions

なし。
