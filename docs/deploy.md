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

`.github/workflows/deploy.yml` は GitHub-hosted の `resolve`、`quality`、`build` と、self-hosted の `deploy` に分かれる。`resolve` は対象 SHA が `origin/main` から到達可能であることを確認し、`quality` と `build` は同じ SHA を checkout して `HEAD` 一致を検証する。automatic main push と最新 main SHA の手動 deploy は、`quality` の `make test`、`make detekt`、clean-tree 検査が成功するまで、GHCR login、image build/push、signed bundle 作成、NAS deployへ進まない。

過去の main SHA を明示した `workflow_dispatch` は recovery 経路として `quality` を skip し、既存どおり image build/deployへ進む。`build` は resolved SHA 単位の concurrency group（`cancel-in-progress: true`）で並列 build 同士の重複トリガーだけをキャンセルする。`deploy` は `fukurou-production-deploy` group（`cancel-in-progress: false`）で直列化し、production deploy が同時実行されないようにする。異なる SHA の `build` job は並列実行できるため、1 件の `deploy` job が runner 割り当て待ちで滞留しても後続 push の quality/build 開始を塞がない。`deploy` job には `timeout-minutes: 35` を設定し、25分のexecutor watchdogとrecoveryを収容しつつrunner上のハングを停止する。

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
| foundation harness       | `/usr/local/libexec/fukurou-mcp-credential-isolation-check` |
| self-hosted runner name  | `dxp4800plus-fukurou-prod`                 |
| self-hosted runner label | `fukurou-prod`                             |
| production image         | `ghcr.io/matsumo0922/fukurou@sha256:<digest>` |

## Pinned CLI acceptance qualification

merge candidate の provider qualification は、production 用 `llm-auth` と分離した Docker volume
`llm-canary-auth` を使う。candidate image の app UID/GID で directory を初期化し、Claude と Codex を
この専用 volume にだけ login する。

```sh
IMAGE='ghcr.io/matsumo0922/fukurou@sha256:<digest>'
docker volume create llm-canary-auth
docker run --rm --user 0:0 --mount type=volume,src=llm-canary-auth,dst=/canary-auth \
  --entrypoint /bin/sh "$IMAGE" -ec \
  'chown 10001:10004 /canary-auth && install -d -o 10001 -g 10004 /canary-auth/.claude /canary-auth/.codex'
docker run --rm -it --user 10001:10004 --mount type=volume,src=llm-canary-auth,dst=/canary-auth \
  --env HOME=/canary-auth --env CLAUDE_CONFIG_DIR=/canary-auth/.claude \
  --entrypoint /usr/local/bin/claude "$IMAGE" auth login
docker run --rm -it --user 10001:10004 --mount type=volume,src=llm-canary-auth,dst=/canary-auth \
  --env HOME=/canary-auth --env CODEX_HOME=/canary-auth/.codex \
  --entrypoint /usr/local/bin/codex "$IMAGE" login --device-auth
```

merge qualification は immutable repository digest を一度だけ照合し、同じ harness invocation と image で
foundation を1回、4 phase acceptance matrix を3回実行する。短い operator smoke は matrix だけを1回実行する。

```sh
scripts/mcp-credential-isolation-check --qualification --runs 3 --reuse-image "$IMAGE"
scripts/mcp-credential-isolation-check --cli-acceptance --runs 1 --reuse-image "$IMAGE"
```

結果には digest と allowlist 済み status だけを残し、prompt、credential、provider stdout/stderr は含めない。
acceptance container は dedicated auth を read-only mount し、production auth、DB、vault、Docker socket、
production network を持たない。deploy executor はこの acceptance を required hook として呼び出さないため、
deploy-time smoke の完了条件は別 change まで未達のままとする。

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
sudo install -d -o root -g root -m 0700 /srv/fukurou/deploy-state
sudo install -d -o root -g root -m 0700 /srv/fukurou/runtime/launch-fence
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

repository の deploy script を root-owned script として反映する。

