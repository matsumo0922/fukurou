# LLM token / cost / CLI auth 計測メモ

Issue #19「計測: LLM token・コストと CLI auth 運用を検証する」の実施メモ。

このメモは実装ではなく計測結果である。product code と test code は変更していない。CLI stdout/stderr の token 情報を失わないため、計測時だけ `/tmp` の wrapper を `FUKUROU_CLAUDE_COMMAND_TEMPLATE` / `FUKUROU_CODEX_COMMAND_TEMPLATE` に指定した。prompt、model、runner の Kotlin 実装、MCP tool allowlist は変更していない。

## 0. 再計測追記: 実運用想定 model

2026-07-03 16:07 JST に、実運用想定を `Opus 4.8(high)` + `gpt-5.5(xhigh)` として再計測を試みた。初回計測の `claude-fable-5` は 2026-07-07 までの限定上位 model のため、通常 cadence の基準値ではなく、上位 model 参考値として扱う。

再計測条件:

| 項目             | 値                                                                                                      |
| -------------- | ------------------------------------------------------------------------------------------------------ |
| Claude         | `FUKUROU_CLAUDE_MODEL=opus`, `FUKUROU_CLAUDE_COMMON_ARGS="--effort high"`                              |
| Codex          | `FUKUROU_CODEX_MODEL=gpt-5.5`                                                                          |
| Codex xhigh 指定 | 計測用 wrapper `/tmp/fukurou-codex-xhigh-capture.sh` で `-c model_reasoning_effort="xhigh"` と `--json` を追加 |
| runner         | `./gradlew :trading:runOneShotLlm --no-daemon`                                                         |
| product code   | 変更なし                                                                                                   |

結果:

| 検証                               | 結果                                                                                                        |
| -------------------------------- | --------------------------------------------------------------------------------------------------------- |
| Claude Opus high direct smoke    | 失敗。Claude 429 session limit。reset は 18:00 JST                                                             |
| runner invocation                | `ebbb146d-f456-4cd9-aa1e-6a740cd3112c`                                                                    |
| runner status                    | `NO_TRADE_AUDITED`                                                                                        |
| proposer phase                   | 3,079 ms、provider `claude`、exitCode `1`                                                                   |
| runner audit                     | `NO_TRADE_EXIT`、reason `proposer_missing_decision`、cause `IllegalStateException`                          |
| Claude tokens / cost             | すべて 0。LLM 推論に入る前に quota で拒否されたため、有効実測ではない                                                                 |
| Codex gpt-5.5 xhigh direct smoke | 成功。`input_tokens=23,449`, `cached_input_tokens=4,992`, `output_tokens=132`, `reasoning_output_tokens=121` |
| Codex runner / Falsifier         | 未測定。Proposer が quota failure で閉じたため                                                                       |

補足:

- 現行 `DefaultLlmCommandRenderer` は `FUKUROU_CODEX_COMMON_ARGS` に `-c` を含めることを禁止しているため、通常 env だけでは Codex の `model_reasoning_effort="xhigh"` を runner に渡せない。今回は計測用 wrapper で再現した。
- `gpt-5.5(xhigh)` の token は direct `say ok` smoke の値であり、Falsifier 実行の値ではない。
- `Opus 4.8(high)` の有効 runner token / latency / tool call は未測定。18:00 JST 以降に quota reset を待って再試行が必要。

## 1. 環境

| 項目           | 値                                                                         |
| ------------ | ------------------------------------------------------------------------- |
| 計測日          | 2026-07-03 15:42:46 JST                                                   |
| repository   | `/Users/daichi-matsumoto/dev/App/fukurou`                                 |
| branch       | `main`                                                                    |
| DB           | repo の `docker-compose.yml` + `docker-compose.dev.yml` の PostgreSQL       |
| runner       | `./gradlew :trading:runOneShotLlm --no-daemon`                            |
| MCP jar      | `:mcp:buildFatJar` で生成した `mcp/build/libs/fukurou-mcp-all.jar`             |
| Claude CLI   | `2.1.199 (Claude Code)`                                                   |
| Codex CLI    | `codex-cli 0.142.5`                                                       |
| Claude model | 初回: `claude-fable-5`。再計測: `opus` + `--effort high` は quota failure で未測定   |
| Codex model  | runner では未到達。direct smoke では `gpt-5.5` + `model_reasoning_effort="xhigh"` |
| trading mode | `PAPER`                                                                   |

参考にした公開 docs:

- OpenAI Codex pricing: https://developers.openai.com/codex/pricing
- OpenAI Codex auth: https://developers.openai.com/codex/auth
- OpenAI Codex non-interactive mode: https://developers.openai.com/codex/noninteractive
- Claude usage / length limits: https://support.claude.com/en/articles/11647753-how-do-usage-and-length-limits-work
- Claude usage best practices: https://support.claude.com/en/articles/9797557-usage-limit-best-practices
- Claude Code with Pro / Max: https://support.claude.com/en/articles/11145838-use-claude-code-with-your-pro-or-max-plan
- Claude Max plan: https://support.claude.com/en/articles/11049741-what-is-the-max-plan

## 2. Phase 0 前提検証

