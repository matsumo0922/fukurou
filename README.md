# Fukurou 🦉

暗号資産（BTC）現物のデイトレードを、LLM に判断させる自律トレーディング bot。

## コンセプト

- コードは最低限の「安全床」だけを強制し、判断は LLM に広い裁量を与える
- 売買能力は自作 MCP サーバー（`fukurou-mcp`）としてツール提供し、LLM がツール呼び出しで取引する
- LLM は CLI（`claude` / `codex`）シェルアウトで実行（サブスク利用）
- 取引・監査ログは PostgreSQL を正本にし、Obsidian Writer / Reflection Runner が人間向けノートを生成する

## 安全床

1. 1 トレード最大損失 2%
2. 全ポジションに損切り必須（取引所ネイティブ逆指値を第一）
3. ナンピン禁止
4. 最大ドローダウン -15% で全停止
5. 合計エクスポージャー上限
6. 残高・レート・呼び出し回数上限

## 技術スタック

Kotlin/JVM ・ Ktor ・ Exposed ・ PostgreSQL ・ Docker Compose ・ MCP 公式 Kotlin SDK ・ GMO コイン API ・ React ・ TypeScript ・ Vite

## ステータス

詳細設計は [`docs/design.md`](docs/design.md)、MCP runtime と Docker 手順は [`docs/mcp-runtime.md`](docs/mcp-runtime.md) を参照。

現時点では、`:trading` の paper account / broker / safety / reconciler / decision protocol / evaluation / knowledge writer / reflection runner / 週次 PromptCandidates / GMO Public market data、`:mcp-gmo-coin` の GMO Public market tools、`:mcp` の fukurou stdio server と fat jar、`:fukurou` の Ktor backend + 常駐 worker、`web/` の Vite + React + TypeScript foundation が実装済みです。

## Backend / API

Gradle module は `:fukurou`、package root は `me.matsumo.fukurou` です。

公開済みの Ktor API:

- `GET /revision`
- `GET /health/live`
- `GET /health/ready`
- `/evaluation/*`
- `/ops/*`
- `GET /ops/runtime-config`
- `GET /ops/runs`
- `GET /ops/runs/{invocationId}`
- `GET /ops/activity/catalog`
- `GET /swagger`
- `GET /openapi.json`

`/health/ready` は `DB_URL` / `DB_USER` / `DB_PASSWORD` が揃っている場合に Hikari + Exposed で PostgreSQL に接続し、`SELECT 1` が成功したら ready を返します。

## Local development

PostgreSQL を Docker Compose で起動します。

```sh
cp .env.example .env
editor .env # POSTGRES_PASSWORD を設定する
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres
```

`.env` の `POSTGRES_PASSWORD` と同じ値を `DB_PASSWORD` に設定して Ktor を起動します。

```sh
export DB_URL="jdbc:postgresql://localhost:5432/fukurou"
export DB_USER="fukurou"
export DB_PASSWORD="<your local password>"
make run
```

よく使うコマンド:

```sh
make test
make detekt
make build
```

runtime config は DB 上の active version を code-owned catalog default に重ねて typed config として検証します。DB bootstrap は `runtime_config_versions` / `runtime_config_values` に初期 active version を作成し、active snapshot に不足する code-owned catalog key がある場合は既存値を保持した complete snapshot を新しい active version として作成します。key の削除は無効化手段ではなく、bootstrap が catalog default で復元します。`RUNTIME` key は active DB config が正本で、`.env` は secret / deployment / bootstrap 値に使います。`GET /ops/runtime-config` は code-owned catalog から実効設定、version 履歴、warning を返し、secret は設定済み / 未設定だけを返します。active config が不正または一時的に読めない場合も WebUI と runtime config admin API は起動し、取引 runtime、manual trigger、daemon worker は fail closed します。draft / validate / activate / rollback は `/ops/runtime-config/drafts` と `/ops/runtime-config/versions/{versionId}/rollback` で行い、active 化と rollback は保存済み候補を現在の catalog / typed config で再検証します。valid な active version へ戻ると、runtime config warning、`/health/ready`、manual trigger gate は現在の active snapshot に基づいて再評価されます。

## Web development

`web/` は Vite + React + TypeScript のローカル Web 基盤です。Ktor API を `make dev-api` で起動した状態で Vite dev server を使います。

```sh
make dev-api
```

別 terminal で Web UI を起動します。

```sh
npm --prefix web ci
make dev-web
```

`make dev-api` は `.env` を dotenvx で読み込み、ローカル PostgreSQL 向けの `DB_URL` / `DB_USER` / `DB_PASSWORD` を補完して Ktor を起動します。

Vite dev server は既定で `http://localhost:8080` の Ktor API へ proxy します。接続先は `VITE_FUKUROU_API_TARGET` で上書きできます。

WebUI の `Config` 画面（`/app/config`）は `/ops/runtime-config` を表示します。Runtime group は draft 編集、diff preview、validation、activate、rollback を扱います。Deployment group は read-only で表示し、Secrets group は設定有無だけを表示します。warning がある場合は validation error を i18n 表示し、復旧操作の入口を維持します。secret 値は API response と画面のどちらにも出しません。

WebUI の `Activity` 画面（`/app/activity`）は `/ops/runs` の decision run 一覧を新しい順に cursor pagination で表示し、`Executed / Denied / No Trade / Interrupted / Running / Failed` で絞り込みます。run を選択すると `/ops/runs/{invocationId}` から Trigger、Proposer、Intent / TradePlan、Falsifier、Runner / SafetyFloor、全 Order / Execution の各段階と raw/debug projection を右ペインへ表示します。Falsifier の verdict、LLM 申告値、SafetyFloor の再計算値と拒否 rule は別レイヤーとして扱います。terminal reason は形式検証済みの理由 code だけを返し、raw/debug projection も保存済み JSON payload をそのまま公開せず、公開可能な識別子・event type・状態だけを返します。既存の `/ops/activity` と `/ops/activity/catalog` は互換 API として残ります。

Web 側の検証は次を使います。

```sh
npm --prefix web run verify
```

OpenAPI 型は committed snapshot の `web/openapi/fukurou.openapi.json` から生成します。Ktor API contract を変更した場合は、Ktor を起動して snapshot と生成型を更新します。

```sh
curl -fsS http://localhost:8080/openapi.json -o web/openapi/fukurou.openapi.json
npm --prefix web run generate:api
```

## Deployment

Docker Compose と GitHub Actions による GHCR image pull 型の NAS deploy scaffold を用意しています。

- `Dockerfile`
- `docker-compose.yml`
- `docker-compose.dev.yml`
- `docker-compose.prod.yml`
- `.github/workflows/deploy.yml`
- `scripts/deploy/deploy-fukurou`
- `scripts/deploy/sudoers-fukurou`
- `scripts/prod-curl`
- [`docs/deploy.md`](docs/deploy.md)

秘密情報は repository にコミットしません。Cloudflare Tunnel token と PostgreSQL password は `.env` で管理し、Cloudflare Access の Service Token は NAS `.env` ではなく手元の未追跡 env file か secret manager に置きます。

## ライセンス

[PolyForm Strict License 1.0.0](LICENSE)
