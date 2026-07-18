## Context

PR-1 は exact target の JVM quality gate を追加したが、historical manual target は緊急復旧経路を維持するため暫定的に quality を bypass している。root executor は target が main から到達可能であることだけを確認し、production deploy lock を待つ間により新しい revision が稼働しても古い target を適用できる。また signed bundle は通常 deploy と operator-authorized rollback、DB schema 変更後に旧 image を起動できるかを区別しない。

既存 executor は signed typed bundle、immutable digest、rollback snapshot、maintenance/fence、hash-chained journal、post-deploy revision/readiness check、durable recovery を持つ。本変更は別 deploy path を作らず、この transaction の mutation 前 admission と recovery policyを拡張する。root-owned executor を変更するため、repository merge だけでは rollout できない。

## Goals / Non-Goals

**Goals:**

- lock 取得時点の稼働 revision を authority とし、通常 deploy の revision 後退を mutation 前に拒否する。
- historical target を manual dispatch、空でない理由、signed `AUTHORIZED_ROLLBACK` に限定する。
- current-to-target diff の schema-sensitive path を root 側で再判定し、migration rollback mode を fail closed にする。
- `ROLL_FORWARD_ONLY` failure で旧 image を自動起動せず、既存 durable safe boundary と journal を維持する。
- bundle schema v2 と executor contract v2 を staged rollout し、v1 journal/recovery state の読取を維持する。

**Non-Goals:**

- backup、restore、monitoring、alerting。
- DB schema の自動 rollback、destructive migration、history rewrite。
- main 外 commit の deploy、署名なしの緊急 bypass、root executor 以外からの production mutation。
- migration の意味的互換性を自動証明すること。明示 mode と理由は operator review の監査証跡であり、正しさの証明ではない。

## Decisions

### 1. Bundle schema v2 が event、intent、reason、migration mode、path inventory hash を署名する

新 workflow は `bundleSchemaVersion: 2`、`workflowEvent`、`deployIntent`、`operatorReason`、`migrationRollbackMode`、`schemaSensitivePathsSha256` を canonical JSON に含め、`minimumContractVersion: 2` を要求する。値域は schema と executor の両方で閉じる。

- automatic `push`: `FORWARD`、空 reason、`AUTO_IMAGE_ROLLBACK`
- latest-main manual dispatch: `FORWARD`
- historical manual dispatch: `AUTHORIZED_ROLLBACK` と空白除去後に非空の reason
- explicit `BACKWARD_COMPATIBLE` / `ROLL_FORWARD_ONLY`: manual dispatch と非空 reason が必須

workflow input だけを authority にする案は採らない。event と target の関係から workflow が intent を導出し、root executor が observed current revision との関係を再検証する。

### 2. Monotonicity は production lock 内、unfinished recovery 後、candidate mutation 前に判定する

executor は signed bundle 検証後に production lock を取得し、既存 unfinished deployment を先に recovery する。その後、candidate image pull、rollback capture、maintenance、fence、DB、compose mutation より前に次を行う。

1. `origin/main` を fetch し、target object と main 到達可能性を確認する。
2. `fukurou-ktor` の immutable `FUKUROU_REVISION` を observed current として読む。container が存在するのに revision が空・不正なら拒否する。
3. container が存在しない場合は read-only DB snapshot が `PRE_FOUNDATION` で、`STATE_ROOT` に published deployment directory が1件もない場合だけ fresh install とする。rootが管理するdeploy historyをbootstrap markerとし、可変なrepository HEADはfreshの証拠に使わない。
4. `FORWARD` は current == target または current が target の ancestor の場合だけ許可する。
5. `AUTHORIZED_ROLLBACK` は target が current の strict ancestor の場合だけ許可する。同一 SHA、divergent history、current より新しい target は拒否する。

workflow の queue 前確認だけで済ませる案は TOCTOU を残すため採らない。HTTP `/revision` だけを authority にする案も、停止中・unready container の安全な判定ができないため採らず、container config の build-time revisionを使う。

### 3. Schema-sensitive path inventory は repository と root-owned installed copyを hash で束縛する

`scripts/deploy/deploy-schema-sensitive-paths-v1.txt` を1行1 git pathspecの正本とする。初期 inventory は production DB bootstrap/migration SQL、deploy DB helper、application/trading persistence schema ownerを保守的に含める。

