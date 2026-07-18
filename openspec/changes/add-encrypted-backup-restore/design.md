## Context

Production は PostgreSQL 16 を `/srv/fukurou` で運用し、root-owned deploy artifact は reviewed repository revision から operator が手動 install する。現在の deploy executor は `/var/lock/fukurou-deploy.lock` の下で release transaction を実行するが、database backup repository、logical snapshot、actual restore evidence は存在しない。

本 change は個人の paper trading system に、同一 NAS 内での限定的な recovery capability を追加する。production database の自動置換は行わず、backup と restore drill は root だけが実行する。status は後続の monitoring PR が読むための producer contract までを定義し、本 change では application container に mount しない。

## Goals / Non-Goals

**Goals:**

- same-NAS の encrypted daily PostgreSQL logical backup を平文 dump なしで作る。
- integrity を確認できた snapshot を newest 14 daily generations 保持する。
- exact verified snapshot を週次で isolated PostgreSQL 16 へ restoreし、schema/constraint/critical-table/read-only invariant と cleanup を検証する。
- attempt と last-known-good evidence を分離した atomic・versioned・redacted status を公開する。
- deterministic fixture、production backup entrypointを実PostgreSQL/resticで通すDocker integration drill、root systemd templates、operator runbook を同じ change に含める。

**Non-Goals:**

- `/ops/monitoring`、GitHub/Cloudflare alert、Ktor/composeへの status mount。
- PITR、WAL archive、off-site copy、NAS-loss protection、保証 RPO/RTO。
- automatic production DB restore、production replacement command、destructive backfill。
- deploy と backup の開始後 race を排除する完全相互排他、deploy executor/sudoers の変更。
- live trading の実装または有効化。

## Decisions

### 1. （ユーザー確認済み）restic と `pg_dump -Fc` を使う

root command はproduction container内のshellから`POSTGRES_USER`と`POSTGRES_DB`だけを出力してstrict identifier validationし、host非公開のPostgreSQLへ`docker exec -i fukurou-postgres pg_dump`で接続する。container-local connectionを使い、production DB passwordをinspect出力、pg_dump引数・環境変数、host filesystemへ渡さない。`pg_dump --format=custom --compress=0`のstdoutを`restic backup --stdin`へ流し、固定host/path/tagとbounded metadataだけをsnapshotへ付ける。source revisionはdump前後で同一であることを確認したapplication revisionであり、PostgreSQL/Application container ID、DB identity、application revisionのいずれかが変化したattemptはsuccess evidenceを進めない。repository passwordはroot:root 0400の固定password fileから読み、値を引数、log、statusへ出さない。

DB lock保持時間を上限60秒にする。pg_dump sessionにはattempt固有のvalidated `application_name`を設定する。独立watchdogは別connectionから同名backendがexactly oneであることを確認してPIDを固定し、deadline時に単一SQLの`WHERE pid = expected AND application_name = expected`で再照合してそのbackendだけを`pg_terminate_backend`し、backend消滅をboundedに確認する。watchdogの全control query、cancel、wait、reapは同じabsolute deadlineの残時間でboundedにし、設定から60秒を超えて拡張できない。dumpが先に完了した場合はwatchdogをcancel/reapする。0件または複数件なら他backendをterminateせずstable failureにし、termination/disappearanceを確認できなければ`WATCHDOG_TERMINATION_FAILED`としてsuccess evidenceを進めない。`pg_dump`が自身で上書きするsession timeoutはlock解放の根拠にしない。`pipefail`と各pipeline processの終了値を検証し、dump側が失敗したのにrestic側だけが成功したsnapshotをsuccess扱いしない。current processがexact IDを得た部分snapshotだけを`forget`（`--prune`なし）し、特定またはforgetに失敗した場合は以後のintegrity/retentionを実行しない。

独自暗号形式は暗号とintegrity formatを自作するため採らない。稼働中 volume のfilesystem copyはdatabase整合性を保証しない。PITR toolingは合意済みscopeを超える。

### 2. （ユーザー確認済み）integrity と retention evidence を分離する

