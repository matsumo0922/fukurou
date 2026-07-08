#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat >&2 <<'EOF'
Usage:
  query-fukurou-llm-daemon-log.sh --since "YYYY-MM-DD HH:MM:SS+09" [--until "YYYY-MM-DD HH:MM:SS+09"]

Environment:
  FUKUROU_PROD_SSH_HOST       SSH host for the NAS. Default: dxp4800plus
  FUKUROU_POSTGRES_CONTAINER  PostgreSQL container name. Default: fukurou-postgres

The script runs SELECT-only SQL inside fukurou-postgres and does not print DB credentials.
EOF
}

ssh_host="${FUKUROU_PROD_SSH_HOST:-dxp4800plus}"
postgres_container="${FUKUROU_POSTGRES_CONTAINER:-fukurou-postgres}"
since_jst=""
until_jst=""

while (($# > 0)); do
  case "$1" in
    --since)
      since_jst="${2:-}"
      shift 2
      ;;
    --until)
      until_jst="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "${since_jst}" ]]; then
  echo "--since is required" >&2
  usage
  exit 1
fi

if [[ -z "${until_jst}" ]]; then
  until_jst="2999-12-31 23:59:59+09"
fi

timestamp_pattern='^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\+09|Z)$'

if [[ ! "${since_jst}" =~ ${timestamp_pattern} ]]; then
  echo "--since must look like YYYY-MM-DD HH:MM:SS+09 or YYYY-MM-DDTHH:MM:SS+09" >&2
  exit 1
fi

if [[ ! "${until_jst}" =~ ${timestamp_pattern} && "${until_jst}" != "2999-12-31 23:59:59+09" ]]; then
  echo "--until must look like YYYY-MM-DD HH:MM:SS+09 or YYYY-MM-DDTHH:MM:SS+09" >&2
  exit 1
fi

safe_name_pattern='^[A-Za-z0-9._@-]+$'

if [[ ! "${ssh_host}" =~ ${safe_name_pattern} ]]; then
  echo "FUKUROU_PROD_SSH_HOST contains unsupported characters" >&2
  exit 1
fi

if [[ ! "${postgres_container}" =~ ${safe_name_pattern} ]]; then
  echo "FUKUROU_POSTGRES_CONTAINER contains unsupported characters" >&2
  exit 1
fi

ssh "${ssh_host}" "docker exec -i ${postgres_container} sh -lc 'export PGPASSWORD=\"\$POSTGRES_PASSWORD\"; psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -v ON_ERROR_STOP=1 -P pager=off -qAt -F \"|\"'" <<SQL
BEGIN READ ONLY;
SELECT 'CHECKED_AT_JST|' || to_char(now() AT TIME ZONE 'Asia/Tokyo', 'YYYY-MM-DD HH24:MI:SS.MS');
SELECT 'WINDOW|' || '${since_jst}' || '|' || '${until_jst}';

