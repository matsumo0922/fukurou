## Why

Issue #189 の pinned CLI acceptance harness は merge 済みだが、production deploy はまだ real-provider の auth / output / MCP tool resolution を required hook として実行しない。candidate が provider contract と非互換でも cutover できる残り1つの DoD を、既存の signed/typed deploy protocol 内で閉じる。

## What Changes

- （ユーザー確認済み）既存 `CLI_AUTH_PREFLIGHT_V1` を signed bundle の required hook にし、exact candidate digest の cutover 前に1回だけ実行する。
- （ユーザー確認済み）installed foundation harness の `--cli-acceptance --runs 1` を再利用し、専用 `llm-canary-auth` volume が無い、または acceptance が失敗した deploy を fail closed にする。
- （agent 仮決め）既存 capability catalog と operation ID を再利用し、新しい catalog version、DB schema、credential provisioning を追加しない。
- raw provider output と credential を deploy log に出さず、hook 成否と typed failure だけを残す。
- contract / executor / workflow / E2E fixture と既存 deploy documentation を同じ変更で更新する。
- 3-run operator qualification、credential login、自動再認証、Issue #154 の pre-filter 有効化はこの変更に含めない。

## Capabilities

### New Capabilities

なし。

### Modified Capabilities

- `pinned-cli-acceptance-canary`: one-run acceptance を signed deploy required hook として cutover 前に必須化し、失敗時の fail-closed と rollback 境界を定める。

## Impact

- `.github/workflows/deploy.yml` の signed bundle required hook / operation set
- `scripts/deploy/deploy-fukurou` の typed operation validation と candidate hook dispatch
- deploy contract / E2E selftests と既存 Kotlin contract test
- `docs/deploy.md`、`docs/mcp-runtime.md`
- production credential、DB、trading semantics、API contractへの変更なし
