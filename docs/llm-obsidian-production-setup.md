# LLM daemon / Obsidian Writer / Reflection Runner production setup

Fukurou production で Claude Code / Codex CLI auth と Obsidian vault を準備し、runtime config の `obsidian.enabled` / `daemon.enabled` を有効化する前に確認する手順。

Obsidian Writer / Reflection Runner と LLM daemon は独立した worker であり、同時に有効化する必要はない。Obsidian Writer / Reflection Runner は DB 由来の deterministic Markdown を生成し、完了済み前週の `Knowledge/PromptCandidates/` だけ低優先の LLM CLI を使う。CLI auth がない場合でも deterministic note は生成され、PromptCandidates は fail-closed status note と backoff に留まる。LLM daemon は Claude / Codex CLI auth と MCP runtime の疎通確認後に有効化する。

## 現在の推奨順序

1. Obsidian vault directory を作る。
2. deploy 後の WebUI で Claude / Codex CLI login flow を開始する。
3. CLI login state と MCP path の smoke test を確認する。
4. Obsidian Writer / Reflection Runner だけを有効化し、vault に Markdown が生成されることを確認する。
5. LLM daemon を有効化する。最初は paper mode / 低頻度 / 低コスト model で監視する。

## NAS directory

Ktor container は UID `10001` の `appuser` で動く。vault は `appuser` が書ける必要がある。CLI login state は production compose の `llm-auth` volume に保存するため、NAS 側に Claude / Codex auth file 用 directory は作らない。

```sh
sudo install -d -m 0750 -o 10001 -g 10001 /srv/fukurou/obsidian-vault
```

## NAS `.env`

NAS の `/srv/fukurou/.env` は `.env.example` の形を正とし、値だけ環境に合わせる。production container では MCP fat jar は image 内の path を使う。CLI login state は compose-managed volume に置くため、NAS `.env` に CLI auth file path は設定しない。

```dotenv
FUKUROU_MCP_JAR_PATH=/app/fukurou-mcp-all.jar

FUKUROU_OBSIDIAN_VAULT_PATH_HOST=/srv/fukurou/obsidian-vault
```

`llm.proposer.provider` / `model` / `effort` と `llm.falsifier.provider` / `model` / `effort` は WebUI `/app/config` の Runtime group で role ごとに管理する。空の model は CLI 側の既定値を使い、`DEFAULT` effort は renderer が effort 指定を出さない。Claude は `--model` / `--effort`、Codex は `-m` と code-owned `model_reasoning_effort` TOML を使う。低コスト運用を優先する場合は、まず production container と同じ CLI で provider / model / effort の組み合わせを smoke test してから active 化する。`llm.claudeModel` / `llm.codexModel` は Reflection 用として維持する。

## WebUI CLI login

deploy 後、`llm-auth` volume が root owner で作られている場合、auth copy を作れずに失敗することがある。必要なら一度だけ owner を直す。per-run artifact はcomposeがapp UID/shared groupで作る `/run/fukurou/llm-homes` tmpfsに置く。

```sh
ssh dxp4800plus \
  'sudo docker exec -u root fukurou-ktor sh -lc "chown -R 10001:10004 /tmp/fukurou-cli-home /run/fukurou/llm-homes && chmod -R g+rwX /tmp/fukurou-cli-home /run/fukurou/llm-homes"'
```

WebUI の System 画面は `/ops/llm-auth` を読み、Claude Code / Codex の login state を表示する。CLI auth は `/health` / `/health/ready` には混ぜないため、CLI が logged_out でも Ktor / DB / reconciler readiness の意味は変わらない。login state は非 secret の credential marker file で判定するため、CLI が keychain など marker file 以外へ credential を保存する構成では System が logged_out を示す場合がある。その場合は fallback 手順と smoke test で実際の CLI auth を確認する。

WebUI の Controls 画面で `CLI Auth` を開き、Claude Code または Codex の login を reason 付きで開始する。Claude Code は表示された `authorizationUrl` を手元の browser で開き、browser flow が返した token/code を Claude Code session 専用の入力欄から 1 回だけ送信する。Codex は device auth flow のまま進め、WebUI に token/code 入力欄を出さない。WebUI / API / audit payload は access token、refresh token、API key、credential file content、送信した token/code を返さない。audit には provider、session ID、reason、status、secret を含まない detail だけを残す。

CLI login state は production compose の `llm-auth` volume が正本である。

- Claude Code は `HOME=/tmp/fukurou-cli-home` の `~/.claude` を使う。
- Codex login は auth source の `CODEX_HOME=/tmp/fukurou-cli-home/.codex` を使う。runner は auth file だけを per-run home へ copy し、config/session を永続 home に書かない。

