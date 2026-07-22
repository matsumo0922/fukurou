## 1. deploy.yml 簡素化

- [ ] 1.1 `resolve` job を SHA 検証（push/workflow_dispatch の両方で main reachability を確認）に書き換える。`deploy-intent-resolver` の呼び出しを削除する
- [ ] 1.2 `resolve` job に、push イベント限定の軽量 descendant check（現在稼働中 revision の子孫であることの確認）を追加する。workflow_dispatch はこのチェックの対象外とする
- [ ] 1.3 `quality` job を削除する
- [ ] 1.4 `build` job から署名 bundle 生成（openssl 署名、capability catalog hash、contract version、operation 列）を削除し、image build/push + digest output に縮小する
- [ ] 1.5 `deploy` job から `--print-contract-version`/`--print-schema-sensitive-paths-sha256` 検証を削除し、build job が output した image digest を `deploy-fukurou` へ渡す
- [ ] 1.6 `workflow_dispatch` の `migration_rollback_mode` input を削除する

## 2. NAS executor（deploy-fukurou）書き直し

- [ ] 2.1 bundle 署名検証・typed operation set 検証・capability catalog 検証・schema-sensitive mode 検証・deploy intent 検証を削除する
- [ ] 2.2 revision monotonicity 検証・journal/recovery state machine・candidate preflight hook（`run_candidate_preflight`、`CLI_AUTH_PREFLIGHT_V1`/`FOUNDATION_PREFLIGHT_V1` 両hook）・canary compose/token 機構を削除する
- [ ] 2.3 CLI acceptance gate（`run_cli_acceptance_gate`）を削除する
- [ ] 2.4 image pull を digest 固定（`<image>@<digest>`）に変更する
- [ ] 2.5 production deploy lock 取得後・mutation 直前に、automatic push（push イベント由来の deploy）に限り authoritative な descendant check を実行する（resolve job の早期チェックは fast-fail の最適化に過ぎず、この executor 側チェックが正本。workflow_dispatch は対象外）
- [ ] 2.6 DB helper marker を、実行のたびに root 設置された実ファイル（`fukurou-deploy-db` + `scripts/deploy/sql/**/*.sql`、`LC_ALL=C` sort + `path\0sha256(content)\0` 連結 manifest の SHA-256）から再計算し、(a) install 時に記録した root marker、(b) 候補 image に embed された期待値の両方と照合するチェックを追加する（migration より前。不一致なら fail closed）
- [ ] 2.7 paused-state marker（deployment ID・target SHA・expected digest・maintenance generation・gap ID・phase）を launch disable の前に書き込み、launch disable → drain → OPEN gap event → migration、health成功後 → launch resume → CLOSE gap event（同一 gap ID 参照）→ marker clear、の順序で実装する。phase は各段階で更新し、永続化する。gap ID・maintenance generation は gap が CLOSE されるまでどの code path でも失われないようにする
- [ ] 2.8 executor 起動時に paused-state marker が `PAUSED_BEFORE_MIGRATION`/`MIGRATION_DONE`/`CUTOVER_STARTED` の場合は新規 deploy を拒否し、`--acknowledge-paused-state <deployment-id>` （exact deployment ID 一致必須）がない限り再開しないようにする。acknowledge は phase を `ACKNOWLEDGED_FOR_REDEPLOY` に遷移させるのみで、marker の clear・gap ID の破棄・launch resume は行わない
- [ ] 2.9 executor 起動時に paused-state marker が `ACKNOWLEDGED_FOR_REDEPLOY` の場合、新しい deploy 試行は既存の gap ID・maintenance generation を引き継ぎ（新規 gap を開かない）、marker の deployment ID・target SHA・expected digest だけを更新して phase を `PAUSED_BEFORE_MIGRATION`（または migration 済みなら `MIGRATION_DONE`）に戻し、launch disable/drain を再実行せず処理を続ける
- [ ] 2.10 引き継いだ gap ID で成功した deploy は、CLOSE gap event を元の gap ID で記録してから marker を clear する（インシデント全体の gap 期間が最初の pause から最終成功まで連続することを保証する）
- [ ] 2.11 executor 起動時に paused-state marker が `CUTOVER_HEALTHY_PENDING_CLOSE` の場合、稼働中コンテナの digest と health を再確認し、両方成功していれば migration/compose up を再実行せず launch resume + CLOSE gap 記録（marker の gap ID 参照）+ marker clear を冪等に行う。いずれか失敗していれば 2.8 のフォールバックに従う
- [ ] 2.12 executor 起動時に、drain-complete sentinel ファイルの存在と、旧 executor（contract v2）の active journal/rollback-state パスが空であることの両方を確認し、いずれかを満たさなければ新規 deploy を拒否して fail closed する（archive 済み terminal 履歴は検査対象に含めない）
- [ ] 2.13 migration 実行の直前に PostgreSQL backup（restic、`--invoked-by-deploy` 経由）呼び出しを追加する
- [ ] 2.14 `deploy_compose()` を中心とした pull → migration → compose up → health確認 → digest 照合 の直線フローに書き直す
- [ ] 2.15 sudoers 境界（`github-runner` は `deploy-fukurou` のみ sudo 可）に影響する変更がないことを確認する

