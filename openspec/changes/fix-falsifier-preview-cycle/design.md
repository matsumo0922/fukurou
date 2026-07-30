## Context

`submit_decision` が entry intent を `PENDING_FALSIFICATION` として保存した後、Falsifier はその intent を read tools で独立検証して verdict を保存する。現在の canonical Falsifier tool policy と production-equivalent canary は `preview_order` も公開しているが、`preview_order` が受け付けるのは fresh な Falsifier 承認済み intent である。この順序の不一致により、承認前 preview の `FALSIFIER_APPROVAL_MISSING` が Falsifier 自身の拒否理由へ循環している。

production `FukurouMcpServer` は allowlist にかかわらず全 tool を登録し、非許可 tool を call-time limiter で拒否している。そのため canonical policy と launch manifest だけから preview を外しても、Codex の `tools/list` には preview が残り、拒否理由が `mcp_tool_not_allowed` に変わるだけで循環は解消しない。

承認後の production runner は LLM tool call ではなく `previewApprovedEntry` から broker preview を実行し、その結果を同一の `place_order` 経路へ渡す。この経路は循環に関与せず、SafetyFloor の最終権限を保持している。

## Goals / Non-Goals

**Goals:**

- Falsifier が承認前に `preview_order` を発見・呼び出せない canonical policy と model-visible tool registry にする。
- Falsifier prompt、production manifest、production-equivalent canary の tool 契約を一致させる。
- APPROVED 後の runner preview → place 順序と SafetyFloor の authoritative evaluation を維持する。

**Non-Goals:**

- Falsifier の on/off、条件起動、policy version、期間比較を実装しない。
- `preview_order` MCP tool 自体や SafetyFloor の判定を変更しない。
- provider、model、base system prompt の取引方針、注文 lifecycle、DB schema を変更しない。phase-specific Falsifier prompt の tool 指示は変更対象とする。

## Decisions

1. **（ユーザー確認済み）`preview_order` を Falsifier phase の canonical allowlist と model-visible registry から除外する。**
   - 承認前に成功しない tool を Falsifier へ公開し続けると、拒否結果が downside evidence に見える循環を残す。
   - 代替案の「承認前 preview を許可する」は、approval gate の意味を phase ごとに分岐させ、runner と MCP の SafetyFloor 入力境界を広げるため採用しない。
   - production server は全 tool handler を組み立てた後、bootstrap の canonical `allowedToolNames` に含まれない tool を SDK registry から除外する。call-time limiter は直接 handler 参照などに対する defense-in-depth として維持する。

2. **（agent 仮決め）`preview_order` の schema registration は phase allowlist と分離して維持する。**
   - MCP tool 自体と既存の standalone contract test は残し、今回の修正を Falsifier phase の公開範囲に限定する。
   - catalog の schema 対象には preview を明示的に残し、tool の wire schema drift を起こさない。

3. **（agent 仮決め）回帰証明は production MCP registry と runner ordering の 2 点で行う。**
   - Falsifier の `tools/list` に `preview_order` が存在しないことを、production bootstrap から実 `FukurouMcpServer` を構築する経路で固定する。
   - 既存の approved entry test で、Falsifier verdict 後に runner が `preview_order` → `place_order` を実行することを証明する。
   - preview rejection test で、保存済み APPROVED verdict が維持されることを明示的に検査する。
   - production-equivalent CLI canary の Falsifier tool list も同じ policy に同期する。

## Risks / Trade-offs

- [Falsifier が deterministic SafetyFloor 結果を承認前に参照できなくなる] → SafetyFloor は Falsifier と独立した承認後 gate であり、preview rejection は runner が no-trade として監査する。Falsifier は market/account/intent の read evidence に限定する。
- [phase allowlist から外した tool の schema registration が誤って消える] → schema 対象に preview を明示し、unrestricted MCP tool contract test を維持する。
- [canary と production policy が再びずれる] → canary の phase tool listを同じ change で更新し、対象 test と CLI canary self-test を検証対象に含める。
- [production に旧 allowlist の legacy env override が残る] → 2026-07-31 の read-only `/ops/runtime-config` で `runner.falsifierAllowedTools.currentValue=null`、warning なしを確認した。production は code-owned default を使うため、新 image の default 更新で切り替わる。

## Migration Plan

1. Falsifier canonical allowlist、prompt、canary、現在形 docs を同時に更新する。
2. deploy 後の新規 Falsifier process の `tools/list` から `preview_order` が消える。確認済みの production state では legacy env override がなく、DB migration と runtime config activation は不要。
3. rollback はこの commit を戻すだけで、保存済み intent、verdict、ledger は変更しない。

## Open Questions

なし。Phase 2 の運用方針は後続 change で扱う。
