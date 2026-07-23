# セルフホストデプロイ手順（Fukurou backend scaffold）

起動時 bootstrap は保存済み `paper_account.initial_cash_jpy` を歴史的事実のまま `LEGACY_IMPORTED` account epoch へ登録し、active config、残高、BTC、halt state、equity peak、position/order/execution/equity history を変更しない。account/config baseline が不一致な場合は paper trading と CURRENT evaluation が fail closed するため、owner が runtime config draft を validate し、zero-open-risk 状態で activate する。baseline 変更時に履歴 row を更新せず、`CONFIG_ACTIVATED` epoch と `EPOCH_START` snapshot を監査付き transaction で作成する。

`paper.initialCashJpy` を変更する deploy では、runtime config activation 前に open position、`OPEN` / `PENDING_CANCEL` order、BTC 残高が 0 であることを確認する。activation は account reset、risk equity 同期、`EPOCH_START` snapshot、監査 event を同一 transaction で保存し、既存の halt state は解除しない。

Fukurou の最小 Ktor backend を NAS 上で常時稼働させ、Cloudflare Tunnel + Access で公開・保護するための運用手順。

この scaffold では `ktor` + `postgres` + `cloudflared` の 3 サービスを扱う。Ktor backend、paper trading runtime、常駐 `ProtectionReconciler`、MCP stdio fat jar の image 同梱、LlmInvoker、daemon scheduler、Obsidian Writer、Reflection Runner、週次 PromptCandidates 生成まで実装済み。Knowledge note の自動適用と live 実発注は実装しない。

## 全体像

```text
main 更新
  ↓
GitHub-hosted runner が Docker image を build
  ↓
GHCR に commit tag と immutable image digest を push
  ↓
NAS の self-hosted runner が固定 script を sudo 実行
  ↓
root executor が digest 固定 image を pullし、backup と migration を実行
  ↓
root-owned checkout の docker-compose.prod.yml で compose up と health 確認
```

権限境界は次のように分ける。

- `github-runner` は `docker` group に入れない
- `github-runner` は `/usr/local/sbin/deploy-fukurou` だけを `sudo` できる
- `/usr/local/sbin/deploy-fukurou` と `/etc/sudoers.d/fukurou-deploy` は root 所有
- GitHub 管理の deploy script source は `scripts/deploy/` に置くが、NAS root への反映は手動
- production compose は `docker-compose.prod.yml` で管理し、deploy script が指定 SHA のものだけを使う

`.github/workflows/deploy.yml` は GitHub-hosted の `resolve` と `build`、self-hosted の `deploy` に分かれる。`resolve` は push または `workflow_dispatch` が指定した SHA を `origin/main` 上の commit として固定する。push では production の `/revision` を読み、対象 SHA が稼働 revision の子孫であることを build 前に早期確認する。NAS executor は production deploy lock 取得後にも同じ descendant check を実行し、こちらを正本とする。`workflow_dispatch` は past-SHA 再デプロイを許可するため descendant check の対象外になる。

`build` は対象 SHA の image を GHCR へ push し、registry が返した immutable digest を output する。deploy workflow は JVM test、detekt、署名 bundle、capability catalog、schema-sensitive diff 分類、CLI acceptance を実行しない。品質 gate は pull request の `pr-quality-gate` が担い、main merge を deploy 承認として扱う。

`deploy` は `fukurou-production-deploy` concurrency group で直列化し、workflow event、image digest、deployment ID、target SHA を `/usr/local/sbin/deploy-fukurou` へ渡す。executor は digest 固定で image を pull し、root-installed DB helper marker を照合してから launch pause、backup、migration、compose cutover、health/digest 確認、launch resume を行う。

