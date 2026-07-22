## Context

`deploy-fukurou`（1856行）は署名検証・typed operation set・capability catalog・schema-sensitive diff・revision monotonicity・journal ベースの recovery state machine・candidate preflight hook・CLI acceptance gate が密に絡み合った executor で、これらはすべて `openspec/specs/deploy-quality-gate`・`openspec/specs/deploy-revision-safety`・`openspec/specs/pinned-cli-acceptance-canary` の 3 spec に分散して定義されている。schema-sensitive ゲートは 7/21 以降 main の自動デプロイを全件拒否しており、owner は「main マージ = 承認」という単純なモデルへの回帰を求めている。

**改訂履歴**: 初版の設計を独立 falsifier（clean context）に反証させたところ、9件の blocking 反例が見つかった。うち4件（DB rollback方針・FOUNDATION_PREFLIGHT_V1存置可否・main branch protection・unfinished journalの扱い）は owner 確認を経て解決した。残り5件（backup/deploy lock競合・automatic push の順序レース・launch maintenance/drain欠落・root設置DB helperとの整合・immutable image digest欠落）は設計修正で閉じた。本版はその反映版

## Goals / Non-Goals

**Goals:**
- deploy.yml を resolve（SHA 検証のみ）→ build → deploy の 3 job に戻す
- NAS executor を pull → migration → compose up → health確認の直線的な流れに書き直す
- schema migration の自動適用（migration 前 backup 1 ステップのみ追加）
- sudoers 境界（`github-runner` は `deploy-fukurou` のみ sudo 可）を維持する
- automatic push に限り、最小限の revision 逆行防止を残す（F-2 対応）
- migration・compose cutover 中の launch maintenance/drain を維持する（F-5 対応）
- image は digest 固定で pull・検証する（F-8 対応）

**Non-Goals:**
- PostgreSQL backup/restore の中核ロジック（`scripts/backup/`）の変更。ただし deploy executor が保持する deploy lock 内から安全に呼び出せるようにする最小限のフラグ追加は行う（F-1 対応、下記 Decisions 参照）
- self-hosted runner の構成変更、Cloudflare Tunnel/Access の変更
- Dockerfile launcher/supervisor 撤去（S1 = #288 の範囲）
- 署名・schema-sensitive 分類・journal に基づく**自動** rollback 機構の新設（過去 SHA 再デプロイという手動運用で足りる）
- `scripts/mcp-credential-isolation-check` の qualification 機能自体の変更（deploy からの呼び出しだけを止める。1-run smoke / 3-run merge qualification という手動ツールとしての機能は残す）
- 互換性のない schema migration からの自動 DB rollback（owner 確認済み。下記 Decisions 参照）
- fresh install 以外の historical/legacy な revision 互換性維持（simple executor は旧 v1/v2 journal フォーマットを読まない。ただし切替時の一度限りの前提条件は Migration Plan で扱う）

## Decisions

### owner 確認済みの価値判断

- **CLI acceptance preflight は撤去する（owner 確認済み）**: `run_cli_acceptance_gate()` と `CLI_AUTH_PREFLIGHT_V1` hook 経路の両方を削除し、`pinned-cli-acceptance-canary` spec の該当 Requirement のみ REMOVED とする
- **FOUNDATION_PREFLIGHT_V1（secret/MCP 隔離の事前検証）も撤去する（owner 確認済み）**: 初版では「capability catalog 撤去の論理的帰結」という agent 仮決めだったが、falsifier（F-6）指摘を受けて owner に個別確認した。candidate preflight hook 機構全体（catalog が定義する 5 hook すべて）を削除する
- **schema migration 後の past-SHA redeploy は DB を戻さないことを受け入れる（owner 確認済み、F-3 対応）**: 自動的な互換性チェックや DB rollback 機構は持たない。「schema-sensitive な変更を含む migration 後は、past-SHA 再デプロイでの復旧を保証しない。事故時は forward fix で直すか、migration 前 backup から手動で restic restore する」と `docs/deploy.md` に明記する。`deploy-pipeline-baseline` capability にこの制約を Scenario として明文化する（未検証のまま黙って残さない）
- **main branch protection を有効化する（owner 確認済み、F-9 対応）**: Stage 1 の PR CI（`pr-quality-gate`）job を required status check として設定し、PR 経由を必須にする。実際の有効化は GitHub 設定変更（`gh api` 経由）であり、コード diff ではなく Stage 1 の実装タスクとして扱う。exact check name は Stage 1 workflow の job 名から取る

