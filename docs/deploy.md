# セルフホストデプロイ手順（Fukurou backend scaffold）

Fukurou の最小 Ktor backend を NAS 上で常時稼働させ、Cloudflare Tunnel + Access で公開・保護するための運用手順。

この scaffold では `ktor` + `postgres` + `cloudflared` の 3 サービスを扱う。Ktor backend、paper trading runtime、常駐 `ProtectionReconciler`、MCP stdio fat jar の image 同梱、LlmInvoker、daemon scheduler、Obsidian Writer、Reflection Runner、週次 PromptCandidates 生成まで実装済み。Knowledge note の自動適用と live 実発注は実装しない。

## 全体像

```text
main 更新
  ↓
GitHub-hosted runner が Docker image を build
  ↓
GHCR に ghcr.io/matsumo0922/fukurou:<commit-sha> を push
  ↓
NAS の self-hosted runner が固定 script を sudo 実行
  ↓
root-owned checkout が指定 SHA を検証して docker-compose.prod.yml を参照
  ↓
docker compose pull && docker compose up -d
```

権限境界は次のように分ける。

- `github-runner` は `docker` group に入れない
- `github-runner` は `/usr/local/sbin/deploy-fukurou` だけを `sudo` できる
- `/usr/local/sbin/deploy-fukurou` と `/etc/sudoers.d/fukurou-deploy` は root 所有
- GitHub 管理の deploy script source は `scripts/deploy/` に置くが、NAS root への反映は手動
- production compose は `docker-compose.prod.yml` で管理し、deploy script が指定 SHA のものだけを使う

## 本番環境の想定値

| 項目 | 値 |
| --- | --- |
| 公開ドメイン | `fukurou.matsumo.me` |
| deploy root | `/srv/fukurou` |
| root checkout | `/srv/fukurou/repo` |
| NAS `.env` | `/srv/fukurou/.env` |
| deploy script | `/usr/local/sbin/deploy-fukurou` |
| self-hosted runner name | `dxp4800plus-fukurou-prod` |
| self-hosted runner label | `fukurou-prod` |
| production image | `ghcr.io/matsumo0922/fukurou:<commit-sha>` |

## NAS 側の初期セットアップ

NAS に必要なコマンドが入っていることを確認する。

```sh
docker version
docker compose version
git --version
flock --version
sudo -V
```

deploy root を root 所有で作成する。

```sh
sudo install -d -m 0755 /srv/fukurou
```

Obsidian Writer を有効化する場合に備え、vault 用 directory を作成する。Ktor container は非 root の `appuser`（UID `10001`）で動くため、bind mount 元は UID `10001` が書き込める必要がある。

```sh
sudo install -d -m 0750 -o 10001 -g 10001 /srv/fukurou/obsidian-vault
```

LLM daemon / Obsidian Writer の有効化、Claude Code / Codex の container login、container 内 smoke test は [LLM daemon / Obsidian Writer production setup](llm-obsidian-production-setup.md) に従う。

root checkout を作成する。private repository の場合は read-only deploy key を先に登録しておく。

```sh
sudo git clone git@github.com:matsumo0922/fukurou.git /srv/fukurou/repo
sudo git -C /srv/fukurou/repo fetch origin +refs/heads/main:refs/remotes/origin/main
sudo git -C /srv/fukurou/repo checkout main
```

NAS 用 `.env` を作成する。`.env` は git 管理しない。

```sh
sudo install -m 0600 /dev/null /srv/fukurou/.env
sudo editor /srv/fukurou/.env
```

必要な値は次の通り。

```dotenv
CLOUDFLARED_TUNNEL_TOKEN=

POSTGRES_DB=fukurou
POSTGRES_USER=fukurou
POSTGRES_PASSWORD=

FUKUROU_OBSIDIAN_ENABLED=false
FUKUROU_OBSIDIAN_VAULT_PATH=/vault
FUKUROU_OBSIDIAN_WRITE_INTERVAL_SECONDS=300
FUKUROU_REFLECTION_MIN_INTERVAL_SECONDS=3600
FUKUROU_REFLECTION_QUERY_LIMIT=1000
FUKUROU_REFLECTION_CALIBRATION_LOOKBACK_DAYS=180
FUKUROU_REFLECTION_RECENT_DECISION_LIMIT=50
FUKUROU_REFLECTION_SAMPLE_WARNING_TRADE_COUNT=30
FUKUROU_REFLECTION_PROMPT_CANDIDATE_PROVIDER=CLAUDE
FUKUROU_REFLECTION_PROMPT_CANDIDATE_TIMEOUT_SECONDS=60
FUKUROU_REFLECTION_PROMPT_CANDIDATE_MAX_ATTEMPTS=2
# FUKUROU_OBSIDIAN_VAULT_PATH_HOST=/srv/fukurou/obsidian-vault
# Obsidian writer と Reflection Runner は FUKUROU_OBSIDIAN_ENABLED を共有する。

# production container では image 内の MCP fat jar を使う。
FUKUROU_MCP_JAR_PATH=/app/fukurou-mcp-all.jar

# CLI auth を配置して smoke test が通るまでは false を維持する。
FUKUROU_LLM_DAEMON_ENABLED=false
```

Cloudflare Access の `CF-Access-Client-Id` / `CF-Access-Client-Secret` は手元の検証環境で使う credential であり、NAS の `.env` には保存しない。

## GHCR login

NAS root で GHCR に login する。token は command history に残さない方法で渡す。

```sh
sudo bash -c 'read -rsp "GHCR token: " token; echo; printf "%s" "$token" | docker login ghcr.io -u matsumo0922 --password-stdin'
```