workflow は push の before-to-target diff に inventory が含まれる場合、automatic deploy を build 前に block し、manual compatibility reviewを要求する。`before` が40桁SHAでない、zero SHA、取得不能、targetのancestorでない場合と、`workflow_dispatch`では早期分類を行わずroot authorityへ委譲する。早期分類はbuild費用を避ける最適化でありadmission authorityではない。root executor は observed current-to-target diff を同じ inventory で必ず再分類する。bundle 内の inventory hash、repository artifact、root-owned installed copy の hash が一致しない場合は拒否する。これにより queued/skipped deployで workflow の comparison base が実際の current とずれても root 側で漏れを閉じる。

path listを workflow と shell functionへ二重記述する案は drift を生むため採らない。全 Kotlin diff を schema-sensitive とする案は過検知が大きすぎるため採らない。

### 4. Migration rollback mode は actual current-to-target diff と recovery の両方を制御する

- diff が schema-sensitive でない場合、`AUTO_IMAGE_ROLLBACK` を許可し、既存 automatic image rollback を使う。
- diff が schema-sensitive の場合、`AUTO_IMAGE_ROLLBACK` を拒否し、manual dispatch が署名した `BACKWARD_COMPATIBLE` または `ROLL_FORWARD_ONLY` と理由を要求する。
- `BACKWARD_COMPATIBLE` は既存 durable rollbackを使う。
- `ROLL_FORWARD_ONLY` が `PREPARED` / `CAPTURED` の safety mutation 前に失敗した場合、running production、repository checkout、maintenance/fenceを変更せず、candidateを破棄して新terminal `CANDIDATE_ABORTED` を記録する。存在しないsafe boundaryを新設しない。
- `ROLL_FORWARD_ONLY` が `SAFETY_MUTATION_STARTED` 以降に失敗した場合だけ、previous image/repositoryを復元せず、既存 recovery primitive で maintenance/fenceとOPEN gapを再確立する。safe boundaryとcanary revokeの両方を証明できた場合だけ`MANUAL_RECOVERY_REQUIRED` terminalを記録し、証明不能なら`RECOVERY_STARTED`を残して次回起動で再試行する。DB restoreは実行しない。

live errorの`rollback_on_error`とstartupの`recover_unfinished_deployments`は、どちらもstate.jsonのsigned migration modeとrecovery origin stateから同じpolicyを選ぶ。`CANDIDATE_ABORTED`をterminal/valid journal transitionとして追加し、restart後にprevious checkoutやimage起動へ流れないようにする。

非 schema-sensitive diff に明示的なより保守的 modeを許す案は、operator inputの意味を曖昧にするため採らない。actual diff と mode の完全対応を要求する。

### 5. Accepted intent evidence は rollback state と journal の最初の entry に保存する

accepted current/target、intent、redacted operator reason、migration mode、inventory hash、schema-sensitive判定、probe済みcandidate image digestを root-only `state.json` と `PREPARED` journal details に保存する。v2 recoveryはstateと最初のjournal detailsを完全照合し、installed inventory hashとも一致した場合だけmodeを採用する。中断deploy自身の保存済みdigestだけをfence recovery codecとして使い、次にqueueされた未admission bundleのdigestを流用しない。reason は長さを制限し、制御文字を拒否し、secret patternを受け付けない。mutation前 rejection は state directoryを作らず、stable reason codeと current/targetだけを stderrへ出す。

rollback composeは可変なrepository HEADからcopyせず、accepted current revisionのGit objectからsnapshotする。target revisionのcomposeはmutation前に一時fileへsnapshotしてrender可能性を検証する。safety mutation後のcandidate preflightはcheckoutを変更せず、現在のproduction composeとdeny overlayへcandidate digestを束縛し、成功後にtargetをcheckoutしてproduction composeを切り替える。これによりRFO failureがcandidate checkout後に止まりrepository HEADとrunning revisionが分離しても、次のroll-forwardが誤ったcompose/revision pairをrollback evidenceへ保存しない。

current SHA を workflow bundleへ入れる案は、GitHub-hosted runner が production currentを観測できず偽の authorityになるため採らない。observed current は root executor が署名済み intentと組み合わせて記録する。

### 6. Contract v2 は root executor pre-installを merge gateにする

executor は contract v2を返し、新 workflow は `--print-contract-version == 2` を要求する。v1 bundle/journal validatorは既存 unfinished recoveryを読むため残すが、v2 workflowだけが新 deployを発行する。

PR merge 前に operator がproduction checkoutを変更しない一時detached worktreeから、PR HEAD の executor と schema-sensitive inventoryをroot-owned pathへ一組で installし、contract/selftestを確認する。pre-install後から mergeまで旧 main workflowはcontract/hash mismatchでfail closedするため、短い controlled deploy freezeとする。このfreezeではproduction deployだけでなく、executor、public key、foundation harness/SQL、capability catalog、schema-sensitive inventoryへ触れる他PRのmergeも止める。pre-install後にreview HEADとmerge予定treeの対象file hashを再照合する。PR はこの手動作業前に mergeしない。

