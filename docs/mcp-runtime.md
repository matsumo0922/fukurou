# MCP runtime / Docker 配線メモ

Step6 時点の `fukurou-mcp` runtime と Docker 配線の正本メモ。

## 現在の構成

- `:mcp` は stdio server として `mcp/build/libs/fukurou-mcp-all.jar` を生成する。
- `:mcp-gmo-coin` は GMO Coin Public API の market read tools を提供する library module で、standalone 用に `mcp-gmo-coin/build/libs/gmo-coin-mcp-all.jar` も生成できる。
- Docker image には Ktor 用 `/app/app.jar`、fukurou MCP 用 `/app/fukurou-mcp-all.jar`、standalone GMO Coin MCP 用 `/app/gmo-coin-mcp-all.jar` を同梱する。
- production image には daemon 既定構成で使う `claude` / `codex` CLI も同梱する。ただし auth token は image に入れず、WebUI または fallback で作成した login state を `llm-home` volume に保存する。
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
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :mcp-gmo-coin:smokeStdio
```

`:mcp:smokeStdio` と `:mcp:timeoutStdio` は、子プロセスに `-Dfukurou.mcp.testInMemoryRuntime=true` と `FUKUROU_MCP_TEST_IN_MEMORY_RUNTIME=true` を渡して DB なしの no-trade smoke として実行する。両 task は MCP server process の raw stdout を SDK client へ渡す前に全行 JSON として parse し、stdio stdout が JSON-RPC 専用 channel であることを検証する。通常の MCP jar 起動ではこの二重 opt-in を付けず、DB env が欠けていれば fail closed する。

`:mcp-gmo-coin:smokeStdio` は standalone GMO Coin MCP fat jar を stdio 子プロセスとして起動し、SDK client へ渡す前の raw stdout を全行 JSON として parse しながらネットワーク必須の Public `get_ticker` tool を呼び出す。stderr は診断出力として許容し、stdout は JSON-RPC 専用 channel として検証する。

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

LLM CLI / MCP 登録の deployment boundary は env から差し替えられる。server 名、tool allowlist、sandbox wrapper は renderer に直書きせず、次の設定で渡す。model override と daemon cadence は WebUI `/app/config` の Runtime group で管理する。

```sh
FUKUROU_MCP_SERVER_NAME=fukurou-mcp
FUKUROU_MCP_SERVER_COMMAND=java
FUKUROU_MCP_SERVER_ARGS='-jar ${mcpJarPath}'
FUKUROU_CLAUDE_COMMAND_TEMPLATE=claude
FUKUROU_CODEX_COMMAND_TEMPLATE=codex
FUKUROU_PROPOSER_ALLOWED_TOOLS=
FUKUROU_FALSIFIER_ALLOWED_TOOLS=
FUKUROU_CLAUDE_COMMON_ARGS=
FUKUROU_CODEX_COMMON_ARGS=
FUKUROU_CODEX_FALSIFIER_ARGS=
```

`FUKUROU_MCP_SERVER_ARGS` では `${mcpJarPath}` を `FUKUROU_MCP_JAR_PATH` / request の jar path に置き換える。Codex を外部 sandbox で包む場合は、例えば `FUKUROU_CODEX_COMMAND_TEMPLATE='docker run --rm ... codex'` のように command template 側へ prefix を持たせる。local 開発では既定どおり素の `codex` 起動にできるが、この状態で `FUKUROU_CODEX_FALSIFIER_ARGS="--yolo"` を指定すると起動時に拒否する。`--yolo` の許可判定は command 名と危険 flag の deny-list による起動時チェックであり、sandbox の完全性そのものは運用設定の責務として確認する。`--privileged`、host network、root bind mount は拒否し、`--network none` は許可する。`FUKUROU_CLAUDE_COMMON_ARGS` / `FUKUROU_CODEX_COMMON_ARGS` は補助的な安全側引数だけに使い、MCP config、allowed tools、permission、sandbox、approval、Codex `-c` を上書きする flag と session 保存を止める `--ephemeral` は起動時に拒否する。Codex には structured output の `--json` を常に付け、common args 側の重複指定は除く。

runner の成功判定は DB が唯一の正本であり、LLM の stdout や exit code から decision は parse しない。Proposer 終了後に `FUKUROU_INVOCATION_ID` に紐づく `decisions` 行を読み、行がなければ `CallerNoTradeGuard` で no-trade audit を残して fail closed する。

CLI process 終了後は provider output を semantic response、structured usage、raw process result に分離する。pre-filter と Reflection は semantic response だけを読み、Codex JSONL 全文を応答本文として扱わない。Claude の raw stdout / stderr は historical usage fallback のため redaction と truncate 後の audit に保存する。Codex の raw stdout / stderr は prompt、tool payload、command output、path を含み得るため永続化せず、audit には `rawOutputOmitted` と status、exit code、usage、検出済み generic signal だけを保存する。Codex が command render、process start、cleanup で process result を返せない場合も例外 message と path は永続化せず、runner phase audit には固定の `failureCategory` と安全な例外 class 名だけを残す。同じ失敗を表す `NO_TRADE_EXIT` は message を省略し、`llm_runs.error_message` は固定文言にする。Claude は従来どおり例外 message を redaction と truncate 後に保存する。runner 生成の一時 config、session、auth copy は Codex session から model を解決した後、成功、timeout、非 0 exit、起動失敗、cancellation の各経路で削除する。cleanup 失敗は呼び出し失敗として fail closed し、timeout や非 0 exit でも取得済み usage は audit へ残すが、phase は成功へ昇格させない。

`ENTER` / `ADD_LONG` decision は Falsifier を起動し、fresh な `APPROVED` が DB にあるときだけ persisted `trade_intents` の宣言値から `PlaceOrderCommand` を組み立て、`ToolCallGuard.runTradeTool -> PaperBroker -> SafetyFloor` の既存経路へ流す。`EXIT` / `REDUCE` / `ADJUST_PROTECTION` decision は Falsifier と EV gate を通さず、保存済み decision と paper ledger から対象を一意に決められる場合だけ runner が決定論的に close / reduce / protection update を実行する。`REDUCE` は `close_ratio` を必須にし、`EXIT` は常に full close として扱う。order placement 用の第三 LLM session は起動しない。

runner が CLI に渡す MCP config は、MCP server env を親 process 継承任せにしない。DB credential を含むため、Claude では permission 0600 の一時 JSON file を作り、その path だけを `--mcp-config` に渡す。Codex では `CODEX_HOME/config.toml` に書き、`mcp_servers.*.env.*` を `-c` argv へ展開しない。production では `FUKUROU_CODEX_PERSISTENT_HOME=/tmp/fukurou-cli-home/.codex` を明示し、`llm-home` volume 内の Codex home に config だけを in-place 生成する。local など未設定時は従来どおり一時 `CODEX_HOME` を作り、親 `CODEX_HOME/auth.json`（未設定時は `HOME/.codex/auth.json`）が存在する場合だけ 0600 で copy する。概念的には少なくとも以下を MCP server env へ明示する。

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
        "FUKUROU_SYSTEM_PROMPT_VERSION": "system-prompt-v1.12",
        "FUKUROU_MARKET_SNAPSHOT_ID": "<snapshot id>",
        "FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT": "48",
        "FUKUROU_MCP_ACT_TOOL_CALL_LIMIT": "3",
        "FUKUROU_MCP_ALLOWED_TOOLS": "get_trade_intent,get_ticker,get_candles,...,submit_decision"
      }
    }
  }
}
```