## 3. fukurou-deploy-db / backup 連携

- [ ] 3.1 契約検証との連携部分（deploy-fukurou 側からの呼び出しインターフェース）が新 executor と整合することを確認する。`install-foundation`/`install-indexes` 等の操作ディスパッチ自体は変更しない
- [ ] 3.2 `fukurou-deploy-db`/SQL の root install 手順に、canonical manifest 生成（`LC_ALL=C` sort + `path\0sha256\0` 連結の SHA-256）と atomic な marker 記録を追加する。build 時に candidate image へ同じアルゴリズムで計算した期待値を embed する
- [ ] 3.3 `scripts/backup/backup-fukurou` に `--invoked-by-deploy` フラグを追加し、deploy lock 保持中の呼び出しで `backup-common` の lock チェックをスキップできるようにする。backup の中核ロジック（snapshot 取得・保持ポリシー）は変更しない

## 4. selftest / validation 削減

- [ ] 4.1 `scripts/deploy/deploy-contract-selftest` を削除する
- [ ] 4.2 `scripts/deploy/deploy-e2e-selftest` を削除する
- [ ] 4.3 `scripts/deploy/deploy-runtime-selftest` を削除する
- [ ] 4.4 `scripts/deploy/deploy-intent-resolver` と `scripts/deploy/deploy-intent-resolver-selftest` を削除する
- [ ] 4.5 `scripts/deploy/canary-compose-selftest` を削除する
- [ ] 4.6 `scripts/deploy/deploy-bundle.schema.json`・`deploy-capability-catalog-v1.json`・`deploy-contract-v1.json`・`deploy-public-key.pem`・`deploy-schema-sensitive-paths-v1.txt` を削除する
- [ ] 4.7 `.github/workflows/deploy-validation.yml` を新 executor の `bash -n` + `deploy-db-selftest`/`deploy-postgres-selftest` + Kotlin contract test 実行に縮小する

## 5. contract test 更新

- [ ] 5.1 `ReleaseDeployFoundationContractTest.kt` から撤去済み機構（署名検証、capability catalog、CLI acceptance 実行順序 assert 等）を検証しているテストを削除する
- [ ] 5.2 残るテストが新 executor の構造（pull→migration→compose up→health確認の順序、sudoers境界）を検証する内容になっているか確認し、必要なら加筆する

## 6. ドキュメント

- [ ] 6.1 `docs/deploy.md` の「Pinned CLI acceptance qualification」セクションを削除する
- [ ] 6.2 「release / deploy safety foundation」セクションを新構成（署名なし、contract なし、schema-sensitive ゲートなし、FOUNDATION_PREFLIGHT_V1 なし）に書き直す
- [ ] 6.3 「Rollback」セクションを「過去 SHA を workflow_dispatch で指定して再デプロイする。DB は復元されない」運用に書き直す
- [ ] 6.4 schema migration 事故時の復旧手順（forward fix / 手動 restic restore）を新設する
- [ ] 6.5 「本 PR merge 前」チェックリストを新設する: 旧 workflow で通常 deploy を最低1回実行し、旧 executor の journal drain・maintenance/fence/gap state 正常化を確認する。確認後、terminal 化した journal/rollback-state 履歴を監査用ディレクトリへ archive し、active パスを空にした上で drain-complete sentinel ファイルを作成してから本 PR を merge する手順を明記する（merge 後は旧 executor が新 bundle 形式を parse できず、通常経路での drain 確認ができなくなるため）
- [ ] 6.6 「本 PR merge 後・NAS 配置時」チェックリストを新設する: 新 executor と DB helper/SQL（canonical marker 込み）を同一 SHA から同時配置する手順を明記する

## 7. 検証

- [ ] 7.1 `make test` / `make detekt` を実行する
- [ ] 7.2 新 `deploy-fukurou` の `bash -n` 構文チェックを実行する
- [ ] 7.3 `deploy-db-selftest`/`deploy-postgres-selftest` を実行する
- [ ] 7.4 `ReleaseDeployFoundationContractTest` を実行する
