# MCP runtime / Docker 配線メモ

Step6 時点の `gmo-coin-mcp` runtime と Docker 配線の正本メモ。

## 現在の構成

- `:mcp` は stdio server として `mcp/build/libs/fukurou-mcp-all.jar` を生成する。
- Docker image には Ktor 用 `/app/app.jar` と MCP 用 `/app/fukurou-mcp-all.jar` を同梱する。
- container の entrypoint は Ktor のまま。daemon / CLI 実装時は、同一 image 内の MCP fat jar を `java -jar /app/fukurou-mcp-all.jar` で stdio 子プロセスとして起動する。
- `:mcp` は tool schema、引数 parse、`:trading` への委譲だけを持つ。rate-limit / retry / error 分類は `:trading.exchange.gmo` の GMO Public client 境界で行う。

## local smoke

MCP fat jar と stdio smoke:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :mcp:buildFatJar
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :mcp:smokeStdio
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :mcp:timeoutStdio
```

`:mcp:smokeStdio` と `:mcp:timeoutStdio` は、子プロセスに `-Dfukurou.mcp.testInMemoryRuntime=true` と `FUKUROU_MCP_TEST_IN_MEMORY_RUNTIME=true` を渡して DB なしの no-trade smoke として実行する。通常の MCP jar 起動ではこの二重 opt-in を付けず、DB env が欠けていれば fail closed する。

Docker image に MCP fat jar が入ることの確認:

```sh
docker build -t fukurou:step6 .
docker run --rm --entrypoint /bin/sh fukurou:step6 -lc 'test -f /app/fukurou-mcp-all.jar'
```

CLI から stdio MCP を登録する場合の command / args:

```json
{
  "command": "java",
  "args": ["-jar", "/app/fukurou-mcp-all.jar"]
}
```

ローカル checkout の jar を使う場合は `args` を `["-jar", "mcp/build/libs/fukurou-mcp-all.jar"]` に差し替える。

## config

既定値は `.env.example` に記載する。主な env override:

| env | 既定 | 用途 |
| --- | --- | --- |
| `FUKUROU_TRADING_SYMBOL` | `BTC` | 取引対象 symbol |
| `FUKUROU_TRADING_MODE` | `PAPER` | paper/live 予約値。live 実発注は未実装 |
| `FUKUROU_PAPER_INITIAL_CASH_JPY` | `100000` | paper 初期 JPY 残高 |
| `FUKUROU_MARKET_SLIPPAGE_BPS` | `5` | paper MARKET / STOP 約定 slippage |
| `FUKUROU_MAX_RISK_PER_TRADE_RATIO` | `0.02` | 1 trade group 最大損失 |
| `FUKUROU_MAX_DRAWDOWN_RATIO` | `-0.15` | HARD_HALT drawdown |
| `FUKUROU_MAX_TOTAL_EXPOSURE_RATIO` | `0.80` | 合計 exposure 上限 |
| `FUKUROU_GMO_PUBLIC_REST_PER_SECOND` | `10` | GMO Public REST client-side limit |
| `FUKUROU_GMO_RETRY_MAX_ATTEMPTS` | `3` | 一時失敗 retry 回数 |

GMO Public REST の timeout / retry backoff / fallback fee も `.env.example` から上書きできる。GMO `/public/v1/symbols` が取得できる場合、paper 手数料は取引所 rule を優先する。

## secrets / CLI auth

- Docker image に Claude / Codex の auth token、GMO private key、Cloudflare token を焼き込まない。
- CLI auth file を検証 container に渡す場合は read-only mount にする。
- CLI home への cache / session 書き込みと auth file の read-only mount は分ける。
- token / cost / CLI auth mount の再起動実測は #19 の範囲で扱い、Step6 では image に MCP fat jar を同梱し、手順を固定するところまでとする。

## paper / live の構造的乖離

- paper STOP は `ProtectionReconciler` が動いている間だけ約定判定される。live の native STOP は bot 停止中も取引所側で作動するため、paper の方が保護が弱い。
- paper は当面 all-or-none 約定で、GMO の FAK 部分約定は完全再現しない。
- paper の slippage / fallback spread / fallback fee は config で保守的に近似する。live 化前に実測で較正する。
- `LIVE` mode は typed config の予約値であり、`LiveGmoBroker` と live 実発注は未実装。ユーザーの明示要求なしに有効化しない。
