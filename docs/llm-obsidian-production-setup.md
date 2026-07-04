# LLM daemon / Obsidian Writer production setup

Fukurou production で Claude Code / Codex CLI auth と Obsidian vault を準備し、`FUKUROU_OBSIDIAN_ENABLED` / `FUKUROU_LLM_DAEMON_ENABLED` を有効化する前に確認する手順。

Obsidian Writer と LLM daemon は独立した worker であり、同時に有効化する必要はない。Obsidian Writer は DB の内容を Markdown へ機械的に書き出すだけなので、LLM auth が無くても単独で有効化できる。LLM daemon は Claude / Codex CLI auth と MCP runtime の疎通確認後に有効化する。

## 現在の推奨順序

1. CLI auth mount と vault directory を作る。
2. Claude / Codex CLI を container 内で実際に叩いて応答を確認する。
3. Obsidian Writer だけを有効化し、vault に Markdown が生成されることを確認する。
4. LLM daemon を有効化する。最初は paper mode / 低頻度 / 低コスト model で監視する。

## NAS directory

Ktor container は UID `10001` の `appuser` で動く。auth file は read-only bind mount 元だが、file 自体は `appuser` が読める必要がある。vault は `appuser` が書ける必要がある。

```sh
sudo install -d -m 0750 -o 10001 -g 10001 /srv/fukurou/claude
sudo install -d -m 0750 -o 10001 -g 10001 /srv/fukurou/codex
sudo install -d -m 0750 -o 10001 -g 10001 /srv/fukurou/obsidian-vault
```

## Auth file placement

Codex は手元の `~/.codex/auth.json` を NAS の bind mount 元へ配置する。

```sh
ssh dxp4800plus \
  'sudo sh -c "cat > /srv/fukurou/codex/auth.json && chown 10001:10001 /srv/fukurou/codex/auth.json && chmod 0600 /srv/fukurou/codex/auth.json"' \
  < ~/.codex/auth.json
```

Claude Code は macOS Keychain の generic password `Claude Code-credentials` から Linux `~/.claude/.credentials.json` と同じ形式の JSON を抽出して配置する。

```sh
security find-generic-password -s 'Claude Code-credentials' -w \
  | ssh dxp4800plus \
      'sudo sh -c "cat > /srv/fukurou/claude/.credentials.json && chown 10001:10001 /srv/fukurou/claude/.credentials.json && chmod 0600 /srv/fukurou/claude/.credentials.json"'
```

中身を表示せず、存在・permission・owner だけ確認する。

```sh
ssh dxp4800plus \
  'sudo stat -c "%a %u:%g %s %n" /srv/fukurou/codex/auth.json /srv/fukurou/claude/.credentials.json'
```

期待値は `600 10001:10001`。

## NAS `.env`

NAS の `/srv/fukurou/.env` は `.env.example` の形を正とし、値だけ環境に合わせる。production container では MCP fat jar は image 内の path を使う。

```dotenv
FUKUROU_MCP_JAR_PATH=/app/fukurou-mcp-all.jar

FUKUROU_CLAUDE_CREDENTIALS_FILE=/srv/fukurou/claude/.credentials.json
FUKUROU_CODEX_AUTH_FILE=/srv/fukurou/codex/auth.json
FUKUROU_OBSIDIAN_VAULT_PATH_HOST=/srv/fukurou/obsidian-vault

FUKUROU_OBSIDIAN_ENABLED=false
FUKUROU_LLM_DAEMON_ENABLED=false
```

`FUKUROU_CLAUDE_MODEL` / `FUKUROU_CODEX_MODEL` は未指定なら CLI 側の既定 model になる。低コスト運用を優先する場合は、まず smoke test でその account が受け付ける model 名を確認してから指定する。

## Container smoke test

deploy 後、container 内の mount と path を確認する。

```sh
ssh dxp4800plus '
docker exec fukurou-ktor sh -lc "
  test -s /run/fukurou-claude-auth/.credentials.json && echo CLAUDE_MOUNT_OK
  test -s /run/fukurou-codex-auth/auth.json && echo CODEX_MOUNT_OK
  test -f /app/fukurou-mcp-all.jar && echo MCP_JAR_OK
  test -w /vault && echo VAULT_WRITABLE
"
'
```