```sh
sudo install -m 0755 /srv/fukurou/repo/scripts/deploy/deploy-fukurou /usr/local/sbin/deploy-fukurou
sudo install -m 0755 /srv/fukurou/repo/scripts/deploy/fukurou-deploy-db /usr/local/libexec/fukurou-deploy-db
sudo install -m 0555 /srv/fukurou/repo/scripts/mcp-credential-isolation-check /usr/local/libexec/fukurou-mcp-credential-isolation-check
sudo install -m 0444 /srv/fukurou/repo/scripts/deploy/sql/mcp-role.sql /usr/local/share/fukurou/mcp-role.sql
sudo install -m 0644 /srv/fukurou/repo/scripts/deploy/sql/deploy-foundation-v1.sql /usr/local/share/fukurou/deploy-foundation-v1.sql
sudo install -m 0644 /srv/fukurou/repo/scripts/deploy/sql/deploy-foundation-v1-indexes.sql /usr/local/share/fukurou/deploy-foundation-v1-indexes.sql
sudo install -m 0444 /srv/fukurou/repo/scripts/deploy/deploy-capability-catalog-v1.json /usr/local/share/fukurou/deploy-capability-catalog-v1.json
sudo install -m 0444 /srv/fukurou/repo/scripts/deploy/deploy-public-key.pem /usr/local/share/fukurou/deploy-public-key.pem
sudo cc -std=c17 -O2 -Wall -Wextra -Werror -I/srv/fukurou/repo/scripts/runtime \
  /srv/fukurou/repo/scripts/runtime/fukurou-runtime-supervisor.c -lcrypto \
  -o /usr/local/libexec/fukurou-runtime-supervisor
sudo chown root:root /usr/local/libexec/fukurou-runtime-supervisor
sudo chmod 0555 /usr/local/libexec/fukurou-runtime-supervisor
```

sudoers template を反映する。

```sh
sudo install -m 0440 /srv/fukurou/repo/scripts/deploy/sudoers-fukurou /etc/sudoers.d/fukurou-deploy
sudo visudo -cf /etc/sudoers.d/fukurou-deploy
```

deploy script や sudoers template を変更した場合も、`/usr/local/sbin` と `/etc/sudoers.d` への反映は管理者が手動で行う。GitHub Actions から root-owned script を自動更新しない。

root executor、DB helper、foundation harness、foundation SQL、public key は同じ commit から一組で反映する。workflow は installed contract version と、署名 bundle 内の executor/public-key/foundation-harness hash が repository artifact と一致しない場合に typed operation を一件も実行しない。repository 側の変更だけでは root-owned artifact は変わらないため、artifact を変更する PR では production を safe-stop し、管理者が同じ merge SHA の一組を反映してから同じ SHA/digest の workflow を再実行する。

GitHub Actions の `DEPLOY_SIGNING_PRIVATE_KEY` secret は、repository の `deploy-public-key.pem` と対になる Ed25519 private key を PEM 形式で保持する。private key は NAS `.env`、repository、workflow artifact、rollback bundleへ保存しない。

## release / deploy safety foundation

deploy workflow は candidate SHA/image digest、contract version、versioned capability catalog、executor/public-key/foundation-harness hashを canonical JSON bundle に固定し、Ed25519 署名を付ける。executorはrollback directory、catalog、maintenanceを変更する前にexact digestのcandidate PID 1へrequired hook tupleをprobeし、candidate executableが未実装のoperationを拒否する。実際にdispatchしたoperation IDはhash chain付きroot-only ledgerへ永続化する。削除、再定義、version downgrade、parent fork、duplicate ID、未知schema/profile/entrypoint、candidate未実装operationではmutationを開始しない。

検証後の最初の deploy state mutation は `/srv/fukurou/deploy-state/<deployment-id>/` の rollback bundle capture である。bundle は `FRESH` / `PRE_FOUNDATION` / `FOUNDATION_V1`、previous repository SHA、全compose source/hash、configured image reference/image ID/repo digest/revision、runtime config、maintenance tuple、raw fence hash、foundation fingerprint、PID registration summary、installed artifact hash、DB capture時刻をroot-onlyで保持する。render済み compose、`.env`、credential、key bytesは保持しない。