v2 rollout後はroot executorをv1へdowngradeしない。v1 binaryはv2の`ROLL_FORWARD_ONLY`と`CANDIDATE_ABORTED` journal transitionを理解できず、terminal済みv2 audit historyが存在するだけで後続deployを`RECOVERY_JOURNAL_CORRUPT`として止めうる。application/workflowを前版へ戻す場合も、root deploy foundationはv1 journalを読めるv2 executorのまま維持する。root executor downgradeが必要なら、v2 audit historyを保持したまま読む専用互換設計を別changeで用意する。

### 7. Authorized rollbackにもquality bypassを設けない

すべてのtargetはexact SHAで`make test`と`make detekt`を通す。既知goodなhistorical imageでも、現在のCI環境でqualityが失敗する場合は`AUTHORIZED_ROLLBACK`を発行できない。これは復旧可用性より、未検証artifactをproductionへ通さないfail-closedを優先する明示的trade-offである。本projectはpaper tradingであり、quality bypassを緊急権限として追加しない。失敗時はflaky/tooling failureを修復するか、qualityを満たすforward fixをdeployする。

## Risks / Trade-offs

- [inventory漏れで schema-sensitive diff を AUTO と誤分類する] → code-owned owner pathを保守的に列挙し、workflow/rootの両方を同じhash固定artifactから読むcontract testを置く。
- [queued workflow の comparison base が production current と異なる] → workflow判定は早期停止用に限定し、root lock内のactual current-to-target diffをauthorityにする。
- [container欠損を fresh install と誤認する] → `PRE_FOUNDATION` snapshotとpublished deployment directory 0件の両方を要求し、可変なrepo HEADを証拠にせず、既存installのcontainer欠損はunknownとして拒否する。
- [`ROLL_FORWARD_ONLY` failureでproductionが停止したままになる] → 意図したfail-closedとしてmaintenance/fence/journalを保持し、signed targetへのroll-forwardまたはoperator recoveryを要求する。
- [v2 recovery evidence欠損をlegacy AUTOとして扱う] → version/evidence/journal/inventoryを完全照合し、legacy fallbackはstateVersion/acceptedDeployなしのv1 journalだけへ限定する。
- [RFO後のrepository HEADとrunning revisionが分離する] → accepted current/targetのGit objectからrollback/candidate composeをそれぞれsnapshotする。
- [reasonにsecretや制御文字が入る] → bounded printable UTF-8、空白trim、known secret pattern拒否をworkflow/executorで行い、journalには検証済み文字列だけを保存する。
- [executor pre-install中にdeployが走る] → production deploy concurrencyを確認し、controlled freeze中はv1 workflowがcontract/hash mismatchでmutation前に停止する。
- [historical targetのqualityが現在のCIで失敗しrollback不能になる] → bypassしないことを受容し、test/tooling修復またはqualityを満たすforward fixを復旧経路にする。
- [inventoryに新しいschema owner pathが未収載になる] → hash一致testは網羅性を証明しないと明記し、persistence/migration ownerの追加・移動時に同じPRでinventoryを更新するreview checklistをdocsへ置く。

## Migration Plan

1. PR HEADでbundle v2、executor contract v2、inventory、selftests、docsを完成させ、Opus approvalとCI greenを得る。PRは未mergeで止める。
2. operatorが進行中deployとunfinished stateがないことを確認し、rootでPR HEADのexecutorとinventoryをinstallする。installed contract v2、file owner/mode、contract/runtime/E2E selftest、root artifact hash一式を確認し、対象artifactへ触れる他mergeをfreezeする。
3. PRをmergeする。main push workflowがquality、bundle v2 build、root intent admission、normal forward deployを成功させることを確認する。
4. manual fixtureでqueued old SHA rejection、reasonなしrollback rejection、authorized rollback、schema-sensitive mode mismatch、`ROLL_FORWARD_ONLY` manual recovery terminalを確認する。
5. application/workflowをrollbackする場合は新workflowを止め、v2 executorでv1/v2 journal recoveryをterminalへ完了する。root executorとv2 audit historyは戻さず、前版applicationをdeployするためのv2 bundle/workflow互換を維持する。DB historyは戻さない。

## Open Questions

なし。productionでのroot pre-installとPR mergeは、PR-2 approval後にoperatorと別ターンで実行する。
