## Why

Issue #292（Epic #286）。デプロイパイプラインは 2026-07-11 時点で 115 行の Build → Deploy だったが、6 日間で署名つき bundle、executor contract、schema-sensitive 手動承認ゲート、JVM quality ゲート、monotonic deploy intent、CLI acceptance preflight が積み上がり、workflow 455 行 + NAS 側 executor 90KB + selftest 群にまで肥大化した。1 回のデプロイに約 23 分かかり、7/21 以降は schema-sensitive ゲートが main の自動デプロイを全件拒否している（`SCHEMA_SENSITIVE_MODE_MISMATCH`）。owner の要求は「main にマージされた時点でデプロイしてよい。手動承認ゲートは撤廃する」であり、マージ権限を持つ owner のマージ判断そのものが承認である。

本 change は Stage 1（#add-pr-ci-workflow、PR 時の `make test`/`make detekt` 新設）の後続として、deploy.yml と NAS 側 executor を Build → Deploy の素直な構成に戻す。

## What Changes

- `deploy.yml` を `resolve`（SHA 検証のみ）→ `build` → `deploy` の 3 job に簡素化する。撤去: JVM quality job（Stage 1 の PR CI に代替済み）、schema-sensitive ゲート、deploy intent（FORWARD/AUTHORIZED_ROLLBACK 区分と reason 検証）、bundle 署名（openssl 署名生成/検証）、contract version・capability catalog・operation 列
- **BREAKING**: `deploy-fukurou`（NAS 側 executor）を「image pull → migration 実行（`fukurou-deploy-db` 相当）→ compose up → health 確認」を中心に据えたスクリプトに書き直す。撤去: bundle 署名検証、typed operation set 検証、capability catalog 検証、schema-sensitive mode 検証、deploy intent 検証、revision monotonicity 検証、rollback journal/recovery state machine、contract v2 pre-install ゲート、CLI acceptance preflight（`run_cli_acceptance_gate` および `CLI_AUTH_PREFLIGHT_V1` hook 経路の両方）
- **owner 確認済み**: capability catalog 撤去の論理的帰結として、catalog が定義する candidate preflight hook 機構（`run_candidate_preflight()`）自体を削除する。これは `CLI_AUTH_PREFLIGHT_V1` だけでなく `FOUNDATION_PREFLIGHT_V1`（secret/MCP 隔離の事前検証）hook も含む。独立 falsifier 指摘 F-6 を受けて owner に個別確認済み。hook 実行が使っていた canary compose/token 機構（`write_canary_compose` 等）も付随して削除する
- schema migration は手動承認なしで自動適用する。migration 実行前に PostgreSQL backup（restic）を 1 ステップ追加する。deploy executor が保持する deploy lock 内から安全に呼び出せるよう、`scripts/backup/backup-fukurou` に `--invoked-by-deploy` フラグを追加する（backup の中核ロジックは変更しない、独立 falsifier 指摘 F-1 対応）
- rollback は「過去 SHA を `workflow_dispatch` で指定して再デプロイ」という素朴な運用にする。`workflow_dispatch` に対する ancestry 検証や `AUTHORIZED_ROLLBACK` という特別な intent 区分は持たない。ただし automatic push（`push` イベント）に限り、現在稼働中の revision の子孫であることを軽量確認する（独立 falsifier 指摘 F-2 対応。同時 push が順序レースで古い方が後着した場合に無警告でロールフォワードすることを防ぐ）
- **owner 確認済み**: schema-sensitive な migration 後に past-SHA へ再デプロイしても DB は戻らないことを受け入れる。自動的な互換性チェックや DB rollback 機構は持たず、事故時は forward fix か手動 restic restore に委ねる（独立 falsifier 指摘 F-3 対応）
- migration・compose cutover 中は launch（LLM decision loop）を disable/drain し、cutover 成功後に resume する。完全な journal state machine は持たないが、この最小限の maintenance/drain は残す（独立 falsifier 指摘 F-5 対応。paper truth の infrastructure gap 記録要件との整合のため）
- image は digest 固定で pull・検証する。build job が push した digest を deploy job・executor に引き渡し、`docker compose up -d` 後に稼働中コンテナの digest が一致することを確認する（独立 falsifier 指摘 F-8 対応。tag 上書きによる取り違え防止）
- 新 executor は、root 設置された `fukurou-deploy-db`/SQL の version marker と candidate image が期待する version を migration 実行前に照合し、不一致なら fail closed する（独立 falsifier 指摘 F-7 対応。workflow 更新と NAS 配置のタイミングずれによる stale migration 防止）
- selftest/validation を削減する。撤去対象の機構だけを検証していた `deploy-contract-selftest`・`deploy-e2e-selftest`・`deploy-runtime-selftest`・`deploy-intent-resolver`・`deploy-intent-resolver-selftest` および `deploy-bundle.schema.json`・`deploy-capability-catalog-v1.json`・`deploy-contract-v1.json`・`deploy-public-key.pem`・`deploy-schema-sensitive-paths-v1.txt` を削除する。`deploy-validation.yml` は新 executor の `bash -n` + 実行 selftest 1 本程度に縮小する
- `fukurou-deploy-db`（DB migration 実行）は機能を残す。契約検証（deploy-fukurou 側にあった署名/catalog 検証との連携部分）だけが対象
- `deploy-queue-watchdog.yml` は変更しない（self-hosted runner の死活検知として実用があるため）
- `ReleaseDeployFoundationContractTest.kt` から撤去済み機構（署名検証、catalog、CLI acceptance の実行順序 assert 等）の検証を削除する
- `docs/deploy.md` を新構成の現在形で書き直す（Pinned CLI acceptance qualification セクション、署名/contract/catalog の運用手順、schema-sensitive ゲートの説明を撤去し、Rollback セクションを新しい運用に合わせて書き直す）。「新 executor 配置前チェックリスト」（旧 executor の unfinished journal drain 確認、DB helper/SQL 同時配置、独立 falsifier 指摘 F-4 対応）と「schema migration 事故時の復旧手順」（forward fix / 手動 restic restore、F-3 対応）を新設する