LLM reservation は同じ transaction で `SPAWN_RESERVED` registration を作る。PID 1 は child を start gate で停止したまま container instance、PID namespace inode、PID、process start ticks を採取し、`ACTIVE` への exact CAS が成功した場合だけ exec を許可する。provider が起動する MCP も同じ invocation/reservation lineage へ別 role で登録し、通常完了、current-process stale recovery、previous-generation recoveryはいずれもreservationと同じtransactionで全 role を `TERMINAL` にする。PID registrationを持たないlegacy reservationも回収できる。terminal row は24時間経過後に最大1,000件ずつ削除する。registration、DB、process identity の不一致や観測不能は launch socket を閉じたままにする。

production container の PID 1 は `fukurou-runtime-supervisor` であり、全LLM/MCP spawnをserialized socketへ集約する。request はversion、profile、length-prefixed argv/env、FD role bitmap、nonceを持ち、peer UID/GID、fixed executable、option順、path、environment、FD種別を同じprofileとして検証する。reject pathは受信した全FDを閉じる。fence は固定key順のcanonical JSON bytesとSHA-256を共通codecでatomic更新する。

deploy maintenance は durable disable ACK、同generationのDB maintenance commit、active process drainの順で進む。PID 1が利用できない場合は application containerのPID 0を確認してからDB maintenanceへ進む。再開はDB maintenance clear後に同generationのenable ACKを取得する。startup時はDB maintenanceとhost fenceを照合するまでspawnを許可せず、欠損、破損、generation不一致、DB failureではlaunchを閉じたままApplication/opsを起動する。

candidate hookはproduction fenceを開かない。`CANARY_ONLY` tokenをcandidate SHA/image digest/catalog hashへ固定し、root-generated Compose projectのinternal fixture networkで同じimage、PID 1、read-only、tmpfs、capability条件を使う。署名bundleへhash固定したinstalled foundation harnessが、同じdigestの一時PID 1に対してproviderとMCPのtyped launch、fixture auth、required tool/output schema、failure cleanupを実行し、repository checkout内のscriptは実行しない。終了時は一時container、internal network、volumeが0件であることを確認する。production DB credential、endpoint、mutation toolは渡さない。

deploy journal と canary audit は sequence、previous state、previous hash、canonical payload hash、現在の末尾 sequence に対するCASを持つappend historyである。新しいdeploy journalはrollback state directoryを公開する前かつ最初のsafety mutation前に`PREPARED`、launch disable開始前に`SAFETY_MUTATION_STARTED`を永続化する。`CAPTURED`から始まる旧形式の正当なhash chainはversion-aware validatorで読み取り、terminal historyは再実行せず、unfinished historyは記録済みstateに対応するstartup recoveryへ入る。hash改ざんと形式ごとの不正なtransitionは拒否する。

live errorとstartup recoveryはいずれもmaintenance再確立、active reservation 0、disable fence、OPEN gapを再確認してから保存済みrollbackを実行する。観測したgap stateが`MISSING`なら永続化済みOPEN eventを再送し、存在しない場合はrecovery-owned gapを開く。`OPEN`は同じdeployment gapを継続し、`CLOSED`はrecovery-owned gapを開いてrollback区間を記録する。`ROLLED_BACK`を永続化できた場合だけ次のdeployへ進む。

deadlineはdeploy lock取得時の`/proc/uptime`を共通の起点にする。startup recovery、candidate operation probe、rollback captureを含むforward処理は起点から20分、recoveryは同じ起点から25分をabsolute deadlineとし、Docker、Git、DB helperの全callを残りbudget以下へ制限する。TERM/INT/HUP/deadlineはrollback capture前も処理し、外部commandは独立process groupへTERM/KILLを伝播する。

maintenance intervalはroot DB helperがappend-only `infrastructure_gap_events`へimmutableなOPEN/CLOSE factを直接記録する。decision/run/order/position/execution/tradeはrun開始からexposure終了までの共通causal projectionで`ELIGIBLE` / `INFRASTRUCTURE_GAP` / `ATTRIBUTION_MISSING`に分類し、非terminal run、order/intent/decision/runの不一致、position内execution orderの不一致もmissingにする。summary、setup、calibration、benchmark、prior PnL、kill criterion、run rate、report、reflection、knowledge、usageは同じeligible境界を使い、APIはentity type別件数とgap catalogを返す。依頼期間と交差するgapだけを上限判定し、gap 1,000件超、entity 20,000件超、integrity不整合、timeoutは部分値を返さない。

