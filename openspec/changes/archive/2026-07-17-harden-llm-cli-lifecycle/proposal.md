## Why

Issue #189 の最初の delivery stage として、no-MCP Claude が copied auth と同時に `--bare` を受け取る欠陥、pre-filter release barrier の未接続、provider failure の heuristic、Codex model attribution の session file 依存を解消する。後続の process lifecycle、cost、deploy smoke を安定した provider invocation contract 上で実装できる状態を先に作る。

## What Changes

- `daemon.preFilterEnabled` より強い code-owned release barrier を daemon 実行経路へ戻し、Issue #189 の全 gate が満たされるまで pre-filter child を起動しない。
- Claude no-MCP 呼び出しから認証を無効化する `--bare` を廃止し、strict empty MCP config、copied auth、pre-filter の明示 model、空 tool policy を同時に成立させる。
- Claude/Codex の pinned output を typed parser で検証し、auth、rate/session limit、quota、timeout、process exit、cleanup failure を安定した failure category として監査する。
- Codex model 帰属を session file scan から configured/provider-output metadata へ移し、per-run session cleanup を attribution の前提から外す。
- Issue #154 の pre-filter activation 自体は行わず、本 change の全 gate 完了後も別 change で明示的に release する。

## Stage Scope

- **この stage（PR 1）**: release barrier、no-MCP auth/tool policy、canonical RISK_REDUCTION_ONLY の fixed supervisor allowlist、typed provider output/failure、model attribution。
- **後続 stage**: process/cleanup terminal と orphan SLO、Codex cost API、exact-candidate deploy smoke/canary。
- **non-goal**: Issue #154 activation、process supervisor 再設計、cost API、deploy script 変更、実資金取引。

## Capabilities

### New Capabilities

- `llm-cli-invocation-contract`: Claude/Codex の認証、引数、出力 schema、model attribution、typed provider failure の contract。

### Modified Capabilities

なし。

## Impact

- 主な対象は `trading` module の invocation model、command renderer、output parser、auditor と、`:fukurou` の daemon/manual/Reflection/report composition である。
- 既存 `RUNNER_PHASE_COMPLETED` payload と安全なログへ typed failure/model attribution を追加する。DB schema、evaluation API、Web UI、deploy scripts は変更しない。
- Docker image の Claude/Codex pin、runtime supervisor/launcher の構造、production credential、実資金取引、Issue #154 activation は変更しない。fixed supervisor は既存の canonical RISK_REDUCTION_ONLY allowlist だけを追加する。
- 既存 `atomic-paper-risk-exit` と `postgres-test-connection-bounds` の requirements は変更しない。
