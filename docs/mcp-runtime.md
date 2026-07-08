# MCP runtime / Docker 配線メモ

Step6 時点の `fukurou-mcp` runtime と Docker 配線の正本メモ。

## 現在の構成

- `:mcp` は stdio server として `mcp/build/libs/fukurou-mcp-all.jar` を生成する。
- `:mcp-gmo-coin` は GMO Coin Public API の market read tools を提供する library module で、standalone 用に `mcp-gmo-coin/build/libs/gmo-coin-mcp-all.jar` も生成できる。
- Docker image には Ktor 用 `/app/app.jar`、fukurou MCP 用 `/app/fukurou-mcp-all.jar`、standalone GMO Coin MCP 用 `/app/gmo-coin-mcp-all.jar` を同梱する。
- production image には daemon 既定構成で使う `claude` / `codex` CLI も同梱する。ただし auth token は image に入れず、container 内でログインした state を `llm-home` volume に保存する。
- container の entrypoint は Ktor のまま。daemon / CLI 実装時は、同一 image 内の MCP fat jar を `java -jar /app/fukurou-mcp-all.jar` で stdio 子プロセスとして起動する。
- production runtime の MCP server process は `fukurou-mcp` 1 つだけ。`:mcp` は `:mcp-gmo-coin` の market tools を同一 `Server` に埋め込み、account / trade / test tools と一緒に公開する。
- `:mcp-gmo-coin` は tool schema、引数 parse、`:trading` への委譲だけを持つ。rate-limit / retry / error 分類は `:trading.exchange.gmo` の GMO Public client 境界で行う。
- fukurou 埋め込み時の短期足 kline request 予算は `GMO_MAX_DAILY_KLINE_REQUESTS` で強制する。standalone 起動時はこの fukurou 固有予算を注入しない。

## local smoke

MCP fat jar と stdio smoke:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :mcp:buildFatJar
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :mcp-gmo-coin:buildFatJar
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :mcp:smokeStdio
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :mcp:timeoutStdio
```

`:mcp:smokeStdio` と `:mcp:timeoutStdio` は、子プロセスに `-Dfukurou.mcp.testInMemoryRuntime=true` と `FUKUROU_MCP_TEST_IN_MEMORY_RUNTIME=true` を渡して DB なしの no-trade smoke として実行する。通常の MCP jar 起動ではこの二重 opt-in を付けず、DB env が欠けていれば fail closed する。

Docker image に MCP fat jar が入ることの確認:

```sh
docker build -t fukurou:step6 .
docker run --rm --entrypoint /bin/sh fukurou:step6 -lc 'test -f /app/fukurou-mcp-all.jar && test -f /app/gmo-coin-mcp-all.jar'
```

CLI から stdio MCP を登録する場合の command / args:

```json
{
  "command": "java",
  "args": ["-jar", "/app/fukurou-mcp-all.jar"]
}
```

ローカル checkout の jar を使う場合は `args` を `["-jar", "mcp/build/libs/fukurou-mcp-all.jar"]` に差し替える。

## one-shot runner

手動の 1 回実行は `:trading` の JavaExec task から起動する。新規 module は作らず、runner core は `:trading` の `me.matsumo.fukurou.trading.invoker` / `me.matsumo.fukurou.trading.runner` に置く。

`gradle.properties` で configuration cache が有効なため、env を変えて手動再実行する場合は `--no-configuration-cache` を付ける。Ktor 常駐 daemon は Gradle を経由せず、同一 process 内から runner core を直接呼ぶ。

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :mcp:buildFatJar
DB_URL=jdbc:postgresql://localhost:5432/fukurou \
DB_USER=fukurou \
DB_PASSWORD=<local password> \
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :trading:runOneShotLlm --no-configuration-cache
```

container image 内で同じ runner main を使う場合は、Ktor fat jar を classpath として指定し、MCP は同梱済み fat jar を stdio 子プロセスとして登録する。

```sh
java -cp /app/app.jar me.matsumo.fukurou.trading.runner.OneShotRunnerMainKt
```