deploy foundation のlocal semantic fixtureは次を実行する。`deploy-postgres-selftest`は使い捨てのPostgreSQL 16 containerでroot DB helperの全operationを検証し、`canary-compose-selftest`はproduction composeへdeny overlayを合成した実効JSONを検証するため、どちらもDockerが必要である。

```sh
scripts/deploy/deploy-contract-selftest
scripts/deploy/deploy-runtime-selftest
scripts/deploy/deploy-db-selftest
scripts/deploy/deploy-postgres-selftest
scripts/deploy/canary-compose-selftest
docker build --target launcher-build -t fukurou-launcher-build:selftest .
docker run --rm fukurou-launcher-build:selftest ./fukurou-runtime-supervisor --protocol-selftest
```

`deploy-e2e-selftest` は deploy transaction 全体の production-like E2E である。local registry へ push した実 candidate image、supervisor 非搭載の PRE_FOUNDATION 相当 image、使い捨て PostgreSQL、実 lifecycle canary を使い、実 executor を Linux harness container 内で root 実行する。scenario は (1) 稼働中 PRE_FOUNDATION production への foundation 導入成功、(2) canary 失敗から旧 image への fence-fallback ENABLE を含む `ROLLED_BACK` terminal、(3) 未終端 `RECOVERY_STARTED` journal・maintenance enabled・fence `DISABLED_PENDING_DB` という状態からの次 deploy 起動と自動 recovery 完遂をカバーする。実行時間が長いため手動実行前提とし、deploy executor・DB helper・lifecycle canary・compose・foundation schema のいずれかを変更する PR は、merge 前に同一 HEAD でこの selftest を完走させた evidence を PR に添付する。selftest は compose project `fukurou-e2e` に隔離して実行する（ローカル開発 DB の volume には触れない）が、`fukurou-ktor` / `fukurou-postgres` の container 名は production compose 側で固定のため、同名 container が稼働中の環境では実行を拒否する。

```sh
scripts/deploy/deploy-e2e-selftest                     # 全 scenario
FUKUROU_E2E_SCENARIOS="2 3" scripts/deploy/deploy-e2e-selftest  # scenario 選択
FUKUROU_E2E_KEEP=1 scripts/deploy/deploy-e2e-selftest  # 失敗調査時に sandbox を残す
```

executor は `FUKUROU_IMAGE_REPOSITORY`（default `ghcr.io/matsumo0922/fukurou`）と `FUKUROU_DEPLOY_HEALTH_TIMEOUT`（application health 待ちの秒数、default 120）を env で上書きできる。どちらも E2E selftest が local registry と遅い開発機のために使う seam であり、production（NAS）は default 値で運用する。

recovery が復元した container に supervisor が存在しない場合（PRE_FOUNDATION image への rollback）、executor は supervisor ENABLE の代わりに active launch 0 を検証したうえで launch fence を直接 `ENABLED` へ書き込み、journal の `ROLLED_BACK` に `restoredEnable: "fence-fallback"` を記録する。recovery が deterministic に継続不能な場合（state.json の image reference / revision 不正、runtime config CAS 不一致、restored runtime の ENABLE 失敗）は terminal `MANUAL_RECOVERY_REQUIRED` を理由付きで journal へ書く。transient な失敗（docker / DB timeout 等）は journal を `RECOVERY_STARTED` のまま残し、次回 deploy 起動時の自動 recovery で再試行される。

production cutoverとLLM phase manifestのimage referenceは、どちらもcandidate digestを含む同じimmutable referenceである。executorはcheckout前とrollback時に保存済みcomposeとの互換変数にも同じimmutable referenceを束縛し、tagへ退行させない。commit tagは表示用で、pull、create、health後にconfigured reference、image ID/repo digest、`/revision`を照合する。executorはrollback capture前にproduction composeをrender検証し、失敗時はstageとstable reasonを出して未確定の一時stateを削除する。executorはlock取得の共通起点からforward 20分、recoveryは同じ起点から25分（forward終了後は最大5分）のabsolute budgetを持ち、TERM/INT/HUP/deadlineでもjournalからrecoveryへ入る。`FRESH` / `PRE_FOUNDATION`は旧serviceを自動再開せず、maintenance/fence/gapを閉じない。

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

