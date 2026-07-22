## 1. PR quality gate workflow

- [x] 1.1 `.github/workflows/pr-quality.yml` を新設し、`pull_request` トリガーで Java 21 + Gradle setup + `make test` + `make detekt` + `git diff --exit-code` を実行する
- [x] 1.2 PR ごとの concurrency group（`cancel-in-progress: true`）を設定する
- [x] 1.3 `permissions: contents: read` のみを付与する

## 2. 検証

- [x] 2.1 worktree 内で `make test` と `make detekt` を実行し、workflow が参照する内容と一致することを確認する（HEAD c98b7a33、`make test`/`make detekt`/`git diff --exit-code` 全て成功）
- [x] 2.2 workflow YAML を手動で構文確認する（actionlint/yamllint 相当ツールは未導入のため目視確認。既存 `web.yml`/`deploy.yml` と同じ構文パターンを踏襲）

## 3. main branch protection（PR merge 後、owner 実行）

- [ ] 3.1 本 PR を merge し、workflow が実際に一度実行されて job 名を確認する
- [ ] 3.2 `gh api repos/matsumo0922/fukurou/branches/main/protection` で branch protection を設定し、3.1 の job を required status check にする。single-owner project のため required approving review count は 0 とするが、`enforce_admins: true`（または同等のノーバイパス ruleset）を明示的に有効化し、owner 自身の admin 権限による bypass を防ぐ
- [ ] 3.3 設定後に `gh api repos/matsumo0922/fukurou/branches/main/protection` の GET で readback し、required status check の job 名と `enforce_admins: true` が反映されていることを確認する