このとき runner は `FUKUROU_MCP_JAR_PATH` が未指定なら local checkout 用の `mcp/build/libs/fukurou-mcp-all.jar` を使う。container では次のように `/app/fukurou-mcp-all.jar` を明示する。

```sh
FUKUROU_MCP_JAR_PATH=/app/fukurou-mcp-all.jar \
FUKUROU_REPOSITORY_ROOT=/app \
FUKUROU_LLM_WORKING_DIRECTORY=/tmp/fukurou-llm \
java -cp /app/app.jar me.matsumo.fukurou.trading.runner.OneShotRunnerMainKt
```

LLM CLI / MCP 登録は env から差し替えられる。server 名、tool allowlist、model 名、sandbox wrapper は renderer に直書きせず、次の設定で渡す。

```sh
FUKUROU_MCP_SERVER_NAME=fukurou-mcp
FUKUROU_MCP_SERVER_COMMAND=java
FUKUROU_MCP_SERVER_ARGS='-jar ${mcpJarPath}'
FUKUROU_CLAUDE_COMMAND_TEMPLATE=claude
FUKUROU_CODEX_COMMAND_TEMPLATE=codex
FUKUROU_CLAUDE_MODEL=
FUKUROU_CODEX_MODEL=
FUKUROU_PROPOSER_ALLOWED_TOOLS=
FUKUROU_FALSIFIER_ALLOWED_TOOLS=
FUKUROU_CLAUDE_COMMON_ARGS=
FUKUROU_CODEX_COMMON_ARGS=
FUKUROU_CODEX_FALSIFIER_ARGS=
FUKUROU_LLM_DAEMON_ENABLED=false
FUKUROU_LLM_FLAT_HEARTBEAT_SECONDS=900
FUKUROU_LLM_HOLDING_CHECK_SECONDS=900
FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR=4
FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY=96
FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC=
```

`FUKUROU_MCP_SERVER_ARGS` では `${mcpJarPath}` を `FUKUROU_MCP_JAR_PATH` / request の jar path に置き換える。Codex を外部 sandbox で包む場合は、例えば `FUKUROU_CODEX_COMMAND_TEMPLATE='docker run --rm ... codex'` のように command template 側へ prefix を持たせる。local 開発では既定どおり素の `codex` 起動にできるが、この状態で `FUKUROU_CODEX_FALSIFIER_ARGS="--yolo"` を指定すると起動時に拒否する。`--yolo` の許可判定は command 名と危険 flag の deny-list による起動時チェックであり、sandbox の完全性そのものは運用設定の責務として確認する。`--privileged`、host network、root bind mount は拒否し、`--network none` は許可する。`FUKUROU_CLAUDE_COMMON_ARGS` / `FUKUROU_CODEX_COMMON_ARGS` は補助的な安全側引数だけに使い、MCP config、allowed tools、permission、sandbox、approval、Codex `-c` を上書きする flag は起動時に拒否する。

runner の成功判定は DB が唯一の正本であり、LLM の stdout や exit code から decision は parse しない。Proposer 終了後に `FUKUROU_INVOCATION_ID` に紐づく `decisions` 行を読み、行がなければ `CallerNoTradeGuard` で no-trade audit を残して fail closed する。`ENTER` decision の場合だけ Falsifier を起動し、fresh な `APPROVED` が DB にあるときだけ persisted `trade_intents` の宣言値から `PlaceOrderCommand` を組み立て、`ToolCallGuard.runTradeTool -> PaperBroker -> SafetyFloor` の既存経路へ流す。order placement 用の第三 LLM session は起動しない。

runner が CLI に渡す MCP config は、MCP server env を親 process 継承任せにしない。DB credential を含むため、Claude では permission 0600 の一時 JSON file を作り、その path だけを `--mcp-config` に渡す。Codex では `CODEX_HOME/config.toml` に書き、`mcp_servers.*.env.*` を `-c` argv へ展開しない。production では `FUKUROU_CODEX_PERSISTENT_HOME=/tmp/fukurou-cli-home/.codex` を明示し、container 内でログインした Codex home に config だけを in-place 生成する。local など未設定時は従来どおり一時 `CODEX_HOME` を作り、親 `CODEX_HOME/auth.json`（未設定時は `HOME/.codex/auth.json`）が存在する場合だけ 0600 で copy する。概念的には少なくとも以下を MCP server env へ明示する。

