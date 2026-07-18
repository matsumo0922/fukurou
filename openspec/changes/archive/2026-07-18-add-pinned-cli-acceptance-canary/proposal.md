## Why

Issue #189 の runtime contract は実装済みだが、pinned Claude/Codex CLI の認証、出力 schema、phase ごとの tool policy を exact candidate image で実 provider に接続して確認する acceptance harness がない。CLI や provider 側の互換性変化を merge 前に検出できる再現可能な qualification boundary が必要である。

## What Changes

- exact candidate image と production の command renderer、output adapter を通す実 provider acceptance driverを追加する。
- Claude `PRE_FILTER`、Claude `PROPOSER`、Codex `FALSIFIER`、Claude `REFLECTION` の phase matrix を実行し、no-MCP と MCP tool resolution の両方を検証する。
- merge qualification では各 phase を3回実行し、auth、configured model、output schema、tool inventory、timeout、artifact cleanup を fail closed で判定する。
- canary は専用の read-only `llm-canary-auth` sourceだけを参照し、productionの `llm-auth`、DB、vault、trading runtime、raw credential、raw provider outputへアクセスしない。
- exact-image harnessはimmutable digestを一度だけ解決し、同じinvocationでfoundationと3回matrixを合成するmerge qualification、および同じmatrixの1回operator smokeを提供する。本changeではdeploy executor、signed bundle、capability catalogへ接続しない。
- canary の運用契約と検証手順を既存 deploy / LLM runtime documentation に追記する。

## Capabilities

### New Capabilities

- `pinned-cli-acceptance-canary`: pinned CLI の phase matrix、credential isolation、acceptance 判定、merge/deploy 実行回数、deploy gate を定義する。

### Modified Capabilities

- なし。

## Impact

- `trading` の production renderer / parser を再利用する canary entrypoint と fixture MCP server
- exact-image acceptance harness とdata-free fixture MCP
- exact-image canary scripts と既存 deploy / MCP runtime documentation
- deploy executor、signed bundle、capability catalog、DB migration、production trading state、SafetyFloor、#154 pre-filter activationには変更なし
- OpenSpec planning/recoveryを除くcode・test・docs差分はadd/delete合計1,100行をhard stopとし、超過時は実装をstage-outする