処理順はsnapshot作成、candidate snapshot ID特定、`restic check`、`restic dump`を最後までdrainしながら`pg_restore --list`の結果も独立検証するcustom archive read、snapshotへの`integrity-checked` tag付与、tag operationが返す新しいauthoritative snapshot IDの再取得、last-known-good backup evidenceのatomic publish、固定`fukurou-postgres`かつ`integrity-checked`のAND tag predicate・host・pathだけを対象とする`forget --keep-daily 14 --prune`とする。restic CLIではAND tagsを単一のcomma-separated `--tag fukurou-postgres,integrity-checked`で指定し、複数`--tag`のOR semanticsを使わない。archive consumerは`pg_restore --list`終了後も同じstdinを`cat`でEOFまでdrainし、consumerのlist statusとproducerのfull-stream statusを別々に検証する。checkまたはarchive検証が失敗・中断した場合はdestructive retentionを実行しない。retention失敗はcurrent attemptを失敗にする一方、repository構造とarchive構造を確認したnew authoritative snapshotのidentity/freshnessは保持する。「recoverable」は週次actual restore成功後のsnapshotだけに使い、日次evidenceは`integrityChecked`と呼ぶ。

各snapshotは作成時にattempt tag、検証成功後に`integrity-checked` tagを持つ。tag付与がsnapshot IDを変えることをcontract testで固定し、statusとrestoreはtag後のIDだけをauthorityにする。中断した過去attempt snapshotは検証済みcandidateとpartial candidateを区別できないため自動forgetしない。retention groupから除外し、non-secret countをstatusへ記録してrunbookのfull-stream検証後manual forget/pruneへ送る。current attempt内でproducer failureとexact IDを同時に確認できた場合だけ自動forgetする。

「14日間」ではなく restic calendar bucket の newest 14 daily snapshots をauthorityとする。attempt resultとlast successを分離し、recoverabilityとhousekeeping failureを同時に表現する。

### 3. （agent 仮決め）start-time contentionとdeploy優先の上限を定義する

backupとrestore drillは `/var/lock/fukurou-backup.lock` をnon-blockingで共有し、job全体を直列化する。shared lock取得後にproduction deploy lockをnon-blocking probeし、busyならDB、repository、Docker操作前にstable `DEPLOY_IN_PROGRESS` で失敗する。lock順はbackup lock、deploy probeで固定する。

probe後にdeployが開始するraceは残るため、完全相互排他、deploy待機、deploy transaction内のpre-backup gateは主張しない。ただしproduction DBへACCESS SHAREを持つ`pg_dump`は60秒で必ず終了させ、deployのDDL/rollbackをunboundedに待たせない。testはdeploy-lock busy時のmutationゼロ、dump timeout時の部分snapshot隔離、DB接触が60秒上限を越えないことを証明する。このraceを完全に閉じるroot deploy executorのshared DB-ops lockは別changeとする。

### 4. （ユーザー確認済み）exact verified snapshotをdisposable PostgreSQL 16へrestoreする

restore drillはstatusのlast integrity-checked snapshot IDを明示選択し、`restic dump` stdoutを`pg_restore --no-owner --no-acl --exit-on-error --single-transaction` stdinへstreamする。production owner/ACLはcode-owned deployment bootstrapが再構築するauthorityであり、このdrillはdata/schema recoveryを検証してrole/privilege recoveryを主張しない。将来別途承認されたproduction replacementでは、application起動前にdeploy foundationと`mcp-role.sql`を再適用し、PUBLIC revokeとMCP privilegeを検証することをrunbookの必須境界にする。生成prefix/labelを持つ専用container、internal network、volume、別credentialを使い、production name、network、volume、credential、host portを共有しない。CPU、memory、PID、overall timeoutをboundedにする。

