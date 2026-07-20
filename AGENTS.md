# fukurou Agent Notes

共通ルール（口調、Git/PR、RTK）は `~/.codex/AGENTS.md` と `~/.codex/RTK.md` に従うこと。このファイルには `fukurou` 固有の補足だけを書く。

## プロジェクト性格と DoD

single-owner の hobby / 実験プロジェクト。暗号資産 BTC 現物トレーディング bot の実験と投資の学習が目的で、実験スループットを優先する。実験的実装や方針転換が頻繁に起こる前提で issue に取り組み、過度な堅牢性・セキュリティ・監視は求めない。

- Ktor/JVM backend。主要 module は `:fukurou`、package root は `me.matsumo.fukurou`。
- 標準 DoD は「動く、関連回帰テスト 1 本、資金と paper truth を壊さない」とする。
- 1 PR は human-authored diff 1,000 行を目安とし、超える場合は分割を先に検討する。
- OpenSpec（propose→specs→tasks）は schema、ledger、order lifecycle、cross-module contract の変更に限定する。分析・実験はスクリプト + 小 PR で直行する。
- property test、chaos 注入、coverage 数値目標は、資金・ledger・order lifecycle に直接関係する変更だけに要求する。
- レビューで block する基準は「資金が漏れる、paper truth が歪む、production が落ちる」に限定し、その他は suggestion とする。
- issue には背景・現状のコード位置・scope 外（やらないこと）を省略せず書く。軽くするのは受け入れ条件であって、実装 agent が迷わないための文脈ではない。
- 実験の合格ラインと運用ルール（Owner score、3 ヶ月レビュー、期間ベース証拠バー）は Epic #181 を正とする。

## 認可境界

- 自由に実行してよい: read-only の調査、ビルド、テスト、静的解析。
- 指示があれば実行する: 実装・コミット・PR 作成。sibling worktree で行い、main checkout のローカル差分と混ぜない。
- オーナーの明示承認が必要: 実資金を動かす機能の実装・有効化、live 取引への切り替え、paper baseline の epoch switch。
- 依頼されていない汎用 framework、dashboard、監視基盤、alert 機構は追加しない。スコープ外で見つけた問題は修正せず報告する。

```bash
git worktree add ../fukurou-<task-slug> -b <branch-name>
cp -p .env ../fukurou-<task-slug>/.env
cd ../fukurou-<task-slug>
```

`.env` は git 管理外のローカル実行用 file で、`git worktree add` ではコピーされない。DB password、外部 API credential、Cloudflare Access service token などがビルドまたは smoke test に必要な場合は、worktree 作成直後に元 checkout からコピーする。

## 不変条件（軽量化・調整の対象外）

- 資金保護の 5 不変条件（最大損失、損切り必須、ナンピン禁止、最大ドローダウン停止、エクスポージャー上限）を迂回できる設計にしない。取引実行や scheduler を追加する場合は `docs/design.md` の「安全床」を優先する。
- secret 境界: Cloudflare token、PostgreSQL password、取引所 API key、LLM credential などの secret はログ、PR、コメントに出さない。
- paper trading / dry-run を既定とし、本番取引への切り替えは config と運用手順の両方で明示的にする。
- LLM 呼び出し回数上限は資金保護ではなく運用 policy（サブスクコストと負荷の管理）。変更には config と根拠の記録を要するが、5 不変条件と同列の調整不能な安全床ではない。

### Paper 真実性

paper trading は、実資金投入前に LLM の判断、SafetyFloor、注文ライフサイクルを評価するシミュレーションであり、live と意味を揃える。paper 成績を良く見せるための例外や、live で再現できない状態遷移を導入しない。

- production paper baseline の正本は current account epoch の 1,000,000 円とする。baseline は runtime config activation に伴う監査済み epoch switch だけで変更し、既存履歴の rescale や destructive backfill を行わない。
- evaluation は account epoch と execution semantics cohort を分離し、`LEGACY_PRE_WS` を current KPI へ黙って混ぜない。attribution 不能 trade は missing として母集団に残す。
- 「約定した可能性がある」を「約定した」に変換しない。観測・処理できなかった市場事象は未知またはデータ欠損として記録し、過去の価格履歴から paper 約定を遡及作成しない。
- infrastructure failure、market-data gap、監視停止は strategy outcome と分離する。影響を受けた期間・注文・position は評価不能として明示し、勝率、EV、profit factor などの戦略評価へ黙って混ぜない。
- live の約定事実は取引所の注文・約定結果を正本とし、paper の約定事実も事前に定義した因果的な入力と処理境界だけから作る。
- paper execution fidelity を変更するときは、LLM 評価への影響、データ欠損時の扱い、live との意味の一致を先に設計し、回帰テストと運用上の監査証跡を同じ変更で更新する。

## 開発リファレンス

- 通常テスト `make test` / 静的解析 `make detekt` / ビルド `make build` / サーバ起動 `make run`。
- DB を使うローカル起動は、`docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres` で PostgreSQL を起動し、`.env` の `POSTGRES_PASSWORD` と同じ値を `DB_PASSWORD` に設定する。
- コード編集後は IntelliJ MCP の `get_file_problems` で対象ファイルの warning を確認する。使えない場合は `make detekt` と関連テストで代替する。
- API docs の正本は Ktor route-local の `.describe {}` であり、静的 YAML を正本にしない。endpoint の追加・変更は route 実装と同じ差分で `.describe {}` を更新し、wire contract を変えるときは OpenAPI schema と route test / contract test も更新する。公開面は `/swagger` と `/openapi.json`、人間向けの summary / description は日本語で書く。
- fukurou 固有の skill は `.codex/skills/` を正本とし、`.claude/skills/<skill>/SKILL.md` は正本への symlink として置く（実体の二重管理をしない）。skill は CLI 非依存・repo root 相対パスで書き、personal Skills repo へ公開しない。LLM daemon / paper trading の時系列確認は `.codex/skills/fukurou-llm-daemon-log-audit/` を使う。

## Deployment

deploy 手順、NAS 設定、self-hosted runner、Cloudflare 構成の正本は `docs/deploy.md`。agent が守る境界だけをここに置く:

- `github-runner` に Docker/root 権限を直接渡さない。sudoers は `/usr/local/sbin/deploy-fukurou` だけに限定する。
- Cloudflare Access service token は NAS `.env` ではなく、手元の未追跡 env file か secret manager に置く。
- 公開入口は Cloudflare Tunnel + Access とし、Ktor container の host port は公開しない。
- `/health/ready` の readiness 条件を変更するときは、compose の health / dependency と運用確認手順も合わせて見直す。

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
