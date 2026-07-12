# MCP runtime / Docker 配線メモ

Step6 時点の `fukurou-mcp` runtime と Docker 配線の正本メモ。

## 現在の構成

- `:mcp` は stdio server として `mcp/build/libs/fukurou-mcp-all.jar` を生成する。
- `:mcp-gmo-coin` は GMO Coin Public API の market read tools を提供する library module で、standalone 用に `mcp-gmo-coin/build/libs/gmo-coin-mcp-all.jar` も生成できる。
- Docker image には Ktor 用 `/app/app.jar`、fukurou MCP 用 `/app/fukurou-mcp-all.jar`、standalone GMO Coin MCP 用 `/app/gmo-coin-mcp-all.jar` を同梱する。
- production image には daemon 既定構成で使う `claude` / `codex` CLI も同梱する。ただし auth token は image に入れず、WebUI または fallback で作成した login state を `llm-auth` volume に保存する。
- container の entrypoint は Ktor のまま。production の MCP は `/usr/local/libexec/fukurou-mcp-launcher <manifest-id>` だけから起動する。`java -jar` と DB env を使う直接起動は production runner の対応経路ではない。
- production runtime の MCP server process は `fukurou-mcp` 1 つだけ。`:mcp` は `:mcp-gmo-coin` の market tools を同一 `Server` に埋め込み、account / trade / test tools と一緒に公開する。
- `:mcp-gmo-coin` は tool schema、引数 parse、`:trading` への委譲だけを持つ。rate-limit / retry / error 分類は `:trading.exchange.gmo` の GMO Public client 境界で行う。
- GMO Public client は permit 取得後の実 `HttpClient.send` attempt ごとに `GMO_PUBLIC_REST_REQUEST_COMPLETED` を記録する。payload は request / operation / client instance / process / decision run / tool call の ID、client type / role、固定 operation / endpoint、attempt、request sequence、時刻、request duration、permit wait、結果分類、HTTP status、固定 `ERR-5003` code だけを含む。response body、message、URI、query、credential、例外 message、filesystem path は保存しない。
- runner は phase、decision run context、canonical allowlist、tool budget、runtime snapshot を owner-only MCP launch manifest に固定し、launcher が MCP bootstrap へ渡す。MCP bootstrap は検証済み manifest の phase を request audit role へ明示的に投影し、環境変数から role を推論しない。埋め込み MCP は coroutine scope 内で decision run / tool call / role を request audit へ伝播する。standalone GMO Coin MCP は同じ allowlist payload を stderr に出し、DB audit を要求しない。
- request audit の append に失敗した logical request は取得済み response を利用せず、追加 retry もしない。`GMO_PUBLIC_REST_REQUEST_COMPLETED` は Activity の既定 timeline から除外され、`/ops/audit?eventType=GMO_PUBLIC_REST_REQUEST_COMPLETED` の明示 filter で取得する。
- request audit は request hot path で `command_event_log` へ同期保存する。5 秒周期の reconciler は通常 ticker / trades / candles の 3 attempt を行うため、retry と symbol cache miss を除いても 1 日約 51,840 event が下限目安になる。現在は `command_event_log` の retention / pruning を行わない。event type 限定 retention、専用 table、durable outbox による batch 化はいずれも未実装で、選択には production の event 量、保存時間、DB latency の観測値を必要とする。監査完了前に response を利用する非 durable async 化は行わない。
- `ProtectionReconciler` は一般的な tick 取得失敗を従来どおり degraded tick として扱う一方、GMO rate-limit exhaustion と request audit failure は pass failure へ遷移させる。WebSocket periodic maintenance でも maintenance success を記録せず、readiness と failure audit に障害を反映する。`PaperBroker` の optional market-data fallback も同じ 2 種類を握りつぶさず、注文判断を fail closed にする。paper / live の設定値と注文状態遷移は変えない。
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

production CLI が登録する command / args:

```json
{
  "command": "/usr/local/libexec/fukurou-mcp-launcher",
  "args": ["<opaque-manifest-id>"]
}
```

ローカルの DB なし smoke は上記 Gradle task の test-only 二重 opt-in を使う。secret-bearing DB env へ fallback する one-shot 経路はなく、production launcher、manifest directory、root-only password file がない環境では fail closed する。

## one-shot runner