| 対象                                 | 結果      | 根拠                                                                                                                                                                              |
| ---------------------------------- | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `claude --version`                 | OK      | `2.1.199 (Claude Code)`                                                                                                                                                         |
| `codex --version`                  | OK      | `codex-cli 0.142.5`                                                                                                                                                             |
| Claude `--print` / `-p`            | OK      | `claude --help` に存在。`claude -p "say ok" --output-format json` が exit 0                                                                                                          |
| Claude `--output-format json`      | OK      | help に存在。smoke は JSON result を返した                                                                                                                                               |
| Claude `--no-session-persistence`  | OK      | help に存在。runner 相当の flag セットで exit 0                                                                                                                                            |
| Claude `--mcp-config <file/json>`  | OK      | help に存在。空 `mcpServers` JSON で flag acceptance を確認                                                                                                                              |
| Claude `--strict-mcp-config`       | OK      | help に存在。runner 相当の flag セットで exit 0                                                                                                                                            |
| Claude `--allowedTools`            | OK      | help に存在。`--allowedTools "Bash"` で flag acceptance を確認                                                                                                                          |
| Claude `--permission-mode dontAsk` | OK      | help に `dontAsk` が選択肢として存在。runner 相当の flag セットで exit 0                                                                                                                          |
| Codex `exec`                       | OK      | `codex exec --help` に存在                                                                                                                                                         |
| Codex `--sandbox read-only`        | OK      | help に存在。Codex 管理 sandbox 内では `Operation not permitted` で失敗したが、sandbox 外では exit 0                                                                                               |
| Codex `-c approval_policy="never"` | OK      | help に `-c key=value` が存在。sandbox 外 smoke で `approval: never` と表示                                                                                                               |
| Claude 非対話 smoke                   | OK      | `claude -p "say ok" --output-format json` が exit 0。JSON usage 取得可                                                                                                               |
| Codex 非対話 smoke                    | 条件付き OK | `codex exec --sandbox read-only -c 'approval_policy="never"' "say ok"` は Codex 管理 sandbox 内で失敗、sandbox 外で exit 0                                                                |
| Claude auth 経路                     | 部分確認    | `CLAUDE_CONFIG_DIR` は未設定。`claude auth status` は `loggedIn=true`、`authMethod=claude.ai`、`subscriptionType=max`。portable な auth file は特定できず、OS keychain / first-party login 前提と判断 |
| Codex auth 経路                      | OK      | `~/.codex/auth.json` が存在し permission は `0600`                                                                                                                                   |
| runner 方式の Codex auth copy         | OK      | 一時 `CODEX_HOME` に `config.toml` と `auth.json` だけを置き、両方 `0600`。`HOME` を空の一時 dir にして `codex exec` が exit 0                                                                        |
| Codex config merge 隔離              | OK      | 一時 workdir の `.codex/config.toml` に壊れた model sentinel を置いた状態でも、一時 `CODEX_HOME` のみで `codex exec` が exit 0。working directory 側 `.codex/` は merge されなかった                           |

Codex CLI はこの Codex セッションの managed sandbox 内では `failed to initialize in-process app-server client: Operation not permitted` で起動できなかった。以後の Codex direct smoke は sandbox 外実行として扱った。

## 3. Phase 1 one-shot runner 実測

初回は `claude-fable-5` で 5 回試行したが、4 回目までが有効な `NO_TRADE` decision、5 回目は Claude session limit による fail-closed だった。ENTER は観測できなかったため、Falsifier / Codex runner phase は未測定。

再計測の `Opus 4.8(high)` は quota reset 前で有効 run を取得できなかった。記録できた runner 事象は上記「再計測追記」に分離した。

Claude token の `total` は `input_tokens + cache_creation_input_tokens + cache_read_input_tokens + output_tokens` として集計した。`total_cost_usd` は Claude JSON の API 換算値であり、サブスク課金額ではない。

| run | invocation                             | status              | action     | provider | input | cache create | cache read | output |   total | cost USD | proposer ms | wall span ms | tool calls | timeout / retry          |
| --- | -------------------------------------- | ------------------- | ---------- | -------- | ----: | -----------: | ---------: | -----: | ------: | -------: | ----------: | -----------: | ---------: | ------------------------ |
| 1   | `3a2260a2-ab62-4362-bc7d-4855087f06d7` | `NO_TRADE_DECISION` | `NO_TRADE` | claude   | 7,284 |       57,350 |    165,345 |  3,538 | 233,517 | 1.562085 |      74,714 |       74,724 |         11 | なし                       |
| 2   | `b3d1b180-1c5e-43a1-b5fd-c746c59c7f3b` | `NO_TRADE_DECISION` | `NO_TRADE` | claude   | 7,286 |       46,666 |    223,020 |  4,017 | 280,989 | 1.430050 |      85,550 |       85,559 |         12 | なし                       |
| 3   | `24dccfed-10ad-4a36-9367-845f251abb74` | `NO_TRADE_DECISION` | `NO_TRADE` | claude   | 7,284 |       42,628 |    178,060 |  3,964 | 231,936 | 1.301660 |      76,479 |       76,490 |         10 | なし                       |
| 4   | `ee845ea7-ecb5-49f4-adf8-740ebe5f35ed` | `NO_TRADE_DECISION` | `NO_TRADE` | claude   | 7,061 |       43,581 |    174,407 |  5,299 | 230,348 | 1.381587 |      98,187 |       98,196 |         13 | なし                       |
| 5   | `cad3be02-e8d7-488c-823a-702acaa318cf` | `NO_TRADE_AUDITED`  | 未保存        | claude   |     0 |            0 |          0 |      0 |       0 |        0 |       2,371 |        2,395 |          0 | Claude 429 session limit |