`main` push により `.github/workflows/deploy.yml` が実行される。resolve job が対象 SHA を固定し、quality job がその SHA の JVM test、detekt、clean-tree を確認する。quality 成功後だけ build job が image を pushし、deploy job が NAS runner 上で次を実行する。

```sh
sudo /usr/local/sbin/deploy-fukurou \
  --bundle <signed-bundle-path> \
  --signature <detached-signature-path> \
  <commit-sha>
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

`safety.maxDrawdownRatio` はruntime内の全consumerへ同じactive値を適用するが、production activationはcode default `-0.15` と数値同値な場合だけをサポートする。merge直前、candidate起動直前、candidate起動直後かつready/traffic activation前の3点で、`activeVersionId`、`activeConfigHash`、canonical値、観測時刻、candidate revisionをread-only確認し、single operatorのconfig freeze中にidentityが一致することを人間が確認する。NON_DEFAULT、UNVERIFIED、freeze不能、identity変更、readback失敗ではmerge/deployしない。root deploy executorによるnon-default rollback fenceは存在しない。

旧imageへのrollback保証は、max drawdown起因のhalt、active値とcode defaultの数値同値、rollback時drawdownがdefault以下、config identity不変をすべて満たすtraceだけに限定する。このtraceではsticky haltからentry fill 0とsweepを継続し、duplicateまたはretrospective executionを作らない。kill criterion、manual halt、market-data failure、回復済みdrawdown、non-default値、一般的な旧image rollbackへこの保証を拡張しない。

`safety.economicEventBlackouts` の code-owned candidate は Federal Reserve 公式 calendar の 2026 年残り FOMC 会合を `America/New_York` 14:00 から UTC へ変換し、前後 60 分で保持する。draft は code-owned candidate と同じ日程に固定せず、安全な window と future FOMC を持つ operator 更新を受け入れる。全 event の window 両端が導出可能であり、`FOMC` と名付けた event が `fomc-` ID を持つことを検証する。active 値が空、不正、期限切れの場合、実行対象 event を空にし、`/ops/runtime-config` は response 時点の専用 warning を返し、SafetyFloor は新規 entry だけを停止する。readiness、ProtectionReconciler、close、cancel、protection update は継続する。calendar 更新は通常の draft / validate / activate 手順を使い、公式 source と UTC 変換結果を確認する。deploy や rollback の一部として production active 値を暗黙に切り替えない。

`llm_launch_reservations` の economic-event migration はnullable `single_attempt_key TEXT`をadditiveに追加する。既存`ECONOMIC_EVENT`はtrigger keyごとに`reserved_at, invocation_id`が最小の1行だけへcanonical keyをbackfillし、重複historyは`NULL`のまま保持して削除しない。その後non-null rowだけのpartial unique indexを作成・検証する。bootstrapは同じschema transaction内で対象row数を事前計測し、backfillとindex stepをtransaction-local `lock_timeout=2s` / `statement_timeout=5s`、transaction retryなしで実行する。候補row数、canonical更新件数、経過時間、index step結果、transaction commit成否をstructured logへ記録する。timeout、migration、verificationのいずれかが失敗するとtransaction全体をrollbackし、readinessをfalse、worker開始数を0に保つ。rollbackでもcolumn、canonical backfill、indexを削除しない。旧binaryへ戻す場合は新binary上でglobal launch gateをOFFにし、RUNNING tradingとrisk-increasing pending workを0にして、修正版へ戻るまでentry経路を再開しない。

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

## MCP credential isolation の移行

この移行は旧imageを止めるPhase 0と、新imageのglobal gateを確認してからcredentialを移行するPhase 1に分ける。merge/deploy前にproduction credentialを変更しない。

### Phase 0: 旧imageをquiescentにする

1. WebUI `/app/config` で `daemon.enabled=false` のdraftを作成し、validateしてactive化する。Ktorを再起動し、`/ops/runtime-config`でeffective valueがfalseであることと、再起動後にscheduler workerが作成されず新しい`DAEMON_STARTED` auditが記録されないことを確認する。旧imageは`llm.launchEnabled`を認識しないため、この段階でglobal gateを設定しようとしない。
2. Phase 0開始後は`POST /ops/trigger`を呼ばず、`OneShotRunnerMain`も直接実行しない。scheduler worker不在と運用上のlaunch禁止を維持したまま、`pgrep -fa OneShotRunnerMain`が空であることを確認する。
3. maintenance connectionで `SELECT count(*) FROM llm_launch_reservations WHERE status='RUNNING';` と `SELECT count(*) FROM llm_runs WHERE status='RUNNING';` がどちらも0であることを確認する。0になるまでdeploy、role provision、credential rotationへ進まない。

### Phase 1: 新imageのglobal gate配下で移行する

4. root:root 0400 の `/srv/fukurou/secrets/fukurou_mcp_db_password` を dummy ではない新規値で作成し、値を shell history、log、PR に出さない。provision時のpsql変数解釈を単純に保つため、十分な長さの英数字だけで生成する。
5. 対象 SHA の新imageを、この文書の signed bundle 付き `deploy-fukurou --bundle ... --signature ... <commit-sha>` でdeployする。欠落している`llm.launchEnabled`はbootstrapによってfalseでactive snapshotへ追加される。Ktor startupの`TradingPersistenceBootstrap`がMCP evaluation viewを作成するまでrole provisioningを実行しない。
6. `sudo docker run --rm --network fukurou_edge curlimages/curl -fsS http://ktor:8080/health/ready` を実行し、maintenance connectionでrequired viewの存在を確認する。`/ops/runtime-config`で`daemon.enabled`と`llm.launchEnabled`のactive valueとeffective valueがすべてfalseであることを確認する。`daemon.enabled=false`なのでscheduler workerは作成されず、新しい`DAEMON_STARTED` auditも記録されない。
7. `POST /ops/trigger`が`LLM_LAUNCH_DISABLED`の409を返すこと、direct `OneShotRunnerMain`がchild processやMCP credentialを使う前にnon-zeroで終了することを確認する。scheduler workerは不在なので`LLM_LAUNCH_DISABLED`のscheduler skip auditを期待しない。その後、手順3のRUNNING 0 queryと`pgrep`を再実行する。
8. `scripts/deploy/provision-fukurou-mcp-role '<maintenance-database-url>' "$POSTGRES_DB" "$POSTGRES_USER" "$FUKUROU_MCP_DB_PASSWORD_FILE"` を実行し、`fukurou_mcp` roleをprovisionする。scriptはMCPのopportunity token、INSERT、close UPDATEがすべてfalseで、app roleのtokenだけがtrueというpostconditionも検証する。preflight、権限不足、postcondition不一致では失敗するため、gateをOFFのまま維持する。
9. deploy済みimageを再利用する場合は`scripts/mcp-credential-isolation-check --reuse-image <exact-image>`を実行し、paper smoke、knowledge toolsを含むrequired MCP call matrixを確認する。local buildを検査する場合は`--reuse-image`を外す。role flag、membership、ownership、effective grantも確認する。
10. canary scan 完了後、旧 shared `config.toml` と session artifact を auth source から分離して削除する。
11. running Ktor containerの`/run/fukurou/llm-homes`がtmpfsであることを`docker inspect`で確認する。旧`fukurou_llm-runs` volumeが残っている場合は、一時containerへread-only mountして残存per-run auth copy、session、quarantine artifactを監査し、必要な証跡を保存してから`fukurou_llm-runs`だけを削除する。永続auth sourceの`fukurou_llm-auth`とDBの`fukurou_pgdata`は削除しない。
12. app の旧 credential を PostgreSQL と NAS `.env` で同時に rotateし、Ktor containerを再起動して新しい値を反映する。旧 credentialで接続できないことを確認する。
13. credential rotation後にも手順3のRUNNING 0 queryと`pgrep`を実行する。証跡を保存してから、次のように`llm.launchEnabled=true`と`daemon.enabled=true`を同一draftで作成し、validateしてactive化する。この2キーはどちらも`NEXT_RESTART`なので、別々のactive化や途中の再起動を行わない。

    ```sh
    draft_id="$(scripts/prod-curl \
      /ops/runtime-config/drafts \
      --json '{
        "baseVersionId": null,
        "values": {
          "llm.launchEnabled": "true",
          "daemon.enabled": "true"
        },
        "note": "resume LLM launch surfaces after MCP credential isolation"
      }' | jq -r '.version.id')"

    scripts/prod-curl "/ops/runtime-config/drafts/${draft_id}/validate" \
      --json '{"reason":"resume LLM launch surfaces after MCP credential isolation"}'
    scripts/prod-curl "/ops/runtime-config/drafts/${draft_id}/activate" \
      --json '{"reason":"resume LLM launch surfaces after MCP credential isolation"}'
    ```