Claude Proposer は既定 allowlist で `get_trade_intent` などの read tool と `submit_decision` を許可し、`place_order` は許可しない。runner は同じ allowlist を MCP server env の `FUKUROU_MCP_ALLOWED_TOOLS` に短い tool 名で渡すため、CLI 側の tool gating が provider / version 差で効かない場合でも MCP server が allowlist 外 tool を no-trade audit 付きで拒否する。`FUKUROU_PROPOSER_ALLOWED_TOOLS` / `FUKUROU_FALSIFIER_ALLOWED_TOOLS` は `mcp__<server-name>__<tool-name>` 形式だけを受け付け、Claude / Codex の組み込み tool 名は許可しない。Claude runner は private `--mcp-config`、`--strict-mcp-config`、`--allowedTools` を渡す。MCP を使う経路では `--tools "ToolSearch"` も渡し、他の built-in tool を隠したまま deferred MCP tool discovery path を残す。`--allowedTools` は permission allowlist であり、MCP tool schema の供給・発見は `ToolSearch` 経由で行う。MCP を使わない reflection は空の MCP config、`--tools ""`、`--bare` で起動し、on-disk project 設定や auto-discovery 由来の tool を読ませない。Codex Falsifier は `intent_id` だけを入力として受け取り、Proposer の理由・thesis・ナラティブは prompt/env に渡さない。Falsifier process env は非 secret の CLI 起動 env、run env 5 変数、`FUKUROU_FALSIFIER_INTENT_ID`、renderer が生成した `CODEX_HOME` に限定し、DB 接続は MCP server config file にだけ明示する。GMO API key/secret や LLM credential env は CLI process / MCP server env のどちらにも渡さない。CLI 認証は production container 内の `llm-home` volume に保存し、auth file path を app env として渡さない。

