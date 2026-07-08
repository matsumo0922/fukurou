# fukurou Agent Notes

共通ルール（口調、Kotlin style、Git/PR、RTK）は `~/.codex/AGENTS.md` と `~/.codex/RTK.md` に従うこと。このファイルには `fukurou` 固有の補足だけを書く。

## Repository Scope

- Ktor/JVM backend。主要 module は `:fukurou`。
- package root は `me.matsumo.fukurou`。
- 実装 PR や issue autopilot では、特に指示がない限り sibling worktree を使い、main checkout のローカル差分と混ぜない。
- `.env` はローカル実行用。Cloudflare token、PostgreSQL password、取引所 API key、LLM credential などの secret はログ、PR、コメントに出さない。

## Product / Safety Notes

- 本リポジトリは暗号資産 BTC 現物トレーディング bot の実験プロジェクトであり、投資助言ではない。
- 実資金を動かす機能は、ユーザーが明示的に要求するまで実装・有効化しない。
- 取引実行や scheduler を追加する場合は、`docs/design.md` の「安全床」を優先する。最大損失、損切り必須、ナンピン禁止、最大ドローダウン停止、エクスポージャー上限、呼び出し回数上限を迂回できる設計にしない。
- 最初の実装は paper trading / dry-run を既定にし、本番取引への切り替えは config と運用手順の両方で明示的にする。

## Swagger / OpenAPI

- API docs の正本は Ktor route-local の `.describe {}`。静的 YAML を正本にしない。
- endpoint を追加・変更したら、route 実装と同じ差分で `.describe {}` も更新する。
- 公開面は `/swagger` と `/openapi.json`。人間向けの summary / description は日本語で書く。
- request / response DTO の wire contract を変えるときは、OpenAPI schema と route test / contract test も合わせて更新する。

## Local Commands

- 通常テスト: `make test`
- 静的解析: `make detekt`
- ビルド: `make build`
- Ktor サーバ起動: `make run`

DB を使うローカル起動では、`docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres` で PostgreSQL を起動し、`.env` の `POSTGRES_PASSWORD` と同じ値を `DB_PASSWORD` に設定する。

## Deployment Notes

- production は GitHub Actions が `ghcr.io/matsumo0922/fukurou:<commit-sha>` を push し、NAS の self-hosted runner が `/usr/local/sbin/deploy-fukurou <sha>` を sudo 実行する。
- NAS deploy root は `/srv/fukurou`、root checkout は `/srv/fukurou/repo`、NAS `.env` は `/srv/fukurou/.env`。
- self-hosted runner は `dxp4800plus-fukurou-prod` / label `fukurou-prod`。
- `github-runner` に Docker/root 権限を直接渡さない。sudoers は `/usr/local/sbin/deploy-fukurou` だけに限定する。
- Cloudflare Tunnel token と PostgreSQL password は NAS `.env` に置く。Cloudflare Access service token は NAS `.env` ではなく、手元の未追跡 env file か secret manager に置く。
- production compose は `docker-compose.prod.yml`。公開入口は Cloudflare Tunnel + Access とし、Ktor container の host port は公開しない。

## Current Placeholder API

- `GET /revision`
- `GET /health`
- `GET /health/live`
- `GET /health/ready`
- `GET /swagger`
- `GET /openapi.json`

`/health/ready` は `DB_URL` / `DB_USER` / `DB_PASSWORD` が揃っている場合に PostgreSQL へ接続する。readiness を変更するときは、compose の health / dependency と運用確認手順も合わせて見直す。

## MCP

- IntelliJ MCP が利用できる場合は、`mcp__idea.search_in_files_by_text` / `mcp__idea.search_in_files_by_regex` でのコード探索、`mcp__idea.open_file_in_editor` での対象確認、`mcp__idea.get_file_problems` での inspection / warning 確認を積極的に使う。
- コードを編集したときは、可能な範囲で `mcp__idea.get_file_problems` を使い、対象ファイルに warning が出ていないか確認する。必要に応じて `mcp__idea.build_project` も使う。
- IntelliJ MCP が使えない場合は、`make detekt` や関連テストなど、変更内容に応じた代替手段で確認する。

## Repo-local Skills

- Fukurou 固有の運用手順や production DB schema に依存する skill は `.codex/skills/` を正本として置く。
- Claude Code から使うため、`.claude/skills/<skill>/SKILL.md` を正本 SKILL.md への symlink として配置する。script 等の実体は `.codex/` 側だけに置き、二重管理しない。
- skill の内容は特定 agent の CLI に依存させず、repo root からの相対パスで手順を書く。
- Fukurou 固有 skill を汎用の personal Skills repo へ公開しない。
- LLM daemon / paper trading の時系列確認は `.codex/skills/fukurou-llm-daemon-log-audit/` を使う。

## Worktree 運用

実装を行う場合は、必ず worktree を作成し、デフォルトディレクトリを汚さない。
特別に指示がある場合や、read-only の調査やビルド・テストの実行はこの限りでない。

