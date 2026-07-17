## Why

`submit_decision` は response loss 後の再送や並行提出を一意に識別できず、同じ LLM invocation から decision、TradePlan、TradeIntent が重複登録され得る。また、request の `invocation_id` が server context を上書きでき、保存 identity の正本が caller 側へ漏れている。

## What Changes

- （ユーザー確認済み）server-owned `(invocationId, phase)` を decision submission の idempotency key とし、同じ canonical business payload の再送は既存 result ID を返す。
- （ユーザー確認済み）同じ key で payload が変わった場合は typed conflict、完了結果を安全に再構成できない場合は UNKNOWN とし、decision side effect を自動再実行しない。
- （ユーザー確認済み）legacy decision を backfill、dedupe、rewriteせず、新規 server-owned submission の authority だけを additive schema で厳格化する。
- （ユーザー確認済み）production の事前 read-only inventory は、非NULL `invocation_id` の重複0件を確認済みである。
- （agent 仮決め）caller-provided `invocation_id` は server context と一致する場合だけ受理し、保存値は常に server context から構築する。
- （agent 仮決め）decision-capable production path は app-owned submission gateway を必須とし、repositoryへのdirect fallbackを許可しない。

## Capabilities

### New Capabilities

- `decision-submission-idempotency`: server-owned invocation/phase authority、payload consistency、response-loss retry、concurrent submit、legacy互換性を規定する。

### Modified Capabilities

なし。

## Impact

- `:mcp` の `submit_decision` parse、gateway必須化、typed error response。
- `:trading` の decision submission contract、app-owned gateway、PostgreSQL/in-memory repository。
- PostgreSQL bootstrapへ新規 authority tableをadditiveに追加する。既存 `decisions`、`trade_plans`、`trade_intents` の行とschemaは書き換えない。
- MCP、gateway、PostgreSQL concurrency/response-loss testsと、現在仕様を説明する `docs/design.md` / `docs/mcp-runtime.md` を更新する。
- root-installed script、NAS bootstrap、OpenAPI、実取引機能には影響しない。