Codex Falsifier は headless の非対話実行を使う。既定 renderer は一時 `CODEX_HOME` で user config から分離し、`--skip-git-repo-check`、read-only sandbox、`approval_policy="never"` を指定する。Codex `0.142.5` では `readOnlyHint=false` の MCP tool が headless approval gate に止められるため、runner が phase allowlist と write tool 定数の交差だけを `CODEX_HOME/config.toml` の tool 単位 stanza として生成する。Falsifier では `submit_falsification` だけ、Codex Proposer では `submit_decision` だけが `approval_mode = "approve"` になり、read tools や `default_tools_approval_mode` は使わない。CLI process の shell sandbox と `approval_policy="never"` は維持され、MCP server 側の `FUKUROU_MCP_ALLOWED_TOOLS` も引き続き最終防衛線になる。

`FUKUROU_CODEX_FALSIFIER_ARGS="--yolo"` / `--dangerously-bypass-approvals-and-sandbox` は通常の production 経路には不要である。ただし外部 sandbox / container command template を使う明示 opt-in 経路の防御として validation は残す。host の素の `codex` template と `--yolo` の組み合わせは起動時に拒否し、Falsifier 専用 args ではこの opt-in flag 以外を拒否する。

## daemon scheduler

Ktor process 内の daemon scheduler は active runtime config の `daemon.enabled=true` で有効化する。code-owned catalog default は false で、WebUI `/app/config` の draft / validate / activate で有効化する。

- flat 状態: 経済イベント条件や価格急変条件がなければ 15 分 heartbeat。
- event 条件: `safety.economicEventBlackouts` の active window、価格急変、STOP 接近、paper entry fill を既存 reservation / cap 経路で評価する。経済イベントは同じ active window で 1 回だけ起動する。
- holding 状態: open position / open order が DB にある場合も 15 分 cadence。paper entry fill は最新 execution を起点に `ENTRY_FILL` として 1 回だけ起動し、cooldown 内の fill burst は後追い発火しない。
- pre-filter: `daemon.preFilterEnabled=true` のとき、flat heartbeat / holding dense check は full LLM 起動前に Claude Haiku で market snapshot の有意変化を判定する。NO の場合は `pre_filter_no_change` として full run を省略し、pre-filter 失敗時は full run へ進む。pre-filter 自体も LLM 呼び出しなので予約済み invocation と hourly / daily cap を消費し、full run だけを省略する。価格急変、STOP 接近、paper entry fill、経済イベントには適用しない。
- 起動予算: hard cap は 7/hour、120/day。flat / holding の 15 分 cadence は通常 4/hour、96/day を使う。event trigger に専用 headroom は予約せず、同じ hard cap の未使用予算を routine cadence より追加で最大 3/hour、24/day まで使う。hard cap 到達後は後続 heartbeat が skip されうる。
- HARD_HALT 中: LLM は起動せず、daemon skip 監査だけを残す。手動再開は `risk_state` の resume 操作で hard_halt を解除してから次 cycle を待つ。

起動可否は `llm_launch_reservations` と `risk_state` を同一 DB transaction で確認し、同時起動、hour/day cap、HARD_HALT の TOCTOU を避ける。LLM には次回起動時刻を決める tool を渡さない。

