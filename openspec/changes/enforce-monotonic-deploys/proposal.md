## Why

Issue #190 の品質ゲート導入後も、queue 待ちの古い workflow が新しい production revision を上書きでき、意図的な rollback と通常 deploy を root executor が区別できない。署名済み intent と lock 内 ancestry 判定を追加し、通常 deploy の revision 後退と migration-aware でない自動 image rollback を mutation 前に拒否する。

## What Changes

- signed deploy bundle に schema version、`FORWARD` / `AUTHORIZED_ROLLBACK` intent、operator reason、migration rollback mode を追加する。
- automatic push は `FORWARD` だけを発行し、historical target は理由付き manual dispatch だけを `AUTHORIZED_ROLLBACK` として発行する。
- root executor が production deploy lock 内で現在 container の revision と target の ancestry を検証し、queued old SHA、divergent history、不明 revision を rollback capture 前に拒否する。
- code-owned schema-sensitive path を root executor が current-to-target diff から判定し、該当時は signed `BACKWARD_COMPATIBLE` または `ROLL_FORWARD_ONLY` の明示を要求する。
- `ROLL_FORWARD_ONLY` の candidate failure は旧 image を自動起動せず、既存 maintenance/fence と durable journal を manual recovery required として保持する。
- bundle v1 の読取互換性を root executor に残しつつ、新 workflow は bundle schema v2 のみを発行する staged rollout にする。
- backup、restore、monitoring、alert はこの PR に含めない。

## Capabilities

### New Capabilities

- `deploy-revision-safety`: signed deploy intent、lock 内 revision monotonicity、authorized rollback、schema-sensitive migration compatibility、failure recovery modeを定義する。

### Modified Capabilities

なし。

## Impact

- `.github/workflows/deploy.yml` の manual inputs、target classification、signed bundle生成、installed executor contract check
- `scripts/deploy/deploy-fukurou`、bundle schema、deploy selftests、production-like E2E fixtures
- `docs/deploy.md` の通常 deploy、authorized rollback、migration compatibility、root executor pre-install 手順
- NAS の root-owned `/usr/local/sbin/deploy-fukurou` は PR merge 前に更新が必要になる。sudoers、DB helper、public key、GitHub secret は変更しない。
