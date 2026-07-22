## Context

deploy.yml の `quality` job は既に確立された参照実装（Java 21 + `gradle/actions/setup-gradle@v4` + `make test` / `make detekt` + `git diff --exit-code` によるクリーンツリー確認）を持つ。新設する PR CI はこの構成をそのまま踏襲する。

## Goals / Non-Goals

**Goals:**
- PR に対して `make test` + `make detekt` を実行する独立 workflow を追加する
- 同一 PR の連続 push を cancel-in-progress で無駄なく回す

**Non-Goals:**
- deploy.yml からの quality job 撤去（Stage 2 の範囲）
- 新しい lint ルールやテストの追加
- Linux 以外のランナーでの実行

## Decisions

- **runner**: `ubuntu-latest`（deploy.yml の quality job と同一。self-hosted runner は使わない — PR CI は untrusted fork からの実行もあり得るため）
- **trigger**: `pull_request`（`pull_request_target` は使わない。secrets を要求しない読み取り専用検証のため）
- **`git diff --exit-code` の要否**: deploy.yml の quality job はビルド成果物が worktree を汚さないことを確認する目的でこのチェックを持つ。PR CI でも同じ理由で踏襲する
- **main branch protection を有効化する（owner 確認済み）**: Stage 2 の独立 falsifier 指摘（F-9）を受けて追加。2026-07-22 時点で GitHub API 確認済みの通り、現状 main には branch protection も ruleset も設定されておらず、direct push が PR CI を素通りできる。job 名確定のため、有効化は本 workflow を merge して一度実行させた後に行う

## Risks / Trade-offs

- [Risk] PR CI が red のまま merge されると、Stage 2 で deploy.yml から quality job を外した後に品質担保が実質的に緩む → Mitigation: 本 change は Stage 2 の前提条件として先に merge し、Stage 2 の受け入れ条件で PR CI が実際に required check として機能していることを確認する
- [Risk] branch protection 有効化前の窓（workflow merge 直後〜job 名確定まで）は direct push が防げない → Mitigation: single-owner project であり、この窓は数分〜数時間程度。owner 自身が窓の間 direct push を行わない運用で足りる