```json
{
  "mcpServers": {
    "fukurou-mcp": {
      "command": "java",
      "args": ["-jar", "/app/fukurou-mcp-all.jar"],
      "env": {
        "DB_URL": "jdbc:postgresql://postgres:5432/fukurou",
        "DB_USER": "fukurou",
        "DB_PASSWORD": "<from runtime env>",
        "FUKUROU_INVOCATION_ID": "<runner invocation id>",
        "FUKUROU_LLM_PROVIDER": "claude|codex",
        "FUKUROU_PROMPT_HASH": "<sha256>",
        "FUKUROU_SYSTEM_PROMPT_VERSION": "system-prompt-v1.8",
        "FUKUROU_MARKET_SNAPSHOT_ID": "<snapshot id>",
        "FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT": "48",
        "FUKUROU_MCP_ACT_TOOL_CALL_LIMIT": "3",
        "FUKUROU_MCP_ALLOWED_TOOLS": "get_trade_intent,get_ticker,get_candles,...,submit_decision"
      }
    }
  }
}
```

Claude Proposer は既定 allowlist で `get_trade_intent` などの read tool と `submit_decision` を許可し、`place_order` は許可しない。runner は同じ allowlist を MCP server env の `FUKUROU_MCP_ALLOWED_TOOLS` に短い tool 名で渡すため、CLI 側の tool gating が provider / version 差で効かない場合でも MCP server が allowlist 外 tool を no-trade audit 付きで拒否する。`FUKUROU_PROPOSER_ALLOWED_TOOLS` / `FUKUROU_FALSIFIER_ALLOWED_TOOLS` は `mcp__<server-name>__<tool-name>` 形式だけを受け付け、Claude / Codex の組み込み tool 名は許可しない。Codex Falsifier は `intent_id` だけを入力として受け取り、Proposer の理由・thesis・ナラティブは prompt/env に渡さない。Falsifier process env は非 secret の CLI 起動 env、run env 5 変数、`FUKUROU_FALSIFIER_INTENT_ID`、renderer が生成した `CODEX_HOME` に限定し、DB 接続は MCP server config file にだけ明示する。GMO API key/secret や LLM credential env は CLI process / MCP server env のどちらにも渡さない。CLI 認証は production container 内の `llm-home` volume に保存し、auth file path を app env として渡さない。

Codex Falsifier は headless の非対話実行を使う。既定 renderer は一時 `CODEX_HOME` で user config から分離し、`--skip-git-repo-check`、read-only sandbox、`approval_policy="never"` を指定する。Codex `0.142.5` では `readOnlyHint=false` の MCP tool が headless approval gate に止められるため、runner が phase allowlist と write tool 定数の交差だけを `CODEX_HOME/config.toml` の tool 単位 stanza として生成する。Falsifier では `submit_falsification` だけ、Codex Proposer では `submit_decision` だけが `approval_mode = "approve"` になり、read tools や `default_tools_approval_mode` は使わない。CLI process の shell sandbox と `approval_policy="never"` は維持され、MCP server 側の `FUKUROU_MCP_ALLOWED_TOOLS` も引き続き最終防衛線になる。

`FUKUROU_CODEX_FALSIFIER_ARGS="--yolo"` / `--dangerously-bypass-approvals-and-sandbox` は通常の production 経路には不要である。ただし外部 sandbox / container command template を使う明示 opt-in 経路の防御として validation は残す。host の素の `codex` template と `--yolo` の組み合わせは起動時に拒否し、Falsifier 専用 args ではこの opt-in flag 以外を拒否する。

## daemon scheduler

Ktor process 内の daemon scheduler は `FUKUROU_LLM_DAEMON_ENABLED=true` で有効化する。production compose は unattended 運用のため true を既定にするが、local で Ktor だけ起動するときは false を推奨する。