## Container login fallback

WebUI から login flow を開始できない場合だけ、SSH越しのcontainer loginをauth source更新専用の例外として使う。通常のprovider invocationをappuser direct CLIで実行しない。CLI の refresh token が失効または revoke された場合は、WebUI または fallback で再ログインする。login state は `llm-auth` volume に保存されるため、container restart / redeploy では残る。`docker volume rm fukurou_llm-auth` のように volume を削除すると Claude / Codex の login state も消える。

Claude Code は container 内で対話ログインする。

```sh
ssh -t dxp4800plus 'docker exec -it -e HOME=/tmp/fukurou-cli-home fukurou-ktor claude auth login'
```

Claude prompt が出たら `/login` を実行し、表示された URL を手元の browser で承認して code を貼り付ける。SSH 越しの paste がうまくいかない場合だけ、SSH port forward を一時的な fallback として使う。

Codex CLI は device auth でログインする。

```sh
ssh -t dxp4800plus 'docker exec -it fukurou-ktor codex login --device-auth'
```

表示された URL / code を手元の browser で承認する。ログイン後、Codex の login state を確認する。

```sh
ssh dxp4800plus \
  'docker exec fukurou-ktor sh -lc "codex login status"'
```

## Container smoke test

deploy 後、container 内の login state と path を確認する。

```sh
ssh dxp4800plus '
docker exec fukurou-ktor sh -lc "
  test -f /app/fukurou-mcp-all.jar && echo MCP_JAR_OK
  test -w /tmp/fukurou-cli-home && echo CLI_HOME_WRITABLE
  test -w /vault && echo VAULT_WRITABLE
  codex login status
"
'
```

Claude auth sourceの実対応fileをread-onlyに確認する。provider invocationのsmokeはfixed launcherを通すproduction canaryで行う。

```sh
ssh dxp4800plus \
  'docker exec fukurou-ktor sh -lc "test -r /tmp/fukurou-cli-home/.claude/.credentials.json"'
```

Codex smoke は `llm-auth` volume 内の auth source を明示して login state を確認する。production runner の config/session は per-run home に生成される。

```sh
ssh dxp4800plus \
  'docker exec fukurou-ktor sh -lc "CODEX_HOME=/tmp/fukurou-cli-home/.codex timeout 120 codex exec --skip-git-repo-check --ephemeral --sandbox read-only -c approval_policy=\\\"never\\\" '\''Reply exactly: FUKUROU_CODEX_OK'\'' < /dev/null"'
```

2026-07-04 時点の確認では、ChatGPT account の Codex CLI で `gpt-5-mini` は受け付けられず、未指定時は `gpt-5.5` が使われた。短い smoke test でも CLI の system prompt / session overhead により token 表示は小さくならないため、daemon 有効化前に model / cost 方針を決める。

## Enable Obsidian Writer / Reflection Runner

Obsidian Writer / Reflection Runner は LLM daemon と独立している。先にこれだけ有効化してよい。`obsidian.enabled=true` では、Trade / Daily note の機械再生成と、`Knowledge/DailyReflections/`、`Knowledge/WeeklyReviews/`、`Knowledge/Calibration/`、`Knowledge/Setups/` への deterministic reflection report 生成、`Knowledge/PromptCandidates/` への週次 prompt candidate note 生成が同じ Ktor process 内で動く。

WebUI `/app/config` で draft を作成し、次の Runtime key を確認して active 化する。

- `obsidian.enabled=true`
- `reflection.minInterval=3600`
- `reflection.queryLimit=1000`
- `reflection.calibrationLookbackDays=180`
- `reflection.recentDecisionLimit=50`
- `reflection.sampleWarningTradeCount=30`
- `reflection.promptCandidateProvider=CLAUDE`
- `reflection.promptCandidateTimeout=60`
- `reflection.promptCandidateMaxAttempts=2`
- `daemon.enabled=false`

Obsidian vault の container 内 path は Deployment key `obsidian.vaultPath` で確認する。この値は compose の mount target と一致させ、WebUI からは編集しない。Runtime draft を active 化した後に Ktor process を再起動する。

deploy 後に vault を確認する。

```sh
ssh dxp4800plus '
find /srv/fukurou/obsidian-vault -maxdepth 4 -type f | sort | head -50
'
```

Writer は DB を正本として、frontmatter、機械導出できる数値、空見出し骨組みだけを書く。解釈的本文や `Knowledge/` の中身は reflection runner の責務であり、ここでは生成しない。