14. Ktorを1回だけ再起動し、`/ops/runtime-config`で両キーのactive valueとeffective valueがすべてtrueであること、新しい`DAEMON_STARTED` auditと通常cycleによってscheduler workerの再開を確認する。production smokeはreservationを共有する`POST /ops/trigger`に限定し、`LLM_LAUNCH_DISABLED`では拒否されず、reservation、起動上限、SafetyFloorなど通常の安全guardを通ることを確認する。direct `OneShotRunnerMain`のgate ONは自動テストを正本とし、通常のmigration完了経路では実行しない。productionでdirect canaryが必要な場合だけ、`docs/mcp-runtime.md`のdirect runner maintenance境界に従ってschedulerと隔離する。

role の `rolsuper`、`rolcreatedb`、`rolcreaterole`、`rolreplication`、`rolbypassrls` はすべて false、membership と object ownership は 0 であることを確認する。MCP の evaluation scope は `mcp_current_evaluation_scope` と `mcp_evaluation_epochs` view から account epoch、3つのbaseline、epoch kind、作成時刻だけを読み、secretを含み得る `runtime_config_versions` / `runtime_config_values` や `paper_account_epochs` への直接SELECTは許可しない。`llm_launch_reservations`、`equity_snapshots` と ledger の UPDATE/DELETE/TRUNCATE も拒否される。必要 call の permission failure は role SQL と inventory を修正して disposable test からやり直す。