手動の 1 回実行は production image 内の fixed launcher、manifest directory、root-only password file が揃う場合だけ起動する。`:trading:runOneShotLlm` は launcher 未導入のhost local checkoutではDB credentialへfallbackせずfail closedになる。runner coreは `:trading` の `me.matsumo.fukurou.trading.invoker` / `me.matsumo.fukurou.trading.runner` に置く。

`gradle.properties` で configuration cache が有効なため、env を変えて手動再実行する場合は `--no-configuration-cache` を付ける。Ktor 常駐 daemon は Gradle を経由せず、同一 process 内から runner core を直接呼ぶ。

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :mcp:buildFatJar
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :trading:runOneShotLlm --no-configuration-cache
```

container image 内で同じ runner main を使う場合は、Ktor fat jarをclasspathとして指定する。MCPはfat jarを直接起動せず、設定済みのfixed launcherへopaque manifest IDだけを渡す。

```sh
java -cp /app/app.jar me.matsumo.fukurou.trading.runner.OneShotRunnerMainKt
```

```sh
FUKUROU_REPOSITORY_ROOT=/app \
FUKUROU_LLM_WORKING_DIRECTORY=/tmp/fukurou-llm \
java -cp /app/app.jar me.matsumo.fukurou.trading.runner.OneShotRunnerMainKt
```

production one-shot は `FUKUROU_MCP_SERVER_COMMAND=/usr/local/libexec/fukurou-mcp-launcher`、manifest directory、root-only password fileが揃わない場合にfail closedする。runnerがMCP jar pathやDB passwordをCLI引数・provider設定へ展開する経路はない。

LLM CLI / MCP 登録の deployment boundary は fixed launcher を使う。server 名と sandbox wrapper は deployment 設定、phase allowlist は code-owned canonical policy とする。model override と daemon cadence は WebUI `/app/config` の Runtime group で管理する。

```sh
FUKUROU_MCP_SERVER_NAME=fukurou-mcp
FUKUROU_MCP_SERVER_COMMAND=/usr/local/libexec/fukurou-mcp-launcher
FUKUROU_CLAUDE_COMMAND_TEMPLATE=claude
FUKUROU_CODEX_COMMAND_TEMPLATE=codex
FUKUROU_CLAUDE_COMMON_ARGS=
FUKUROU_CODEX_COMMON_ARGS=
FUKUROU_CODEX_FALSIFIER_ARGS=
```

Proposer/Falsifier allowlist は env や agent input から差し替えない。Codex を外部 sandbox で包む場合は、例えば `FUKUROU_CODEX_COMMAND_TEMPLATE='docker run --rm ... codex'` のように command template 側へ prefix を持たせる。local 開発では既定どおり素の `codex` 起動にできるが、この状態で `FUKUROU_CODEX_FALSIFIER_ARGS="--yolo"` を指定すると起動時に拒否する。`--yolo` の許可判定は command 名と危険 flag の deny-list による起動時チェックであり、sandbox の完全性そのものは運用設定の責務として確認する。`--privileged`、host network、root bind mount は拒否し、`--network none` は許可する。`FUKUROU_CLAUDE_COMMON_ARGS` / `FUKUROU_CODEX_COMMON_ARGS` は補助的な安全側引数だけに使い、MCP config、allowed tools、permission、sandbox、approval、Codex `-c` を上書きする flag と session 保存を止める `--ephemeral` は起動時に拒否する。Codex には structured output の `--json` を常に付け、common args 側の重複指定は除く。

runner の成功判定は DB が唯一の正本であり、LLM の stdout や exit code から decision は parse しない。Proposer 終了後に `FUKUROU_INVOCATION_ID` に紐づく `decisions` 行を読み、行がなければ `CallerNoTradeGuard` で no-trade audit を残して fail closed する。

CLI process 終了後は provider output を semantic response、structured usage、raw process result に分離する。pre-filter と Reflection は semantic response だけを読み、Codex JSONL 全文を応答本文として扱わない。Claude の raw stdout / stderr は historical usage fallback のため redaction と truncate 後の audit に保存する。Codex の raw stdout / stderr は prompt、tool payload、command output、path を含み得るため永続化せず、audit には `rawOutputOmitted` と status、exit code、usage、検出済み generic signal だけを保存する。Codex が command render または process start で process result を返せない場合も例外 message と path は永続化せず、runner phase audit には固定の `failureCategory` と安全な例外 class 名だけを残す。同じ失敗を表す `NO_TRADE_EXIT` は message を省略し、`llm_runs.error_message` は固定文言にする。manual launch の warning log と standalone one-shot runner の stderr も元例外や stack trace を出さず、同じ固定 category と安全な例外 class 名だけを出す。standalone runner は安全な stderr を出した後も非 0 で終了する。Claude は従来どおり例外 message を redaction と truncate 後に保存し、human-facing failure propagation も変更しない。runner 生成の一時 config、session、auth copy は Codex session から model を解決した後、成功、timeout、非 0 exit、起動失敗、cancellation の各経路で削除する。process 完了後の cleanup 失敗は process result と structured usage を保持し、runner phase audit に実際の status、exit code、usage、`cleanupFailed=true` を保存してから呼び出しを fail-closed にする。timeout や非 0 exit でも取得済み usage は audit へ残すが、phase は成功へ昇格させない。

`ENTER` / `ADD_LONG` decision は Falsifier を起動し、fresh な `APPROVED` が DB にあるときだけ persisted `trade_intents` の宣言値から `PlaceOrderCommand` を組み立て、`ToolCallGuard.runTradeTool -> PaperBroker -> SafetyFloor` の既存経路へ流す。`EXIT` / `REDUCE` / `ADJUST_PROTECTION` decision は Falsifier と EV gate を通さず、保存済み decision と paper ledger から対象を一意に決められる場合だけ runner が決定論的に close / reduce / protection update を実行する。`REDUCE` は `close_ratio` を必須にし、`EXIT` は常に full close として扱う。order placement 用の第三 LLM session は起動しない。

runner が CLI に渡す MCP config は fixed `/usr/local/libexec/fukurou-mcp-launcher` と opaque な manifest ID だけを持つ。DB password、DB env、tool policy は JSON/TOML/argv/agent env に書かない。manifest は `/run/fukurou/mcp-manifests` に `appuser` owner・0600 で atomic に発行し、password を含めず、phase、expiry、canonical allowlist、DB URL/user、run identity、system prompt version、call limitだけを持つ。

production image は Ktor `appuser`（UID 10001）、CLI `llm-agent`（UID 10002）、MCP `mcp-runtime`（UID 10003）を分離する。LLM launcher は唯一の setuid MCP helper を起動する必要があるため `no_new_privs=0` を維持し、effective / permitted / ambient capability は空、core dump と dumpability は無効にする。bounding setにはhelperがroot-only inputをsealed FDへ移してUID/GIDを落とすための`CHOWN`、`DAC_READ_SEARCH`、`SETGID`、`SETUID`、`SETPCAP`だけを残す。MCP launcher は canonical ID、manifest owner/mode/link、root-only password file owner/mode/link を `openat`/`O_NOFOLLOW` で検証し、manifest FD 3 と password FD 4 だけを bootstrap へ渡した後、UID/GID を 10003 へ落とし、active / ambient / bounding capabilityを空にして `no_new_privs=1` を設定する。agent に root、Docker socket、root bind mount は与えない。

merge 前の exact-image gate は LLM/MCP の process identity、capability、FD、environment、setuid/setgid/file capability、container runtime socket/mount inventory を検査する。fixed setuid helper 2個に依存する境界自体は human review が必要な残余リスクである。

manifest IDはexpiryまでMCP launcherを再実行できるbearer capabilityである。tool call limiterは`command_event_log`のinitial countを復元してrun budgetを共有するが、複数MCP processが同時にloadしてinsertする間の競合では上限を少数call超える可能性がある。各act toolは同じSafetyFloorとcaller guardを通り、manifestのphase allowlist外のtoolは起動時とcall時の両方で拒否する。

Claude/Codex config と session は `/run/fukurou/llm-homes` tmpfs の per-run home に app UID・shared group・0770/0660 で生成する。別 UID の launcher process は読取・session 書込ができる一方、world access はない。永続 `llm-auth` は auth source だけに使い、必要な auth file だけを per-run home へ copy する。normal、非0終了、timeout、cancel、parse/start/request/render failure の全経路で config/home/manifest を削除する。cleanup failure は infrastructure failureとしてcurrent processをquarantineし、markerと残存artifactを同じtmpfsに保持する。operatorが監査・解消するかcontainer restartでtmpfs全体を破棄するまでmanual/daemonの次runを拒否する。CLI に見える設定は次の形になる。

```json
{
  "mcpServers": {
    "fukurou-mcp": {
      "command": "/usr/local/libexec/fukurou-mcp-launcher",
      "args": ["<opaque-manifest-id>"]
    }
  }
}
```

Claude Proposer は既定 allowlist で `get_trade_intent` などの read tool と `submit_decision` を許可し、`place_order` は許可しない。runner は同じ allowlist を manifest に canonical な短い tool 名で固定し、MCP bootstrap は phase ごとの完全一致、空、不明、期限切れ、予算超過を拒否する。GMO Public base URL と call budget は manifest の非 secret runtime snapshot を MCP runtime へ適用し、agent env では上書きできない。GMO API key/secret や LLM credential env は CLI process / MCP process のどちらにも渡さない。

Codex Falsifier は headless の非対話実行を使う。既定 renderer は一時 `CODEX_HOME` で user config から分離し、`--skip-git-repo-check`、read-only sandbox、`approval_policy="never"` を指定する。Codex `0.142.5` では `readOnlyHint=false` の MCP tool が headless approval gate に止められるため、runner が phase allowlist と write tool 定数の交差だけを `CODEX_HOME/config.toml` の tool 単位 stanza として生成する。Falsifier では `submit_falsification` だけ、Codex Proposer では `submit_decision` だけが `approval_mode = "approve"` になり、read tools や `default_tools_approval_mode` は使わない。CLI process の shell sandbox と `approval_policy="never"` は維持され、MCP bootstrapがmanifestのphase別canonical allowlistを完全一致で検証する最終防衛線になる。

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

LLM invocation は in-flight run を supersede しない。scheduler は trigger を既存 priority で選んだ後に active reservation を確認し、阻まれた trigger と blocker の invocation ID / trigger kind / key を `DAEMON_TRIGGER_SKIPPED` に記録する。bootstrap は stale な `llm_runs` と対応する RUNNING reservation を同一 transaction で `RESTART_INTERRUPTED` として回収し、invocation ごとに `LLM_INVOCATION_RECOVERED` を残す。`llm_runs.terminal_cause` は status と直交する machine-readable な終了理由であり、legacy terminal row は `LEGACY_UNCLASSIFIED` として読む。

hourly / daily cap の起動時刻は、対象 invocation に reservation が存在する場合は `reserved_at` を正本とする。reservation のない legacy run だけ `RUNNER_PHASE_COMPLETED` / `NO_TRADE_EXIT` の `ts` へ fallback し、複数 phase は invocation 単位で 1 起動にまとめる。

手動 `OneShotRunnerMain` は daemon の `llm_launch_reservations` を通らないため、daemon の `CONCURRENT_INVOCATION` guard からは見えない。手動実行は daemon を停止してから行うこと。runner preflight の rolling cap は reservation 優先規則を使い、手動実行は reservation のない audit fallback として daemon 起動と相互に数える。preflight で拒否された手動実行も phase / NO_TRADE audit を残し、window 外へ出るまで 1 起動として数える。手動実行も開始時に active runtime config snapshot を解決し、その version id / hash を decision run audit へ残す。

Reflection Runner の PromptCandidates は完了済み前週を対象に `REFLECTION` trigger として同じ reservation / cap を使う。reflection の RUNNING 予約は trading trigger を `CONCURRENT_INVOCATION` で塞がない。fresh な trading RUNNING 予約、HARD_HALT、1時間 cap の残り 1 回以下、24時間 cap の残り 4 回以下では PromptCandidates の LLM を呼ばず、status note だけを残す。

## config

runtime config の既定値は code-owned `RuntimeConfigCatalog` が持ち、DB bootstrap が `runtime_config_versions` / `runtime_config_values` に初期 active version を作成する。active snapshot に不足する code-owned catalog key がある場合、bootstrap は既存値を保持した complete snapshot を新しい active version として作成する。明示的に退役した key は新しい active version から除去し、それ以外の unknown key、不正値、validation failure は fail closed する。active DB config が `RUNTIME` key の正本で、`.env.example` と compose は runtime default を列挙しない。`.env` は secret / deployment / bootstrap 値に使う。

paper 約定、SafetyFloor、GMO Public REST の rate-limit / retry / timeout、GMO Public WebSocket の connect timeout / transport liveness timeout / reconnect backoff、runner 上限、daemon cadence / trigger、Reflection Runner、Proposer / Falsifier ごとの `llm.<role>.provider` / `model` / `effort` は WebUI `/app/config` の Runtime group で管理する。provider は `CLAUDE` / `CODEX`、effort は `DEFAULT` / `LOW` / `MEDIUM` / `HIGH` / `XHIGH` を使い、空の model は CLI の既定値を使う。catalog 外 effort は draft validation、activate、rollback で fail closed する。`llm.claudeModel` / `llm.codexModel` は Reflection 用として維持し、role assignment へはコピーしない。取引 symbol / mode、GMO Public REST / WebSocket URL、Obsidian vault path、fixed launcher、MCP role、manifest directory、auth source は deployment boundary として管理する。runtime の現在値と code-owned default は `GET /ops/runtime-config` または WebUI で確認する。

SafetyFloor 系値と fallback fee / spread は、既定値と同等またはより保守的な値だけ許可する。GMO Public REST の rate-limit も既定 10 req/s / burst 10 以下だけ許可する。GMO Public REST の timeout / retry backoff も runtime config で管理する。GMO `/public/v1/symbols` が取得できる場合、paper 手数料は取引所 rule を優先する。

runner 上限も保守側の override だけを許可する。総 tool call は 48 以下、trade 系 tool call は 3 以下、timeout は 180 秒以下、起動数は 7 回/時・120 回/日以下だけを受け入れる。tool call 上限は同じ `decision_run_id` の `command_event_log` と MCP server instance 内 counter を合算して、Proposer / Falsifier の phase をまたいで強制する。上限超過時は tool error を返し、no-trade audit を残す。

`GET /ops/runtime-config` は code-owned `RuntimeConfigCatalog` から Runtime / Deployment / Secrets の実効設定、version 履歴、warning を返す。Runtime group は active DB snapshot から解決した typed config で、Reflection Runner 設定と Claude / Codex model override も含む。Deployment group は `FUKUROU_GMO_PUBLIC_BASE_URL`、`FUKUROU_GMO_PUBLIC_WEBSOCKET_URL`、Obsidian vault path、LLM command template、MCP server command / args / tool allowlist などの deploy 境界、Secrets group は DB password や Cloudflare token などの設定有無を表す。active DB snapshot が不正または一時的に読めない場合、Ktor、WebUI、runtime config admin API は起動し、取引 runtime、manual trigger、daemon worker は fail closed する。valid な active version へ戻ると、runtime config warning、`/health/ready`、manual trigger gate は現在の active snapshot に基づいて再評価される。version 履歴が一時的に読めない場合、API は empty versions と warning を返し、catalog 表示を継続する。`/ops/runtime-config/drafts` は active または指定 version を基準に draft を作成し、`/ops/runtime-config/drafts/{versionId}/validate` は保存済み draft を現在の catalog / typed config で検証する。`/ops/runtime-config/drafts/{versionId}/activate` と `/ops/runtime-config/versions/{versionId}/rollback` は保存済み候補を再検証してから active version を切り替える。draft と inactive version は active version と newest 20 draft / newest 20 inactive version を残す。保守側へ境界を締める code を deploy する場合、active runtime config は deploy 前に新しい境界内の値へ更新する。`/app/config` は Runtime group の draft 編集、diff preview、validation、activate、rollback を扱い、Deployment group を read-only で表示する。Runtime group の変更は process restart 後に適用する。secret 値は API response と画面のどちらにも出さない。

Deployment group の env は `/ops/runtime-config` と `/app/config` に raw value として表示されるため、command template、common args、working directory、repository root、tool allowlist には secret を入れない。

Cloudflare Access は `/app/*` と `/ops/*` を保護し、runtime config draft / validate / activate / rollback を含む state-changing ops endpoints を Access なしで公開しない。

## secrets / CLI auth

- Docker image に Claude / Codex の auth token、GMO private key、Cloudflare token を焼き込まない。
- Production の Claude / Codex login flow は WebUI Controls から reason 付きで開始し、必要時だけ SSH / `docker exec` を fallback とする。Claude は browser flow が返した token/code を WebUI から active session へ 1 回だけ送信し、Codex は device auth flow のまま token/code 入力欄を使わない。login state は `llm-auth` volume 配下の `HOME=/tmp/fukurou-cli-home` に保存する。
- Codex login state は `/tmp/fukurou-cli-home/.codex/auth.json` を auth source とする。renderer は auth file だけを owner/group read-only の per-run home へ copy し、source の `config.toml` を読まない・上書きしない。
- Claude / Codex CLI の access token 更新は CLI に任せる。refresh token が失効または revoke された場合だけ、WebUI または fallback で再ログインする。
- WebUI System は `/ops/llm-auth` の CLI auth と `/health/ready` の WebSocket connection、transport activity、trade、maintenance、gap/recovery を表示する。readiness は DB、startup recovery、接続、未回復 gap、transport freshness、periodic safety maintenance の freshness がすべて正常な場合だけ ready になる。trade は正常に無音になり得るため readiness の必須条件にしない。
- auth 失効や permission prompt で decision / falsification row が保存されない場合、runner は DB-as-truth により no-trade audit を残して次 cycle に進む。
- CLI process の stdout / stderr に `is_error: true` を含む CLI 出力がある場合、runner は `RUNNER_PHASE_COMPLETED.details.cliErrorReported = "true"` を追加する。認証失敗らしい stdout / stderr を検出し、かつ CLI process が非 0 exit または `is_error: true` を含む CLI 出力を返した場合、`RUNNER_PHASE_COMPLETED.details.authFailureSuspected = "true"` と login runbook の warn log も追加する。これらは `proposer_missing_decision` の no-trade に fail closed し、`proposer_no_tool_calls` は process failure、CLI error 報告、認証失敗疑いのいずれもなく、判断未保存かつ許可済み tool call 0 件の場合だけ記録する。
- MCP DB credential は root-only password file と least-privilege `fukurou_mcp` role に分離し、CLI process env/config/session へ渡さない。

`McpDatabaseRoleIntegrationTest` は disposable PostgreSQL に production role SQL を適用し、dangerous role flags、`NOINHERIT`、membership、ownership、PUBLIC/database/schema/table/function の effective privilege を検証する。同じ test は `McpLaunchBootstrap`、`TradingRuntimeFactory.postgresForMcp`、実 `FukurouMcpServer` tool handler を通して Proposer/Falsifier union の 16 required call を全件実行する。GMO ticker は localhost fixture HTTP server に固定し、外部 API や実 credential を使わない。forbidden ledger DML と wrong credential は permission/auth failure になり、app/superuser へ fallback しない。

`scripts/mcp-credential-isolation-check` は exact production image の fixed LLM/MCP launcher、FD bootstrap、local GMO fixture、least-privilege role を通して Proposer/Falsifier の required 16 calls を実行する。UID/setuid/capability/secret read 境界に加え、raw provider stdout/stderr、application/container log、per-run manifest、tool audit export、data-only dump を cleanup 前に取得する。raw marker、独立 segment、JSON/TOML escape、URL percent encode、base64/base64url、hex を全 artifact で検査する。coverage 不足、readiness failure、空 audit、空 dump、scan failure は pass にしない。

## paper / live の構造的乖離

- 新規 paper resting entry は作成時に `expiresAt` を固定し、常駐 `ProtectionReconciler` が LLM runner を待たずに server clock で期限到達を fill より先に処理する。作成時の system TTL と TradePlan `timeStopAt` の早い方を採用し、過去の `timeStopAt` も保持したまま `effectiveTtlSeconds=0` とする。TTL 取消では `expiredAt=expiresAt`、`canceledAt=server processing time` を保存し、runtime config 変更で既存 order の期限を再計算しない。`expiresAt` がない legacy order は推定期限を表示せず、runner の互換 sweep に任せる。
- Activity は position 未紐付けの BUY LIMIT/STOP だけを resting entry とし、`OPEN` だけを約定待ちへ含める。期限到達から 2 polling 間隔（10 秒）までは取消処理中、それを超えた `OPEN` は要対応とする。FILLED run の詳細は、run が作成した order の保存済み entry execution から position を辿り、その position の後続 execution を表示する。後続 execution は side を含めて分類し、別 run の BUY による add-long は決済ではなく position 追加 entry として扱う。`decision_run_id` が null の reconciler execution もこの因果関係で表示し、execution がない約定を気配や価格履歴から推定しない。通常取消は対象 order と、`canceledByDecisionRunId` に保存した取消 actor run の双方を取消済みとして追跡する。表示用気配は `ProtectionReconciler` が取得した GMO REST ticker の best bid/ask と取引所時刻を process 内の共有 store から読み、Activity request 自体は GMO REST を呼ばない。気配は現在の参考値であり、run 時点の価格や paper fill の根拠には使わない。
- TTL 取消の `canceledAt - expiredAt` が 10 秒を超える場合は infrastructure / monitoring delay として `strategyEvaluationEligible=false` と `LIFECYCLE_MONITORING_DELAY` を返す。この order は勝率、EV、profit factor などの strategy outcome 集計へ含めない。現行集計は closed position と execution を正本とするため、未約定の取消 order は集計入力にならない。

Evaluation Report Console の Historical Outcome Ridge と Evidence Relationship Graph は immutable report snapshot だけを authority とする。historical ridge は observed realized R であり forecast ではない。Evidence Relationship Graph は report の evidence reference であり decision provenance や causality ではない。market-data gap、monitoring gap、missing R、truncated input は欠損または除外として表示し、価格履歴から遡及補完しない。current browser context は report evidence、paper fill、historical outcome の入力へ混ぜない。

Evaluation Report Console の current context は `/ops/current-context/ws` の browser read-only WebSocket で配信する。protocol version 1 の envelope は connection-scoped session ID、その session 内で単調増加する sequence、`SNAPSHOT / UPDATE / HEARTBEAT` を持つ。handshake は Origin 必須とし、`FUKUROU_PUBLIC_ORIGIN` に設定した単一の trusted public origin と scheme、host、effective port がすべて一致する接続だけを許可する。forwarded header は authority にせず、設定未指定は fail closed とする。各 source は実データ由来の `observedAt`、server の `receivedAt`、`staleAfterMillis`、freshness を持つ。server は15秒 ping / 45秒 timeout と slow-client closeを適用する。client は envelope と source payload を runtime validation し、gap、session mismatch、malformed envelope、45秒無受信で接続を閉じ、次の full snapshot まで resync 状態を保つ。market quote と runtime risk state は freshness を伴う別 projection であり、pin 済み report revision、claim validation、paper execution の authority にはならない。
- paper STOP は `ProtectionReconciler` が動いている間だけ約定判定される。live の native STOP は bot 停止中も取引所側で作動するため、paper の方が保護が弱い。
- paper の resting BUY LIMIT は発注時に exact-price bid と先行する同価格の自 paper order を `queueAheadBtc` として保存する。WebSocket の eligible SELL 数量が `queueAheadBtc + order.sizeBtc` に達した event でだけ、LIMIT 価格の maker 全量約定にする。partial fill と先行 exchange order cancellation は再現しない。
- transport がhealthyでも、resting BUY LIMITのqueue snapshotは直近realtime tradeを必要とする。`lastTradeAt` が30秒を超える、または未観測の場合は `QUEUE_SNAPSHOT_UNAVAILABLE` としてfail-closedにする。
- queue snapshot 取得不能、板 depth 外、取得中の session 変更は 0 とみなさず注文を fail-closed にする。REST history/candle/ticker や再接続後の履歴を resting fill、STOP、TP の根拠にしない。同期 MARKET、手動 close、crossing LIMIT は既存の即時 command contract を使う。
- WebSocket 待機中も `ProtectionReconciler` は periodic maintenance と transport liveness を独立に管理する。GMO Public WebSocket は server Ping を1分ごとに送り、Pongが3回連続で無い場合に接続を閉じる。Pong成功、subscription acknowledgement、trade は transport activity として `gmoPublic.websocketTransportLivenessTimeout` を更新し、trade無音だけでは gap、再接続、再購読を作らない。close/error、transport timeout、受信buffer overflow、subscription拒否、decode失敗は `TRANSPORT_LIVENESS_LOST` または該当理由のgapとして監査し、impact完了後に `gmoPublic.websocketReconnectBackoff` で再接続する。subscribe/unsubscribe は同一IPで1秒1回までのため、socketごとに1回だけsubscribeする。REST tick は HARD_HALT、kill criterion、ATR trailing の保守だけに使い、resting entryを約定させない。
- paper の slippage / fallback spread / fallback fee は config で保守的に近似する。live 化前に実測で較正する。
- `LIVE` mode は typed model の予約値であり、`LiveGmoBroker` と live 実発注は未実装。現時点では env 起動を fail closed し、ユーザーの明示要求なしに有効化しない。