## Capabilities

### New Capabilities
- `deploy-pipeline-baseline`: main マージ即デプロイ（再テストなし）、automatic push 限定の軽量 revision 逆行防止、NAS executor の pull（digest固定）→migration（DB helperバージョン照合＋事前backup）→launch pause→compose up→health確認→launch resume、過去 SHA 再デプロイによる素朴な rollback（DB 復元なしを明示）、sudoers 境界（`github-runner` は `deploy-fukurou` のみ sudo 可）の維持

### Modified Capabilities
- `deploy-quality-gate`: deploy.yml からの JVM quality gate 撤去に伴い、既存の全 Requirement を除去する（PR 時点の quality gate は Stage 1 の `pr-quality-gate` capability に代替済み）
- `deploy-revision-safety`: 署名つき deploy intent、revision monotonicity、authorized rollback の ancestry 検証、schema-sensitive diff ゲート、journal ベースの evidence/recovery、contract v2 rollout ゲートを全て除去する。代替の簡素な挙動は `deploy-pipeline-baseline` で定義する
- `pinned-cli-acceptance-canary`: FORWARD デプロイ前の CLI acceptance 必須化（Requirement: Forward deploy requires one-run CLI acceptance before cutover）のみを除去する。CLI acceptance harness 自体の qualification 能力（`scripts/mcp-credential-isolation-check` の 1-run smoke / 3-run merge qualification）は本 change の対象外であり、他の Requirement は変更しない

## Impact

- 影響ファイル: `.github/workflows/deploy.yml`、`.github/workflows/deploy-validation.yml`、`scripts/deploy/deploy-fukurou`、`scripts/backup/backup-fukurou`（`--invoked-by-deploy` フラグ追加のみ）、`scripts/deploy/deploy-intent-resolver`（削除）、`scripts/deploy/deploy-intent-resolver-selftest`（削除）、`scripts/deploy/deploy-contract-selftest`（削除）、`scripts/deploy/deploy-e2e-selftest`（削除）、`scripts/deploy/deploy-runtime-selftest`（削除）、`scripts/deploy/canary-compose-selftest`（削除。対象の candidate preflight hook 機構が消えるため）、`scripts/deploy/deploy-bundle.schema.json`（削除）、`scripts/deploy/deploy-capability-catalog-v1.json`（削除）、`scripts/deploy/deploy-contract-v1.json`（削除）、`scripts/deploy/deploy-public-key.pem`（削除）、`scripts/deploy/deploy-schema-sensitive-paths-v1.txt`（削除）、`scripts/deploy/fukurou-deploy-db`（version marker 追加）、`fukurou/src/test/kotlin/me/matsumo/fukurou/ReleaseDeployFoundationContractTest.kt`、`docs/deploy.md`
- 変更しないファイル: `.github/workflows/deploy-queue-watchdog.yml`、`scripts/backup/backup-common`（lock チェック本体のロジックは変更しない）、`scripts/deploy/deploy-db-selftest`、`scripts/deploy/deploy-postgres-selftest`（DB 操作自体の unit/integration test であり撤去対象の契約検証を含まない）、`scripts/deploy/sudoers-fukurou`、`scripts/mcp-credential-isolation-check`
- NAS 側の新 executor 配置は owner の手作業（root 権限）。PR には配置手順・新 executor 配置前チェックリスト（journal drain 確認、DB helper/SQL 同時配置）を `docs/deploy.md` に記載し、PR description に owner 作業として明記する
- **owner 確認済み**: main branch protection を有効化し、Stage 1 の `pr-quality-gate` job を required status check とする（独立 falsifier 指摘 F-9 対応）。これは Stage 1 側の実装タスクとして扱う
- **前提**: Stage 1（`add-pr-ci-workflow`、branch protection 有効化含む）が先に merge されていること