- flat 状態: 経済イベント条件がなければ 15 分 heartbeat。
- event 条件: `FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC` の active window で 1 回だけ起動する。価格急変 ATR 比 / reconciler 状態遷移 / spread 異常などの market event trigger は follow-up とし、この PR では経済イベント trigger のみを実装する。
- holding 状態: open position / open order が DB にある場合も 15 分 cadence。
- 起動予算: 4/hour、96/day。event も heartbeat も同じ予算を消費する。サブスク枠 / token 消費は Usage UI と別途集計で監視する前提で、学習に必要な 15 分間隔を初期値にする。
- HARD_HALT 中: LLM は起動せず、daemon skip 監査だけを残す。手動再開は `risk_state` の resume 操作で hard_halt を解除してから次 cycle を待つ。

起動可否は `llm_launch_reservations` と `risk_state` を同一 DB transaction で確認し、同時起動、hour/day cap、HARD_HALT の TOCTOU を避ける。LLM には次回起動時刻を決める tool を渡さない。

手動 `OneShotRunnerMain` は daemon の `llm_launch_reservations` を通らないため、daemon の `CONCURRENT_INVOCATION` guard からは見えない。手動実行は daemon を停止してから行うこと。rolling cap は `command_event_log` 側で相互に数える。

## config

既定値は `.env.example` に記載する。主な env override:

| env | 既定 | 用途 |
| --- | --- | --- |
| `FUKUROU_TRADING_SYMBOL` | `BTC` | 取引対象 symbol |
| `FUKUROU_TRADING_MODE` | `PAPER` | `PAPER` のみ有効。`LIVE` は予約値で、現時点では起動時に拒否 |
| `FUKUROU_PAPER_INITIAL_CASH_JPY` | `100000` | paper 初期 JPY 残高 |
| `FUKUROU_MARKET_SLIPPAGE_BPS` | `5` | paper MARKET / STOP 約定 slippage |
| `FUKUROU_MAX_RISK_PER_TRADE_RATIO` | `0.02` | 1 trade group 最大損失 |
| `FUKUROU_MAX_DRAWDOWN_RATIO` | `-0.15` | HARD_HALT drawdown |
| `FUKUROU_MAX_TOTAL_EXPOSURE_RATIO` | `0.80` | 合計 exposure 上限 |
| `FUKUROU_GMO_PUBLIC_REST_PER_SECOND` | `10` | GMO Public REST client-side limit。10 以下だけ許可 |
| `FUKUROU_GMO_RETRY_MAX_ATTEMPTS` | `3` | 一時失敗 retry 回数 |
| `FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT` | `48` | 1 decision run あたりの総 tool call 上限 |
| `FUKUROU_MCP_ACT_TOOL_CALL_LIMIT` | `3` | 1 decision run あたりの trade 系 tool call 上限 |
| `FUKUROU_LLM_RUN_TIMEOUT_SECONDS` | `180` | 1 CLI 起動 timeout |
| `FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR` | `4` | runner が audit / DB から数える 1 時間起動上限 |
| `FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY` | `96` | runner / daemon が audit / DB から数える 24 時間起動上限。15 分 cadence を 24 時間維持する初期値 |
| `FUKUROU_RESTING_ENTRY_ORDER_TTL_SECONDS` | `1800` | runner が stale resting entry order を自動 cancel する TTL。既定より長い値は拒否 |
| `FUKUROU_LLM_DAEMON_ENABLED` | `false` | Ktor process 内 daemon scheduler の有効化。production compose は true |
| `FUKUROU_LLM_DAEMON_POLL_SECONDS` | `60` | daemon loop の確認間隔。60 秒以上だけ許可 |
| `FUKUROU_LLM_FLAT_HEARTBEAT_SECONDS` | `900` | flat heartbeat。15 分以上だけ許可 |
| `FUKUROU_LLM_HOLDING_CHECK_SECONDS` | `900` | holding dense check。15 分以上だけ許可 |
| `FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC` | 未指定 | `id\|name\|eventAtUtc\|beforeMinutes\|afterMinutes` を `;` 区切りで列挙 |
| `FUKUROU_MCP_JAR_PATH` | `mcp/build/libs/fukurou-mcp-all.jar` | one-shot runner が stdio 子プロセスとして起動する MCP fat jar |
| `FUKUROU_MCP_SERVER_NAME` | `fukurou-mcp` | Claude / Codex に登録する MCP server 名 |
| `FUKUROU_MCP_SERVER_COMMAND` | `java` | MCP server 起動 command |
| `FUKUROU_MCP_SERVER_ARGS` | 未指定 | MCP server 起動引数。未指定時は `-jar <FUKUROU_MCP_JAR_PATH>` |
| `FUKUROU_CLAUDE_COMMAND_TEMPLATE` | `claude` | Claude CLI 起動 command template |
| `FUKUROU_CODEX_COMMAND_TEMPLATE` | `codex` | Codex CLI 起動 command template。sandbox wrapper prefix を含められる |
| `FUKUROU_CLAUDE_MODEL` | 未指定 | Claude CLI に渡す model 名 |
| `FUKUROU_CODEX_MODEL` | 未指定 | Codex CLI に渡す model 名 |
| `FUKUROU_MCP_ALLOWED_TOOLS` | runner が設定 | MCP server instance 内で許可する short tool 名。通常は手動設定しない |

