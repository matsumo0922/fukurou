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

`.github/workflows/deploy.yml` の `build` job（GitHub-hosted）は commit SHA 単位の concurrency group（`cancel-in-progress: true`）で並列 build 同士の重複トリガーだけをキャンセルする。`deploy` job（self-hosted）は `fukurou-production-deploy` group（`cancel-in-progress: false`）で直列化し、production deploy が同時実行されないようにする。異なる SHA の `build` job は並列実行できるため、1 件の `deploy` job が runner 割り当て待ちで滞留しても後続 push の `build` job 開始を塞がない。`deploy` job には `timeout-minutes: 15` を設定し、runner 上で開始後にハングした場合の安全網とする。

異なる SHA の `build` job が並列に走ると、古い commit の build が新しい commit の build より遅れて完了し、`deploy` job が一時的に古い commit へ後退する場合がある。この場合は次の通常 push、または対象 SHA を明示指定した `workflow_dispatch` の再実行でロールフォワードする。

runner 割り当て待ち（`queued` 状態）の滞留は `timeout-minutes` の対象外のため、`.github/workflows/deploy-queue-watchdog.yml` が 10 分間隔の `schedule` cron で別途検知する。詳細は [トラブルシュート](#deploy-queue-watchdog-が-issue-を作成した) を参照。

## 本番環境の想定値

| 項目                       | 値                                          |
|--------------------------|--------------------------------------------|
| 公開ドメイン                   | `fukurou.matsumo.me`                       |
| deploy root              | `/srv/fukurou`                             |
| root checkout            | `/srv/fukurou/repo`                        |
| NAS `.env`               | `/srv/fukurou/.env`                        |
| deploy script            | `/usr/local/sbin/deploy-fukurou`           |
| self-hosted runner name  | `dxp4800plus-fukurou-prod`                 |
| self-hosted runner label | `fukurou-prod`                             |
| production image         | `ghcr.io/matsumo0922/fukurou:<commit-sha>` |

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

NAS 用 `.env` を作成する。`.env` は git 管理せず、secret / deployment / bootstrap 値だけを保持する。runtime config は active DB config が正本である。

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

# container mount と対応する deployment path。
FUKUROU_OBSIDIAN_VAULT_PATH=/vault
# FUKUROU_OBSIDIAN_VAULT_PATH_HOST=/srv/fukurou/obsidian-vault

# production container では image 内の MCP fat jar を使う。
FUKUROU_MCP_JAR_PATH=/app/fukurou-mcp-all.jar
```

Obsidian Writer / Reflection Runner の有効化、Reflection の interval / query / PromptCandidates 設定、LLM model override、LLM daemon の有効化は WebUI `/app/config` の Runtime group で管理する。CLI auth と MCP path の smoke test が通るまでは `daemon.enabled=false` を active config として維持する。Runtime group の変更は process restart 後に適用する。

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

`scripts/deploy/deploy-fukurou` の変更（`prune_old_images` を含む）は、この `sudo install` コマンドで `/usr/local/sbin/deploy-fukurou` を上書きするまで NAS 上では有効にならない。repository 側の commit だけでは既存の root-owned script は変わらないため、`deploy-fukurou` を変更する PR を merge したら、忘れずに NAS で上記の反映コマンドを実行する。

## NAS image 保持

`deploy-fukurou` は `deploy_compose` 成功後に `prune_old_images` を実行し、`ghcr.io/matsumo0922/fukurou` repository の image を世代数で保持する。

- 既定の保持数は 10 世代。`FUKUROU_IMAGE_RETENTION_COUNT` 環境変数で上書きできる
- 保持数は distinct image ID（digest）単位でカウントする。`:main` タグと最新 `:<commit-sha>` タグは同一 digest を指すため重複カウントしない
- 現在起動中の image（`docker inspect --format '{{.Image}}' fukurou-ktor`）は保持数の順位に関わらず常に prune 対象から除外する
- prune はベストエフォートで動作する。`docker rmi` が失敗しても（image が他 container から参照されているなど）deploy 全体は失敗させない
- `docker system prune -a` は使わず、fukurou repository の image だけを対象にする。他 repository の image には影響しない
- NAS ローカルの prune は GHCR 上の image/tag に影響しない。rollback 時の `docker compose pull` は常に GHCR を正本として再取得するため、prune 後も rollback は成立する

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

## runtime config default 変更の反映

code-owned catalog default の変更は、active runtime config に同じ key が明示保存済みの場合は実効値を上書きしない。runtime config の runtime group は applyMode `NEXT_RESTART` のため、deploy 後に `/ops/runtime-config` または WebUI `/app/config` で現在の `effectiveValue` を確認し、必要な key を draft / validate / activate で active 化する。

例: `safety.minExpectedMoveToCostRatio` と runner の hourly / daily cap を active config に反映する。

```sh
draft_id="$(scripts/prod-curl \
  /ops/runtime-config/drafts \
  --json '{
    "baseVersionId": null,
    "values": {
      "safety.minExpectedMoveToCostRatio": "2.5",
      "runner.maxInvocationsPerHour": "7",
      "runner.maxInvocationsPerDay": "120"
    },
    "note": "paper trading weekly review defaults"
  }' | jq -r '.version.id')"

scripts/prod-curl "/ops/runtime-config/drafts/${draft_id}/validate" \
  --json '{"reason":"paper trading weekly review defaults"}'
scripts/prod-curl "/ops/runtime-config/drafts/${draft_id}/activate" \
  --json '{"reason":"paper trading weekly review defaults"}'
scripts/prod-curl /ops/runtime-config
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

### Deploy Queue Watchdog が issue を作成した

`.github/workflows/deploy-queue-watchdog.yml` は 10 分間隔の cron で `deploy.yml` の実行中 run を調べ、`Deploy on NAS` job が `queued` のまま 10 分以上経過していれば label `ops-alert` の GitHub Issue を作成する。`ops-alert` label は初回検知時に watchdog 自身が冪等に作成するため、事前登録は不要。

- まず self-hosted runner (`dxp4800plus-fukurou-prod`) が online か、[GitHub Status](https://www.githubstatus.com/) に障害が出ていないかを確認する
- 一時的な GitHub 側障害であれば、回復を待つか、詰まっている run を `gh run cancel` してから `workflow_dispatch` で該当 SHA を明示指定して再実行する
- 対応が終わったら issue を close する（watchdog は issue を自動 close しない）

watchdog は job 名 `Deploy on NAS` を直接参照するため、`deploy.yml` の該当 job 名を変更する場合は `deploy-queue-watchdog.yml` 側の判定式も追随させる必要がある。

## 補足

- `cloudflared` は `cloudflare/cloudflared:2026.6.1` に tag pin している。
- `--no-autoupdate` のため cloudflared 更新は compose file の明示更新で行う。
- Claude Code の version を更新する PR では、merge 前に実 CLI で `--tools "ToolSearch"` が built-in tool を隠したまま MCP tool call へ進むことを確認する。
- production compose と deploy workflow は本番権限境界に影響するため、必ず review してから merge する。
