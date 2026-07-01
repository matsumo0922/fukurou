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

使用した設定:

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

- 成功。
- `--permission-mode dontAsk` と `--allowedTools mcp__fukurou-gmo-coin-mcp__get_ticker` で approval prompt なしに `get_ticker` が呼ばれた。
- 出力例: `symbol=BTC last=9747365 bid=9742999 ask=9746000 timestamp=2026-07-01T16:46:28.403Z`

判断:

- Claude headless の読み取り tool は、上記オプションで非対話実行できる。
- 以前は `claude -p` が 401 を返したが、その後 `claude -p "こんにちは"` と MCP tool call の両方が成功したため、少なくとも現在のローカル認証状態では解消済みとして扱う。

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

## Claude dummy trade reject

```sh
claude -p 'reject_dummy_trade MCP tool を reason="headless reject check" で呼び、拒否されたことだけを短く答えて。' \
  --mcp-config .mcp.json \
  --strict-mcp-config \
  --permission-mode dontAsk \
  --allowedTools mcp__fukurou-gmo-coin-mcp__reject_dummy_trade \
  --output-format json \
  --no-session-persistence
```

結果:

- 成功。
- tool handler が `Rejected: Step1 dummy trade never performs side effects.` を返し、取引副作用なしで終了した。
- approval prompt は出なかった。

## 失敗系

### CLI 認証切れ相当

以前の検証では `claude -p 'hello' --output-format json --no-session-persistence` が 401 を返した。MCP tool call 以前に終了し、取引は行われない。

現在のローカル環境では同じ print-mode 認証経路が復旧しており、Claude headless の `get_ticker` と `reject_dummy_trade` は成功した。

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

Claude headless でも missing jar を指定して実行した。

```sh
claude -p 'fukurou-gmo-coin-mcp の get_ticker を使ってみて。MCP server が利用できなければ unavailable とだけ答えて。' \
  --mcp-config '{"mcpServers":{"fukurou-gmo-coin-mcp":{"command":"java","args":["-jar","/tmp/fukurou-missing-mcp.jar"]}}}' \
  --strict-mcp-config \
  --permission-mode dontAsk \
  --allowedTools mcp__fukurou-gmo-coin-mcp__get_ticker \
  --output-format json \
  --no-session-persistence
```

結果:

- Claude は `unavailable` を返した。
- MCP server が起動できない場合も、取引系 tool は呼ばれない。

### timeout

今回の Step1 実装には sleep tool や wrapper daemon がないため、tool timeout / LLM timeout は独立再現していない。Step1.5 の no-trade wrapper 契約で timeout を明示的に固定する。

## CLI 認証と container 化メモ

- Codex はローカルの `CODEX_HOME` 認証を使い、複数回の `codex exec` で headless 実行できた。
- Claude はローカルの Claude Code 認証を使い、複数回の `claude -p` で headless 実行できた。
- container 検証では `CODEX_HOME` / Claude の認証保存先を read-only volume として mount し、token を image や log に焼かない。
- trade 専用 container では一般 Bash/File tool を閉じ、必要な MCP server だけを登録する。`--dangerously-skip-permissions` 系は secret 漏洩リスクがあるため既定にしない。

## 未実測の項目

Issue #4 の追記条件のうち、以下は本 PR では実測していない。

- CLI 認証 token を read-only volume mount し、container 再起動後も非対話で動くこと。
- tool timeout / LLM timeout を独立再現し、呼び出し元が no-trade で終了できること。

どちらも実資金取引前の安全床として必要だが、今回の最小 MCP server には wrapper daemon や timeout 専用 tool がない。#10 の no-trade wrapper / timeout 契約で固定する。

## 次への申し送り

- #3 ではこの実測結果を踏まえて `docs/design.md` を訂正する。
- #10 では no-trade wrapper、timeout、audit log、global lock、ProtectionReconciler 骨格を入れる。
- #5 以降の market tool は `:trading` に業務ロジック、`:mcp` に schema / parse / delegation の薄い層を維持する。
