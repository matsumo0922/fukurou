# fukurou Agent Notes

共通ルール（口調、Kotlin style、Git/PR、RTK）は `~/.codex/AGENTS.md` と `~/.codex/RTK.md` に従うこと。このファイルには `fukurou` 固有の補足だけを書く。

## Repository Scope

- Ktor/JVM backend。主要 module は `:fukurou`。
- package root は `me.matsumo.fukurou`。
- 実装 PR や issue autopilot では、特に指示がない限り sibling worktree を使い、main checkout のローカル差分と混ぜない。
- `.env` はローカル実行用。Cloudflare token、PostgreSQL password、取引所 API key、LLM credential などの secret はログ、PR、コメントに出さない。

## Product / Safety Notes

- 本リポジトリは暗号資産 BTC 現物トレーディング bot の実験プロジェクトであり、投資助言ではない。
- 実資金を動かす機能は、ユーザーが明示的に要求するまで実装・有効化しない。
- 取引実行や scheduler を追加する場合は、`docs/design.md` の「安全床」を優先する。最大損失、損切り必須、ナンピン禁止、最大ドローダウン停止、エクスポージャー上限、呼び出し回数上限を迂回できる設計にしない。
- 最初の実装は paper trading / dry-run を既定にし、本番取引への切り替えは config と運用手順の両方で明示的にする。

## Swagger / OpenAPI

- API docs の正本は Ktor route-local の `.describe {}`。静的 YAML を正本にしない。
- endpoint を追加・変更したら、route 実装と同じ差分で `.describe {}` も更新する。
- 公開面は `/swagger` と `/openapi.json`。人間向けの summary / description は日本語で書く。
- request / response DTO の wire contract を変えるときは、OpenAPI schema と route test / contract test も合わせて更新する。

## Local Commands

- 通常テスト: `make test`
- 静的解析: `make detekt`
- ビルド: `make build`
- Ktor サーバ起動: `make run`

DB を使うローカル起動では、`docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres` で PostgreSQL を起動し、`.env` の `POSTGRES_PASSWORD` と同じ値を `DB_PASSWORD` に設定する。

## Deployment Notes

- production は GitHub Actions が `ghcr.io/matsumo0922/fukurou:<commit-sha>` を push し、NAS の self-hosted runner が `/usr/local/sbin/deploy-fukurou <sha>` を sudo 実行する。
- NAS deploy root は `/srv/fukurou`、root checkout は `/srv/fukurou/repo`、NAS `.env` は `/srv/fukurou/.env`。
- self-hosted runner は `dxp4800plus-fukurou-prod` / label `fukurou-prod`。
- `github-runner` に Docker/root 権限を直接渡さない。sudoers は `/usr/local/sbin/deploy-fukurou` だけに限定する。
- Cloudflare Tunnel token と PostgreSQL password は NAS `.env` に置く。Cloudflare Access service token は NAS `.env` ではなく、手元の未追跡 env file か secret manager に置く。
- production compose は `docker-compose.prod.yml`。公開入口は Cloudflare Tunnel + Access とし、Ktor container の host port は公開しない。

## Current Placeholder API

- `GET /revision`
- `GET /health`
- `GET /health/live`
- `GET /health/ready`
- `GET /swagger`
- `GET /openapi.json`

`/health/ready` は `DB_URL` / `DB_USER` / `DB_PASSWORD` が揃っている場合に PostgreSQL へ接続する。readiness を変更するときは、compose の health / dependency と運用確認手順も合わせて見直す。

## MCP

- IntelliJ MCP が利用できる場合は、`mcp__idea.search_in_files_by_text` / `mcp__idea.search_in_files_by_regex` でのコード探索、`mcp__idea.open_file_in_editor` での対象確認、`mcp__idea.get_file_problems` での inspection / warning 確認を積極的に使う。
- コードを編集したときは、可能な範囲で `mcp__idea.get_file_problems` を使い、対象ファイルに warning が出ていないか確認する。必要に応じて `mcp__idea.build_project` も使う。
- IntelliJ MCP が使えない場合は、`make detekt` や関連テストなど、変更内容に応じた代替手段で確認する。

## Repo-local Skills

- Fukurou 固有の運用手順や production DB schema に依存する skill は `.codex/skills/` を正本として置く。
- Claude Code から使うため、`.claude/skills/<skill>/SKILL.md` を正本 SKILL.md への symlink として配置する。script 等の実体は `.codex/` 側だけに置き、二重管理しない。
- skill の内容は特定 agent の CLI に依存させず、repo root からの相対パスで手順を書く。
- Fukurou 固有 skill を汎用の personal Skills repo へ公開しない。
- LLM daemon / paper trading の時系列確認は `.codex/skills/fukurou-llm-daemon-log-audit/` を使う。

## Worktree 運用

実装を行う場合は、必ず worktree を作成し、デフォルトディレクトリを汚さない。
特別に指示がある場合や、read-only の調査やビルド・テストの実行はこの限りでない。

```bash
git worktree add ../fukurou-<task-slug> -b <branch-name>
cp -p .env ../fukurou-<task-slug>/.env
cd ../fukurou-<task-slug>
```

- `.env` は git 管理外なので、`git worktree add` ではコピーされない。ローカル DB password、外部 API credential、Cloudflare Access service token などが存在し、ビルドまたは smoke test に必要な場合は、worktree 作成直後に必ず元 checkout からコピーする。