有効 4 run の平均:

| 指標                             |                 平均 |
| ------------------------------ | -----------------: |
| Claude input tokens            |              7,229 |
| Claude output tokens           |              4,205 |
| Claude total tokens (cache 含む) |            244,198 |
| Claude API 換算 cost             | 1.418846 USD / run |
| proposer duration              |          83,733 ms |
| wall span                      |          83,742 ms |
| tool calls                     |         11.5 / run |

Tool call 内訳:

| run | 主な tool call                                                                                                                                              |
| --- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `get_account_status` 1, `get_balance` 1, `get_positions` 1, `get_open_orders` 1, `get_ticker` 1, `get_candles` 1, `calc_indicator` 4, `submit_decision` 1 |
| 2   | run 1 + `get_orderbook` 1                                                                                                                                 |
| 3   | `get_account_status` 1, `get_positions` 1, `get_open_orders` 1, `get_ticker` 1, `get_candles` 1, `calc_indicator` 4, `submit_decision` 1                  |
| 4   | run 2 + `get_symbol_rules` 1                                                                                                                              |
| 5   | `NO_TRADE_EXIT` 1 (`proposer_missing_decision`, cause は Claude 429)                                                                                       |

Kline 実サイズ:

| request                                |  実サイズ |                                                   取得 request 数 | 判定                                  |
| -------------------------------------- | ----: | -------------------------------------------------------------: | ----------------------------------- |
| `1hour`, `limit=48`                    |  48 本 | 3 daily requests (`20260703:10`, `20260702:24`, `20260701:24`) | Fixed(7) で足りる                       |
| `1hour`, indicator default `limit=100` | 100 本 |                                               5 daily requests | Fixed(7) で足りる                       |
| `4hour`, `limit=30`                    |  30 本 |                                 1 yearly request (`2026:1100`) | Fixed(7) の対象外。yearly 1 request で足りる |
| `4hour`, indicator default `limit=100` | 100 本 |                                               1 yearly request | Fixed(7) の対象外。yearly 1 request で足りる |

未取得:

- MCP tool 別 latency は未測定。`command_event_log` の `TOOL_CALL_COMPLETED` payload は tool arguments だけで、開始時刻・終了時刻・duration・response size を保存していない。
- Tool response の実サイズは DB には残らない。kline count は同じ public API 条件を再現して確認した。
- Codex Falsifier token は未測定。ENTER が出ず、runner の Codex phase に到達しなかった。

## 4. Phase 2 freshness 120 秒判定

ENTER が 0 / 4 有効 run だったため、`Proposer -> Falsifier -> decision_to_place_order` の実レイテンシは未測定。

ただし、有効 run の Proposer だけで 74.7 秒から 98.2 秒を消費した。もし運用上の鮮度を「Proposer 開始から発注まで 120 秒」と解釈するなら、Falsifier と deterministic paper entry に残る時間は約 22 秒から 45 秒しかない。

一方、現行実装の fresh 判定は `FalsificationRecord.createdAt + 120s` を `approved` 判定時に見るため、Proposer duration そのものは `isFreshApprovedAt` の 120 秒 window には入っていない。`decision_to_place_order` は `intent.createdAt` から paper entry 直前までを別途 audit する設計だが、ENTER 未観測のため実測値はない。

判定:

- 「Falsifier APPROVED から runner の発注まで」の 120 秒 freshness は未測定。
- 「Proposer 開始から発注まで」を 120 秒以内に収めたい設計なら、現状の default model では構造的に厳しい可能性が高い。支配的 phase は少なくとも Proposer。

## 5. Phase 3 auth mount / 再起動耐性

### Codex

| 検証                                            | 結果                                                                                                 |
| --------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `~/.codex/auth.json` 存在                       | あり、permission `0600`                                                                               |
| runner 方式の一時 `CODEX_HOME` copy                | OK。`config.toml` + `auth.json` のみ、permission `0600` で `codex exec` が exit 0                        |
| working directory / home config 隔離            | OK。`HOME` を空にし、workdir `.codex/config.toml` に壊れた sentinel を置いても `codex exec` が exit 0              |
| read-only auth source + writable runtime home | OK。auth source `0400`、runtime home は別 dir、`auth.json` symlink で direct `codex exec` が 2 回連続 exit 0 |
| auth なし negative check                        | OK。`CODEX_HOME` から auth を外すと HTTP 401 で exit 1                                                     |
| runner で Codex auth failure -> no-trade       | 未測定。ENTER が出ず Falsifier phase に到達しなかった                                                             |

Codex の Linux container 検証は未測定。ローカル `codex` は macOS arm64 Mach-O binary で、そのまま Linux container に mount して実行できない。Linux container で再現するには Linux 用 Codex CLI を image 内に別途 install し、同じ `auth.json` を渡す必要がある。

### Claude