restore後はapplication/bootstrapを起動せず、欠落schemaを自動作成して隠さない。versioned code-owned manifestは名前を列挙し、contract testが各entryを現行schema authorityへ結び付けて重複・driftを検出する。固定のtable数を設計へ転記しない。drillはmanifestの全table/view/sequence、unvalidated constraintが0であること、critical-table manifestのprimary key、`BEGIN READ ONLY`下のpaper account/runtime config/ledger lineage data invariantsを検証する。ここでread-onlyはquery transactionの非破壊性を意味し、MCP role/ACL検証を意味しない。legacy cohortのnullable lineageをcurrent writeと誤認してrejectしない。

successは全検証とcontainer/network/volume/temp cleanupの完了を必要とする。global Docker pruneは行わず、own label/prefixだけをcontainer、network、volumeの順で削除し、cleanup後のinventoryが空であることを確認する。signal cleanup開始後は追加のHUP/INT/TERMをmaskし、systemdのstop timeoutは全cleanup commandのworst-case boundより長く保つ。次のdrillはresource作成前にrestore label全体を列挙し、前回のSIGKILLや電源断で残ったresourceが1件でもあれば`RESTORE_CLEANUP_FAILED`で停止する。残留volumeは暗号化されていないproduction data copyなのでdata-at-rest incidentとしてmanual reclaimと記録を要求する。

### 5. （agent 仮決め）status schema v1はattemptとlast-known-goodを分ける

`backup-status.json` は `schemaVersion: 1`、`updatedAt`、backup/restoreそれぞれの`lastAttempt`とnullableな`lastSuccess`を持つ。attemptはsystemd起動時だけ`INVOCATION_ID`とkernel boot IDをdurable evidenceとして記録し、systemd外の実行では両方をnullにする。result codeはallowlist、timestampはUTC、snapshot ID/source revision/count/durationだけを公開する。repositoryを読めずinterrupted candidate countを証明できないattemptはcountを`null`として0件と区別する。credential、child stderr、raw SQL、dump fragment、repository/password path、Docker inspect dumpを禁止する。

同一directoryの0600 temp fileへ書き、JSON validation、file sync、atomic rename、parent directory syncの順でpublishする。directoryはroot:root 0700、statusはroot:root 0600をinstalled contractとする。malformed/unsupported previous statusでは自動mergeとrestoreをfail closedにするが、runbookはcorrupt documentのroot-only quarantine、repository evidence確認、schema v1再初期化を明示し、risk-reducing backupを恒久停止させない。後続PRがprojectionを設計するまでapplicationへmountしない。

### 6. （agent 仮決め）installed authorityとtimerはdefault disabledにする

repository scriptをsystemdから直接実行せず、reviewed exact revisionから `/usr/local/libexec/fukurou/` と `/usr/local/share/fukurou/` へroot-owned installする。installerはshared backup lockをnon-blockingで保持し、backup/restoreのserviceとtimerがすべてinactiveである場合だけartifactを置換する。artifact配置とdaemon reload後、fixed pathのroot:root 0400 markerへinstalled artifact aggregate SHA-256とinstall UTC時刻をatomic publishする。verificationはmarkerのowner/mode/schema/hashを再計算し、欠損・drift・不正をfail closedにする。serviceはroot oneshot、fixed `ExecStart`、restrictive umask/hardening、bounded timeoutを持つ。daily/weekly timerはpersistentかつrandomized delayを持つが、installだけではenableしない。timerはdaily/weeklyのattempt cadenceであり、lock/deploy/failureを越えた毎暦日のsuccessを保証しない。PR-4のalert導入まではoperatorがsystemd failureとroot-only statusを能動確認する。`github-runner` sudoersは変更しない。

### 7. （高リスク・要人間確認）root rolloutをPR approval後のhandoffにする

