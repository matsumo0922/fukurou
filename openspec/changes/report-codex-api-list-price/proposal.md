## Why

Issue #189 の `/evaluation/costs` は Claude CLI が返す monetary cost だけを `knownCostUsd` に集計し、token usage を取得できている Codex phase を常に unpriced としている。Codex の API list-price 相当額を参考値として追加しつつ、ChatGPT/Codex subscription の実請求額とは明確に分離する。

## What Changes

- `RUNNER_PHASE_COMPLETED` の既存 `configuredModel` と token usage を evaluation fact へ投影する。
- exact `gpt-5.5` model にだけ適用する versioned static API list-price catalog を追加する。
- cached input を input から控除し、uncached input / cached input / output を別単価で換算する。reasoning output は output の内数として二重加算しない。
- `/evaluation/costs` に provider-reported cost、API list-price equivalent、catalog-derived estimate、subscription actual cost unavailable の区別と coverage を additive に返す。
- unknown model、`CLI_DEFAULT`、不完全または矛盾した token usage は `$0` にせず unpriced のまま残す。
- route-local OpenAPI、committed OpenAPI snapshot/generated types、評価ドキュメントを同じ変更で更新する。
- 実provider smoke、pricing自動取得、過去の請求再現、DB migration はこのPRに含めない。

## Capabilities

### New Capabilities

- `llm-api-list-price`: Issue #189 の Codex token usage を versioned static catalogで API list-price相当額へ換算し、subscription実費と分離して返す契約。

### Modified Capabilities

なし。

## Impact

- `trading` の evaluation fact/model、集計、PostgreSQL event projection
- `fukurou` の `/evaluation/costs` response/OpenAPI contract と route tests
- committed OpenAPI snapshot と generated TypeScript types
- `docs/design.md` の cost semantics

想定差分は 350〜650 changed lines、hard stop は 800 changed linesとする。上限超過時は UI/report artifact への横展開を行わず、この API contract の完結に必要な範囲だけで停止する。（agent 仮決め）