runner 割り当て待ち（`queued` 状態）の滞留は `timeout-minutes` の対象外のため、`.github/workflows/deploy-queue-watchdog.yml` が 10 分間隔の `schedule` cron で別途検知する。詳細は [トラブルシュート](#deploy-queue-watchdog-が-issue-を作成した) を参照。

## 本番環境の想定値

| 項目                       | 値                                          |
|--------------------------|--------------------------------------------|
| 公開ドメイン                   | `fukurou.matsumo.me`                       |
| deploy root              | `/srv/fukurou`                             |
| root checkout            | `/srv/fukurou/repo`                        |
| NAS `.env`               | `/srv/fukurou/.env`                        |
| deploy script            | `/usr/local/sbin/deploy-fukurou`           |
| simple deploy state      | `/srv/fukurou/deploy`                   |
| DB helper marker         | `/usr/local/share/fukurou/db-helper-manifest.sha256` |
| self-hosted runner name  | `dxp4800plus-fukurou-prod`                 |
| self-hosted runner label | `fukurou-prod`                             |
| production image         | `ghcr.io/matsumo0922/fukurou@sha256:<digest>` |

## NAS 側の初期セットアップ

NAS に必要なコマンドが入っていることを確認する。

```sh
docker version
docker compose version
git --version
flock --version
jq --version
openssl version
sudo -V
```

deploy root を root 所有で作成する。

```sh
sudo install -d -m 0755 /srv/fukurou
sudo install -d -o root -g root -m 0700 /srv/fukurou/deploy
sudo install -d -o root -g root -m 0700 /srv/fukurou/deploy-state
sudo install -d -o root -g root -m 0700 /srv/fukurou/secrets
```

Obsidian Writer を有効化する場合に備え、vault 用 directory を作成する。Ktor container は非 root の `appuser`（UID `10001`）で動くため、bind mount 元は UID `10001` が書き込める必要がある。

```sh
sudo install -d -m 0750 -o 10001 -g 10001 /srv/fukurou/obsidian-vault
```

LLM daemon / Obsidian Writer の有効化、Claude Code / Codex の container login、container 内 smoke test は [LLM daemon / Obsidian Writer production setup](llm-obsidian-production-setup.md) に従う。

`llm_launch_reservations` の execution claim migration は nullable な state / token / claimed / heartbeat の4列と、CLAIMED recovery用・non-CLAIMED active判定用の2つのpartial indexだけをadditiveに追加し、既存rowをbackfillしない。bootstrapのschema verification、旧generation recovery、startup recovery audit、periodic DB scanのいずれかが失敗したcontainerはreadyにならず、daemon / manual / direct admissionからchildを開始しない。CLAIMED bootstrap recoveryはsingle-instanceの旧container/process generation終了を確認したstop/startだけで有効になり、rolling coexistence中には実行しない。rollbackでも列とindexを削除しない。旧binaryへ戻す前にglobal launch gateをOFFにし、evaluation / reflectionをdrainして、RUNNING reservation、RUNNING `llm_runs`、direct runner、未解決claimがすべて0であることを確認する。旧binaryではone-shot claim invariantを有効と扱わない。

terminal evidence captureはcode-owned schema version 1を正本とする。cutover後はactivation boundaryが1 rowだけ存在し、exact-image canaryが完全bundleのevidence / link / coverage graph、phase所属、連続ordinal、MCP roleのwrite拒否を通過することを確認する。旧imageへrollbackする場合はLLM launch gateをOFFにしてactive runをdrainしてから切り替え、post-boundary rowを削除・再尺度化・backfillしない。14日coverageとmaintenance failureの運用観測はstage-out判定の入力であり、必要期間が経過するまでは達成済みとして扱わない。

GMO maintenance availability gate は runtime key と schema を追加しない。rollback 対象時刻が土曜日 09:00〜11:00 JST、公式 status が `OPEN` 以外、または status を確認できない場合は、先に `daemon.enabled=false` を active 化して restart し、scheduler worker が停止した状態を維持する。修正版へ戻すか、定期窓外かつ公式 status `OPEN` を確認するまで daemon を再開しない。ProtectionReconciler は別 worker のまま継続する。runtime config 操作の許可がない場合は rollback せず availability gate を維持する。

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
FUKUROU_PUBLIC_ORIGIN=https://fukurou.example.com

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

`deploy-fukurou`、`fukurou-deploy-db`、migration SQL は同じ reviewed SHA から root-owned path へ同時に配置する。DB helper marker は `fukurou-deploy-db` と `scripts/deploy/sql/**/*.sql` を repository 相対 path で `LC_ALL=C` sort し、各 file の `path + NUL + sha256(content) + NUL` を連結した manifest 全体の SHA-256 である。helper の `write-install-marker` は root-installed 実 file から marker を再計算し、atomic rename で記録する。

```sh
sudo install -d -o root -g root -m 0755 /usr/local/share/fukurou
sudo install -m 0755 /srv/fukurou/repo/scripts/deploy/deploy-fukurou /usr/local/sbin/deploy-fukurou
sudo install -m 0755 /srv/fukurou/repo/scripts/deploy/fukurou-deploy-db /usr/local/libexec/fukurou-deploy-db
sudo install -m 0644 /srv/fukurou/repo/scripts/deploy/sql/deploy-foundation-v1.sql /usr/local/share/fukurou/deploy-foundation-v1.sql
sudo install -m 0644 /srv/fukurou/repo/scripts/deploy/sql/deploy-foundation-v1-indexes.sql /usr/local/share/fukurou/deploy-foundation-v1-indexes.sql
sudo install -m 0444 /srv/fukurou/repo/scripts/deploy/sql/mcp-role.sql /usr/local/share/fukurou/mcp-role.sql
sudo /usr/local/libexec/fukurou-deploy-db write-install-marker
sudo stat -c '%U:%G:%a %n' \
  /usr/local/sbin/deploy-fukurou \
  /usr/local/libexec/fukurou-deploy-db \
  /usr/local/share/fukurou/db-helper-manifest.sha256
```

candidate image は build 時に同じアルゴリズムで計算した marker を `/usr/local/share/fukurou/db-helper-manifest.sha256` へ embed する。executor は migration の前に root-installed 実 file から毎回再計算し、install 時 marker と candidate marker の両方に一致する場合だけ DB helper を実行する。partial install、同一 SHA でない配置、配置後の変更は fail closed になる。

sudoers template を反映する。

```sh
sudo install -m 0440 /srv/fukurou/repo/scripts/deploy/sudoers-fukurou /etc/sudoers.d/fukurou-deploy
sudo visudo -cf /etc/sudoers.d/fukurou-deploy
```

`github-runner` は `/usr/local/sbin/deploy-fukurou` だけを sudo 実行できる。Docker、DB helper、backup entrypoint、shell への直接 sudo 権限は持たない。deploy script や sudoers template の root-owned path への反映は管理者が手動で行い、GitHub Actions から更新しない。

### 本 PR merge 前チェックリスト

simple executor へ切り替える前に、旧 workflow と旧 executor が動く状態で次を完了する。merge 後の workflow は旧 signed bundle contract を生成しないため、旧 executor の通常 recovery 経路による drain 確認は merge 前に行う。

1. 旧 workflow で通常 deploy を最低1回成功させ、旧 executor の startup recovery を実行する。
2. `/srv/fukurou/deploy-state` 配下の全 journal が terminal state であり、unfinished journal が0件であることを確認する。
3. DB maintenance が disabled、launch fence が enabled、OPEN の infrastructure gap が残っていないことを確認する。
4. terminal journal/rollback-state history を `/srv/fukurou/deploy-legacy-archive/` 配下へ移し、active path `/srv/fukurou/deploy-state` と `/srv/fukurou/rollback-state` を空にする。archive は監査用に保持し、simple executor の active path 判定へ含めない。
5. active path が空であることを再確認してから `/srv/fukurou/deploy/.legacy-drain-confirmed` を作成し、確認 UTC 時刻と旧 executor の最終 running revision を記録する。
6. sentinel、空の active path、正常な maintenance/fence/gap state を確認してから merge する。

```sh
sudo install -d -o root -g root -m 0700 \
  /srv/fukurou/deploy \
  /srv/fukurou/deploy-legacy-archive/deploy-state \
  /srv/fukurou/deploy-legacy-archive/rollback-state
sudo test -z "$(sudo find /srv/fukurou/deploy-state -mindepth 1 -maxdepth 1 -print -quit)"
sudo test ! -e /srv/fukurou/rollback-state || \
  sudo test -z "$(sudo find /srv/fukurou/rollback-state -mindepth 1 -maxdepth 1 -print -quit)"
running_revision="$(scripts/prod-curl /revision -fsS)"
printf 'confirmed_at=%s\nlast_revision=%s\n' \
  "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "${running_revision}" | \
  sudo tee /srv/fukurou/deploy/.legacy-drain-confirmed >/dev/null
sudo chown root:root /srv/fukurou/deploy/.legacy-drain-confirmed
sudo chmod 0600 /srv/fukurou/deploy/.legacy-drain-confirmed
```

history の移動は、全 entry が terminal であることを確認した root operator が行う。上記は active path の空確認と sentinel 作成だけを例示し、未確認 history を自動移動しない。

### 本 PR merge 後・NAS 配置時チェックリスト

1. merge commit の exact SHA を `/srv/fukurou/repo` へ fetch する。
2. 同じ SHA から新 executor、DB helper、foundation/index SQL、`mcp-role.sql` を連続して配置する。
3. `/usr/local/libexec/fukurou-deploy-db write-install-marker` を実行する。
4. `bash -n /usr/local/sbin/deploy-fukurou` と marker metadata を確認する。
5. `/srv/fukurou/deploy/.legacy-drain-confirmed` が存在し、legacy active path が空であることを確認する。
6. 次の main push または `workflow_dispatch` で simple deploy を実行する。

配置が途中で失敗した場合は marker を成功証跡として扱わない。同一 SHA の全 artifact を再配置して marker を再生成するまで deploy を実行しない。

## release / deploy safety foundation

workflow は main 上の target SHA と GHCR の immutable image digest を executor へ渡す。署名 bundle、executor contract version、capability catalog、schema-sensitive inventory、deploy intent、migration rollback mode、candidate preflight hook は使わない。automatic push の descendant check は workflow の早期確認に加え、executor が production deploy lock を取得した後、migration・checkout・compose mutation より前に実行する。`workflow_dispatch` は past-SHA application image の再デプロイを許可するため descendant check を行わない。

executor の正常経路は次の順序である。

1. legacy drain sentinel と legacy active state の空を確認する。
2. target SHA が `origin/main` 上に存在することを確認する。push では running revision の子孫であることも確認する。
3. `<image>@<digest>` を pull し、root-installed DB helper/SQL の実 file marker、install 時 marker、candidate marker を照合する。
4. durable paused-state marker を作成し、launch を disable、in-flight launch を drain、同じ gap ID の OPEN event を記録する。
5. deploy lock 内呼び出し用 `--invoked-by-deploy` で PostgreSQL backup を取得する。
6. `fukurou-deploy-db install-foundation` と `install-indexes` を実行する。
7. target SHA の compose を checkout し、digest 固定 reference で `docker compose up -d --no-build` を実行する。
8. `/health/live`、`/health/ready`、`/revision`、running container digest を確認する。
9. launch を resume し、OPEN と同じ gap ID の CLOSE event を記録してから paused-state marker を削除する。

migration、compose up、health、resume、gap close のいずれかが失敗すると automatic rollback を行わない。launch pause 後の失敗では maintenance と OPEN gap を維持し、`/srv/fukurou/deploy/paused-state.json` を残す。

paused-state marker の phase が `PAUSED_BEFORE_MIGRATION`、`MIGRATION_DONE`、`CUTOVER_STARTED` の場合、新規 deploy は拒否される。operator は marker の exact `deploymentId` を確認し、次を実行して `ACKNOWLEDGED_FOR_REDEPLOY` へ遷移させる。acknowledge は marker を削除せず、launch を resume せず、gap ID と maintenance generation を保持する。

```sh
sudo /usr/local/sbin/deploy-fukurou \
  --acknowledge-paused-state '<deployment-id>'
```

その後に新しい deploy run を起動する。新しい run は既存の gap ID と maintenance generation を引き継ぐ。同じ SHA/digest の migration が完了済みなら migration を再実行せず、異なる target なら同じ OPEN gap の中で backup と migration を実行する。成功時の CLOSE は最初の pause で作成した gap ID を参照する。

phase が `CUTOVER_HEALTHY_PENDING_CLOSE` の場合、次の executor 起動は running digest と health を再確認する。両方が一致すれば migration と compose up を繰り返さず、resume、CLOSE、marker clear を冪等に完了する。不一致なら phase を `CUTOVER_STARTED` に戻して acknowledgement を要求する。

local validation は次を実行する。

```sh
bash -n scripts/deploy/deploy-fukurou scripts/deploy/fukurou-deploy-db scripts/backup/backup-fukurou
scripts/deploy/deploy-db-selftest
scripts/deploy/deploy-postgres-selftest
./gradlew :fukurou:test --tests me.matsumo.fukurou.ReleaseDeployFoundationContractTest
```

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

`main` push により `.github/workflows/deploy.yml` が実行される。`resolve` が target SHA を main 上に固定し、`build` が image digest を出力し、deploy job が NAS runner 上で次と同じ interface を実行する。

```sh
sudo /usr/local/sbin/deploy-fukurou \
  --event push \
  --image-digest 'sha256:<64-hex>' \
  --deployment-id 'github-<run-id>-<attempt>' \
  '<commit-sha>'
```

起動後、edge network 内から health と revision を確認する。

```sh
sudo docker run --rm --network fukurou_edge curlimages/curl -fsS http://ktor:8080/health/live
sudo docker run --rm --network fukurou_edge curlimages/curl -fsS http://ktor:8080/health/ready
sudo docker run --rm --network fukurou_edge curlimages/curl -fsS http://ktor:8080/revision
```

実際に起動している container の configured reference と RepoDigest が workflow の digest に一致することを確認する。

```sh
sudo docker inspect --format '{{.Config.Image}} {{.Image}}' fukurou-ktor
sudo docker image inspect --format '{{join .RepoDigests "\n"}}' \
  "$(sudo docker inspect --format '{{.Image}}' fukurou-ktor)"
sudo git -C /srv/fukurou/repo rev-parse HEAD
```

## runtime config default 変更の反映

code-owned catalog default の変更は、active runtime config に同じ key が明示保存済みの場合は実効値を上書きしない。runtime config の runtime group は applyMode `NEXT_RESTART` のため、deploy 後に `/ops/runtime-config` または WebUI `/app/config` で現在の `effectiveValue` を確認し、必要な key を draft / validate / activate で active 化する。

`safety.maxDrawdownRatio` はruntime内の全consumerへ同じactive値を適用するが、production activationはcode default `-0.15` と数値同値な場合だけをサポートする。merge直前、candidate起動直前、candidate起動直後かつready/traffic activation前の3点で、`activeVersionId`、`activeConfigHash`、canonical値、観測時刻、candidate revisionをread-only確認し、single operatorのconfig freeze中にidentityが一致することを人間が確認する。NON_DEFAULT、UNVERIFIED、freeze不能、identity変更、readback失敗ではmerge/deployしない。root deploy executorによるnon-default rollback fenceは存在しない。

旧imageへのrollback保証は、max drawdown起因のhalt、active値とcode defaultの数値同値、rollback時drawdownがdefault以下、config identity不変をすべて満たすtraceだけに限定する。このtraceではsticky haltからentry fill 0とsweepを継続し、duplicateまたはretrospective executionを作らない。kill criterion、manual halt、market-data failure、回復済みdrawdown、non-default値、一般的な旧image rollbackへこの保証を拡張しない。

`safety.economicEventBlackouts` の code-owned candidate は Federal Reserve 公式 calendar の 2026 年残り FOMC 会合を `America/New_York` 14:00 から UTC へ変換し、前後 60 分で保持する。draft は code-owned candidate と同じ日程に固定せず、安全な window と future FOMC を持つ operator 更新を受け入れる。全 event の window 両端が導出可能であり、`FOMC` と名付けた event が `fomc-` ID を持つことを検証する。active 値が空、不正、期限切れの場合、実行対象 event を空にし、`/ops/runtime-config` は response 時点の専用 warning を返し、SafetyFloor は新規 entry だけを停止する。readiness、ProtectionReconciler、close、cancel、protection update は継続する。calendar 更新は通常の draft / validate / activate 手順を使い、公式 source と UTC 変換結果を確認する。deploy や rollback の一部として production active 値を暗黙に切り替えない。

`llm_launch_reservations` の economic-event migration はnullable `single_attempt_key TEXT`をadditiveに追加する。既存`ECONOMIC_EVENT`はtrigger keyごとに`reserved_at, invocation_id`が最小の1行だけへcanonical keyをbackfillし、重複historyは`NULL`のまま保持して削除しない。その後non-null rowだけのpartial unique indexを作成・検証する。bootstrapは同じschema transaction内で対象row数を事前計測し、backfillとindex stepをtransaction-local `lock_timeout=2s` / `statement_timeout=5s`、transaction retryなしで実行する。候補row数、canonical更新件数、経過時間、index step結果、transaction commit成否をstructured logへ記録する。timeout、migration、verificationのいずれかが失敗するとtransaction全体をrollbackし、readinessをfalse、worker開始数を0に保つ。rollbackでもcolumn、canonical backfill、indexを削除しない。旧binaryへ戻す場合は新binary上でglobal launch gateをOFFにし、RUNNING tradingとrisk-increasing pending workを0にして、修正版へ戻るまでentry経路を再開しない。

### gate-shadow receipt scan index の追加

`paper_market_event_receipts(session_id, admission_ordinal)` は既存の大規模 table へ追加するため、通常の schema transaction 内ではなく `CREATE INDEX CONCURRENTLY` で作成する。実行主体は production database owner 権限を持つ maintenance operator であり、`github-runner` に database DDL 権限を追加しない。`TradingPersistenceBootstrap` も同じ DDL を schema transaction 外で冪等に試行するが、既存 database では deploy 前に maintenance connection で完了させ、startup を index build の実行主体にしない。

既存 database の deploy 順序は次のとおり。

1. deploy 前に `paper_market_event_receipts` の行数、table/index size、開始 WAL LSN を記録する。
2. maintenance connection の autocommit で次を実行し、完了まで同じ migration を並行実行しない。

    ```sql
    CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_paper_market_receipts_session_admission_ordinal
      ON paper_market_event_receipts (session_id, admission_ordinal);
    ```

3. 次の catalog query が1行を返すことを確認する。validity flag に加え、key 列順、btree、非 partial、非 expression、key 数が一致することを成功条件とし、index 名の存在だけでは resolver を有効にしない。

    ```sql
    SELECT i.indisvalid, i.indisready, i.indislive
    FROM pg_index AS i
    JOIN pg_class AS index_relation ON index_relation.oid = i.indexrelid
    JOIN pg_class AS table_relation ON table_relation.oid = i.indrelid
    JOIN pg_namespace AS namespace ON namespace.oid = index_relation.relnamespace
    JOIN pg_am AS access_method ON access_method.oid = index_relation.relam
    JOIN pg_attribute AS session_attribute
      ON session_attribute.attrelid = table_relation.oid
     AND session_attribute.attname = 'session_id'
     AND NOT session_attribute.attisdropped
    JOIN pg_attribute AS ordinal_attribute
      ON ordinal_attribute.attrelid = table_relation.oid
     AND ordinal_attribute.attname = 'admission_ordinal'
     AND NOT ordinal_attribute.attisdropped
    WHERE namespace.nspname = current_schema()
      AND table_relation.relname = 'paper_market_event_receipts'
      AND index_relation.relname = 'idx_paper_market_receipts_session_admission_ordinal'
      AND i.indisvalid
      AND i.indisready
      AND i.indislive
      AND access_method.amname = 'btree'
      AND i.indpred IS NULL
      AND i.indexprs IS NULL
      AND i.indnkeyatts = 2
      AND i.indkey::text = session_attribute.attnum::text || ' ' || ordinal_attribute.attnum::text;
    ```

4. 行数が変わっていないこと、`pg_relation_size('idx_paper_market_receipts_session_admission_ordinal')`、終了 WAL LSN と `pg_wal_lsn_diff`、database filesystem の空き容量を記録する。index build 中の WAL 増加が運用余力を超えた場合は deploy を進めない。
5. catalog contract の確認後に新 image を deploy する。resolver は validity、方式、列定義がすべて一致するまで無効で、TTL capture だけが動作する。

fresh DB は先に新 image の bootstrap で `paper_market_event_receipts` と gate-shadow 3 table を作成し、同じ startup の schema transaction commit 後に transaction 外の concurrent provisioning stage が index を作る。readiness 後に上記 catalog queryを実行し、validityを確認してから resolver を有効にする。

`CREATE INDEX CONCURRENTLY` の失敗は同名の invalid index を残すことがある。invalid 状態では `IF NOT EXISTS` は再構築しないため、traffic と長時間 transaction を確認したうえで maintenance connection の autocommit から次を順に実行する。`DROP INDEX CONCURRENTLY` と `CREATE INDEX CONCURRENTLY` を transaction block 内で実行しない。

```sql
DROP INDEX CONCURRENTLY IF EXISTS idx_paper_market_receipts_session_admission_ordinal;
CREATE INDEX CONCURRENTLY idx_paper_market_receipts_session_admission_ordinal
  ON paper_market_event_receipts (session_id, admission_ordinal);
```

計測には少なくとも次を使い、実行前後の値を deploy 証跡へ残す。

```sql
SELECT count(*) AS receipt_rows,
       pg_size_pretty(pg_total_relation_size('paper_market_event_receipts')) AS receipt_total_size,
       pg_current_wal_lsn() AS wal_lsn
FROM paper_market_event_receipts;

SELECT pg_size_pretty(pg_relation_size('idx_paper_market_receipts_session_admission_ordinal'))
  AS receipt_scan_index_size;

SELECT pg_wal_lsn_diff('<終了LSN>', '<開始LSN>') AS wal_bytes_generated;
```

### gate-shadow resolution の照会

capture を有効にする deploy では、capture 開始以前の UTC timestamp を `FUKUROU_GATE_SHADOW_RECONCILIATION_BASELINE` に ISO-8601 で固定する。未設定時は reconciliation 件数を 0 とみなさず「baseline 未確定」として扱う。初回 observation 時刻から baseline を導出しない。観測窓と settle 猶予は `FUKUROU_GATE_SHADOW_HORIZON_SECONDS`（既定 86400 秒）と `FUKUROU_GATE_SHADOW_SETTLEMENT_GRACE_SECONDS`（既定 300 秒）、1 tick の resolver 予算は `FUKUROU_GATE_SHADOW_WALL_TIME_BUDGET_MILLIS`（既定 750 ms）で process 起動時に固定する。

resolver の実行 gate は `(session_id, admission_ordinal)` index の catalog readiness 単独であり、`RECONCILIATION_BASELINE` は resolver を gate しない（reconciliation の下界専用）。index は起動時の `CREATE INDEX CONCURRENTLY` provisioning が完了した時点で活性化するため、deploy 完了で resolver が自動的に走り出す。resolver は shadow テーブルのみを書き ledger を変更しないため、baseline 未設定でも resolution の正しさは因果境界（`socket_observed_at >= window_start_time`）で保たれる。1 tick の resolver 遅延は wall-time 予算に加え、resolver 専用 pool の JDBC `socketTimeout`（秒粒度・最低 1 秒）と `connectionTimeout`（`max(250 ms, budget)`）で driver level に有界化する。したがって sub-second の budget でも実効の per-tick 遅延上限は socketTimeout + connectionTimeout ≈ 1.5 秒程度であり、宣言した budget 値そのものには一致しない（PgJDBC の socketTimeout が秒粒度のため）。

```dotenv
FUKUROU_GATE_SHADOW_RECONCILIATION_BASELINE=2026-07-22T00:00:00Z
```

observation と resolution は次の SQL で照会する。`CROSSED` は因果境界以降の窓内 event が注文価格境界を跨いだという観測であり、paper 約定を意味しない。`UNKNOWN` は crossing を確認できなかった、またはデータ品質が劣化した状態であり、非約定を意味しない。

```sql
SELECT o.order_id,
       o.symbol,
       o.order_type,
       o.window_start_time,
       r.outcome,
       r.crossing_event_sequence,
       r.crossing_price_jpy,
       r.distance_jpy,
       r.data_quality,
       r.resolved_at
FROM gate_shadow_observations AS o
LEFT JOIN gate_shadow_resolutions AS r ON r.observation_id = o.id
ORDER BY o.observed_at DESC
LIMIT 200;
```

capture の取りこぼしは TTL 失効 order の正本と `order_id` で reconciliation する。`<baseline_epoch_millis>` には上記の immutable baseline と同じ時刻を渡す。

```sql
SELECT count(*) AS missing_gate_shadow_observations
FROM orders AS o
LEFT JOIN gate_shadow_observations AS observation ON observation.order_id = o.id
WHERE o.cancel_reason = 'resting_entry_order_ttl_expired'
  AND o.side = 'BUY'
  AND o.order_type IN ('LIMIT', 'STOP')
  AND o.canceled_at >= <baseline_epoch_millis>
  AND observation.order_id IS NULL;
```

resolver は `window_start_time + horizon + settlementGrace` 後に走査する。settlementGrace を超えて commit された窓内 crossing は取りこぼし得るため、この場合も既存 `UNKNOWN` を非約定へ読み替えない。

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

## MCP DB role

`fukurou_mcp` role は least-privilege で、MCP subprocess は既存 app env の `DB_PASSWORD` をそのまま使って接続する（`OneShotLlmRunner` が literal env として MCP subprocess の config にだけ埋め込み、CLI 本体には渡さない。`docs/mcp-runtime.md` 参照）。role の `rolsuper`、`rolcreatedb`、`rolcreaterole`、`rolreplication`、`rolbypassrls` はすべて false、membership と object ownership は 0 であることを確認する。MCP の evaluation scope は `mcp_current_evaluation_scope` と `mcp_evaluation_epochs` view から account epoch、3つのbaseline、epoch kind、作成時刻だけを読み、secretを含み得る `runtime_config_versions` / `runtime_config_values` や `paper_account_epochs` への直接SELECTは許可しない。`llm_launch_reservations`、`equity_snapshots` と ledger の UPDATE/DELETE/TRUNCATE も拒否される。必要 call の permission failure は role SQL と inventory を修正して disposable test からやり直す。

**移行時の注意**: `fukurou_mcp` role のパスワードが `POSTGRES_PASSWORD`（＝ `DB_PASSWORD`）と異なる値で provision 済みの場合、この変更を deploy すると MCP subprocess の DB 接続が認証失敗する。deploy 前に `ALTER ROLE fukurou_mcp WITH PASSWORD '<POSTGRES_PASSWORD と同じ値>';` を実行し、role のパスワードを揃えておく。

merge 前の自動証跡は `McpDatabaseRoleIntegrationTest` の role/effective privilege/required-call matrix を含む。

## PostgreSQL backup / restore

production backup は、同一 NAS の `/srv/fukurou/backups/postgres` に置く暗号化 restic repository へ `pg_dump -Fc -Z0` をstreamするroot-only jobである。日次timerはbackupを毎暦日試行するが、成功を保証しない。integrity-checked tagを持つ固定production groupのnewest 14 daily generationsを保持する。週次restore drillはstatusに記録したexact snapshotをisolated PostgreSQL 16へrestoreし、schema、constraint、critical table、read-only data invariant、owned resource cleanupの実測証跡を更新する。

この運用はPITR/WAL archive、off-site copy、NAS-loss protection、role/ACL recovery、保証RPO/RTO、自動production restoreを提供しない。backup automationには自動alertがないため、operatorはsystemd、root-only status、`GET /ops/monitoring`を能動確認する。deploy rollbackはdatabaseをrestoreしない。

この節のNAS root rolloutは`HANDOFF`である。repositoryへmergeしただけではscheduled jobは動かず、operatorが以下のsecret/repository作成、初回実測、timer enableを完了する。

### Root prerequisites と repository 初期化

NASでroot operatorが次を確認する。

- `bash`、`docker`、`restic`、`jq`、`openssl`、`systemctl`、`journalctl`、`systemd-analyze`がpersistent pathから利用できる。
- GNU coreutilsの`timeout` / `stat` / `df` / `sync` / `sha256sum` / `date` / `install` / `chown` / `chmod` / `mktemp` / `mv` / `rm` / `wc` / `tr` / `sleep`、util-linuxの`flock` / `setsid`、および`awk` / `sed` / `grep`が利用できる。custom archiveのdumpとlist validationは、hostのPostgreSQL clientではなくcaptured production PostgreSQL 16 containerの`pg_dump` / `pg_restore`を使う。
- `/srv/fukurou/backups/postgres`を置くfilesystemに、production databaseの実測sizeに運用reserveを加えたfree spaceがある。
- restic passwordはroot-owned regular file `/srv/fukurou/secrets/restic-password`、mode 0400であり、symlinkではない。
- passwordのrecovery copyはNASと同時に失われない別管理のsecret managerまたは媒体へ保管する。repositoryだけを残してpasswordを失うとrestoreできない。
- repositoryはformat version 2で初期化し、backupではrestic compressionを有効にする。

installerはbackup/repository/status/secret directoryをroot:root 0700、`/srv/fukurou/monitoring-public`をroot:root 0755で作成する。password作成、recovery copy保管、restic repository初期化、statusの成功証跡作成、timer enableはoperatorの責務であり、installerは代行しない。secretとrepositoryはreviewed artifact install後に初期化する。

### Reviewed artifact の install

review済みexact revisionのcheckoutでinstallerを実行する。installerはfixed entrypoint、schema/profile、monitoring publisher、service/timer unit、root-only directory、public projection directoryだけを配置し、password作成、repository初期化、status成功証跡の作成、timer enableを行わない。install時にtimerが既にenabledなら停止し、operatorが明示的にdisableしてからやり直す。

```sh
sudo ./scripts/backup/install-fukurou-backup install
sudo ./scripts/backup/install-fukurou-backup verify-installation
systemctl is-enabled fukurou-postgres-backup.timer || true
systemctl is-enabled fukurou-postgres-restore-drill.timer || true
```

installerがdirectoryを作成した後、root sessionでpasswordとrepositoryを初期化する。secret値をterminal、shell history、journal、PRへ出さない。

```sh
sudo sh -c 'umask 077; openssl rand -base64 48 > /srv/fukurou/secrets/restic-password'
sudo chown root:root /srv/fukurou/secrets/restic-password
sudo chmod 0400 /srv/fukurou/secrets/restic-password
sudo env RESTIC_PASSWORD_FILE=/srv/fukurou/secrets/restic-password \
  restic -r /srv/fukurou/backups/postgres init --repository-version 2
sudo env RESTIC_PASSWORD_FILE=/srv/fukurou/secrets/restic-password \
  restic -r /srv/fukurou/backups/postgres cat config | jq -e '.version == 2'
```

password生成後、別管理recovery copyから値を復元できることを確認する。password自体やhashを運用証跡へ貼らない。production database sizeとbackup filesystemのavailable bytesを測り、reserve込みのcapacity floorを満たすことも確認する。

```sh
sudo docker exec fukurou-postgres sh -ceu \
  'psql --no-psqlrc --tuples-only --no-align --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --command "SELECT pg_database_size(current_database());"'
df -B1 --output=avail /srv/fukurou/backups/postgres
```

`POSTGRES_USER`と`POSTGRES_DB`はcontainer内だけで参照し、database credentialを引数やhost environmentへ渡さない。

install後のentrypointは`/usr/local/libexec/fukurou/{backup-common,backup-fukurou,restore-fukurou,publish-backup-monitoring}`、profileとpublic projection schemaは`/usr/local/share/fukurou/`、unitは`/etc/systemd/system/`にある。entrypointはroot:root 0555、profileとunitはroot:root 0444、backup/status/secret directoryはroot:root 0700である。public projection directoryはroot:root 0755、`backup-restore.json`はroot:root 0444であり、publisherだけがroot-only statusからredacted fieldを再構成してatomic renameする。`/usr/local/share/fukurou/backup-installation-v1.json`はroot:root 0400で、installed artifact全体のaggregate SHA-256とinstall UTC時刻を保持する。`verify-installation`と`verify-rollout`はmarkerのowner/mode/hashを再検証する。installerはshared backup lockをnon-blockingで取得し、backup/restore serviceとtimerがすべてinactiveである場合だけartifactを置換する。unitにsecretは埋め込まず、`FUKUROU_BACKUP_SHARE_DIRECTORY=/usr/local/share/fukurou`だけを固定する。`github-runner`のsudo authorityは`/usr/local/sbin/deploy-fukurou`だけであり、backup/restore権限を追加しない。

### 初回 backup / restore gate

timerを有効にする前に、production deployが動いていない時間帯でmanual backupと、そのbackupが記録したexact snapshotのrestore drillを順に実行する。

```sh
sudo systemctl start fukurou-postgres-backup.service
sudo systemctl status --no-pager fukurou-postgres-backup.service
sudo jq . /srv/fukurou/monitoring/backup-status.json
sudo jq . /srv/fukurou/monitoring-public/backup-restore.json

sudo systemctl start fukurou-postgres-restore-drill.service
sudo systemctl status --no-pager fukurou-postgres-restore-drill.service
sudo jq . /srv/fukurou/monitoring/backup-status.json
sudo jq . /srv/fukurou/monitoring-public/backup-restore.json

sudo ./scripts/backup/install-fukurou-backup verify-rollout
scripts/prod-curl "/ops/monitoring"
```

systemdの`ExecStartPre`はservice本体より先にcurrent invocation / boot identityを持つ`RUNNING` projectionを公開し、`ExecStopPost`は同じidentityだけをterminalへ更新する。terminal publication前にprocessが停止した場合は既存`RUNNING`が残り、Ktorは固定staleness thresholdを超えた時点でbackup / restore componentを`UNKNOWN`として返す。projectionからroot-only snapshot identity、repository path、raw error、credentialを推定しない。

`backupRestore.state`が`UNKNOWN`の場合、`backup`と`restore`は`null`であり、古い`lastSuccessAt`やservice terminalを現在の証拠として利用しない。`projectionPublishedAt`はstalenessの観測時刻として残るため、consumerは必ずcomponent stateとreasonを先に評価する。

最新の`backup.lastAttempt`が`SUCCESS`かつ`retentionSucceeded=true`、最新の`restore.lastAttempt`が`SUCCESS`であり、各attemptのsnapshot IDが対応する`lastSuccess`と一致することを確認する。backupとrestoreの`lastSuccess.snapshotId`も同じであり、そのexact IDが現在開いているrepositoryの固定host/pathと`fukurou-postgres,integrity-checked` AND tagsで一意に存在する必要がある。両attemptにはsystemdが付与した相異なる`serviceInvocationId`と現在のkernel boot IDに一致する`serviceBootId`が必要である。さらにcurrent bootの各unit journalにある最新のstable resultが`SUCCESS`であり、その`_SYSTEMD_INVOCATION_ID`がstatusと一致しなければならない。これによりstatus publication failure後に残る旧successをrejectし、garbage collectionされるoneshot unitの`ExecMain*` runtime stateへ依存しない。両attempt時刻、statusの`updatedAt`とfile mtimeはinstall markerより後で、verification時点から24時間以内でなければならない。status publication失敗後の古いsuccess evidence、artifact reinstall前のdrill、再起動前のboot evidence、systemd外で直接実行したattemptを通さない。NAS再起動またはartifact reinstall後はbackupとrestore drillをsystemctlで再実行してからrollout verificationを行う。古い`lastSuccess`が残っていても、最新retentionまたはrestore cleanupが失敗していればgateは失敗する。status directory/fileはroot:root 0700/0600である。bounded Docker inventoryで`me.matsumo.fukurou.restore.attempt` labelを持つcontainer、network、volumeがすべて0件であることも確認する。Docker global pruneは行わない。

初回backupの成功によってcustom dumpが固定60秒bound内に完了したことを確認し、`durationSeconds`はdump、repository write、full-stream integrity readを含むattempt全体の実測時間として記録する。60秒はbackup性能の目標ではなく、backup開始後にdeployが始まるraceでも`pg_dump`のACCESS SHARE lockがDDL/rollbackを長時間止めないためのhard safety capであり、environmentから延長できない。rollout時のdatabase bytesを運用baselineとして記録し、database sizeがbaselineから25%以上増えた場合、または`DUMP_FAILED` / `WATCHDOG_BACKEND_NOT_OBSERVED` / `WATCHDOG_TERMINATION_FAILED`が1回でも発生した場合はtimerをdisableしてdump所要時間とdeploy coordinationを再評価する。60秒へ近づいた状態をenvironment overrideで延命せず、必要なcoordination方式を別changeで設計する。capacity floorを満たさない場合、last-known-good backup/restoreのいずれかがない場合、cleanup failureがある場合もtimerをdisabledのままにする。

gateを満たした後だけtimerを有効にする。

```sh
sudo systemctl enable --now fukurou-postgres-backup.timer
sudo systemctl enable --now fukurou-postgres-restore-drill.timer
systemctl list-timers 'fukurou-postgres-*'
```

停止時はsnapshot、repository、password、statusを削除せずtimerだけを無効にする。

```sh
sudo systemctl disable --now fukurou-postgres-backup.timer
sudo systemctl disable --now fukurou-postgres-restore-drill.timer
```

### 監視とfailure triage

自動alertがないため、operatorは少なくとも日次attemptと週次drillの後にunit、journal、root-only status、`GET /ops/monitoring`を確認する。statusの`lastAttempt.resultCode`はstable codeだけを公開し、child stderrやsecretを含めない。失敗attemptがlast-known-good evidenceを消していないことと、snapshot age・restore durationを保証RPO/RTOとして読まないことを確認する。

```sh
systemctl --failed 'fukurou-postgres-*'
journalctl -u fukurou-postgres-backup.service --since '2 days ago'
journalctl -u fukurou-postgres-restore-drill.service --since '8 days ago'
sudo jq '{updatedAt, backup, restore}' /srv/fukurou/monitoring/backup-status.json
scripts/prod-curl "/ops/monitoring"
```

`backupRestore` componentが`UNKNOWN / BACKUP_PROJECTION_NOT_ACTIVATED`の場合、application imageは固定のempty host directoryをread-only mountして稼働する。root operatorがreview済みinstallerを実行し、manual backupまたはrestore drillをsystemd経由で開始するとprojectionが有効になる。root-only statusをapplication containerへmountして回避しない。

projectionだけをrollbackする場合は両timerと実行中serviceを停止し、`/srv/fukurou/monitoring-public/backup-restore.json`だけをpublic directory外のroot-onlyな`/srv/fukurou/monitoring/backup-restore.projection-quarantine.json`へ移す。root-only `/srv/fukurou/monitoring/backup-status.json`、repository、snapshotを削除または編集しない。endpointが`UNKNOWN / BACKUP_PROJECTION_NOT_ACTIVATED`を返すことを確認し、review済みpublisherへ戻した後にmanual backup / restore gateを再実行する。

- `BACKUP_BUSY` / `DEPLOY_IN_PROGRESS`: 競合jobまたはdeploy終了後にmanual再実行する。start-time probe後に始まるdeploy raceまで相互排他とはみなさない。
- `CAPACITY_FLOOR_NOT_MET`: DB sizeの測定失敗を含む。PostgreSQL connectivityとDB size、free spaceを再測定し、原因を解消するまで再実行しない。
- `WATCHDOG_TERMINATION_FAILED`: 対象backendのPID/application identityを確認できていない。timerを止め、production lock影響を調査する。
- `INTEGRITY_CHECK_FAILED` / `SNAPSHOT_IDENTITY_FAILED`: retention/pruneを行わずrepositoryとattempt-tagged candidateをroot-onlyで調査する。
- `RETENTION_FAILED`: integrity-checked snapshot evidenceは残るがhousekeepingは失敗している。repositoryを確認してmanual retentionを判断する。
- `BACKUP_SIGNALLED`: backup serviceがsignalで中断された。`lastSuccess`は維持されるため、候補snapshotとjournalを確認してから再実行する。
- `RESTORE_CLEANUP_FAILED`: last verified restoreは更新されない。前回の強制終了で残ったものを含め、`me.matsumo.fukurou.restore.attempt` labelを持つresourceだけを次の順で確認する。

```sh
restore_label='me.matsumo.fukurou.restore.attempt'
sudo docker ps -a --filter "label=${restore_label}" --format 'container {{.ID}} {{.Names}}'
sudo docker network ls --filter "label=${restore_label}" --format 'network {{.ID}} {{.Name}}'
sudo docker volume ls --filter "label=${restore_label}" --format 'volume {{.Name}}'

sudo docker ps -aq --filter "label=${restore_label}" | xargs -r sudo docker rm -f --
sudo docker network ls -q --filter "label=${restore_label}" | xargs -r sudo docker network rm --
sudo docker volume ls -q --filter "label=${restore_label}" | xargs -r sudo docker volume rm --
```

container、network、volumeの順を変えず、各一覧が0件になったことを再確認してからdrillを再実行する。`docker system prune`、`docker volume prune`などのglobal pruneは使わない。restore volumeはrestic repositoryと異なり暗号化されておらず、production DBから復元したcopyを保持する。残留volumeは単なるhousekeeping failureではなくdata-at-rest incidentとして扱い、NASへのアクセスを制限し、削除完了と影響時間を記録する。削除できない場合はtimerをdisabledのままにしてincidentを解消するまでdrillを再開しない。
- その他の`RESTORE_*`: exact integrity-checked snapshotの存在、`/postgres.dump`のcustom archive、profile/invariantを順に確認する。last verified restoreは更新されない。
- `INVALID_STATUS` / `STATUS_PUBLICATION_FAILED`: automationを止め、completeな旧status、repository evidence、filesystemを調べる。

### Repository/status repair

statusがmalformedまたはunsupportedな場合はtimerを無効にし、root-only directory内でstatusをquarantineする。repositoryのsnapshot、fixed tag/host/path、last integrity evidenceを確認する。status fileが存在しない状態では次のmanual backupがschema v1を初期化するため、そのbackupとrestore gateをやり直す。破損statusを手編集でsuccessへ変えない。

interrupted attempt-tagged candidateは自動削除しない。対象snapshotをexact IDでfull-stream検証し、必要性を確認してからmanual `restic forget`を`--prune`なしで行う。その後に`restic check`を完了し、orphan packがないことを確認した場合だけchecked pruneを別操作で行う。repository integrityが不確実な間は`forget --prune`や`prune`を行わない。
statusの`interruptedCandidateCount`が`null`の場合はrepository照合自体が失敗した未知状態であり、0件として扱わない。
既存のuntagged candidateはretention対象外なので、rolloutのために削除する必要はない。manual forgetを検討する場合だけ、次のread-only手順でexact snapshotのfull streamとarchive listの両方を確認する。`SNAPSHOT_ID`は対象のexact IDに置き換える。

```sh
sudo env SNAPSHOT_ID=aaaaaaaa bash <<'ROOT'
set -euo pipefail

readonly repository=/srv/fukurou/backups/postgres
readonly password_file=/srv/fukurou/secrets/restic-password
production_container_id="$(docker inspect --format '{{.Id}}' fukurou-postgres)"

set +e
RESTIC_PASSWORD_FILE="${password_file}" restic --no-cache -r "${repository}" \
  dump "${SNAPSHOT_ID}" /postgres.dump 2>/dev/null | {
    set +e
    docker exec -i "${production_container_id}" pg_restore --list >/dev/null 2>&1
    reader_status="$?"
    cat >/dev/null
    drain_status="$?"
    test "${reader_status}" = 0 && test "${drain_status}" = 0
  }
statuses=("${PIPESTATUS[@]}")
set -e
test "${statuses[0]}" = 0 && test "${statuses[1]}" = 0
printf 'FULL_STREAM_ARCHIVE_OK snapshot=%s\n' "${SNAPSHOT_ID}"
ROOT
```

この手順は`forget`や`prune`を実行しない。

```sh
sudo env RESTIC_PASSWORD_FILE=/srv/fukurou/secrets/restic-password \
  restic -r /srv/fukurou/backups/postgres check
sudo env RESTIC_PASSWORD_FILE=/srv/fukurou/secrets/restic-password \
  restic -r /srv/fukurou/backups/postgres snapshots \
  --tag fukurou-postgres,integrity-checked
sudo env RESTIC_PASSWORD_FILE=/srv/fukurou/secrets/restic-password \
  restic -r /srv/fukurou/backups/postgres prune --dry-run
```

tagのAND predicateはcomma-separatedの単一`--tag`を使う。複数の`--tag`はOR semanticsになるためretention対象の確認に使わない。

`prune --dry-run`でorphan packと削除候補を確認し、full-stream evidence、repository check、対象snapshotの保持を再確認した後だけ同じcommandから`--dry-run`を外す。repository metadataのrepairが必要な場合はautomationを再開せず、password recovery copyとrepositoryの別copyを確保し、使用中restic versionの`repair index` / `repair snapshots`手順を個別にreviewしてから実行する。repair後は`restic check`と初回backup/restore gateをやり直す。

### Production database replacement boundary

このrepositoryのcommandはproduction databaseを置換しない。corruptionまたはdata lossが疑われる場合はrisk-increasing executionを停止し、exact snapshotをisolated environmentへrestoreして内容と証跡を確認する。production replacementは別途明示承認を必要とする。

replacementを承認した場合もowner/ACLをarchiveから再生しない。application起動前にcode-owned `scripts/deploy/sql/deploy-foundation-v1.sql`、index foundation、`scripts/deploy/sql/mcp-role.sql`を適用し、application role、PUBLIC revoke、MCP role/effective privilegeをbootstrap手順どおり検証する。role/ACL bootstrapが確認できないdatabaseをproductionとして起動しない。

## Rollback

application rollback は、過去の known-good commit SHA を `workflow_dispatch` の `image_sha` に指定して再デプロイする。対象 SHA は `origin/main` から到達可能である必要がある。manual dispatch は current revision に対する descendant check を行わず、forward deploy と同じ digest 固定 build/deploy 経路を使う。

past-SHA 再デプロイは application image だけを切り替え、database を復元しない。後続 revision が非互換な schema migration を適用済みの場合、旧 application は現在の schema と互換でない可能性がある。この場合、past-SHA 再デプロイだけでは復旧にならない。

### schema migration 事故からの復旧

1. risk-increasing execution と新規 LLM launch を停止し、paused-state marker、maintenance generation、gap ID、running image digest、直前 backup の status を保存する。
2. database が current application と互換で、修正版 migration を追加できる場合は forward fix を作成し、通常の main merge/deploy で適用する。既存 migration history を書き換えず、破壊的 backfill を行わない。
3. database を migration 前へ戻す必要がある場合は deploy pipeline を使わない。`PostgreSQL backup / restore` の exact integrity-checked snapshot を isolated PostgreSQL 16 へ restore して内容を検証し、`Production database replacement boundary` に従って owner の明示承認後に手動 replacement を行う。
4. manual restore 後は code-owned foundation/index/role SQL、application role、MCP role、runtime config、paper account epoch、order/execution/position history を検証し、health と paper truth の整合を確認してから launch を再開する。

restore によって account epoch や paper baseline を暗黙に切り替えず、観測不能期間を strategy outcome に混ぜない。復旧中の gap は元の gap ID のまま CLOSE まで保持する。

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
- 一時的な GitHub 側障害であれば、回復を待つか、詰まっている run を `gh run cancel` する。再実行時は現在の `origin/main` HEAD を `workflow_dispatch` の `image_sha` へ指定する。詰まった run の古い SHA をそのまま再利用しない。古い SHA を指定する場合は、database がその application image と互換であることを operator が確認し、past-SHA 再デプロイが database を復元しない前提で実行する
- 対応が終わったら issue を close する（watchdog は issue を自動 close しない）

watchdog は job 名 `Deploy on NAS` を直接参照するため、`deploy.yml` の該当 job 名を変更する場合は `deploy-queue-watchdog.yml` 側の判定式も追随させる必要がある。

## 補足

- `cloudflared` は `cloudflare/cloudflared:2026.6.1` に tag pin している。
- `--no-autoupdate` のため cloudflared 更新は compose file の明示更新で行う。
- Claude Code の version を更新する PR では、merge 前に実 CLI で `--tools "ToolSearch"` が built-in tool を隠したまま MCP tool call へ進むことを確認する。
- production compose と deploy workflow は本番権限境界に影響するため、必ず review してから merge する。