| 検証                               | 結果                                                                                                                               |
| -------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `CLAUDE_CONFIG_DIR`              | 未設定                                                                                                                              |
| `claude auth status`             | `loggedIn=true`, `authMethod=claude.ai`, `subscriptionType=max`                                                                  |
| portable auth file               | 未特定。`~/.claude` 配下に明確な token / credentials file は確認できず、first-party login / OS keychain 前提と判断                                     |
| container read-only auth mount   | 未測定。mount できる auth file が特定できないため                                                                                                |
| auth / quota failure fail-closed | 部分確認。5 run 目で Claude 429 session limit になり、runner は `NO_TRADE_EXIT` (`proposer_missing_decision`) を `command_event_log` に残して終了した |

## 6. Phase 4 cadence 推奨値

### 20-50 起動 / 日の見積もり

有効 4 run の平均を NO_TRADE Proposer-only run として外挿した。ENTER/Falsifier は未測定なので含めていない。

|      起動数 | daily total tokens | weekly total tokens | daily API 換算 cost | weekly API 換算 cost | daily wall time | tool calls / day |
| -------: | -----------------: | ------------------: | ----------------: | -----------------: | --------------: | ---------------: |
| 20 / day |          4,883,950 |          34,187,650 |         28.38 USD |         198.64 USD |        27.9 min |              230 |
| 50 / day |         12,209,875 |          85,469,125 |         70.94 USD |         496.60 USD |        69.8 min |              575 |

Claude / Codex のプラン枠:

- Claude は `claude auth status` 上 `subscriptionType=max`。公式 Help では Max は Pro より高い使用量で、Max 5x / 20x があり、5時間 session limit と weekly limit を Settings > Usage で確認するとされている。CLI から具体的な残量数値は取得できなかった。
- 今回は preliminary smoke も含むが、runner 有効 4 回の後に 5 回目で `You've hit your session limit · resets 6pm (Asia/Tokyo)` に到達した。
- Codex 公式 docs では Plus / Pro / Business などの plan と、Pro が Plus の 5x / 20x usage を持つこと、現在の limit は Codex usage dashboard や active CLI session の `/status` で見ることが案内されている。`codex exec` の非対話 run から具体的な週次残量は取得できなかった。

### 判定

初回計測時の `claude-fable-5` の Proposer を 20-50 起動 / 日で回す cadence は不成立寄り。理由は以下。

- NO_TRADE でも平均 84 秒 / 244k total tokens / API 換算 1.42 USD。
- 4 有効 run の直後に Claude session limit に到達し、最低 5 有効 run すら完了できなかった。
- ENTER/Falsifier を含む場合は、Codex phase 分の token と latency がさらに上乗せされるが、未測定。

### #27 への推奨値

`Opus 4.8(high)` + `gpt-5.5(xhigh)` の実運用 baseline は未測定なので、#27 の最終推奨値は quota reset 後の再計測を待つべき。暫定的に daemon を有効化するなら、初回の上位 model 参考値を安全側に倒し、20-50 起動 / 日ではなく、かなり絞った値から始めるべき。

| config                  |     推奨値 | 理由                                                          |
| ----------------------- | ------: | ----------------------------------------------------------- |
| flat 時ハートビート間隔          |    6 時間 | flat だけで 4 起動 / 日に抑える                                       |
| 保有中 LLM check 間隔        |    3 時間 | ProtectionReconciler が deterministic に保護を維持する前提で、LLM 判断は抑える |
| `maxInvocationsPerHour` |       1 | 2026-07-03 時点の現行 12/hour は subscription 枠に対して高すぎる          |
| 追加推奨: daily cap         | 4 / day | #27 の scope に入れられるなら追加すべき。hourly cap だけでは日次・週次枠を守れない        |

20-50 起動 / 日を成立させたい場合の代替案:

- Proposer model を軽量 model に変えて再計測する。
- Proposer prompt / tool policy を縮小し、毎回 1h/100 indicator を複数取らない。
- Codex Falsifier は ENTER 時のみ維持し、NO_TRADE では起動しない現行方針を守る。
- `codex exec --json` 相当の structured usage capture、Claude stdout の永続化、tool duration audit を product code 側で追加してから再計測する。
- サブスク枠ではなく API key billing で使う場合は、API pricing と上限 budget を明示した別設計に分ける。

## 7. 発見した問題

1. runner の token 計測性が低い。
   - `ShellProcessRunner` は stdout/stderr を `ProcessRunResult` に持つが、DB や file に永続化しない。
   - `DefaultLlmCommandRenderer` は Claude に `--output-format json` を付けるが、その JSON は通常 runner 実行後に捨てられる。
   - Codex は runner では `--json` を付けないため、Falsifier に到達しても token の input/output breakdown は標準設定では取りにくい。

2. `command_event_log` では tool latency と response size が取れない。
   - `TOOL_CALL_COMPLETED.payload` は tool arguments であり、tool 開始時刻、終了時刻、duration、response count を含まない。
   - `get_candles` の count や `calc_indicator` の `candle_count` は MCP response には存在するが、audit には保存されない。

3. Claude quota failure の詳細が runner audit では粗い。
   - Claude JSON では `api_error_status=429` と reset 時刻が分かる。
   - DB には `proposer_missing_decision` / `IllegalStateException` として残るため、quota/auth/permission の区別は stdout capture なしでは失われる。

4. Claude auth の container 運用は現状の login 方式では未確定。
   - Codex は `auth.json` file を使った headless copy が成立した。
   - Claude は `claude.ai` first-party login で、container に read-only mount できる auth file を特定できなかった。

