## Why

Issue #189 の runtime contract は実装済みだが、pinned Claude/Codex CLI の認証、出力 schema、phase ごとの tool policy を exact candidate image で実 provider に接続して確認する deploy gate がない。CLI や provider 側の互換性変化を production activation 前に検出し、#189 を閉じられる常設 acceptance boundary が必要である。

## What Changes

- exact candidate image と production の fixed launcher、command renderer、output adapter を通す `CLI_AUTH_PREFLIGHT_V1` を追加する。
- Claude `PRE_FILTER`、Claude `PROPOSER`、Codex `FALSIFIER`、Claude `REFLECTION` の phase matrix を実行し、no-MCP と MCP tool resolution の両方を検証する。
- merge qualification では各 phase を3回、deploy では各 phase を1回実行し、auth、pinned model、output schema、tool inventory、timeout、artifact cleanup を fail closed で判定する。
- canary は production の read-only `llm-auth` source だけを参照し、production DB、vault、trading runtime、raw credential、raw provider outputへアクセスしない。
- signed deploy bundle と capability catalog に required hook を追加し、foundation canary の後、production mutation 前に実行する。
- canary の運用契約と検証手順を既存 deploy / LLM runtime documentation に追記する。

## Capabilities

### New Capabilities

- `pinned-cli-acceptance-canary`: pinned CLI の phase matrix、credential isolation、acceptance 判定、merge/deploy 実行回数、deploy gate を定義する。

### Modified Capabilities

- なし。

## Impact

- `trading` の production renderer / parser を再利用する canary entrypoint と fixture MCP server
- fixed runtime supervisor の acceptance-only listener mode
- deploy executor、signed bundle、capability catalog、workflow contract tests
- exact-image canary scripts と既存 deploy / MCP runtime documentation
- DB migration、production trading state、SafetyFloor、#154 pre-filter activationには変更なし
