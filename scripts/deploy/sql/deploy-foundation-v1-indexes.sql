\set ON_ERROR_STOP on
SET lock_timeout = '1s';
SET statement_timeout = '2min';

CREATE INDEX CONCURRENTLY IF NOT EXISTS positions_evaluation_interval_idx
    ON positions(mode, status, account_epoch_id, closed_at, opened_at, id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS orders_evaluation_interval_idx
    ON orders(created_at, updated_at, decision_run_id, intent_id, id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS executions_evaluation_interval_idx
    ON executions(executed_at, position_id, order_id, decision_run_id, id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS decisions_evaluation_interval_idx
    ON decisions(created_at, invocation_id, id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS llm_runs_evaluation_interval_idx
    ON llm_runs(started_at, finished_at, invocation_id);