### 設計修正で閉じた反例

- **backup と deploy lock の競合を解消する（F-1 対応）**: 既存の `scripts/backup/backup-common` は `/var/lock/fukurou-deploy.lock` を non-blocking で取得しようとし、deploy executor が保持中だと `DEPLOY_IN_PROGRESS` で失敗する。`scripts/backup/backup-fukurou` に `--invoked-by-deploy` フラグを追加し、このフラグ付き実行では deploy lock 取得チェックをスキップする（呼び出し元が既に lock を保持していることが前提のため、二重取得は不要かつ有害）。backup の中核ロジック（snapshot 取得・保持ポリシー）自体は変更しない
- **automatic push の revision 逆行防止は NAS executor 側が正本（F-2 対応、再反証で修正）**: `AUTHORIZED_ROLLBACK` のような intent 区分・署名は持たない。`resolve` job の descendant check は build 前に実行されるため、build 中に別の deploy が production revision を進める TOCTOU レースを検知できず、正本にはなり得ない。**正本の検証は NAS executor が production deploy lock を取得した直後・migration/compose mutation の直前に行う**（イベントが `push` の場合のみ、対象 SHA が「lock 取得時点で」production 稼働中の revision の子孫であることを確認する）。`resolve` job 側の同等チェックは build 前の fast-fail 最適化として残してよいが、権威を持たない。`workflow_dispatch`（past-SHA rollback を含む手動操作）はどちらのチェックの対象外とする
- **migration・compose cutover 中は launch を disable/drain する（F-5 対応）**: 完全な journal state machine は持たないが、`disable_launches`/`resume_launches` 相当（LLM decision loop の一時停止、in-flight process の drain、gap event 記録）は残す。ただし単なる gap 記録だけでは中断・再起動時の安全な再開を保証できないため、durable な paused-state marker に deployment ID・target SHA・expected image digest・maintenance generation・gap ID・phase を記録する（詳細は `deploy-pipeline-baseline` capability の該当 Requirement を参照）
- **root 設置の DB helper/SQL とのバージョン整合を確認する（F-7 対応、再反証で精緻化）**: `fukurou-deploy-db` と migration SQL（`scripts/deploy/sql/**/*.sql`）は NAS に root 権限で事前配置される。version marker は保存された値同士の比較ではなく、**executor が deploy のたびに root 設置された実ファイルから再計算した digest**を使う（partial install や配置後の改変を検出するため）。生成手順: `LC_ALL=C` で対象ファイルの相対パスを bytewise sort し、各ファイルについて `path + NUL + sha256(content) + NUL` を連結した manifest を作り、その manifest 全体の SHA-256 を marker とする。executor はこの再計算した digest を (a) install 時に記録された root marker、(b) candidate image に embed された期待値の両方と照合し、いずれかが不一致なら fail closed する
- **image は digest 固定で pull・検証する（F-8 対応）**: build job が push した image の digest を output し、deploy job・executor に引き渡す。executor は `image@sha256:...` の形式で pull し、`docker compose up` 後に稼働中コンテナの digest が期待値と一致することを確認する。tag 上書きによる取り違えを防ぐ

## Migration Plan

再反証（F-4 PARTIAL 指摘）を受けて、切替順序を「journal drain 確認を **Stage 2 PR merge の前** に、旧 workflow/旧 executor がまだ両方動いている状態で完了させる」形に修正した。merge 後は旧 executor が新 bundle 形式を parse できず recovery を起動する通常経路自体が失われるため、merge 後に drain 確認をしても手遅れになる。

