# Issue #192 implementation evidence

secret-free な観測結果だけを記録する。raw payload、token、password、API key、接続文字列は書かない。未観測の項目は空欄にせず `UNOBSERVED` と書き、成功へ読み替えない。

## Verification deploy

| 項目 | 値 |
|---|---|
| production revision (commit SHA) | |
| immutable image digest | |
| container ID | |
| container 開始時刻 (UTC) | |
| `FUKUROU_ISSUE_192_WS_FAULT_ENABLED` | |
| deploy 時の flat state (resting BUY 0 / open position 0) | |
| flat state でない場合の owner 承認 | |
| 検証 deploy 時刻 (UTC) | |
| 72 時間上限の期限 (UTC) | |

## Read-only rehearsal

production を変更しない確認だけを記録する。

| 項目 | 結果 |
|---|---|
| route 有効状態（不正 purpose で `400`） | |
| route の mutation なし typed 応答（preflight 不成立で `409`） | |
| 固定 requested PK `588ce39f-90ec-4479-9430-f22a6d0356a9` の存在 | |
| 固定 executed PK `0367f844-595a-4ed7-8480-43a1d3e5df6c` の存在 | |
| 上記 2 lookup の latency | |
| lookup 失敗時に abort を呼ばないこと | |
| active runtime config version ID / hash | |
| active account epoch ID | |
| `paper_account.initial_cash_jpy` と runtime `paper.initialCashJpy` の一致 | |
| 次回 backup / restore timer 時刻 (UTC) | |
| arm 最大観測窓との重なり | |
| 既存 reviewed deploy recovery 手順の確認 | |

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

SELECT order_id, status, side, position_id, created_at
FROM orders
WHERE status IN ('OPEN', 'PENDING_CANCEL')
ORDER BY created_at ASC
LIMIT 50;

SELECT position_id, status, opened_at
FROM positions
WHERE status = 'OPEN'
ORDER BY opened_at ASC
LIMIT 50;
```

## Arm 1: `WS-DISCONNECT`

### Preflight boundary

| 項目 | 値 |
|---|---|
| preflight 時刻 (UTC) | |
| revision / image digest / container ID・開始時刻 | |
| `PAPER` mode | |
| active account epoch ID | |
| active runtime config version ID / hash | |
| market-data session ID | |
| last processed sequence | |
| latest receipt admission ordinal | |
| unresolved market-data gap 数 | |
| 対象 resting BUY entry の ID / status / 作成時刻 | |
| open position 数 | |
| active `llm_runs` / launch reservation | |
| runtime config mutation / deploy / backup / restore maintenance | |
| 待機上限（liveness + 2 × backoff + 30 秒、5 分 hard cap） | |
| owner go/no-go（提示した inventory と不可逆な exclusion 影響） | |

### Injection

| 項目 | 値 |
|---|---|
| injection ID | |
| expected session ID | |
| request 送信時刻 (UTC) | |
| HTTP 応答 status / code | |
| requested 固定 audit の durable 確定 | |
| executed 固定 audit の durable 確定 | |

### Gap / impact / recovery

| 項目 | 値 |
|---|---|
| 対象 gap ID | |
| gap reason（`DISCONNECTED` だけを受理） | |
| `started_at` / `impact_applied_at` / `recovered_at` | |
| arm 窓内の追加 gap（全件） | |
| 対象 resting order の cancel reason（`MARKET_DATA_GAP`） | |
| 対象 order の execution 数（0 でなければ `HARD_FAIL`） | |
| authoritative affected inventory（`evaluation_exclusions`） | |
| exclusion reason と gap reason の一致 | |
| preflight inventory との差分 | |
| 新 `CONNECTED` session ID | |
| 新 session の durable receipt | |
| unresolved gap 0 | |
| readiness / public connectivity | |
| preflight admission ordinal 以後の receipt 全件 | |
| terminal より前に buffer 済みだった event | |

### Lineage / KPI

| 項目 | 値 |
|---|---|
| evidence 窓内の `PAPER_WS_V1` execution 総数 | |
| receipt と完全 join した execution 数 | |
| 違反 execution 数 | |
| `GET /evaluation/summary` の resolved scope（active epoch + `CURRENT`） | |
| exclusion summary | |
| 除外 position が closed-trade 母集団に不在であること | |
| position が open のままの場合の記載 | |

### Verdict

| 項目 | 値 |
|---|---|
| verdict (`PASS` / `HARD_FAIL` / `INVALID` / `UNKNOWN`) | |
| hard fail 件数 | |
| unknown 件数 | |
| 通常運転への復旧確認 | |

## Arm 2: `PROCESS-RESTART`

### Preflight boundary

| 項目 | 値 |
|---|---|
| preflight 時刻 (UTC) | |
| revision / image digest / container ID・開始時刻 | |
| active account epoch ID | |
| active runtime config version ID / hash | |
| market-data session ID / last processed sequence | |
| unresolved market-data gap 数 | |
| 対象 open position の ID / status / 開始時刻 | |
| `OPEN` / `PENDING_CANCEL` resting BUY entry 数（0 が条件） | |
| active `llm_runs` / launch reservation | |
| 次回 backup / restore timer と観測窓の関係 | |
| owner go/no-go | |

### Restart

| 項目 | 値 |
|---|---|
| restart 時刻 (UTC) | |
| restart 後の container ID / image digest（不変であること） | |
| restart 後の開始時刻（前進のみ） | |
| 旧 session の `PROCESS_RESTART` gap ID / `impact_applied_at` | |
| authoritative affected inventory と preflight 差分 | |
| exclusion reason と gap reason の一致 | |
| arm 窓内の gap 総数（1 件であること） | |
| 新 session ID（旧 session と異なること） | |
| 最初の post-restart durable receipt | |
| 対象 gap の `recovered_at` | |
| unresolved gap 0 | |
| readiness / public connectivity | |

### Lineage / KPI

| 項目 | 値 |
|---|---|
| evidence 窓内の `PAPER_WS_V1` execution 総数 | |
| receipt と完全 join した execution 数 | |
| 違反 execution 数 | |
| 対象 position が close した場合の closing execution と新 session receipt の join | |
| close を観測しなかった場合の記載 | |
| `CURRENT` KPI 非混入の確認 | |

### Verdict

| 項目 | 値 |
|---|---|
| verdict | |
| hard fail 件数 | |
| unknown 件数 | |
| 通常運転への復旧確認 | |

## Residual unobserved scope

| 項目 | 記載 |
|---|---|
| GMO peer / kernel の callback dispatch（本 change の保証外） | |
| post-preflight receipt のうち dispatch されなかったもの | |
| その他の未観測事項 | |

## Cleanup

| 項目 | 値 |
|---|---|
| cleanup deploy 時の flat state または owner 承認 | |
| cleanup revision (commit SHA) | |
| route `404` の確認 | |
| 通常 WebSocket 再接続 / readiness / public connectivity | |
| NAS `.env` entry 削除時刻 (UTC) | |
| Issue #192 への secret-free summary 投稿 | |
| `Archive without syncing` の選択 | |