WITH query_window AS (
    SELECT
        extract(epoch from '${since_jst}'::timestamptz) * 1000 AS since_ms,
        extract(epoch from '${until_jst}'::timestamptz) * 1000 AS until_ms
),
proposer_phase AS (
    SELECT DISTINCT ON (decision_run_id)
        decision_run_id,
        payload::jsonb #>> '{details,status}' AS process_status,
        payload::jsonb #>> '{details,exitCode}' AS exit_code,
        COALESCE(payload::jsonb #>> '{details,authFailureSuspected}', 'false') AS auth_failure_suspected
    FROM command_event_log
    WHERE event_type = 'RUNNER_PHASE_COMPLETED'
        AND payload::jsonb ->> 'phase' = 'proposer'
        AND decision_run_id IS NOT NULL
    ORDER BY decision_run_id, ts DESC
),
no_trade AS (
    SELECT
        decision_run_id,
        string_agg(
            DISTINCT COALESCE(payload::jsonb ->> 'reason', '<none>'),
            ', ' ORDER BY COALESCE(payload::jsonb ->> 'reason', '<none>')
        ) AS no_trade_reasons
    FROM command_event_log
    WHERE event_type = 'NO_TRADE_EXIT'
        AND decision_run_id IS NOT NULL
    GROUP BY decision_run_id
),
tool_counts AS (
    SELECT decision_run_id, tool_name, count(*) AS count_value
    FROM command_event_log
    WHERE event_type = 'TOOL_CALL_COMPLETED'
        AND decision_run_id IS NOT NULL
    GROUP BY decision_run_id, tool_name
),
tool_summary AS (
    SELECT
        decision_run_id,
        string_agg(tool_name || ':' || count_value, ', ' ORDER BY tool_name) AS tools
    FROM tool_counts
    GROUP BY decision_run_id
),
latest_decision AS (
    SELECT DISTINCT ON (invocation_id)
        invocation_id,
        action,
        estimated_win_probability,
        expected_r_multiple,
        replace(regexp_replace(reason_ja, '\\s+', ' ', 'g'), '|', '/') AS reason_ja,
        replace(regexp_replace(no_trade_conditions_ja, '\\s+', ' ', 'g'), '|', '/') AS no_trade_conditions_ja
    FROM decisions
    WHERE invocation_id IS NOT NULL
    ORDER BY invocation_id, created_at DESC
)
SELECT
    'RUN'
    || '|' || to_char(to_timestamp(llm_runs.started_at / 1000.0) AT TIME ZONE 'Asia/Tokyo', 'YYYY-MM-DD HH24:MI:SS')
    || '|' || COALESCE(to_char(to_timestamp(llm_runs.finished_at / 1000.0) AT TIME ZONE 'Asia/Tokyo', 'YYYY-MM-DD HH24:MI:SS'), '<running>')
    || '|' || llm_runs.invocation_id
    || '|' || COALESCE(llm_runs.trigger_kind, '<manual>')
    || '|' || llm_runs.status
    || '|' || COALESCE(llm_launch_reservations.reason, '<null>')
    || '|' || COALESCE(proposer_phase.process_status, '<none>') || '/' || COALESCE(proposer_phase.exit_code, '<none>') || '/auth=' || COALESCE(proposer_phase.auth_failure_suspected, 'false')
    || '|' || COALESCE(no_trade.no_trade_reasons, '<none>')
    || '|' || COALESCE(latest_decision.action, '<none>')
    || '|' || COALESCE(latest_decision.estimated_win_probability::text, '<none>')
    || '|' || COALESCE(latest_decision.expected_r_multiple::text, '<none>')
    || '|' || COALESCE(left(latest_decision.reason_ja, 220), '<none>')
    || '|' || COALESCE(left(latest_decision.no_trade_conditions_ja, 180), '<none>')
    || '|' || COALESCE(tool_summary.tools, '<none>')
FROM llm_runs
LEFT JOIN llm_launch_reservations ON llm_launch_reservations.invocation_id = llm_runs.invocation_id
LEFT JOIN proposer_phase ON proposer_phase.decision_run_id = llm_runs.invocation_id
LEFT JOIN no_trade ON no_trade.decision_run_id = llm_runs.invocation_id
LEFT JOIN tool_summary ON tool_summary.decision_run_id = llm_runs.invocation_id
LEFT JOIN latest_decision ON latest_decision.invocation_id = llm_runs.invocation_id
CROSS JOIN query_window
WHERE llm_runs.started_at > query_window.since_ms
    AND llm_runs.started_at <= query_window.until_ms
ORDER BY llm_runs.started_at ASC;

WITH query_window AS (
    SELECT
        extract(epoch from '${since_jst}'::timestamptz) * 1000 AS since_ms,
        extract(epoch from '${until_jst}'::timestamptz) * 1000 AS until_ms
)
SELECT
    'LIFECYCLE'
    || '|' || to_char(to_timestamp(ts / 1000.0) AT TIME ZONE 'Asia/Tokyo', 'YYYY-MM-DD HH24:MI:SS')
    || '|' || COALESCE(decision_run_id, '<none>')
    || '|' || COALESCE(payload::jsonb ->> 'phase', '<none>')
    || '|' || COALESCE(payload::jsonb #>> '{details,operation}', '<none>')
    || '|' || COALESCE(payload::jsonb #>> '{details,reason}', '<none>')
    || '|accepted=' || COALESCE(payload::jsonb #>> '{details,accepted}', '<none>')
    || '|ttlSeconds=' || COALESCE(payload::jsonb #>> '{details,ttlSeconds}', '<none>')
    || '|expiredOrderCount=' || COALESCE(payload::jsonb #>> '{details,expiredOrderCount}', '<none>')
    || '|cancelSuccessCount=' || COALESCE(payload::jsonb #>> '{details,cancelSuccessCount}', '<none>')
    || '|cancelFailureCount=' || COALESCE(payload::jsonb #>> '{details,cancelFailureCount}', '<none>')
    || '|canceledOrderIds=' || COALESCE(replace(payload::jsonb #>> '{details,canceledOrderIds}', '|', '/'), '<none>')
    || '|failedOrderIds=' || COALESCE(replace(payload::jsonb #>> '{details,failedOrderIds}', '|', '/'), '<none>')
    || '|failureSummaries=' || COALESCE(replace(regexp_replace(payload::jsonb #>> '{details,failureSummaries}', '\\s+', ' ', 'g'), '|', '/'), '<none>')
    || '|evidence=' || COALESCE(replace(regexp_replace((payload::jsonb #> '{details,evidence}')::text, '\\s+', ' ', 'g'), '|', '/'), '<none>')
FROM command_event_log
CROSS JOIN query_window
WHERE event_type = 'DECISION_LIFECYCLE_COMPLETED'
    AND ts > query_window.since_ms
    AND ts <= query_window.until_ms
ORDER BY ts ASC;

WITH query_window AS (
    SELECT
        extract(epoch from '${since_jst}'::timestamptz) * 1000 AS since_ms,
        extract(epoch from '${until_jst}'::timestamptz) * 1000 AS until_ms
)
SELECT
    'SKIP'
    || '|' || to_char(to_timestamp(ts / 1000.0) AT TIME ZONE 'Asia/Tokyo', 'YYYY-MM-DD HH24:MI:SS')
    || '|' || COALESCE(payload::jsonb ->> 'reason', '<none>')
    || '|' || COALESCE(payload::jsonb ->> 'triggerKind', '<none>')
    || '|' || COALESCE(payload::jsonb ->> 'triggerKey', '<none>')
FROM command_event_log
CROSS JOIN query_window
WHERE event_type = 'DAEMON_TRIGGER_SKIPPED'
    AND ts > query_window.since_ms
    AND ts <= query_window.until_ms
ORDER BY ts ASC;

SELECT
    'PAPER'
    || '|mode=' || mode
    || '|cash=' || cash_jpy
    || '|btc=' || btc_quantity
    || '|mark=' || btc_mark_price_jpy
    || '|equity=' || total_equity_jpy
    || '|dd=' || drawdown_ratio
    || '|updated_jst=' || to_char(to_timestamp(updated_at / 1000.0) AT TIME ZONE 'Asia/Tokyo', 'YYYY-MM-DD HH24:MI:SS')
FROM paper_account;

SELECT
    'RISK'
    || '|hard_halt=' || hard_halt
    || '|state=' || state
    || '|dd=' || drawdown_ratio
    || '|halt_reason=' || COALESCE(replace(regexp_replace(halt_reason, '\\s+', ' ', 'g'), '|', '/'), '<none>')
    || '|updated_jst=' || to_char(to_timestamp(updated_at / 1000.0) AT TIME ZONE 'Asia/Tokyo', 'YYYY-MM-DD HH24:MI:SS')
FROM risk_state;

SELECT
    'LEDGER'
    || '|orders=' || (SELECT count(*) FROM orders)
    || '|positions=' || (SELECT count(*) FROM positions)
    || '|executions=' || (SELECT count(*) FROM executions)
    || '|intents=' || (SELECT count(*) FROM trade_intents)
    || '|falsifications=' || (SELECT count(*) FROM falsifications)
    || '|safety_violations=' || (SELECT count(*) FROM safety_violations);
COMMIT;
SQL