hourly / daily cap の起動時刻は、対象 invocation に reservation が存在する場合は `reserved_at` を正本とする。reservation のない legacy run だけ `RUNNER_PHASE_COMPLETED` / `NO_TRADE_EXIT` の `ts` へ fallback し、複数 phase は invocation 単位で 1 起動にまとめる。

手動 `OneShotRunnerMain` は daemon の `llm_launch_reservations` を通らないため、daemon の `CONCURRENT_INVOCATION` guard からは見えない。手動実行は daemon を停止してから行うこと。runner preflight の rolling cap は reservation 優先規則を使い、手動実行は reservation のない audit fallback として daemon 起動と相互に数える。preflight で拒否された手動実行も phase / NO_TRADE audit を残し、window 外へ出るまで 1 起動として数える。手動実行も開始時に active runtime config snapshot を解決し、その version id / hash を decision run audit へ残す。

Reflection Runner の PromptCandidates は完了済み前週を対象に `REFLECTION` trigger として同じ reservation / cap を使う。reflection の RUNNING 予約は trading trigger を `CONCURRENT_INVOCATION` で塞がない。fresh な trading RUNNING 予約、HARD_HALT、1時間 cap の残り 1 回以下、24時間 cap の残り 4 回以下では PromptCandidates の LLM を呼ばず、status note だけを残す。

## config

runtime config の既定値は code-owned `RuntimeConfigCatalog` が持ち、DB bootstrap が `runtime_config_versions` / `runtime_config_values` に初期 active version を作成する。active snapshot に不足する code-owned catalog key がある場合、bootstrap は既存値を保持した complete snapshot を新しい active version として作成する。明示的に退役した key は新しい active version から除去し、それ以外の unknown key、不正値、validation failure は fail closed する。active DB config が `RUNTIME` key の正本で、`.env.example` と compose は runtime default を列挙しない。`.env` は secret / deployment / bootstrap 値に使う。

paper 約定、SafetyFloor、GMO Public REST の rate-limit / retry / timeout、runner 上限、daemon cadence / trigger、Reflection Runner、LLM model override は WebUI `/app/config` の Runtime group で管理する。取引 symbol / mode、GMO Public base URL、Obsidian vault path、LLM command template、MCP command / args / allowlist、persistent home は deployment boundary として env で管理する。runtime の現在値と code-owned default は `GET /ops/runtime-config` または WebUI で確認する。

SafetyFloor 系値と fallback fee / spread は、既定値と同等またはより保守的な値だけ許可する。GMO Public REST の rate-limit も既定 10 req/s / burst 10 以下だけ許可する。GMO Public REST の timeout / retry backoff も runtime config で管理する。GMO `/public/v1/symbols` が取得できる場合、paper 手数料は取引所 rule を優先する。

runner 上限も保守側の override だけを許可する。総 tool call は 48 以下、trade 系 tool call は 3 以下、timeout は 180 秒以下、起動数は 7 回/時・120 回/日以下だけを受け入れる。tool call 上限は同じ `decision_run_id` の `command_event_log` と MCP server instance 内 counter を合算して、Proposer / Falsifier の phase をまたいで強制する。上限超過時は tool error を返し、no-trade audit を残す。

`GET /ops/runtime-config` は code-owned `RuntimeConfigCatalog` から Runtime / Deployment / Secrets の実効設定、version 履歴、warning を返す。Runtime group は active DB snapshot から解決した typed config で、Reflection Runner 設定と Claude / Codex model override も含む。Deployment group は `FUKUROU_GMO_PUBLIC_BASE_URL`、Obsidian vault path、LLM command template、MCP server command / args / tool allowlist などの deploy 境界、Secrets group は DB password や Cloudflare token などの設定有無を表す。active DB snapshot が不正または一時的に読めない場合、Ktor、WebUI、runtime config admin API は起動し、取引 runtime、manual trigger、daemon worker は fail closed する。valid な active version へ戻ると、runtime config warning、`/health/ready`、manual trigger gate は現在の active snapshot に基づいて再評価される。version 履歴が一時的に読めない場合、API は empty versions と warning を返し、catalog 表示を継続する。`/ops/runtime-config/drafts` は active または指定 version を基準に draft を作成し、`/ops/runtime-config/drafts/{versionId}/validate` は保存済み draft を現在の catalog / typed config で検証する。`/ops/runtime-config/drafts/{versionId}/activate` と `/ops/runtime-config/versions/{versionId}/rollback` は保存済み候補を再検証してから active version を切り替える。draft と inactive version は active version と newest 20 draft / newest 20 inactive version を残す。保守側へ境界を締める code を deploy する場合、active runtime config は deploy 前に新しい境界内の値へ更新する。`/app/config` は Runtime group の draft 編集、diff preview、validation、activate、rollback を扱い、Deployment group を read-only で表示する。Runtime group の変更は process restart 後に適用する。secret 値は API response と画面のどちらにも出さない。