1. **Stage 1（PR CI + main branch protection）を先に merge・有効化する**
2. **Stage 2 PR を merge する前に、旧 workflow（現行 `deploy.yml`）で最低 1 回、通常の deploy を実行し、旧 executor の `recover_unfinished_deployments` が起動して journal が non-terminal のまま残っていないことを確認する。** これは既存の署名・contract 検証込みの通常経路であり、新規の recovery-only entrypoint を作る必要はない。確認項目: (a) journal が全て terminal 状態であること、(b) maintenance/fence state が正常（OPEN gap が残っていない）こと
3. **drain 確認後、旧 executor 自身の操作として、terminal 化した journal/rollback-state の履歴を監査用の別ディレクトリ（例: `/var/lib/fukurou/deploy-legacy-archive/`）へ移動し、旧 executor が参照する「active」パスを空にする。空になったことを確認したら、drain-complete sentinel ファイル（例: `/var/lib/fukurou/deploy/.legacy-drain-confirmed`、確認日時と旧 executor の最終 revision を含む）を作成する。** 再反証（F-4 PARTIAL 指摘）で、正常終了した journal も含めて「1件でもファイルがあれば拒否」する設計だと新 executor が常時起動不能になる欠陥が指摘されたため、新 executor は archive 済み履歴ではなく sentinel の有無と active パスの空判定だけを見る設計にした
4. **journal drain 確認・archive・sentinel 作成が完了してから Stage 2 PR を merge する**。merge 直後は NAS 側にまだ旧 executor が残っているため、新 workflow が渡す引数（署名なし・digest 固定）を旧 executor が parse できず fail closed する（安全側だが、次の NAS 配置までデプロイは失敗し続ける）
5. **NAS 側への新 executor・DB helper/SQL の同時配置（root 権限、owner 手作業）を行う**。配置後の次の merge からデプロイが正常に機能する
6. **defense-in-depth（F-4 対応）**: 新 executor は起動時に (a) drain-complete sentinel が存在すること、(b) 旧 executor の active journal/rollback-state パスが空であることの両方を確認し、いずれかを満たさなければ新規 deploy を拒否して fail closed する（自動 recovery はしない。archive 済み監査履歴は検査対象に含めないため、正常終了履歴を誤検知しない）。これにより「うっかり drain 確認・sentinel 作成を飛ばして切り替えた」場合でも、unfinished 状態を無視して migration/compose mutation が走ることを防ぐ

NAS 側への新 executor（および DB helper/SQL の同時配置）は owner の手作業（root 権限）。本 PR は `docs/deploy.md` に配置手順とチェックリスト（上記2〜5）を記載するのみで、実際の確認・配置・切り替えは owner が行う。

rollback: 新パイプラインへの切替後に問題が発覚した場合、旧 `deploy.yml`/`deploy-fukurou` に戻すには git revert + NAS 側の旧 executor 再配置（owner 手作業）が必要。自動ロールバック機構は持たない

## Risks / Trade-offs

- [Risk] schema-sensitive migration 後の past-SHA redeploy は DB を戻さない → Mitigation: owner 確認済みの受容リスク。`docs/deploy.md` に forward-fix / 手動 restic restore の復旧手順を明記する
- [Risk] FOUNDATION_PREFLIGHT_V1 撤去により、candidate image の secret/MCP 隔離回帰が本番投入前に検知されなくなる → Mitigation: owner 確認済みの受容リスク。`docker-compose.prod.yml` の権限設定と `make test` の該当テストが最後の防波堤になる
- [Risk] backup lock フラグ（`--invoked-by-deploy`）の追加により、deploy 以外の経路から誤ってこのフラグを使うと lock 保護なしで backup が走る可能性 → Mitigation: フラグは deploy-fukurou からの呼び出し1箇所のみで使用し、他のドキュメント・運用手順には露出させない
- [Risk] resolve job の descendant check だけに頼ると、build 中に別 push が production revision を進める TOCTOU レースを見逃す → Mitigation: NAS executor が lock 取得後・mutation 直前に権威ある descendant check を行う（F-2 対応）。resolve job のチェックは fast-fail 最適化に過ぎない
- [Risk] NAS 側 executor の配置が owner 手作業のため、journal drain 確認・archive・sentinel 作成を怠ると F-4 の状況が再発する → Mitigation: sentinel 方式により、confirmation を怠った場合は新 executor が起動時に自動検知して fail closed する（`docs/deploy.md` のチェックリストと合わせた二重の防御）