```bash
git worktree add ../fukurou-<task-slug> -b <branch-name>
cp -p .env ../fukurou-<task-slug>/.env
cd ../fukurou-<task-slug>
```

- `.env` は git 管理外なので、`git worktree add` ではコピーされない。ローカル DB password、外部 API credential、Cloudflare Access service token などが存在し、ビルドまたは smoke test に必要な場合は、worktree 作成直後に必ず元 checkout からコピーする。

<!-- agents-rules:kotlin:begin -->
<!-- この区間は Agents リポジトリが管理する。編集は Agents の rules/kotlin.md で行い、make link-project で更新する -->
# Kotlin / Jetpack Compose 規約

Kotlin プロジェクト共通のコーディング規約。整形など静的解析で判定できる規約（trailing comma、メソッドチェーンの改行、Composable の `modifier` 引数、ラムダ引数名の時制、Immutable なコレクション型の強制など）は `rules/lint/` のテンプレートを取り込んだ detekt / compose-rules 設定で強制する。このファイルには静的解析で判定できない、判断を伴う規約だけを置く。

## Kotlin

### 命名

- 変数名は役割が分かる名前にする。ループの添字も `i` ではなく `index` とする
- KDoc は日本語で書く。対象は定数値 / `data class` / `enum` / `data object` / `object` / `class`（Activity / Fragment / Dialog / ViewModel を除く）

### 分割

- 同じ処理を2箇所目に書こうとした時点で、共通メソッドまたは共通クラスへ切り出す
- ラムダ本体は3文以内に収める。4文以上、または分岐を2つ以上含む場合はメソッドへ切り出す
- 論理演算子（`&&` / `||`）を2つ以上含む条件式は、意味を表す名前の Boolean 変数に切り出してから比較に使う

### 段落

- 処理は意味のまとまり（値の算出 / 条件分岐 / 早期 return / 副作用を伴う呼び出し / 最終 return）ごとに空行で区切り、段落として読めるようにする

```kotlin
/** 商品を購入し、レシートを発行する */
fun purchase(item: Item): Result<Receipt> {
    val totalPrice = item.price * item.quantity

    if (totalPrice <= 0) return Result.failure(InvalidPriceException(item))

    val receipt = paymentClient.charge(totalPrice)

    return Result.success(receipt)
}
```

### エラー処理

- 失敗が呼び出し側の処理対象になる場合は `Result`（または `runCatching`）を返す
- `throw` は事前条件違反だけに使い、`require` / `requireNotNull` / `error` で表現する
- catch した例外は握りつぶさず、ログに出力するか `Result.failure` に変換する

### 引数

- 関数定義は引数2つまでなら1行で書く。デフォルト引数を含む場合は引数ごとに改行する
- 関数呼び出しは引数2つまでなら名前付き引数なしで1行で書く。次のいずれかに該当する場合は、引数ごとに改行して名前付き引数を使う: 引数が3つ以上 / 同じ型・形の引数が連続する / 引数内にメソッドチェーン・匿名オブジェクト・計算式が入る
- Java メソッドは名前付き引数を使えないため、1行が長くなる場合は改行だけ行う

## Jetpack Compose

### 状態と安定性

- Composable から参照する `data class` には `@Immutable`（全プロパティが不変の場合）または `@Stable`（可変プロパティを含む場合）を付ける。Compose コンパイラの安定性推論に頼らず宣言することで、recomposition のスキップを保証するため

```kotlin
/** レシピ一覧画面の UI 状態 */
@Immutable
data class RecipeListUiState(
    val recipes: ImmutableList<Recipe>,
    val isLoading: Boolean,
)
```

### 命名

- private でない Composable には、配置先の画面 Composable の名前を prefix として付ける。複数の画面から参照される Composable には prefix を付けない

```kotlin
// HomeScreen に配置する Composable
@Composable
internal fun HomeTopAppBar(...)

// RecipeDetailScreen に配置する Composable
@Composable
internal fun RecipeDetailTopAppBar(...)
```

### 可視性

- Composable は `internal fun` で定義し、モジュール外から使う場合のみ `public` にする
- 1つの Composable からのみ呼ばれる分割済みの Composable は `private fun` にする

### レイアウト

- `Column` / `Row` の子要素間の間隔は `Arrangement.spacedBy` と各要素の `Modifier.padding` で調整する。固定サイズの `Spacer`（`Spacer(Modifier.width(8.dp))` など）は間隔調整に使わない
- `Spacer(Modifier.weight(1f))` のような比率ベースの `Spacer` は使ってよい

### 呼び出し

- Composable の呼び出しは名前付き引数を使い、引数ごとに改行する。引数が1つで短い場合のみ、1行・名前付き引数なしで書いてよい
- `modifier` 引数は呼び出し時の引数の先頭に置く
- `Modifier` のチェインが2リンク以上になる場合は、リンクごとに改行する

```kotlin
HomeTopAppBar(
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp),
    title = uiState.title,
    onNavigationClick = { viewModel.close() },
)
```
<!-- agents-rules:kotlin:end -->
