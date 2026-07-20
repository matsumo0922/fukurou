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

`.github/workflows/deploy.yml` は GitHub-hosted の `resolve`、`quality`、`build` と、self-hosted の `deploy` に分かれる。`resolve` は対象 SHA が `origin/main` から到達可能であることを確認し、`quality` と `build` は同じ SHA を checkout して `HEAD` 一致を検証する。automatic main push、最新 main SHA の手動 deploy、過去の main SHA を対象にした authorized rollback のすべてが、`quality` の `make test`、`make detekt`、clean-tree 検査に成功するまで、GHCR login、image build/push、signed bundle 作成、NAS deployへ進まない。quality bypass は存在しない。

automatic push は署名 bundle v2 に `push`、`FORWARD`、空の operator reason、`AUTO_IMAGE_ROLLBACK` を固定する。`workflow_dispatch` で過去の main SHA を指定すると workflow が `AUTHORIZED_ROLLBACK` を導出し、240文字以内の `rollback_reason` を必須にする。`BACKWARD_COMPATIBLE` または `ROLL_FORWARD_ONLY` を選ぶ場合も、manual dispatch と理由が必須になる。reasonは監査用の非secret説明だけを受け入れ、control character、secret-like assignment、Bearer credential、credential入りURI、private-key header、既知のtoken prefixをworkflowとroot executorの両方で拒否する。入力から deploy intent を直接指定する経路はない。

`build` は resolved SHA 単位の concurrency group（`cancel-in-progress: true`）で並列 build 同士の重複トリガーだけをキャンセルする。`deploy` は `fukurou-production-deploy` group（`cancel-in-progress: false`）で直列化する。異なる SHA の build が逆順で完了しても、root executor は production lock 内で稼働 revision を再観測し、queued old `FORWARD` を rollback capture、maintenance、fence、DB、Compose mutationより前に拒否する。revisionを後退させる経路は理由付き `AUTHORIZED_ROLLBACK` だけである。

`scripts/deploy/deploy-schema-sensitive-paths-v1.txt` はDB migration/bootstrap、deploy DB helper、application/trading persistence schema ownerのcode-owned inventoryである。automatic push の `before` から target までの差分がinventoryに一致すると、resolve jobがimage publication前にmanual compatibility reviewを要求する。comparison baseを安全に確定できない場合とmanual dispatchでは早期判定をadmissionに使わず、root executorがlock内のobserved current-to-target diffを同じinstalled inventoryで必ず再分類する。schema-sensitive diffは`BACKWARD_COMPATIBLE`または`ROLL_FORWARD_ONLY`、非schema-sensitive diffは`AUTO_IMAGE_ROLLBACK`だけを受け入れる。

schema ownerを追加、移動、削除するときは、同じcommitでinventoryを更新する。各entryはrepository rootからの相対pathまたはdirectory prefixを1行で記述し、glob、絶対path、`..`を使わない。変更時は、DB migration/bootstrap、deploy DB helper、application persistence、trading persistenceの4区分を見直し、移動元entryを残していないこと、新しいownerを漏らしていないこと、`scripts/deploy/deploy-intent-resolver-selftest`と`ReleaseDeployFoundationContractTest`が通ることを確認する。

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

foundation harness を変更した image の deploy 前には、`sudo install -m 0555 /srv/fukurou/repo/scripts/mcp-credential-isolation-check /usr/local/libexec/fukurou-mcp-credential-isolation-check` を再実行し、signed bundle の harness hash と installed copy を一致させる。一致しない deploy は `INSTALLED_FOUNDATION_HARNESS_HASH_MISMATCH` で停止する。

結果には digest と allowlist 済み status だけを残し、prompt、credential、provider stdout/stderr は含めない。production qualification は harness override env を拒否し、明示的な selftest mode だけ fake Docker / foundation harness を許可する。
acceptance container は dedicated auth を read-only mount し、production auth、DB、vault、Docker socket、
production network を持たない。署名済み `FORWARD` bundle は `CLI_AUTH_PREFLIGHT_V1`、`FOUNDATION_PREFLIGHT_V1` の順序を必須とし、executor は exact candidate digest に対する `--cli-acceptance --runs 1` を rollback capture と launch mutation より前に実行する。`AUTHORIZED_ROLLBACK` は foundation-only の hook set を維持し、新しい provider qualification を通過したとは扱わない。Codex は configured `-m gpt-5.5` を検証するが、CLI output が served model を報告しないため provider-observed model identity は未検証である。

