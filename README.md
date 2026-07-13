# Fukurou 🦉

暗号資産（BTC）現物のデイトレードを、LLM に判断させる自律トレーディング bot。

paper trading の基準資金は immutable な account epoch で管理する。fresh install の current epoch は 1,000,000 円で、新規 order/execution は `PAPER_WS_V1` execution semantics と canonical runtime config hash を lineage として保存する。既存履歴は rescale せず `LEGACY_PRE_WS` cohort として current evaluation から分離する。

`paper.initialCashJpy` の activate / rollback は、open position、resting order、BTC 残高がすべて 0 の場合だけ runtime config と同一 transaction で新 epoch を開始する。拒否時は config と口座を変更せず `PAPER_ACCOUNT_EPOCH_SWITCH_REJECTED` を Activity に残す。Evaluation と report は未指定時に active epoch + `CURRENT` を使い、legacy は selector で参照する。

## コンセプト

- コードは最低限の「安全床」だけを強制し、判断は LLM に広い裁量を与える
- 売買能力は自作 MCP サーバー（`fukurou-mcp`）としてツール提供し、LLM がツール呼び出しで取引する
- LLM は CLI（`claude` / `codex`）シェルアウトで実行（サブスク利用）
- 取引・監査ログは PostgreSQL を正本にし、Obsidian Writer / Reflection Runner が人間向けノートを生成する
- LLM run の終了理由は stable な terminal cause で記録し、daemon は in-flight invocation を supersede せず blocker を監査する
- LLM daemon の automatic launch は、毎週土曜日 09:00〜11:00 JST の GMO 定期メンテナンス窓と Public status が `OPEN` でない期間を reservation 前に抑止する。status timeout・不正 response・transport failure も fail closed とし、`DAEMON_LAUNCH_SUPPRESSED` を strategy の `NO_TRADE` と分離して記録する

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

現時点では、`:trading` の paper account / broker / safety / WebSocket paper execution / decision protocol / evaluation / knowledge writer / reflection runner / 週次 PromptCandidates / GMO Public market data、`:mcp-gmo-coin` の GMO Public market tools、`:mcp` の fukurou stdio server と fat jar、`:fukurou` の Ktor backend + 常駐 worker、`web/` の Vite + React + TypeScript foundation が実装済みです。

entry decision は full run 前に固定した bounded material-state manifest と、server が生成する episode / thesis / geometry / material-state identity を decision と intent に保存します。`/evaluation/summary` の `deduplication` と Overview は identity / shadow coverage、resting maintenance、false-suppression proxy の観測値を表示します。ゼロ分母の rate は `null` です。

paper の未約定 LIMIT、BUY STOP、protective STOP、virtual TP は GMO Public WebSocket `trades/BTC` の接続中に受信した realtime event だけで前進します。transport activity、trade、periodic maintenance は別々に監査し、trade 無音だけでは接続障害にしません。close/error、decode failure、buffer overflow、transport liveness timeout は market-data gap として永続化し、影響する注文・position・decision run を成績評価から除外します。REST history、candle、再接続後の履歴から遡及約定は作りません。

## Backend / API

Gradle module は `:fukurou`、package root は `me.matsumo.fukurou` です。

公開済みの Ktor API:

- `GET /revision`
- `GET /health/live`
- `GET /health/ready`
- `/evaluation/*`: 既存集計 API と、共通 LLM reservation / audited manual generation / immutable revision / deterministic evidence を扱う Evaluation Report Console API
- `/ops/*`
- `GET /ops/runtime-config`
- `GET /ops/runs`
- `GET /ops/runs/{invocationId}`
- `GET /ops/activity/catalog`
- `GET /swagger`
- `GET /openapi.json`

`/health/ready` は、Hikari + Exposed による PostgreSQL 接続、runtime config の有効性、startup recovery 完了、LLM execution claim の outcome-unknown registry・heartbeat・bounded periodic recovery scan、WebSocket が `CONNECTED`、未回復の market-data gapがないこと、fresh な transport activity と periodic maintenance をすべて確認して ready を返します。DB scan failureまたはtermination fenceを確認できないstale claimがある間はready 503かつ新しいLLM admission 0を維持します。trade は正常に無音になり得るため、readiness の必須条件にしません。

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