5. Codex の reasoning effort を runner env だけで指定できない。
   - `DefaultLlmCommandRenderer` は `FUKUROU_CODEX_COMMON_ARGS` から `-c` を禁止している。
   - `gpt-5.5(xhigh)` を runner で計測するには、今回のような wrapper か product code 側の明示 config が必要。

## 8. 未測定項目と理由

| 未測定項目                                   | 理由                                                                         |
| --------------------------------------- | -------------------------------------------------------------------------- |
| `Opus 4.8(high)` の有効 runner run         | 2026-07-03 16:07 JST 時点で Claude 429 session limit。reset 18:00 JST 待ち       |
| 最低 5 回の有効 runner run                    | 5 回目で Claude 429 session limit に到達したため。有効 decision は 4 回                   |
| ENTER -> Falsifier -> paper entry       | 4 有効 run すべて `NO_TRADE`。5 回目は quota failure                                |
| Codex Falsifier token / cost / duration | ENTER が出ず runner の Codex phase に到達しなかった                                    |
| `decision_to_place_order` duration      | ENTER と APPROVED が未観測                                                      |
| MCP tool 別 latency                      | 現行 audit schema に duration がない                                             |
| runner 内の tool response size            | 現行 audit schema に response size がない。kline は public API 再現で補足               |
| Claude read-only auth mount container   | portable auth file が特定できない                                                 |
| Linux container の Codex CLI 実行          | ローカル Codex が macOS arm64 binary のため。別途 Linux image に Codex CLI install が必要 |
| サブスク残量の具体数値                             | CLI から取得できず、Claude / Codex とも UI の Usage 画面確認が必要                           |

## 結論（初回計測時点。追加検証後の再判定は §12 を参照）

Issue #19 の成立基準に対する自己評価は「不成立」。理由は、`claude-fable-5` Proposer の有効実測は取れたが、実運用想定の `Opus 4.8(high)` は quota failure で未測定、5 有効 run、Codex Falsifier、freshness、container Claude auth も未測定であり、現時点では 20-50 起動 / 日の cadence 成立を判断できないため。

## 9. 追加検証（2026-07-03 18:25-18:50 JST、quota reset 後）

初回計測の未測定項目を回収する追加検証。product code / test code は変更していない。

### 9.1 重要な訂正: 「Opus 再計測失敗」の真因は quota ではなく Gradle configuration cache

`gradle.properties` に `org.gradle.configuration-cache=true` があるため、`runOneShotLlm`（JavaExec）の子プロセス環境は**最初に configuration した時点の環境変数 snapshot が再利用され、以後の env 変更が無視される**。

- 実証: `FUKUROU_CLAUDE_COMMAND_TEMPLATE` を「argv を記録して即 exit 1 する」debug wrapper に差し替えても、旧 capture wrapper + 既定 model（`claude-fable-5`）で実行された。`--no-configuration-cache` を付けると差し替えが反映された。
- 帰結 1: 本メモ §0 の再計測（16:07）と、reset 後の最初の 4 run（18:27-18:36、invocation `48df6246` / `3d8d1761` / `25a7c9eb` / `2907cde9`）は、`FUKUROU_CLAUDE_MODEL=opus` を指定していたにもかかわらず**実際には `claude-fable-5` で実行されていた**。この 4 run は fable-5 の有効サンプルとして扱う（4 run とも有効な `NO_TRADE` decision。fable-5 の有効 run は初回 4 + 追加 4 = 計 8）。
- 帰結 2: 計測・運用とも、env で挙動を変える場合は `--no-configuration-cache` が必須。#27 の daemon 化で JavaExec 経由の起動を採用する場合は同じ地雷を踏む（発見した問題 §11-6）。
- 副産物: debug wrapper（即 exit 1）の run は proposer phase 433 ms で `NO_TRADE_AUDITED`（`proposer_missing_decision`）に fail-closed した。LLM 起動失敗系の no-trade 動作の追加確認になっている。
- argv 実測: `--no-configuration-cache` 付きで `-p <prompt>` → `--model opus --effort high`（user 指定）→ `--mcp-config <temp> --strict-mcp-config --allowedTools <11 tools>` → `--permission-mode dontAsk --output-format json --no-session-persistence`（enforced 最後）の順を確認。設計どおり。

### 9.2 `Opus 4.8(high)` 有効 runner 実測（3 run、すべて有効な NO_TRADE）

`--no-configuration-cache` + `FUKUROU_CLAUDE_MODEL=opus` + `--effort high`。`modelUsage` が `claude-opus-4-8` であることを capture JSON で確認済み。

| run | invocation                             | duration ms | input | cache create | cache read | output |   total | cost USD | tool calls |
| --- | -------------------------------------- | ----------: | ----: | -----------: | ---------: | -----: | ------: | -------: | ---------: |
| A   | `a688417d-2a98-4027-9cff-65c6866f68f9` |     117,655 | 7,063 |       58,580 |    200,881 |  7,573 | 274,097 |   0.9109 |         14 |
| B   | `be78347d-c08b-4d81-8a08-26023de4d2ea` |      89,201 | 7,286 |       45,957 |    212,679 |  4,962 | 270,884 |   0.7264 |         11 |
| C   | `bb321010-42c5-4c00-a221-baa6309fbbd5` |      86,084 | 7,284 |       48,789 |    178,136 |  5,225 | 239,434 |   0.7440 |         13 |