`llm-canary-auth` は fresh install / disaster recovery の bootstrap prerequisite であり、deploy は volume の作成、login、refresh、production `llm-auth` への fallback を行わない。login、operator smoke、deploy は同時実行せず、事前に `sudo -V` の preserve environment 一覧へ `FUKUROU_CANARY_DOCKER`、`FUKUROU_FOUNDATION_HARNESS`、`FUKUROU_CLI_CANARY_SELFTEST` が含まれないことを確認する。executor もこれらを明示的に除去する。provider/account の rate、session、quota は production と共有され得て、1 matrix は稼働中 production と同時に最大 2 CPU / 2 GiB を使う。失敗時は safe typed failure と root-only 0700 artifact path だけを deploy log に残し、raw provider output と credential を出さない。

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
sudo install -m 0444 /srv/fukurou/repo/scripts/deploy/deploy-schema-sensitive-paths-v1.txt /usr/local/share/fukurou/deploy-schema-sensitive-paths-v1.txt
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

root executor、DB helper、foundation harness、foundation SQL、public key、schema-sensitive inventory は同じcommitから一組で反映する。workflowはinstalled contract version `2`、installed inventory hash、署名bundle内のexecutor/public-key/foundation-harness/inventory hashを検証し、不一致ではcandidate image pullやtyped operationを開始しない。

bundle v2を導入するPRは、merge前のreview済みexact HEADでroot executorとinventoryを先にinstallする。この作業を始める前に進行中deployとunfinished journalがないことを確認し、完了までproduction deployと、executor、public key、foundation harness/SQL、capability catalog、schema-sensitive inventoryへ触れる他PRのmergeをfreezeする。production checkout `/srv/fukurou/repo` のbranch、HEAD、working treeは変更せず、review HEAD専用の一時detached worktreeからinstallとselftestを行う。

```sh
sudo bash <<'ROOT'
set -euo pipefail

readonly production_repo=/srv/fukurou/repo
readonly review_worktree=/srv/fukurou/preinstall/deploy-v2-review
readonly reviewed_head='<reviewed-pr-head-sha>'
readonly production_head="$(git -C "${production_repo}" rev-parse HEAD)"
readonly production_branch="$(git -C "${production_repo}" symbolic-ref --short HEAD 2>/dev/null || true)"
readonly production_status="$(git -C "${production_repo}" status --porcelain=v1)"

cleanup() {
  local exit_code=$?
  trap - EXIT

  if git -C "${production_repo}" worktree list --porcelain | grep -Fqx "worktree ${review_worktree}"; then
    git -C "${production_repo}" worktree remove "${review_worktree}" || exit_code=1
  fi
  if [[ "$(git -C "${production_repo}" rev-parse HEAD)" != "${production_head}" ]] ||
     [[ "$(git -C "${production_repo}" symbolic-ref --short HEAD 2>/dev/null || true)" != "${production_branch}" ]] ||
     [[ "$(git -C "${production_repo}" status --porcelain=v1)" != "${production_status}" ]]; then
    echo "production checkout changed during deploy v2 pre-install" >&2
    exit_code=1
  fi

  exit "${exit_code}"
}
trap cleanup EXIT

[[ "${reviewed_head}" =~ ^[0-9a-f]{40}$ ]]
[[ ! -e "${review_worktree}" ]]
git -C "${production_repo}" fetch --no-tags origin "${reviewed_head}"
git -C "${production_repo}" cat-file -e "${reviewed_head}^{commit}"
install -d -o root -g root -m 0700 /srv/fukurou/preinstall
git -C "${production_repo}" worktree add --detach "${review_worktree}" "${reviewed_head}"
install -d -o root -g root -m 0755 /usr/local/share/fukurou
install -o root -g root -m 0755 \
  "${review_worktree}/scripts/deploy/deploy-fukurou" \
  /usr/local/sbin/deploy-fukurou
install -o root -g root -m 0444 \
  "${review_worktree}/scripts/deploy/deploy-schema-sensitive-paths-v1.txt" \
  /usr/local/share/fukurou/deploy-schema-sensitive-paths-v1.txt

test "$(/usr/local/sbin/deploy-fukurou --print-contract-version)" = 2
test "$(/usr/local/sbin/deploy-fukurou --print-schema-sensitive-paths-sha256)" = \
  "$(sha256sum "${review_worktree}/scripts/deploy/deploy-schema-sensitive-paths-v1.txt" | awk '{print $1}')"
stat -c '%U:%G:%a %n' \
  /usr/local/sbin/deploy-fukurou \
  /usr/local/share/fukurou/deploy-schema-sensitive-paths-v1.txt
bash -n "${review_worktree}/scripts/deploy/deploy-fukurou"
(
  cd "${review_worktree}"
  "${review_worktree}/scripts/deploy/deploy-contract-selftest"
  "${review_worktree}/scripts/deploy/deploy-runtime-selftest"
)
ROOT
```

