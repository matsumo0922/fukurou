# Step1 MCP 疎通スパイク結果

Issue #4 の実測メモ。`docs/design.md` には OCO 前提など古い記述が残っているため、本スパイクでは Issue #2 / #4 を正本として扱った。

## 実装したもの

- Gradle module `:trading`
  - `Ticker`
  - `TradingSymbol`
  - `MarketDataSource`
  - `GmoPublicMarketDataSource`
- Gradle module `:mcp`
  - MCP Kotlin SDK `0.14.0`
  - stdio server fat jar `mcp/build/libs/fukurou-mcp-all.jar`
  - tool `get_ticker`
  - reject-only dummy tool `reject_dummy_trade`
- Claude project config `.mcp.json`
- stdio smoke task `./gradlew :mcp:smokeStdio`

## MCP SDK / fat jar

MCP Kotlin SDK `0.14.0` の stdio server transport は `StdioServerTransport(input, output) { ... }` の builder 形式を使う。

fat jar は固定名にした。

```sh
./gradlew :mcp:buildFatJar
java -jar mcp/build/libs/fukurou-mcp-all.jar
```

## 直接 smoke

```sh
./gradlew :mcp:smokeStdio
```

結果:

- `get_ticker` は GMO Public API から BTC ticker を取得できた。
- `reject_dummy_trade` は副作用なしで reject された。

確認できた出力例:

```text
get_ticker ok: {"symbol":"BTC","last":"9759012","bid":"9755244","ask":"9755245","high":"9820000","low":"9400004","volume":"211.10479","timestamp":"2026-07-01T16:33:16.531Z"}
reject_dummy_trade rejected as expected: Rejected: Step1 dummy trade never performs side effects.
```

## Claude headless

使用予定の設定:

```sh
claude -p 'MCP tool get_ticker を必ず使って BTC の ticker を取得し、最後に取得した symbol/last/bid/ask/timestamp だけを短く出力して。' \
  --mcp-config .mcp.json \
  --strict-mcp-config \
  --permission-mode dontAsk \
  --allowedTools mcp__fukurou-gmo-coin-mcp__get_ticker \
  --output-format json \
  --no-session-persistence
```

結果:

- 未達。MCP 有無に関係なく `claude -p 'hello'` が `401 Invalid authentication credentials` で失敗した。
- `claude auth status` は `loggedIn: true` / `authMethod: claude.ai` / `subscriptionType: max` を返した。
- `--model sonnet` を指定しても同じ 401。

判断:

- 今回の Claude 未達は MCP server 起動や tool approval の問題ではなく、Claude CLI の print-mode 認証経路の問題として扱う。
- Claude の headless 承認設定は上記コマンド形で継続検証する。

## Codex headless

Codex は user config を編集せず、`-c` の一時設定で MCP server を登録した。sandbox 内では app-server client 初期化が `Operation not permitted` で落ちたため、検証は権限外で実行した。

```sh
codex exec \
  --ignore-user-config \
  --sandbox read-only \
  -c 'approval_policy="never"' \
  -c 'mcp_servers.fukurou-gmo-coin-mcp.command="java"' \
  -c 'mcp_servers.fukurou-gmo-coin-mcp.args=["-jar","<repo>/mcp/build/libs/fukurou-mcp-all.jar"]' \
  'Use the fukurou-gmo-coin-mcp get_ticker MCP tool to fetch BTC ticker. Then answer with only symbol, last, bid, ask, timestamp. Do not run shell commands and do not edit files.'
```

結果:

- 成功。
- `mcp: fukurou-gmo-coin-mcp/get_ticker started`
- `mcp: fukurou-gmo-coin-mcp/get_ticker (completed)`
- 出力例: `BTC, 9755244, 9749911, 9750600, 2026-07-01T16:34:53.755Z`

## Codex dummy trade reject

```sh
codex exec \
  --ignore-user-config \
  --sandbox read-only \
  -c 'approval_policy="never"' \
  -c 'mcp_servers.fukurou-gmo-coin-mcp.command="java"' \
  -c 'mcp_servers.fukurou-gmo-coin-mcp.args=["-jar","<repo>/mcp/build/libs/fukurou-mcp-all.jar"]' \
  'Call the fukurou-gmo-coin-mcp reject_dummy_trade MCP tool with reason="headless reject check". Report whether it was rejected. Do not run shell commands and do not edit files.'
```

結果:

- 非 read-only tool として host 側で `user cancelled MCP tool call` になった。
- approval prompt は出ず、取引系 tool は headless `approval_policy="never"` で no-trade 終了した。
- SDK smoke では server handler まで届き、`Rejected: Step1 dummy trade never performs side effects.` が返ることを確認済み。

## 失敗系

### Claude 認証切れ相当

`claude -p 'hello' --output-format json --no-session-persistence` が 401 を返した。MCP tool call 以前に終了し、取引は行われない。

### MCP 起動失敗

missing jar を指定して Codex headless を実行した。

```sh
codex exec \
  --ignore-user-config \
  --sandbox read-only \
  -c 'approval_policy="never"' \
  -c 'mcp_servers.fukurou-gmo-coin-mcp.command="java"' \
  -c 'mcp_servers.fukurou-gmo-coin-mcp.args=["-jar","/tmp/fukurou-missing-mcp.jar"]' \
  'Try to call fukurou-gmo-coin-mcp get_ticker for BTC. If the MCP server is unavailable, say unavailable and do not use any other tools.'
```

結果:

- Codex の callable tools に `get_ticker` が出ず、`Unavailable` で終了した。
- no-trade で終了した。

### timeout

今回の Step1 実装には sleep tool や wrapper daemon がないため、tool timeout / LLM timeout は独立再現していない。Step1.5 の no-trade wrapper 契約で timeout を明示的に固定する。

## CLI 認証と container 化メモ

- Codex はローカルの `CODEX_HOME` 認証を使い、複数回の `codex exec` で headless 実行できた。
- Claude は `claude auth status` では logged in だが、`claude -p` は 401。container mount の前に print-mode 認証経路の修復が必要。
- container 検証では `CODEX_HOME` / Claude の認証保存先を read-only volume として mount し、token を image や log に焼かない。
- trade 専用 container では一般 Bash/File tool を閉じ、必要な MCP server だけを登録する。`--dangerously-skip-permissions` 系は secret 漏洩リスクがあるため既定にしない。

## 次への申し送り

- #3 ではこの実測結果を踏まえて `docs/design.md` を訂正する。
- #10 では no-trade wrapper、timeout、audit log、global lock、ProtectionReconciler 骨格を入れる。
- #5 以降の market tool は `:trading` に業務ロジック、`:mcp` に schema / parse / delegation の薄い層を維持する。