平均: duration 97.6 秒 / total 261,472 tokens / API 換算 0.794 USD / tool calls 12.7。

fable-5（平均 84 秒 / 244k / 1.42 USD）との比較: 所要時間は 1 割強遅く、token は同オーダー、API 換算 cost は約 44% 安い。

### 9.3 Codex Falsifier standalone 実測（ENTER 経路の代替計測）

runner からは ENTER が出ないため、`trade_intents` / `trade_plans` / `decisions` に現在価格と整合する ENTER intent を手動 seed（`invocation_id LIKE 'spike19-falsifier-standalone-%'` で識別可能。計測当時は DB に残置）し、runner の `DefaultLlmCommandRenderer` が生成するものと同一構成（ephemeral `CODEX_HOME` + `config.toml` + `auth.json` copy + enforced args + `buildFalsifierPrompt` と同一 prompt）で `codex exec` を直接実行した。model は `gpt-5.5` + `model_reasoning_effort="xhigh"`。

注記: §13.4 の後続 fast mode 計測で seed `86e558fc-702f-47f8-b965-5f21316fc1cd` を再利用する過程で、当該 seed の APPROVED `falsifications` 行は削除済み。以下の「永続化」は §9.3 計測時点の事実を示す記録である。

| 試行  | 構成                                                                | 結果                                                                                                                                                                                                                            |
| --- | ----------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | 既定 enforced（`--sandbox read-only` + `approval_policy="never"`）    | セッションは完走（56 秒 / input 68,294 / output 2,414 / 9 tool calls）したが、`submit_falsification` が `user cancelled MCP tool call` で自動キャンセルされ **falsifications 行は未作成**。※この試行は seed の JSON 形式不備で `get_trade_intent` も失敗しており REJECTED 判断だった |
| 2   | 既定 enforced（seed 修正後）                                             | `get_trade_intent` 成功、Falsifier は APPROVED を判断したが、**`submit_falsification` が再び自動キャンセルされ行は未作成**（57 秒 / input 66,735 / output 2,422）                                                                                            |
| 3   | enforced + `--yolo`（`CODEX_FALSIFIER_ARG_ALLOWLIST` 済みの明示 opt-in） | **APPROVED が falsifications に永続化**。111 秒 / input 186,572（cached 137,856）/ output 4,761（reasoning 3,386）/ 17 tool calls。`submit_falsification` の DB 書き込みから codex プロセス終了までの gap は **3,603 ms**                                  |

**構造的発見**: codex は `approval_policy="never"` の下で書き込み系 MCP tool の承認要求を自動拒否するため、**既定 enforced 構成のままでは Falsifier は verdict を提出できず、ENTER 経路は常に no-trade に落ちる**（fail-closed 方向なので危険はないが、#26 の受け入れ条件が実行時に成立しない）。実運用は `FUKUROU_CODEX_FALSIFIER_ARGS="--yolo"` の明示 opt-in が必須で、これは #32 設計が想定した「外部 sandbox（container）前提の opt-in」経路そのもの。`--yolo` は codex 側 sandbox の全解除なので、#27 の container 隔離が前提条件になる。より細粒度の設定（MCP tool 単位の承認免除）が codex にあるかは追加調査の余地あり。

### 9.4 freshness 120 秒判定（実測ベース）

- 現行実装の freshness gate は `falsification.createdAt + 120s` を runner の承認判定時に見る。実測では submit → codex 終了が 3.6 秒、直後の承認判定 + paper 発注は ms オーダーのため、**120 秒 window は構造的に余裕で成立**。
- 一方「Proposer 開始 → 発注」の全体レイテンシは Opus 平均 97.6 秒 + Falsifier(xhigh) 111 秒 ≒ **約 3.5 分**。falsification freshness とは別に、intent 作成時点の市況からどれだけずれてよいか（intent 鮮度）は #27/#28 の設計論点として残る。

### 9.5 Claude auth の container read-only mount（未特定だった portable auth file を特定）

- macOS の Claude Code は auth を **Keychain の generic password `Claude Code-credentials`** に格納しており、その値は Linux の `~/.claude/.credentials.json` と同一形式の JSON（`claudeAiOauth.accessToken` / `refreshToken` / `expiresAt` / `scopes` / `subscriptionType`）。`security find-generic-password -s "Claude Code-credentials" -w` で抽出できる。
- 検証: `node:20-slim` container に `@anthropic-ai/claude-code` 2.1.197 を install し、抽出した JSON を `/root/.claude/.credentials.json` に **read-only mount** → `claude -p "say ok" --output-format json` が成功（headless、Max サブスク認識）。**container 再起動後も成功**（session/cache の書き込みは home 側で、mount と分離されている）。
- negative check: auth なし（空の `CLAUDE_CONFIG_DIR`）では JSON が `is_error: true` / `Not logged in` を返す。**exit code は 0** なので、exit code 判定では検出できない — runner の DB-as-truth 判定（decision 不在 → `proposer_missing_decision` で no-trade）が正しく効く設計であることの傍証。
- 運用上の注意: `accessToken` の `expiresAt` は数時間先（実測時点で約 5.7 時間）。read-only mount では refresh 結果を書き戻せないため、長期無人運転では (a) mount 元 file の定期更新、(b) `claude setup-token` による長寿命 token、のいずれかを #27 で検証する必要がある。