`llm-home` / `llm-cache` volume が root owner で作られている場合、CLI が helper / cache を書けずに失敗することがある。必要なら一度だけ owner を直す。

```sh
ssh dxp4800plus \
  'sudo docker exec -u root fukurou-ktor sh -lc "chown -R 10001:999 /tmp/fukurou-cli-home /tmp/fukurou-cli-cache"'
```

Claude Code を低コスト model で叩く。応答は固定文字列にする。

```sh
ssh dxp4800plus \
  'docker exec fukurou-ktor sh -lc "timeout 90 claude --print --model haiku --max-budget-usd 0.02 --no-session-persistence --output-format text '\''Reply exactly: FUKUROU_CLAUDE_OK'\''"'
```

Codex は renderer と同じく、read-only mount 元の `auth.json` を writable な一時 `CODEX_HOME` へ copy して叩く。

```sh
ssh dxp4800plus '
docker exec fukurou-ktor sh -lc "
  rm -rf /tmp/fukurou-codex-auth-test
  mkdir -p /tmp/fukurou-codex-auth-test
  cp /run/fukurou-codex-auth/auth.json /tmp/fukurou-codex-auth-test/auth.json
  chmod 600 /tmp/fukurou-codex-auth-test/auth.json
  CODEX_HOME=/tmp/fukurou-codex-auth-test timeout 120 codex exec --skip-git-repo-check --ephemeral --sandbox read-only -c approval_policy=\\\"never\\\" '\''Reply exactly: FUKUROU_CODEX_OK'\'' < /dev/null
  status=\$?
  rm -rf /tmp/fukurou-codex-auth-test
  exit \$status
"
'
```

2026-07-04 時点の確認では、ChatGPT account の Codex CLI で `gpt-5-mini` は受け付けられず、未指定時は `gpt-5.5` が使われた。短い smoke test でも CLI の system prompt / session overhead により token 表示は小さくならないため、daemon 有効化前に model / cost 方針を決める。

## Enable Obsidian Writer

Obsidian Writer は LLM daemon と独立している。先にこれだけ有効化してよい。

```dotenv
FUKUROU_OBSIDIAN_ENABLED=true
FUKUROU_LLM_DAEMON_ENABLED=false
```

deploy 後に vault を確認する。

```sh
ssh dxp4800plus '
find /srv/fukurou/obsidian-vault -maxdepth 4 -type f | sort | head -50
'
```

Writer は DB を正本として、frontmatter、機械導出できる数値、空見出し骨組みだけを書く。解釈的本文や `Knowledge/` の中身は reflection runner の責務であり、ここでは生成しない。

## Enable LLM daemon

LLM daemon を有効化する前に、少なくとも次を満たすこと。

- `claude --print --model haiku ...` の smoke test が成功している。
- `codex exec ...` の smoke test が成功している。
- `FUKUROU_MCP_JAR_PATH=/app/fukurou-mcp-all.jar` が running container に反映されている。
- `FUKUROU_TRADING_MODE=PAPER` のままになっている。
- `FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR` / `FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY` が意図した上限になっている。
- Codex の model / cost 方針を確認している。

有効化する。

```dotenv
FUKUROU_LLM_DAEMON_ENABLED=true
```

その後 deploy し、revision と container log を確認する。

```sh
scripts/prod-curl "/revision" -fsS
ssh dxp4800plus 'docker logs --since 10m fukurou-ktor | tail -200'
```

## Known constraints

- Claude Code の OAuth credentials は期限切れすることがある。失効した場合は Keychain から再抽出して NAS の file を更新する。
- Codex の低コスト model 名は account / CLI の対応に依存する。未確認の model を `FUKUROU_CODEX_MODEL` に入れると Falsifier phase が fail-closed する。
- 既定の Codex Falsifier は `--sandbox read-only` と `approval_policy="never"` で起動する。ENTER 時に `submit_falsification` まで進めたい場合、この既定構成では write tool が承認されず fail-closed する可能性がある。paper entry まで自動で進めるには、外部 sandbox / container command template と `FUKUROU_CODEX_FALSIFIER_ARGS` の明示 opt-in を別途設計・検証する。
- `FUKUROU_CODEX_FALSIFIER_ARGS="--yolo"` や `--dangerously-bypass-approvals-and-sandbox` は、host の素の `codex` command template では使わない。必ず外部 sandbox で filesystem / network / secret mount を閉じてから使う。
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