初回 image が作成された後、pull できることを確認する。

```sh
sudo docker pull ghcr.io/matsumo0922/fukurou:<commit-sha>
```

## deploy script と sudoers

repository の deploy script を root-owned script として反映する。

```sh
sudo install -m 0755 /srv/fukurou/repo/scripts/deploy/deploy-fukurou /usr/local/sbin/deploy-fukurou
```

sudoers template を反映する。

```sh
sudo install -m 0440 /srv/fukurou/repo/scripts/deploy/sudoers-fukurou /etc/sudoers.d/fukurou-deploy
sudo visudo -cf /etc/sudoers.d/fukurou-deploy
```

deploy script や sudoers template を変更した場合も、`/usr/local/sbin` と `/etc/sudoers.d` への反映は管理者が手動で行う。GitHub Actions から root-owned script を自動更新しない。

## self-hosted runner

repository 専用 runner を作成し、label は `fukurou-prod` にする。

```sh
sudo useradd --system --create-home --shell /bin/bash github-runner
sudo install -d -o github-runner -g github-runner /srv/actions-runner/fukurou
sudo -u github-runner -H bash

cd /srv/actions-runner/fukurou
mkdir -p actions-runner
cd actions-runner
./config.sh \
  --url https://github.com/matsumo0922/fukurou \
  --token <REGISTRATION_TOKEN> \
  --name dxp4800plus-fukurou-prod \
  --labels fukurou-prod \
  --unattended
exit
```

systemd service として登録する。

```sh
cd /srv/actions-runner/fukurou/actions-runner
sudo ./svc.sh install github-runner
sudo ./svc.sh start
sudo ./svc.sh status
```

`github-runner` が Docker を直接触れず、deploy script だけ sudo できることを確認する。

```sh
id github-runner
sudo -l -U github-runner
```

期待する状態。

- `github-runner` が `docker` group に所属していない
- `github-runner` が `sudo /usr/local/sbin/deploy-fukurou` だけを実行できる
- `sudo docker`、`sudo bash`、`sudo sh` などは許可されていない

## Cloudflare Tunnel / Access

Cloudflare Zero Trust で remotely-managed tunnel を作成し、NAS の `/srv/fukurou/.env` に `CLOUDFLARED_TUNNEL_TOKEN` を設定する。

Public Hostname は次のように設定する。

- Subdomain/Domain: `fukurou.matsumo.me`
- Service: `http://ktor:8080`

Cloudflare Access で Service Auth policy を作成し、手元の検証環境には Service Token を保存する。NAS `.env` には Service Token を置かない。
Access policy は `/app/*` と `/ops/*` を対象にし、runtime config draft / validate / activate / rollback を含む state-changing ops endpoints を Access なしで公開しない。

## 初回デプロイ確認

`main` push により `.github/workflows/deploy.yml` が実行される。build job が image を push し、deploy job が NAS runner 上で次を実行する。

```sh
sudo /usr/local/sbin/deploy-fukurou <commit-sha>
```

起動後、edge network 内から health check する。

```sh
sudo docker run --rm --network fukurou_edge curlimages/curl -fsS http://ktor:8080/health/live
sudo docker run --rm --network fukurou_edge curlimages/curl -fsS http://ktor:8080/health/ready
```

公開 URL は Cloudflare Access 越しに確認する。

```sh
scripts/prod-curl "/health/live" -s -o /dev/null -w "%{http_code}\n"
scripts/prod-curl "/health/ready" -s -o /dev/null -w "%{http_code}\n"
```

実際に起動している container が対象 commit SHA tag の image を使っていることを確認する。

```sh
sudo docker inspect --format '{{.Config.Image}}' fukurou-ktor
sudo docker ps --filter name=fukurou- --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
sudo git -C /srv/fukurou/repo rev-parse HEAD
```

## Rollback

rollback は過去の commit SHA tag を指定して workflow_dispatch を再実行する。指定できるのは `origin/main` から到達可能で、かつ `docker-compose.prod.yml` を含む commit に限る。

deploy script は指定 SHA が `origin/main` から到達可能か確認してから checkout するため、任意 branch や未レビュー commit を NAS に流し込まない。

## トラブルシュート

### deploy job が runner を拾わない

- GitHub repository の runner 一覧で `dxp4800plus-fukurou-prod` が online か確認する
- label に `fukurou-prod` が付いているか確認する
- NAS 上で runner service の状態を確認する

### GHCR push が 403 で失敗する

- GHCR package `matsumo0922/fukurou` の Actions access に repository が追加されているか確認する
- repository role が `Write` になっているか確認する
- `.github/workflows/deploy.yml` の build job に `packages: write` permission があるか確認する

### deploy script が SHA を拒否する

- SHA が 40 文字の commit SHA か確認する
- その SHA が `origin/main` から到達可能か確認する
- その SHA に `docker-compose.prod.yml` が含まれているか確認する
- `/srv/fukurou/repo` が fetch できるか確認する

### readiness が 503 のまま

- `/srv/fukurou/.env` の `POSTGRES_PASSWORD` が PostgreSQL volume 初期化時の値と一致しているか確認する
- `fukurou-postgres` service の healthcheck を確認する
- `DB_URL` は compose 内で `jdbc:postgresql://postgres:5432/${POSTGRES_DB}` として組み立てられる

## 補足

- `cloudflared` は `cloudflare/cloudflared:2026.6.1` に tag pin している。
- `--no-autoupdate` のため cloudflared 更新は compose file の明示更新で行う。
- production compose と deploy workflow は本番権限境界に影響するため、必ず review してから merge する。