一時worktreeを削除した後、production checkoutのbranch、HEAD、working treeが作業前と同じであることを上記のexact比較で確認する。verification後にreview HEADとmerge予定treeのexecutor/inventory hashが同一であることを再確認する。このpre-installと対象hash一致、targeted selftest、CI、clean-context approvalが揃うまでPR-2をmergeしない。pre-install後からmergeまで旧bundle v1 workflowはcontract mismatchでfail closedするため、この期間は意図したcontrolled deploy freezeになる。

GitHub Actions の `DEPLOY_SIGNING_PRIVATE_KEY` secret は、repository の `deploy-public-key.pem` と対になる Ed25519 private key を PEM 形式で保持する。private key は NAS `.env`、repository、workflow artifact、rollback bundleへ保存しない。

## release / deploy safety foundation

deploy workflow は candidate SHA/image digest、bundle schema/contract version、workflow event、deploy intent、operator reason、migration rollback mode、schema-sensitive inventory hash、versioned capability catalog、executor/public-key/foundation-harness hashをcanonical JSON bundle v2に固定し、Ed25519署名を付ける。executorは署名、closed value、exact target、repository/installed inventory hashを検証し、production lock内でunfinished recoveryを完了してからrevision ancestryとactual diffを判定する。その後、rollback directory、catalog、maintenanceを変更する前にexact digestのcandidate PID 1へrequired hook tupleをprobeする。C supervisor は `cli-auth` と `foundation` の operation ID / schema / profile / slug の完全一致を実行時に要求する。`deploy-contract-v1.json` の intent別hook setはtest用のstatic oracleであり、executor入力やruntime authorityではない。

検証後の最初の deploy state mutation は `/srv/fukurou/deploy-state/<deployment-id>/` の rollback bundle capture である。bundleはaccepted current/target revision、intent、検証済みreason、migration mode、inventory hash、actual schema-sensitive result、probe済みcandidate image digestに加え、従来のruntime/rollback evidenceをroot-onlyで保持する。rollback用composeは可変なrepository HEADではなくaccepted current revisionのGit objectから保存する。target revisionのcomposeはmutation前に一時fileへsnapshotしてrender可能性を検証する。safety mutation後のcandidate preflightはcheckoutを変更せず、現在のproduction composeとdeny overlayへcandidate digestを束縛して実行し、target checkoutはpreflight成功後のproduction compose切替直前に行う。このためRFO中断後にrepository HEADとrunning revisionが分離していても、別revisionのcomposeをrollback evidenceへ混ぜない。container不在をfresh installとして扱うのは、read-only DB snapshotが`PRE_FOUNDATION`でpublished deployment directoryが0件の場合だけである。container revisionの欠損・不正、commit object不在、main外、divergent history、prior deployment historyを伴うcontainer欠損はunknown current revisionとしてmutation前に拒否する。

LLM reservation は同じ transaction で `SPAWN_RESERVED` registration を作る。PID 1 は child を start gate で停止したまま container instance、PID namespace inode、PID、process start ticks を採取し、`ACTIVE` への exact CAS が成功した場合だけ exec を許可する。provider が起動する MCP も同じ invocation/reservation lineage へ別 role で登録し、通常完了、current-process stale recovery、previous-generation recoveryはいずれもreservationと同じtransactionで全 role を `TERMINAL` にする。PID registrationを持たないlegacy reservationも回収できる。terminal row は24時間経過後に最大1,000件ずつ削除する。registration、DB、process identity の不一致や観測不能は launch socket を閉じたままにする。

production container の PID 1 は `fukurou-runtime-supervisor` であり、全LLM/MCP spawnをserialized socketへ集約する。request はversion、profile、length-prefixed argv/env、FD role bitmap、nonceを持ち、peer UID/GID、fixed executable、option順、path、environment、FD種別を同じprofileとして検証する。reject pathは受信した全FDを閉じる。fence は固定key順のcanonical JSON bytesとSHA-256を共通codecでatomic更新する。