### 9.6 tool call 予算の枯渇リスク（新規発見）

Proposer 実測 11-16 calls + Falsifier 実測 17 calls に対し、cross-phase の総予算 `FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT` の既定は 30。**ENTER 経路では予算枯渇が現実に起こりうる**（Falsifier が途中で拒否され fail-closed → APPROVED が取れず no-trade）。conservative-only 制約（既定 30 以下のみ許可）のため env では引き上げられない。#27 までに、ENTER 経路の実測を増やした上で既定値の見直し（product code 変更）を検討すること。

## 10. 追加検証後の実測サマリ

| 項目              | fable-5（8 有効 run） | Opus 4.8 high（3 有効 run） | Codex Falsifier gpt-5.5 xhigh（1 有効 run） |
| --------------- | ----------------: | ----------------------: | --------------------------------------: |
| duration 平均     |            約 84 秒 |                  97.6 秒 |                                   111 秒 |
| total tokens 平均 |       約 244k-280k |                    261k |                input 187k + output 4.8k |
| API 換算 cost 平均  |          1.42 USD |                0.79 USD |                                 -（サブスク） |
| tool calls 平均   |           11.5-14 |                    12.7 |                                      17 |

ENTER 経路のフルコスト見積もり（Opus Proposer + xhigh Falsifier）: 約 3.5 分 / Claude 261k + Codex 191k tokens / API 換算 0.8 USD + Codex サブスク消費。

## 11. 発見した問題（追加分）

1. **Gradle configuration cache が `runOneShotLlm` の env 変更を無視する。** `org.gradle.configuration-cache=true` のため、JavaExec の子プロセス環境が最初の snapshot で固定される。計測時は `--no-configuration-cache` 必須。#27 の daemon 起動方式の設計入力（JavaExec 経由なら同じ罠がある）。
2. **既定 enforced 構成では codex Falsifier が `submit_falsification` を提出できない**（`approval_policy="never"` が書き込み系 MCP tool 承認を自動拒否）。`FUKUROU_CODEX_FALSIFIER_ARGS="--yolo"` の明示 opt-in が実運用必須で、container 隔離（#27)が前提条件。
3. **ENTER 経路の tool call 総予算（既定 30）が Proposer + Falsifier 合算で枯渇しうる**（実測 12.7 + 17 ≒ 30）。
4. **Claude access token の期限が数時間**のため、read-only auth mount 単体では長期無人運転に不足。refresh 書き戻し or `setup-token` の検証が #27 に必要。

## 12. 再判定

追加検証により、初回「不成立」の主要因（Opus 有効実測なし、Falsifier 未測定、freshness 未測定、Claude container auth 未測定）はすべて回収した。自己評価を**「条件付き成立」**に更新する。

- 成立した項目: claude（fable-5 / Opus 4.8 high）と codex（gpt-5.5 xhigh Falsifier）の token / 時間 / tool call 実測、freshness 120 秒の構造成立、Claude / Codex 両方の read-only auth mount + 再起動耐性（Claude は Linux container で、Codex は host 等価検証で）、失敗系（quota 429 / auth なし / LLM 起動失敗 / falsification 未提出）の no-trade fail-closed。
- 条件（未解決のまま #27 に渡すもの）: (a) `FUKUROU_CODEX_FALSIFIER_ARGS="--yolo"` + container 隔離の本配線、(b) tool call 予算 30 の見直し、(c) Claude token refresh の長期運用方式、(d) runner 一気通貫の ENTER → `PAPER_ENTRY_PLACED` の観測（市況依存。Falsifier standalone で代替済み）、(e) サブスク枠の具体的残量（CLI から取得不能。Usage UI での人間確認が必要）。
- cadence 推奨: §6 の絞った初期値（heartbeat 6h / 保有中 3h / `maxInvocationsPerHour=1` / daily cap 4）を維持する。Opus の API 換算 cost は fable-5 より安いが、サブスク枠の実数が不明な以上、絞った値から開始して #28 の集計で調整するのが安全側。

## 13. 追加モデル計測（2026-07-03 19:03-19:10 JST）

ユーザー指定により、Claude `sonnet`（実体は `claude-sonnet-5`）と、Codex `gpt-5.5` の `high` / `xhigh` + fast mode を追加確認した。product code / test code は変更していない。

### 13.1 Codex fast mode の外部指定可否

公式 docs 上は外部指定可能。OpenAI Codex Speed docs では、Fast mode は GPT-5.5 / GPT-5.4 を対象に 1.5x speed、GPT-5.5 は Standard の 2.5x credit 消費で、CLI では `/fast on|off|status`、永続設定では `service_tier = "fast"` と `[features].fast_mode = true` を使うとされている。

ローカル検証:

- `codex exec --help` に専用 `--fast` flag はない。
- `codex features list` では `fast_mode` は `stable` / `true`。
- `codex debug models -c 'service_tier="fast"' -c 'features.fast_mode=true'` で `gpt-5.5` に `additional_speed_tiers=["fast"]` と `service_tiers=[{id:"priority", name:"Fast"}]` があることを確認。
- `codex exec --json -m gpt-5.5 -c 'service_tier="fast"' -c 'features.fast_mode=true' ...` は exit 0。`model_reasoning_effort="xhigh"` / `"high"` の両方で実行できた。
- ただし `codex exec --json` の `usage` には service tier / fast state の明示フィールドが出ないため、capture JSON 単体から fast 適用を直接証明することはできない。