merge後、operatorはNASでrestic/systemdとpersistent install pathの利用可能性を確認する。installerがroot-owned backup/status/secret directoryを作成し、operatorがpasswordを作って別管理のrecovery copyを保管し、compressionを有効にしたrestic repository format v2を初期化する。reviewed exact HEADのcommands/profile/unitsをinstallし、DB size、backup filesystem free space、manual dump所要時間を測定する。初回dumpが60秒bound内に完了しなければtimerをenableせず、deployとのcoordination方式を別changeで設計する。rollout verificationは最新backup attemptの`SUCCESS`かつretention成功、最新restore attemptの`SUCCESS`、attempt/last-success間とbackup/restore間のexact snapshot ID一致に加え、両attemptの相異なるsystemd `INVOCATION_ID`と現在のkernel boot IDをstatus内のdurable evidenceとして要求する。各unitのcurrent-boot journalにある最新stable resultも`SUCCESS`かつ同じinvocation IDでなければならず、publication failure後に残った旧statusをrejectする。oneshot unitのgarbage collectionで消えうる`ExecMain*` runtime propertyをauthorityにしない。両attempt、status更新とfile evidenceはinstall marker後かつ24時間以内でなければならない。そのsnapshotが現在開いているrepository内のfixed host/pathとAND tagsに一意に存在し、bounded Docker inventoryでrestore ownership labelを持つcontainer/network/volumeが0件であることを確認した後だけtimersをenableする。NAS再起動またはartifact reinstall後はdrillをsystemctlで再実行し、status publication失敗後の古い`lastSuccess`、期限切れevidence、最新retention/cleanup失敗を成功へ読み替えない。secret値やdump内容は証跡に残さない。前提が満たせなければtimersをdisabledのままにする。

### 8. （ユーザー確認済み）production recoveryは明示operator actionのままにする

deploy rollbackはDB snapshotをreplayしない。runbookはsnapshot選定、isolated restore/evidence review、risk-increasing execution停止、別途明示承認という境界を記すが、本changeはproduction databaseへrestoreするcommandやoptionを持たない。

## Risks / Trade-offs

- [NAS故障でDBとbackupを同時に失う] → same-NAS限定を明記し、off-site/NAS-loss protectionを主張しない。
- [restic password喪失でrestore不能になる] → root-only passwordと別管理recovery copyをhuman rollout gateにする。
- [deployがlock probe直後に始まる] → 完全相互排他を主張せず、exact application-name/PID watchdogでDB lockをbounded解放し、child failureをsuccessに昇格しない。必要ならdeploy executor shared lockを別changeにする。
- [`restic check`だけではlogical restoreを証明できない] → archive list検証をdailyに追加し、weekly actual `pg_restore` とdata invariantsだけをrecoverability authorityにする。
- [repositoryがproduction filesystemを圧迫する] → `pg_dump --compress=0`でrestic dedupを有効にし、backup前にdatabase sizeに対するconfigurable free-space reserveをfail closedで検証する。初回rolloutで実測し、保証SLAは置かない。
- [日次full-stream検証がNAS I/Oを使う] → 最新snapshot全体のdecrypt/read costを受け入れ、bounded job timeoutと実測durationをstatusへ残す。これはactual restoreの代替とは主張しない。
- [backup/restore失敗がalertされない] → PR-3の明示的限界とし、operator checkをrunbookへ置く。自動alertはPR-4/PR-5で追加する。
- [cleanupの一部が失敗する] → drill全体をfailedにし、last verifiedを進めず、own resourceだけを再cleanup可能にする。
- [status publishが失敗する] → jobをfailedにし、atomic rename前のcomplete documentを維持する。

## Migration Plan

1. commands、profile、schema、fixtures、systemd templates、docsを実装し、OpenSpec strict validation、shell/static/integration/full quality、clean-context reviewを通す。
2. merge時点ではNAS automationはdisabledで、production behaviorは変わらない。
3. operatorがrestic、root directory/password/recovery copy/repositoryを準備し、reviewed artifactをinstallする。
4. first backupとexact snapshot restore drillをmanual実行し、status、owner/mode、cleanupを確認する。
5. evidence確認後だけdaily/weekly timersをenableする。
6. rollback時はtimersをdisableしてartifactを前版へ戻す。repository、snapshot、password、statusは削除しない。

## Open Questions

なし。PITR、off-site保管、完全なdeploy/backup相互排他が必要になった場合は、運用実測を根拠に別changeで設計する。