deploy maintenance は durable disable ACK、同generationのDB maintenance commit、active process drainの順で進む。PID 1が利用できない場合は application containerのPID 0を確認してからDB maintenanceへ進む。再開はDB maintenance clear後に同generationのenable ACKを取得する。startup時はDB maintenanceとhost fenceを照合するまでspawnを許可せず、欠損、破損、generation不一致、DB failureではlaunchを閉じたままApplication/opsを起動する。

candidate hookはproduction fenceを開かない。`CANARY_ONLY` tokenをcandidate SHA/image digest/catalog hashへ固定し、root-generated Compose projectのinternal fixture networkで同じimage、PID 1、read-only、tmpfs、capability条件を使う。署名bundleへhash固定したinstalled foundation harnessが、同じdigestの一時PID 1に対してproviderとMCPのtyped launch、fixture auth、required tool/output schema、failure cleanupを実行し、repository checkout内のscriptは実行しない。終了時は一時container、internal network、volumeが0件であることを確認する。production DB credential、endpoint、mutation toolは渡さない。

deploy journal と canary audit は sequence、previous state、previous hash、canonical payload hash、現在の末尾 sequence に対するCASを持つappend historyである。新しいv2 deploy journalはrollback state directoryを公開する前かつ最初のsafety mutation前に`PREPARED`、launch disable開始前に`SAFETY_MUTATION_STARTED`を永続化する。v1 journalの正当なhash chainはversion-aware validatorで読み、unfinished recoveryへ入る。v1 historyにv2専用`CANDIDATE_ABORTED`を混在させず、v2 executorがv1 recoveryをterminalへ進める。

`AUTO_IMAGE_ROLLBACK`と`BACKWARD_COMPATIBLE`のlive error/startup recoveryは既存のprevious-image rollbackを使う。previous imageはmaintenance中のため、まずlivenessを確認し、maintenance clearとfence enableの後でreadinessを確認してからgapを閉じる。readinessを確認できなければmaintenance/fenceを再びdisabledへ戻してmanual recoveryとする。`ROLL_FORWARD_ONLY`が`PREPARED`/`CAPTURED`で失敗した場合はproduction、checkout、maintenance、fenceを変更せず`CANDIDATE_ABORTED`を記録する。`SAFETY_MUTATION_STARTED`以後の失敗はmaintenance/disable fence/OPEN gapの再確立とcanary revokeが両方成功した場合だけ`MANUAL_RECOVERY_REQUIRED`を記録し、途中で証明できなければ`RECOVERY_STARTED`を残して次回起動で再試行する。previous imageの起動、previous checkoutへの復元、DB restoreは行わない。operatorは署名targetへのroll-forwardまたは別途監査したmanual recoveryで解消する。

v2 recoveryは`stateVersion: 2`、`acceptedDeploy`、最初の`PREPARED` details、installed inventory hashを完全照合し、そこへ保存した中断deploy自身のcandidate digestだけをfence recovery codecに使う。mode、digest、accepted evidenceの欠損・不一致をlegacy `AUTO_IMAGE_ROLLBACK`へ読み替えず、journalを未終端のままfail closedする。legacy AUTO policyを適用するのは、`stateVersion`と`acceptedDeploy`が存在せず、journal versionが1である既存v1 stateだけである。

deadlineはdeploy lock取得時の`/proc/uptime`を使い、startup recovery、candidate operation probe、production compose validation の後に CLI acceptance 専用の750秒 admission budgetへ切り替える。container側は1-runを720秒で停止し、残り30秒をcleanupに使う。acceptance成功時の`/proc/uptime`をforward/recovery共通の新しい起点にし、rollback captureを含むforward処理はそこから20分、recoveryは同じ起点から25分をabsolute deadlineとする。GitHub deploy jobの外側上限は60分であり、Docker、Git、DB helperの全callを各stageの残りbudget以下へ制限する。TERM/INT/HUP/deadlineはrollback capture前も処理し、外部commandは独立process groupへTERM/KILLを伝播する。

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

