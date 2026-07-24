## Why

Issue #292 (Epic #286) はデプロイパイプラインを Build → Deploy の素直な構成に戻す。現状 JVM テスト・静的解析（`make test` / `make detekt`）は `deploy.yml` の `quality` job だけが実行しており、PR に対する独立した JVM テスト CI が存在しない。deploy.yml から `quality` job を撤去する前に、PR 時点でテスト・静的解析を担保する CI をまず新設する必要がある。

## What Changes

- `.github/workflows/` に PR quality gate workflow を新設する。`pull_request` イベントで `make test` と `make detekt` を実行し、失敗時は red にする
- 同一 PR への連続 push は `concurrency` group + `cancel-in-progress: true` で前の実行をキャンセルする
- 対象範囲は repository 全体の JVM ソースとする（`web/**` のような path filter は付けない。deploy.yml の既存 `quality` job も path filter なしで全体を検証しているため、挙動を踏襲する）
- **owner 確認済み**: main branch protection を有効化し、この workflow の job を required status check とする。PR 経由を必須にし、direct push が CI を素通りして自動デプロイされる経路を塞ぐ（Stage 2 の独立 falsifier 指摘 F-9 対応。2026-07-22 時点で GitHub API 確認済みの通り、main には branch protection も ruleset も設定されていない）

## Capabilities

### New Capabilities
- `pr-quality-gate`: pull_request イベントで JVM テスト（`make test`）と静的解析（`make detekt`）を実行し、失敗を PR の required check として可視化する

### Modified Capabilities
（なし。既存の `deploy-quality-gate` capability は deploy.yml 側の quality job を対象としており、本 change では変更しない。deploy.yml からの quality job 撤去は別 change（Stage 2: deploy パイプライン簡素化）で扱う）

## Impact

- 影響ファイル: `.github/workflows/`（新規 workflow 1 本）、GitHub repository 設定（main branch protection、コード diff ではない）
- 既存の `deploy.yml` `quality` job・`ReleaseDeployFoundationContractTest.kt`・NAS 側 script には触れない
- 本 change は Stage 2（deploy.yml から quality job を撤去する change）の前提条件であり、Stage 2 の PR より先に merge・branch protection 有効化される必要がある
- branch protection の有効化は workflow が実際に一度実行されて job 名が確定してから行う（PR merge 後、`gh api` 経由）