runtime config は DB 上の active version を code-owned catalog default に重ねて typed config として検証します。DB bootstrap は `runtime_config_versions` / `runtime_config_values` に初期 active version を作成し、active snapshot に不足する code-owned catalog key がある場合は既存値を保持した complete snapshot を新しい active version として作成します。明示的に退役した key は新しい active version から除去し、それ以外の unknown key は fail closed します。`RUNTIME` key は active DB config が正本で、`.env.example` と compose は runtime default を列挙せず、`.env` は secret / deployment / bootstrap 値に使います。LLM model override と Reflection Runner の interval / query / PromptCandidates 設定は `RUNTIME`、Obsidian vault path は container mount と対応する read-only の `DEPLOYMENT` として扱います。`GET /ops/runtime-config` は code-owned catalog から実効設定、version 履歴、warning を返し、secret は設定済み / 未設定だけを返します。active config が不正または一時的に読めない場合も WebUI と runtime config admin API は起動し、取引 runtime、manual trigger、daemon worker は fail closed します。draft / validate / activate / rollback は `/ops/runtime-config/drafts` と `/ops/runtime-config/versions/{versionId}/rollback` で行い、active 化と rollback は保存済み候補を現在の catalog / typed config で再検証します。valid な active version へ戻ると、runtime config warning、`/health/ready`、manual trigger gate は現在の active snapshot に基づいて再評価されます。

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

current-context WebSocket は `.env` の `FUKUROU_PUBLIC_ORIGIN=http://localhost:5173` を local browser origin の正本として検証します。production では Cloudflare Tunnel の Public Hostname origin を設定し、未設定時は接続を拒否します。

WebUI の `Config` 画面（`/app/config`）は `/ops/runtime-config` を表示します。Runtime group は Proposer / Falsifier ごとの provider、model、reasoning effort を含む draft 編集、diff preview、validation、activate、rollback を扱います。provider と effort は catalog 候補から選択し、model が空なら CLI 側の既定を使います。Deployment group は read-only で表示し、Secrets group は設定有無だけを表示します。warning がある場合は validation error を i18n 表示し、復旧操作の入口を維持します。secret 値は API response と画面のどちらにも出しません。

WebUI の `Activity` 画面（`/app/activity`）は `/ops/runs` の decision run 一覧を新しい順に cursor pagination で表示し、server-side の `filter` query で outcome を目的別に絞り込みます。正常な未約定 BUY LIMIT/STOP は「約定待ち」、期限到達から reconciler の通常処理猶予内は「期限到達・取消処理中」、猶予を超えても `OPEN` の order は「期限超過・未取消」、TTL 取消済み order は「期限切れ・取消済み」、通常取消は「取消済み」と表示し、protective SELL order や process failure と区別します。process failure は outcome と独立した marker で表示し、`ACTION_REQUIRED` filter でも取得できます。SafetyFloor 拒否、RUNNING、TTL 取消、通常取消はそれぞれ専用 filter を持ちます。一覧と詳細の top-level `latestMarketQuote` は `ProtectionReconciler` が取得した ticker の best bid/ask と取引所時刻を共有 store から返し、API request は市場データを再取得しません。参考気配は現在の表示用であり、run 時点の価格や paper fill の根拠ではありません。filter 指定時の API は 1 request あたり最大 1,000 raw run を走査し、上限到達時は最後に走査した raw run の `nextBefore` を返します。run 詳細の FILLED は、run が作成した order の保存済み entry execution と同じ position の後続 execution だけを時刻順に表示し、execution 証跡がない約定を推定しません。後続 execution は side を含めて分類し、別 run の BUY による add-long は決済ではなく position 追加 entry として表示します。未約定詳細は注文条件、期限、安全性、処理経路の通常区画で表示します。run ID は完全一致検索でき、検索結果は現在の filter / pagination に依存しません。通常取消は対象 order の run と取消を実行した actor run を `canceledByDecisionRunId` で追跡します。TTL 取消の監視遅延が通常処理猶予を超えた order は「監視遅延・評価対象外」とし、勝率、EV、profit factor などの strategy metrics へ含めません。`expiresAt` がない legacy order は期限を推定せず「期限記録なし」と表示します。raw/debug projection は保存済み JSON payload をそのまま公開せず、公開可能な識別子・event type・状態だけを返します。既存の `/ops/activity` と `/ops/activity/catalog` は互換 API として残ります。

`/ops/activity` と Activity catalog は `DAEMON_LAUNCH_SUPPRESSED` と typed infrastructure reason を公開し、日英の label / description を持ちます。この event は evaluation report の `NO_TRADE` 母集団へ含めません。

Activity は Proposer / Falsifier ごとの phase audit から configured model / effort、rendered effort、observed model を別々に表示し、configured model を usage や cost attribution に使いません。

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
- `.github/workflows/deploy-queue-watchdog.yml`
- `scripts/deploy/deploy-fukurou`
- `scripts/deploy/sudoers-fukurou`
- `scripts/prod-curl`
- [`docs/deploy.md`](docs/deploy.md)

秘密情報は repository にコミットしません。Cloudflare Tunnel token と PostgreSQL password は `.env` で管理し、Cloudflare Access の Service Token は NAS `.env` ではなく手元の未追跡 env file か secret manager に置きます。

## ライセンス

[PolyForm Strict License 1.0.0](LICENSE)