`AUTO_IMAGE_ROLLBACK`と`BACKWARD_COMPATIBLE`のrecoveryが復元したcontainerにsupervisorが存在しない場合（PRE_FOUNDATION imageへのrollback）、executorはsupervisor `ENABLE`の代わりにactive launch 0を検証したうえでlaunch fenceを直接`ENABLED`へ書き込み、journalの`ROLLED_BACK`に`restoredEnable: "fence-fallback"`を記録する。これらのmodeでrecoveryがdeterministicに継続不能な場合（state.jsonのimage reference/revision不正、runtime config CAS不一致、restored runtimeの`ENABLE`失敗）はterminal `MANUAL_RECOVERY_REQUIRED`を理由付きでjournalへ書く。`ROLL_FORWARD_ONLY`は保存済みのintent、検証済みoperator reason、origin stateを使い、safety mutation前なら`CANDIDATE_ABORTED`、以後ならprevious imageを起動せずsafe boundaryを証明して`MANUAL_RECOVERY_REQUIRED`へ進む。どのmodeでもdocker/DB timeoutなどのtransient failureはjournalを`RECOVERY_STARTED`のまま残し、次回deploy起動時に同じv2 accepted evidenceから再試行する。次にqueueされたrunのintent、reason、candidate digestでstale deploymentのpolicyを上書きしない。

production cutoverとLLM phase manifestのimage referenceは、どちらもcandidate digestを含む同じimmutable referenceである。executorはcheckout前とrollback時に保存済みcomposeとの互換変数にも同じimmutable referenceを束縛し、tagへ退行させない。commit tagは表示用で、pull、create、health後にconfigured reference、image ID/repo digest、`/revision`を照合する。executorはrollback capture前にproduction composeをrender検証し、失敗時はstageとstable reasonを出して未確定の一時stateを削除する。executorはacceptance成功時の共通起点からforward 20分、recoveryは同じ起点から25分（forward終了後は最大5分）のabsolute budgetを持ち、TERM/INT/HUP/deadlineでもjournalからrecoveryへ入る。`FRESH` / `PRE_FOUNDATION`は旧serviceを自動再開せず、maintenance/fence/gapを閉じない。

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

application rollback は過去のcommit SHAを`workflow_dispatch`の`image_sha`に指定し、空でない`rollback_reason`を記入して再実行する。対象がcurrent revisionのstrict ancestorである場合だけworkflow/root executorが`AUTHORIZED_ROLLBACK`として受け入れる。同一SHA、新しいSHA、divergent/main外SHAは拒否する。historical targetも現在のCI環境で`make test`、`make detekt`、clean-tree検査を通す必要があり、quality bypassはない。

schema-sensitive diffでは、旧imageを起動できるとreviewした場合だけ`BACKWARD_COMPATIBLE`、旧imageへ戻せない場合は`ROLL_FORWARD_ONLY`を選び、判断理由を同じmanual dispatchへ残す。非schema-sensitive diffへexplicit modeを付けることと、schema-sensitive diffへ`AUTO_IMAGE_ROLLBACK`を付けることはroot再分類で拒否される。

workflow/application codeを前版へ戻す場合も、root-owned `/usr/local/sbin/deploy-fukurou`、installed schema-sensitive inventory、v2 audit historyは維持する。先にv2 executorでv1/v2 unfinished journalをterminalへ収束させ、v2 bundleを発行できるworkflow経路から前版applicationをdeployする。contract v1 executorへのdowngradeは、v2の`ROLL_FORWARD_ONLY`、`CANDIDATE_ABORTED`、retained terminal historyを解釈できないためunsupportedである。root executor downgradeが必要な場合は、v2 audit historyを保持したまま読める別のcompatibility designを先に用意する。

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
- 一時的な GitHub 側障害であれば、回復を待つか、詰まっている run を `gh run cancel` する。再実行時は現在の `origin/main` HEADを`workflow_dispatch`へ指定し、空の`rollback_reason`と`AUTO_IMAGE_ROLLBACK`で新しいforward intentを作る。詰まったrunの古いSHAをそのまま再利用しない。古いSHAを意図的に指定するのは、そのSHAがproduction currentのstrict ancestorで、理由付き`AUTHORIZED_ROLLBACK`として戻す場合だけである
- 対応が終わったら issue を close する（watchdog は issue を自動 close しない）

watchdog は job 名 `Deploy on NAS` を直接参照するため、`deploy.yml` の該当 job 名を変更する場合は `deploy-queue-watchdog.yml` 側の判定式も追随させる必要がある。

## 補足

- `cloudflared` は `cloudflare/cloudflared:2026.6.1` に tag pin している。
- `--no-autoupdate` のため cloudflared 更新は compose file の明示更新で行う。
- Claude Code の version を更新する PR では、merge 前に実 CLI で `--tools "ToolSearch"` が built-in tool を隠したまま MCP tool call へ進むことを確認する。
- production compose と deploy workflow は本番権限境界に影響するため、必ず review してから merge する。