merge 前の自動証跡は `McpDatabaseRoleIntegrationTest` の role/effective privilege/required-call matrix と、`scripts/mcp-credential-isolation-check` の tool audit export・DB data-only dump・encoding scan を含む。scan coverage や dump が欠けた run は無効とし、再実行する。real provider model output probe は operator auth を必要とする別の human check として記録し、自動 check 成功へ読み替えない。

providerがper-run home内へshared groupから通常削除できないmodeのnested artifactを作成した場合、cleanupはfixed LLM launcherのpath限定cleanup modeへ委譲する。対象は`/run/fukurou/llm-homes`直下にappuserが作成したcanonical per-run homeだけで、validated rootから開いたdirectory FDを基準にsymlinkを追跡せずtreeを削除する。helperは同じreal directory inodeのowner traversal/write権限だけを回復し、regular fileのread mode、symlink target、scope外inodeを変更しない。helperを含むcleanup failureでは`/run/fukurou/llm-homes/.cleanup-quarantine`が残り、manual/daemonの次runはcurrent container process内でfail closedになる。markerとper-run artifactは同じtmpfsにあり、container restartでは両方が同時に破棄される。operatorはdaemonを無効のまま残存per-run homeとmanifestを監査し、filesystem原因を解消してからmarkerを削除するか、監査後にcontainerを再起動する。markerだけを先に消したり、strategy NO_TRADEとして成績へ混ぜたりしない。

rotation 後は旧 image で LLM phase を再有効化しない。障害時は daemon disabled のまま現 image を維持するか、修正版へ roll-forward する。

この境界はfixed setuid helper 2個とdeployごとのprivilege inventory gateに依存する。merge前にfinal imageでsetuid/setgid、file capability、runtime/root control socket、LLM/MCP process属性のexact checkが通ることを確認する。imageまたはCLIを更新してNode内部FD配置が変わった場合は、差分を監査してから`validate-llm-launcher-probe.mjs`の`liveFds` exact inventoryを更新し、同じfinal imageでcanaryを再実行する。

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
