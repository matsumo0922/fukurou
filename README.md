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

現時点では、`:trading` の paper account / broker / safety / reconciler / decision protocol / evaluation / knowledge writer / reflection runner / GMO Public market data、`:mcp-gmo-coin` の GMO Public market tools、`:mcp` の fukurou stdio server と fat jar、`:fukurou` の Ktor backend + 常駐 worker、`web/` の Vite + React + TypeScript foundation が実装済みです。

## Backend / API

Gradle module は `:fukurou`、package root は `me.matsumo.fukurou` です。

公開済みの Ktor API:

- `GET /revision`
- `GET /health/live`
- `GET /health/ready`
- `/evaluation/*`
- `/ops/*`
- `GET /ops/runtime-config`
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

runtime config は `.env.example` の `FUKUROU_*` で上書きできます。既定は BTC 現物 / `PAPER` / 仮想 10 万円です。`GET /ops/runtime-config` は code-owned catalog から実効設定を読み取り専用で返し、secret は設定済み / 未設定だけを返します。

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

WebUI の `Config` 画面（`/app/config`）は `/ops/runtime-config` を表示します。Runtime / Deployment / Secrets の各 group は読み取り専用で、secret 値は画面に表示しません。

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

未定
