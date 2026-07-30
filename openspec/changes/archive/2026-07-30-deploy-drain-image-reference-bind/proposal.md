## Why

`docker-compose.prod.yml` は `FUKUROU_IMAGE_REFERENCE` を必須変数として参照するため、`docker compose` の全 subcommand が実行時にこの変数を要求する。executor は現在 `compose_cutover()` の直前でしか変数を export しない一方で、`drain_launches()` の強制停止経路はそれより前に `docker compose stop ktor` を実行する。結果として、active launch が natural deadline 内に 0 にならない deploy だけが interpolation error で失敗し、paused-state を残したまま後続 deploy を fail closed させる（issue #329、実際の失敗: Actions run 30417591999）。

## What Changes

- executor が `docker compose` を呼ぶより前に candidate の immutable image reference を必ず bind するよう、bind の実行位置を強制 drain 経路にも先行させる。
- `ReleaseDeployFoundationContractTest` に「executor 本文で最初に `docker compose` が現れる位置より前に `bind_image_reference` の呼び出しが現れる」という順序 assertion を追加する。
- `FUKUROU_IMAGE_REFERENCE` の必須性、immutable digest 固定、強制停止後の PID 0 確認 → active launch interrupt → drain 完了という既存順序は変更しない。

## Capabilities

### New Capabilities

（なし）

### Modified Capabilities

- `deploy-pipeline-baseline`: executor が compose を呼ぶすべての経路で、事前に candidate の immutable image reference が bind 済みであることを要求する Requirement を追加する（既存の pause / drain / cutover の順序要件は変更しない）。

## Impact

- `scripts/deploy/deploy-fukurou`（`bind_image_reference` / `drain_launches` / `start_new_pause` / `compose_cutover`）
- `fukurou/src/test/kotlin/me/matsumo/fukurou/ReleaseDeployFoundationContractTest.kt`
- `docs/deploy.md`（既存記述に影響がある場合のみ）
- merge 後、既存 runbook に従った root-owned executor の更新と、paused-state の acknowledge → fresh deploy による復旧が必要（運用手順であり、この change のコード変更範囲外）
