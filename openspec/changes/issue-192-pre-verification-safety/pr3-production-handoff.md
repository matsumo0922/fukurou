# Issue #192 PR-3 production verification and cleanup HANDOFF

PR-2 is the merge/deploy prerequisite that hardens the temporary controller. Do not begin this HANDOFF until production `/revision` contains the reviewed PR-2 safety fix. This file is a template, not observed PR-2 production evidence.

secret-free な観測結果だけを記録する。raw payload、token、password、API key、接続文字列は書かない。未観測の項目は空欄にせず `UNOBSERVED` と書き、成功へ読み替えない。

PR-1 archive の template を引き継ぐ。production で確認するまでは `UNOBSERVED` とし、agent の推測、local test、設計上の期待値を production evidence へ転記しない。

## Pre-deploy blocking audit

2026-07-20 21:12 JST 時点の read-only observation。これは verification deploy 後の evidence ではない。

| 項目 | 観測 |
|---|---|
| production revision | `cbc76e97b60eda89c6d47963b18c1cf1754121ac` |
| PR-1 merge revision | `7d34fe3320651483f7bfd403ce94b13b52929772` |
| PR-1 automatic deploy | `FAILED` before build/deploy: `SCHEMA_SENSITIVE_AUTOMATIC_DEPLOY_REQUIRES_MANUAL_REVIEW` ([run 29739348870](https://github.com/matsumo0922/fukurou/actions/runs/29739348870)) |
| current image digest / container ID / started UTC | `sha256:4de5433a706fd30fbcba515ada8861eace8d5bb78568ad1b0acd5886fdd36515` / `4a9db0bcfcd2d92e4797e18d8bce206718c5abb3f096507553ecd7ddfc49bcab` / `2026-07-20T05:13:38.677520239Z` |
| readiness / market session | `ready` / `CONNECTED` session `bb2d564f-c6e8-40df-8383-9d3a1fe3c71a`, unresolved gap `0` |
| current risk / inventory | risk `RUNNING`; resting BUY `0`; open position `0`; active LLM run/reservation `0` |
| fixed requested/executed audit PK | both absent |
| current account epoch | `98549973-557c-4e9e-863e-d5cad3a85103` (`CONFIG_ACTIVATED`) |
| active runtime config | `4222946e-ae4e-48f6-8784-ee6569572e68` / `091349d9e3cefa7e0742bb7b7daa08af7831c6058e6c502b4961da606bed9a6e` |
| account/runtime baseline | both `100000 JPY` and mutually consistent, but repository `AGENTS.md` canonical production baseline `1000000 JPY` と不一致 |
| `PAPER_WS_V1` executions in prior 7 days | `0` |
| NAS flag state | `UNKNOWN_PERMISSION_DENIED`; SSH user cannot read `/srv/fukurou/.env` and no value was exposed |
| next backup / restore drill | backup `2026-07-21 00:14:54 JST`; restore drill `2026-07-26 04:09:59 JST` |

**Blocking:** canonical baseline の owner判断、root権限を持つoperatorによるNAS flag設定、schema-sensitive diffのmanual compatibility mode選択が揃うまでverification deployを実行しない。

### Owner decisions (2026-07-20 22:19 JST)

| 判断 | 決定 |
|---|---|
| canonical paper baseline | verification前に監査付きruntime config activationで`1000000 JPY`の新epochへ整合する。既存epochをbackfillしない |
| NAS flag operator | 権限を持つownerが手動設定する。secretや`.env`内容をartifactへ転記しない |
| manual deploy compatibility mode | `ROLL_FORWARD_ONLY` |

**Remaining block:** clean-context再反証のblocking 0、baseline activation後の整合確認、ownerによるNAS flag設定、deploy直前flat-state再確認が揃うまでproduction write/deployを実行しない。

### Independent re-falsification

| 項目 | 結果 |
|---|---|
| falsifier | fresh `gpt-5.5` / `xhigh`（Claude Opus 4.8 rate limitのためユーザーが変更） |
| verdict | `BLOCKING` |
| blocking | `B-PR2-01`: operator query後にresting BUYが`PENDING_CANCEL`へ遷移してもcontrollerが再確認せずdisconnectできる |
| disposition | `ACCEPTED`: requested audit前のauthoritative controller preflightへ`PENDING_CANCEL=0`を追加し、no-audit/no-disconnect test後にfresh再反証する |
| non-blocking | baseline activation直前のBTC balance 0、全open/pending order 0、activation audit IDをevidenceへ追加する |
| accepted fix | controller final preflightに`PENDING_CANCEL=0`を追加。混入時`PREFLIGHT_INVENTORY_REJECTED`、audit 0、disconnect 0をtestで証明 |
| targeted validation | `./gradlew :fukurou:test --tests me.matsumo.fukurou.Issue192WsFaultSeamTest` — `BUILD SUCCESSFUL` |
| residual race disposition | final authoritative read後の状態遷移はglobal lockを追加せず、post-impact差分で`INVALID`、再注入なし。continuous isolationは主張しない |

Second clean-context re-falsification found one blocking identity-swap counterexample: owner-approved order A can disappear and an unapproved order B can satisfy count-only preflight. Disposition is `ACCEPTED`; command and controller final preflight are bound to target order ID, expiry, and minimum remaining TTL before requested audit. Production mutation remains blocked until implementation, proving tests, and another fresh falsification pass.

Accepted fix implementation binds the route request and both fixed audit payloads to target order UUID, approved expiry, and a bounded minimum remaining TTL. Final preflight rejects target absence/replacement, non-`OPEN`/non-BUY/position-linked state, expiry mismatch, or insufficient TTL before audit/disconnect. `Issue192WsFaultSeamTest`, `:fukurou:detekt`, and `git diff --check` passed; production remains unmodified pending another fresh falsification.

Third clean-context re-falsification found one blocking specification overclaim: application final preflight cannot authoritatively read NAS backup/restore timers, GitHub deploy state, runtime-config mutation, or every active `llm_run`. Disposition is `ACCEPTED` as a contract correction, not a cross-system coordinator: controller owns application-local final reads; the operator performs bounded request-time reads for NAS/GitHub/runtime maintenance and all active work. Drift after either final check is `INVALID` with no retry. Two safe-side non-blocking findings are documented as purpose-only fixed-row consumption and fail-closed `STREAM_UNAVAILABLE` before stream publication; no additional temporary framework is added.

## Verification deploy

| 項目 | 値 |
|---|---|
| production revision (commit SHA) | UNOBSERVED |
| immutable image digest | UNOBSERVED |
| container ID | UNOBSERVED |
| container 開始時刻 (UTC) | UNOBSERVED |
| `FUKUROU_ISSUE_192_WS_FAULT_ENABLED` | UNOBSERVED |
| manual deploy compatibility mode / reason | `ROLL_FORWARD_ONLY` / UNOBSERVED |
| canonical baseline activation version / epoch | UNOBSERVED |
| activation直前 open position / 全 OPEN・PENDING_CANCEL order / BTC balance | UNOBSERVED |
| baseline activation owner go / outcome audit ID | UNOBSERVED |
| deploy 時の flat state (resting BUY 0 / open position 0) | UNOBSERVED |
| flat state でない場合の owner 承認 | UNOBSERVED |
| 検証 deploy 時刻 (UTC) | UNOBSERVED |
| 72 時間上限の期限 (UTC) | UNOBSERVED |

## Read-only rehearsal

production を変更しない確認だけを記録する。

| 項目 | 結果 |
|---|---|
| route 有効状態（不正 purpose で `400`） | UNOBSERVED |
| route の mutation なし typed 応答（preflight 不成立で `409`） | UNOBSERVED |
| public origin 未認証 request の Cloudflare Access 拒否 | UNOBSERVED |
| 固定 requested PK `588ce39f-90ec-4479-9430-f22a6d0356a9` の存在 | UNOBSERVED |
| 固定 executed PK `0367f844-595a-4ed7-8480-43a1d3e5df6c` の存在 | UNOBSERVED |
| 上記 2 lookup の latency | UNOBSERVED |
| lookup failure の fail-closed repository test / SHA（productionでは誘発しない） | UNOBSERVED |
| active runtime config version ID / hash | UNOBSERVED |
| active account epoch ID | UNOBSERVED |
| `paper_account.initial_cash_jpy` と runtime `paper.initialCashJpy` の一致 | UNOBSERVED |
| 次回 backup / restore timer 時刻 (UTC) | UNOBSERVED |
| arm 最大観測窓との重なり | UNOBSERVED |
| 既存 reviewed deploy recovery 手順の確認 | UNOBSERVED |
| 直近7日 `PAPER_WS_V1` execution 件数 | UNOBSERVED |
| 直近7日 resting BUY 作成件数 / fill件数 / expiry件数 | UNOBSERVED |

```sql
-- 固定 audit の primary-key lookup（最大 2 行）
SELECT id, event_type, tool_name, client_request_id, ts
FROM command_event_log
WHERE id IN (
  '588ce39f-90ec-4479-9430-f22a6d0356a9',
  '0367f844-595a-4ed7-8480-43a1d3e5df6c'
);
```

```sql
-- preflight inventory（bounded）
SELECT state, id AS session_id, last_processed_sequence
FROM market_data_sessions
ORDER BY connected_at DESC
LIMIT 1;

SELECT COUNT(*) AS unresolved_market_data_gaps
FROM market_data_gaps
WHERE recovered_at IS NULL;

SELECT order_id, status, side, position_id, created_at, expires_at, expiry_source
FROM orders
WHERE status IN ('OPEN', 'PENDING_CANCEL')
  AND side = 'BUY'
  AND position_id IS NULL
ORDER BY created_at ASC
LIMIT 50;

SELECT position_id, status, opened_at
FROM positions
WHERE status = 'OPEN'
ORDER BY opened_at ASC
LIMIT 50;

SELECT COUNT(*) AS paper_ws_v1_executions_last_7d
FROM executions
WHERE execution_semantics_version = 'PAPER_WS_V1'
  AND executed_at >= (EXTRACT(EPOCH FROM NOW() - INTERVAL '7 days') * 1000)::BIGINT;
```

## Arm 1: `WS-DISCONNECT`

Before either arm, use the maintenance read-only connection and require both counts to be zero. This query is the operator-owned all-active-work check; the controller separately enforces its fresh trading launch-reservation gate.

```sql
SELECT
  (SELECT COUNT(*) FROM llm_runs WHERE status = 'RUNNING') AS running_llm_runs,
  (SELECT COUNT(*) FROM llm_launch_reservations WHERE status = 'RUNNING') AS running_launch_reservations;
```

### Preflight boundary

| 項目 | 値 |
|---|---|
| preflight 時刻 (UTC) | UNOBSERVED |
| operator final maintenance check / controller final authoritative read 時刻 / post-impact inventory差分 | UNOBSERVED |
| revision / image digest / container ID・開始時刻 | UNOBSERVED |
| `PAPER` mode | UNOBSERVED |
| active account epoch ID | UNOBSERVED |
| active runtime config version ID / hash | UNOBSERVED |
| market-data session ID | UNOBSERVED |
| last processed sequence | UNOBSERVED |
| latest receipt admission ordinal | UNOBSERVED |
| unresolved market-data gap 数 | UNOBSERVED |
| 対象 `OPEN` resting BUY entry の ID / status / 作成時刻 | UNOBSERVED |
| 対象 order の `expires_at` / `expiry_source` / 残り TTL | UNOBSERVED |
| `PENDING_CANCEL` resting BUY entry 数（0 が条件） | UNOBSERVED |
| open position 数 | UNOBSERVED |
| active `llm_runs` / launch reservation | UNOBSERVED |
| runtime config mutation / deploy / backup / restore maintenance | UNOBSERVED |
| 待機上限（liveness + 2 × backoff + 30 秒、5 分 hard cap） | UNOBSERVED |
| owner go/no-go（提示した inventory と不可逆な exclusion 影響） | UNOBSERVED |

### Injection

| 項目 | 値 |
|---|---|
| injection ID | UNOBSERVED |
| expected session ID | UNOBSERVED |
| request 送信時刻 (UTC) | UNOBSERVED |
| HTTP 応答 status / code | UNOBSERVED |
| requested 固定 audit の durable 確定 | UNOBSERVED |
| executed 固定 audit の durable 確定 | UNOBSERVED |

### Gap / impact / recovery

| 項目 | 値 |
|---|---|
| 対象 gap ID | UNOBSERVED |
| gap reason（`DISCONNECTED` だけを受理） | UNOBSERVED |
| `started_at` / `impact_applied_at` / `recovered_at` | UNOBSERVED |
| arm 窓内の追加 gap（全件） | UNOBSERVED |
| 対象 resting order の cancel reason（`MARKET_DATA_GAP`） | UNOBSERVED |
| 対象 order の execution 数 / causal receipt / gap前後分類 | UNOBSERVED |
| gap開始前 causal fill（あれば `INVALID`） | UNOBSERVED |
| gap開始以後 noncausal execution（あれば `HARD_FAIL`） | UNOBSERVED |
| authoritative affected inventory（`evaluation_exclusions`） | UNOBSERVED |
| exclusion reason と gap reason の一致 | UNOBSERVED |
| preflight inventory との差分 | UNOBSERVED |
| 新 `CONNECTED` session ID | UNOBSERVED |
| 新 session の durable receipt | UNOBSERVED |
| unresolved gap 0 | UNOBSERVED |
| container readiness / public origin connectivity（確認経路を分離） | UNOBSERVED |
| arm窓 receipt の先頭/末尾 admission ordinal・総件数 | UNOBSERVED |
| dispatch済み / 未dispatch receipt 件数 | UNOBSERVED |
| terminal より前に buffer 済みだった event 件数・違反件数 | UNOBSERVED |

### Lineage / KPI

| 項目 | 値 |
|---|---|
| evidence 窓内の `PAPER_WS_V1` execution 総数 | UNOBSERVED |
| receipt と完全 join した execution 数 | UNOBSERVED |
| 違反 execution 数 | UNOBSERVED |
| `GET /evaluation/summary` の resolved scope（active epoch + `CURRENT`） | UNOBSERVED |
| exclusion summary | UNOBSERVED |
| 除外 position が closed-trade 母集団に不在であること | UNOBSERVED |
| position が open のままの場合の記載 | UNOBSERVED |

### Verdict

| 項目 | 値 |
|---|---|
| verdict (`PASS` / `HARD_FAIL` / `INVALID` / `UNKNOWN`) | UNOBSERVED |
| hard fail 件数 | UNOBSERVED |
| unknown 件数 | UNOBSERVED |
| 通常運転への復旧確認 | UNOBSERVED |

## Arm 2: `PROCESS-RESTART`

### Preflight boundary

| 項目 | 値 |
|---|---|
| preflight 時刻 (UTC) | UNOBSERVED |
| revision / image digest / container ID・開始時刻 | UNOBSERVED |
| active account epoch ID | UNOBSERVED |
| active runtime config version ID / hash | UNOBSERVED |
| market-data session ID / last processed sequence | UNOBSERVED |
| unresolved market-data gap 数 | UNOBSERVED |
| 対象 open position の ID / status / 開始時刻 | UNOBSERVED |
| `OPEN` / `PENDING_CANCEL` resting BUY entry 数（0 が条件） | UNOBSERVED |
| active `llm_runs` / launch reservation | UNOBSERVED |
| 次回 backup / restore timer と観測窓の関係 | UNOBSERVED |
| owner go/no-go | UNOBSERVED |

### Restart

| 項目 | 値 |
|---|---|
| restart 時刻 (UTC) | UNOBSERVED |
| 実行者 / SSH host / exact command | UNOBSERVED |
| restart 前の container ID / image digest / 開始時刻 | UNOBSERVED |
| restart 後の container ID / image digest（不変であること） | UNOBSERVED |
| restart 後の開始時刻（前進のみ） | UNOBSERVED |
| 旧 session の `PROCESS_RESTART` gap ID / `impact_applied_at` | UNOBSERVED |
| authoritative affected inventory と preflight 差分 | UNOBSERVED |
| exclusion reason と gap reason の一致 | UNOBSERVED |
| arm 窓内の gap 総数（1 件であること） | UNOBSERVED |
| 新 session ID（旧 session と異なること） | UNOBSERVED |
| 最初の post-restart durable receipt | UNOBSERVED |
| 対象 gap の `recovered_at` | UNOBSERVED |
| unresolved gap 0 | UNOBSERVED |
| container readiness / public origin connectivity（確認経路を分離） | UNOBSERVED |

### Lineage / KPI

| 項目 | 値 |
|---|---|
| evidence 窓内の `PAPER_WS_V1` execution 総数 | UNOBSERVED |
| receipt と完全 join した execution 数 | UNOBSERVED |
| 違反 execution 数 | UNOBSERVED |
| 対象 position が close した場合の closing execution と新 session receipt の join | UNOBSERVED |
| close を観測しなかった場合の記載 | UNOBSERVED |
| `CURRENT` KPI 非混入の確認 | UNOBSERVED |

### Verdict

| 項目 | 値 |
|---|---|
| verdict | UNOBSERVED |
| hard fail 件数 | UNOBSERVED |
| unknown 件数 | UNOBSERVED |
| 通常運転への復旧確認 | UNOBSERVED |

## Residual unobserved scope

| 項目 | 記載 |
|---|---|
| GMO peer / kernel の callback dispatch（本 change の保証外） | UNOBSERVED |
| post-preflight receipt のうち dispatch されなかったもの | UNOBSERVED |
| その他の未観測事項 | UNOBSERVED |

## Cleanup

| 項目 | 値 |
|---|---|
| cleanup deploy 時の flat state または owner 承認 | UNOBSERVED |
| non-flat 時に提示した追加 `PROCESS_RESTART` exclusion inventory | UNOBSERVED |
| merge 時刻をcleanup deploy承認点としてownerが承認 | UNOBSERVED |
| cleanup revision (commit SHA) | UNOBSERVED |
| route `404` の確認 | UNOBSERVED |
| 通常 WebSocket 再接続 / readiness / public connectivity | UNOBSERVED |
| NAS `.env` entry 削除時刻 (UTC) | UNOBSERVED |
| Issue #192 への secret-free summary 投稿 | UNOBSERVED |
| `Archive without syncing` の選択 | UNOBSERVED |
