## Why

Issue #190 の CI DoD として、production image は対象 commit の JVM test と detekt が成功した場合だけ publish/deploy できなければならない。現行 deploy workflow は fat-jar を含む Docker build の成功だけで image を push できるため、変更ラッシュ前に exact-SHA の品質ゲートを独立した stage として追加する。

## What Changes

- （ユーザー確認済み）Issue #190 を5 PRへ分割し、この stage は OpenSpec 一式と exact-SHA test/detekt gate だけを扱う。
- deploy workflow で対象 SHA を一度解決し、quality、image build、production deploy の全 job が同じ immutable SHA を入力に使う。
- `make test` と `make detekt` の両方が成功するまで GHCR login、image build/push、signed deploy bundle 作成、NAS deploy を開始しない。
- workflow の production call path を file-level contract test で固定し、運用ドキュメントを現在仕様へ更新する。
- SHA 単調性、authorized rollback、migration compatibility、backup/restore、monitoring/alert は後続の独立 OpenSpec change/PRへ残す。

## Capabilities

### New Capabilities

- `deploy-quality-gate`: resolved target SHA と test/detekt、image publication、production deploy の必須依存関係を定義する。

### Modified Capabilities

なし。

## Impact

- `.github/workflows/deploy.yml`
- `fukurou/src/test/kotlin/me/matsumo/fukurou/ReleaseDeployFoundationContractTest.kt`
- `docs/deploy.md`
- GitHub-hosted runner の Java/Gradle実行時間。NAS root artifact、sudoers、runtime API、DB schema は変更しない。