Reflection Runner は DB を正本として、日次・週次・confidence calibration・setup tag taxonomy の Markdown を deterministic に生成する。PromptCandidates は完了済み前週を対象に LLM CLI で生成し、JSON schema を満たす候補だけを `requires_human_approval: true` の note として保存する。Daily reflection は LLM を呼ばない。PromptCandidates は system prompt や trading config を自動変更せず、人間承認前の候補だけを残す。Daily note は A-2 Obsidian Writer の所有物であり、Reflection Runner は `Daily/` 配下を更新しない。

PromptCandidates は `generated` / `invalid_output` / `input_truncated` / `budget_deferred` / `llm_failed` / `failed_backoff` の status を frontmatter に保存する。`budget_deferred` は試行回数を増やさず、`llm_failed` は 24 時間 backoff 後に同じ週で最大 2 回まで再試行する。trading の RUNNING 予約、HARD_HALT、または LLM hour/day cap の headroom 不足がある場合は LLM を呼ばず deterministic report だけを生成する。

Reflection Runner の loop は `reflection.minInterval` と `obsidian.writeInterval` の大きい方を使う。Markdown 本文には生成時刻だけで変わる field を書かず、対象 period の DB データが変わらない tick は unchanged として扱う。日付または週の境界直前に保存された decision / trade を確定ノートへ反映するため、current period と previous period の Daily / Weekly / TagTaxonomy report を同じ tick で再生成する。

## Enable LLM daemon

LLM daemon を有効化する前に、少なくとも次を満たすこと。

- exact imageのfixed launcher canaryでClaude/Codexのprovider invocation経路が成功している。
- `codex login status` と `codex exec ...` の smoke test が成功している。
- fixed MCP launcher、manifest directory、root-only password fileがrunning containerに反映されている。
- `FUKUROU_TRADING_MODE=PAPER` のままになっている。
- WebUI `/app/config` で `runner.maxInvocationsPerHour` / `runner.maxInvocationsPerDay` が意図した上限になっている。
- Codex の model / cost 方針を確認している。

WebUI `/app/config` で `daemon.enabled=true` と `llm.launchEnabled=true` を同じ Runtime draft に設定し、validate / activate してからKtor processを1回再起動する。再起動後に両キーのeffective valueがtrueであることを確認し、その後、revisionとcontainer logを確認する。

```sh
scripts/prod-curl "/revision" -fsS
ssh dxp4800plus 'docker logs --since 10m fukurou-ktor | tail -200'
```

`FLAT_HEARTBEAT` で daemon が起動したら、`command_event_log` / `/evaluation/runs` で判断が保存されていることを確認する。

## Known constraints

- Claude / Codex CLI は access token を自動 refresh する。refresh token 自体が失効または revoke された場合だけ、WebUI または fallback で再ログインする。
- Runner は stdout / stderr に `is_error: true` を含む CLI 出力を検出した場合、`RUNNER_PHASE_COMPLETED.details.cliErrorReported = "true"` を残す。認証失敗らしい stdout / stderr を検出し、かつ CLI process が非 0 exit または `is_error: true` を含む CLI 出力を返した場合、`RUNNER_PHASE_COMPLETED.details.authFailureSuspected = "true"` と login runbook の warn log も残す。どちらも運用上の発見シグナルであり、runner は `proposer_missing_decision` の no-trade に fail closed する。`proposer_no_tool_calls` は process failure、CLI error 報告、認証失敗疑いのいずれもなく、判断未保存かつ許可済み tool call 0 件の場合だけ記録する。
- Codex の低コスト model 名は account / CLI の対応に依存する。未確認の model / effort 組み合わせを `llm.proposer.*` または `llm.falsifier.*` で active 化すると該当 phase が fail-closed する。
- 既定の Codex Falsifier は `--skip-git-repo-check`、`--sandbox read-only`、`approval_policy="never"` で起動し、`CODEX_HOME/config.toml` に `submit_falsification` だけを tool 単位で `approval_mode = "approve"` として書く。これにより shell sandbox は保ったまま、ENTER 時の Falsifier verdict 保存まで進める。
- `FUKUROU_CODEX_FALSIFIER_ARGS="--yolo"` や `--dangerously-bypass-approvals-and-sandbox` は通常運用には不要である。外部 sandbox で filesystem / network / secret mount を閉じた command template を明示 opt-in する場合の防御的 validation としてのみ残す。
- live 実発注は未実装であり、production でも `PAPER` mode を維持する。

## Cleanup

一時 sudoers を使った場合は必ず消す。

```sh
ssh dxp4800plus '
sudo rm -f /etc/sudoers.d/zz-fukurou-codex-temp
if sudo -n true 2>/dev/null; then
  echo TEMP_SUDO_STILL_ENABLED
else
  echo TEMP_SUDO_REMOVED
fi
'
```