SafetyFloor 系 env と fallback fee / spread は、既定値と同等またはより保守的な値だけ許可する。GMO Public REST の rate-limit も既定 10 req/s / burst 10 以下だけ許可する。GMO Public REST の timeout / retry backoff も `.env.example` から上書きできる。GMO `/public/v1/symbols` が取得できる場合、paper 手数料は取引所 rule を優先する。

runner 上限も保守側の override だけを許可する。総 tool call は 48 以下、trade 系 tool call は 3 以下、timeout は 180 秒以下、起動数は 4 回/時・96 回/日以下だけを受け入れる。tool call 上限は同じ `decision_run_id` の `command_event_log` と MCP server instance 内 counter を合算して、Proposer / Falsifier の phase をまたいで強制する。上限超過時は tool error を返し、no-trade audit を残す。

## secrets / CLI auth

- Docker image に Claude / Codex の auth token、GMO private key、Cloudflare token を焼き込まない。
- Production の Claude / Codex login state は container 内でログインし、`llm-home` volume 配下の `HOME=/tmp/fukurou-cli-home` に保存する。
- Codex は `FUKUROU_CODEX_PERSISTENT_HOME=/tmp/fukurou-cli-home/.codex` を明示したときだけ、renderer がその場の `config.toml` を上書きする。local 開発の実 `HOME/.codex/config.toml` を汚さないため、`HOME` から永続 mode を推測しない。
- Claude / Codex CLI の access token 更新は CLI に任せる。refresh token が失効または revoke された場合だけ、container 内で再ログインする。
- auth 失効や permission prompt で decision / falsification row が保存されない場合、runner は DB-as-truth により no-trade audit を残して次 cycle に進む。
- CLI process が非 0 exit で認証失敗らしい stdout / stderr を出した場合、runner は `RUNNER_PHASE_COMPLETED.details.authFailureSuspected = "true"` と login runbook の warn log を追加する。
- 既知制約: Falsifier の CLI process env には DB credentials を渡さないが、Falsifier が起動する MCP server config には現時点で full DB credentials が入る。read-only DB user 分離は follow-up とする。

## paper / live の構造的乖離

- paper STOP は `ProtectionReconciler` が動いている間だけ約定判定される。live の native STOP は bot 停止中も取引所側で作動するため、paper の方が保護が弱い。
- paper は当面 all-or-none 約定で、GMO の FAK 部分約定は完全再現しない。
- paper の slippage / fallback spread / fallback fee は config で保守的に近似する。live 化前に実測で較正する。
- `LIVE` mode は typed model の予約値であり、`LiveGmoBroker` と live 実発注は未実装。現時点では env 起動を fail closed し、ユーザーの明示要求なしに有効化しない。