Deployment group の env は `/ops/runtime-config` と `/app/config` に raw value として表示されるため、command template、common args、working directory、repository root、tool allowlist には secret を入れない。

Cloudflare Access は `/app/*` と `/ops/*` を保護し、runtime config draft / validate / activate / rollback を含む state-changing ops endpoints を Access なしで公開しない。

## secrets / CLI auth

- Docker image に Claude / Codex の auth token、GMO private key、Cloudflare token を焼き込まない。
- Production の Claude / Codex login flow は WebUI Controls から reason 付きで開始し、必要時だけ SSH / `docker exec` を fallback とする。Claude は browser flow が返した token/code を WebUI から active session へ 1 回だけ送信し、Codex は device auth flow のまま token/code 入力欄を使わない。login state は `llm-home` volume 配下の `HOME=/tmp/fukurou-cli-home` に保存する。
- Codex は `FUKUROU_CODEX_PERSISTENT_HOME=/tmp/fukurou-cli-home/.codex` を明示したときだけ、renderer がその場の `config.toml` を上書きする。local 開発の実 `HOME/.codex/config.toml` を汚さないため、`HOME` から永続 mode を推測しない。
- Claude / Codex CLI の access token 更新は CLI に任せる。refresh token が失効または revoke された場合だけ、WebUI または fallback で再ログインする。
- WebUI System は `/ops/llm-auth` で CLI auth 状態を表示する。`/health` / `/health/ready` は Ktor / DB / reconciler readiness の意味だけを持つ。
- auth 失効や permission prompt で decision / falsification row が保存されない場合、runner は DB-as-truth により no-trade audit を残して次 cycle に進む。
- CLI process の stdout / stderr に `is_error: true` を含む CLI 出力がある場合、runner は `RUNNER_PHASE_COMPLETED.details.cliErrorReported = "true"` を追加する。認証失敗らしい stdout / stderr を検出し、かつ CLI process が非 0 exit または `is_error: true` を含む CLI 出力を返した場合、`RUNNER_PHASE_COMPLETED.details.authFailureSuspected = "true"` と login runbook の warn log も追加する。これらは `proposer_missing_decision` の no-trade に fail closed し、`proposer_no_tool_calls` は process failure、CLI error 報告、認証失敗疑いのいずれもなく、判断未保存かつ許可済み tool call 0 件の場合だけ記録する。
- 既知制約: Falsifier の CLI process env には DB credentials を渡さないが、Falsifier が起動する MCP server config には現時点で full DB credentials が入る。read-only DB user 分離は follow-up とする。

## paper / live の構造的乖離

- paper STOP は `ProtectionReconciler` が動いている間だけ約定判定される。live の native STOP は bot 停止中も取引所側で作動するため、paper の方が保護が弱い。
- paper の LIMIT は all-or-none 約定で、GMO の FAK 部分約定は完全再現しない。LIMIT 価格までの板深さではFAK部分約定になるケースは、crossing / resting のどちらも乖離メモとして残す。crossing は tool response と runner lifecycle payload、resting は `command_event_log` の reconcile pass payload に伝搬する。
- paper の LIMIT は板が取得できる場合、BUY は `bestAsk <= limitPrice`、SELL は `bestBid >= limitPrice` で到達判定する。発注時点で板を跨ぐ LIMIT は SafetyFloor と paper 約定の両方で taker cost として扱う。板が取得できない場合だけ、WARNを出してlast price比較へfallbackする。
- paper の slippage / fallback spread / fallback fee は config で保守的に近似する。live 化前に実測で較正する。
- `LIVE` mode は typed model の予約値であり、`LiveGmoBroker` と live 実発注は未実装。現時点では env 起動を fail closed し、ユーザーの明示要求なしに有効化しない。