参照:

- https://developers.openai.com/codex/speed
- https://developers.openai.com/codex/config-basic
- https://developers.openai.com/codex/cli/slash-commands

### 13.2 Claude Sonnet 5 runner 実測

`claude -p "say ok" --model sonnet --output-format json` と `--effort high` 付きの direct smoke はどちらも `claude-sonnet-5` で成功した。runner 実測は `--no-configuration-cache` + `FUKUROU_CLAUDE_MODEL=sonnet`。`FUKUROU_CLAUDE_COMMON_ARGS` は未指定。

duration は §9.2 の Opus 表と同じく capture JSON の `duration_ms` を採用した。DB の `RUNNER_PHASE_COMPLETED` に記録された proposer phase duration は 57,039 ms で、capture JSON より 1,604 ms 長い。

| invocation                             | status              | model             | duration ms | input | cache create | cache read | output |   total | cost USD | tool calls |
| -------------------------------------- | ------------------- | ----------------- | ----------: | ----: | -----------: | ---------: | -----: | ------: | -------: | ---------: |
| `b06bb867-b2e7-43f0-9157-c45e4425d28d` | `NO_TRADE_DECISION` | `claude-sonnet-5` |      55,435 | 7,140 |       63,975 |    267,411 |  3,482 | 342,008 | 0.537723 |         11 |

比較メモ:

- Opus 4.8 high 平均: 97.6 秒 / 261k tokens / 0.794 USD / 12.7 tool calls。
- Sonnet 5 はこの 1 run では Opus high より速く、API 換算 cost も低い。ただし Sonnet 側は effort 未指定、Opus 側は `--effort high` のため、これは純粋な model 差ではなく model 差と effort 差が混ざった参考比較である。
- Sonnet 5 は cache read が多く total token は増えた。1 run だけなので cadence 判断には、Sonnet 5 の `--effort high` runner 実測または Opus の effort 未指定 runner 実測を揃えた追加サンプルが必要。

### 13.3 Codex gpt-5.5 fast mode smoke

`codex exec --json` direct smoke。どちらも `service_tier="fast"` + `features.fast_mode=true` を指定。

| model / effort             |   wall |  input | cached input | output | reasoning output | 結果     |
| -------------------------- | -----: | -----: | -----------: | -----: | ---------------: | ------ |
| `gpt-5.5` / `xhigh` / fast | 13.2 秒 | 23,449 |        2,432 |    152 |              141 | exit 0 |
| `gpt-5.5` / `high` / fast  |  4.7 秒 | 23,449 |        2,432 |     73 |               62 | exit 0 |

### 13.4 Codex Falsifier standalone fast mode

runner 経由では ENTER が市況依存のため、§9.3 と同じ standalone 方式で実行した。ローカルでは `--yolo` が安全レビューで拒否されたため、`--sandbox read-only` + `approval_policy="never"` のまま測定した。このため `submit_falsification` は `user cancelled MCP tool call` で失敗し、falsifications には永続化されていない。

| model / effort             | seed                                   |   wall |   input | cached input | output | reasoning output | tool calls | verdict attempt | 永続化                                 |
| -------------------------- | -------------------------------------- | -----: | ------: | -----------: | -----: | ---------------: | ---------: | --------------- | ----------------------------------- |
| `gpt-5.5` / `xhigh` / fast | `86e558fc-702f-47f8-b965-5f21316fc1cd` | 約 84 秒 | 405,773 |      325,376 |  4,729 |            3,390 |         15 | `APPROVED`      | なし (`user cancelled MCP tool call`) |
| `gpt-5.5` / `high` / fast  | `86e558fc-702f-47f8-b965-5f21316fc1cd` |   48 秒 |  86,827 |       71,040 |  2,604 |            1,831 |          3 | `APPROVED`      | なし (`user cancelled MCP tool call`) |

別 seed `b20c7d1a-e019-4d05-b909-0484504c218e` でも high fast を試したが、seed 側の古い JSON 形式不備で `get_trade_intent` が失敗し、`REJECTED` attempt になったため比較対象から外した（wall 約 34 秒、input 115,607、cached 65,280、output 1,940、reasoning 779）。

判定:

- fast mode の外部指定は、公式 docs と CLI catalog 上は可能。非対話では `-c 'service_tier="fast"' -c 'features.fast_mode=true'` で指定できる。
- runner の現行 env だけでは `-c` が forbidden のため、fast mode / reasoning effort を Codex runner に渡すには wrapper か product code 側の明示 config が必要。
- xhigh fast は read tool を広く使い、既存 xhigh standard（§9.3 の 111 秒 / input 187k / output 4.8k / 17 calls）より wall は短かったが、input token は増えた。market state と tool 選択が違うため単純比較はできない。
- high fast はかなり短いが、tool call が 3 回に留まり、検証の深さも浅い。Falsifier 品質を維持するなら high fast を採用する前に、必須 read tool policy を prompt または tool budget 設計で固定して再測定すべき。
