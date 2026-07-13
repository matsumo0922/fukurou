SET lock_timeout = '1s';
SET statement_timeout = '30s';

CREATE TABLE IF NOT EXISTS llm_launch_maintenance (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    generation BIGINT NOT NULL CHECK (generation >= 0),
    enabled BOOLEAN NOT NULL,
    deployment_id VARCHAR(96),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

INSERT INTO llm_launch_maintenance(singleton, generation, enabled)
VALUES (TRUE, 0, FALSE)
ON CONFLICT (singleton) DO NOTHING;

CREATE TABLE IF NOT EXISTS infrastructure_gap_events (
    event_id UUID PRIMARY KEY,
    gap_id UUID NOT NULL,
    deployment_id VARCHAR(96) NOT NULL,
    boundary VARCHAR(5) NOT NULL CHECK (boundary IN ('OPEN', 'CLOSE')),
    reason VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (deployment_id, boundary),
    UNIQUE (gap_id, boundary)
);

CREATE TABLE IF NOT EXISTS llm_pid_registrations (
    registration_id UUID PRIMARY KEY,
    invocation_id VARCHAR(128) NOT NULL,
    reservation_id UUID NOT NULL,
    role VARCHAR(24) NOT NULL CHECK (role IN ('PROVIDER', 'MCP', 'CANARY_FIXTURE')),
    container_instance_id VARCHAR(96) NOT NULL,
    pid_namespace_inode BIGINT,
    process_id INTEGER,
    process_start_ticks BIGINT,
    state VARCHAR(24) NOT NULL CHECK (state IN ('SPAWN_RESERVED', 'ACTIVE', 'TERMINAL')),
    registered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    terminal_at TIMESTAMPTZ,
    terminal_reason VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS infrastructure_gap_events_interval_idx
    ON infrastructure_gap_events(boundary, occurred_at, deployment_id, gap_id);
CREATE INDEX IF NOT EXISTS infrastructure_gap_events_retry_idx
    ON infrastructure_gap_events(deployment_id, boundary, payload_hash);
CREATE UNIQUE INDEX IF NOT EXISTS llm_pid_registrations_active_pid_idx
    ON llm_pid_registrations(container_instance_id, pid_namespace_inode, process_id, process_start_ticks)
    WHERE state = 'ACTIVE';
CREATE UNIQUE INDEX IF NOT EXISTS llm_pid_registrations_active_invocation_role_idx
    ON llm_pid_registrations(invocation_id, role)
    WHERE state <> 'TERMINAL';
CREATE INDEX IF NOT EXISTS llm_pid_registrations_cleanup_idx
    ON llm_pid_registrations(terminal_at, registration_id)
    WHERE state = 'TERMINAL';

CREATE OR REPLACE FUNCTION validate_infrastructure_gap_close_v1()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    open_event infrastructure_gap_events%ROWTYPE;
BEGIN
    IF NEW.boundary = 'OPEN' THEN
        RETURN NEW;
    END IF;

    SELECT * INTO open_event
    FROM infrastructure_gap_events
    WHERE gap_id = NEW.gap_id AND boundary = 'OPEN'
    FOR KEY SHARE;
    IF NOT FOUND
        OR open_event.deployment_id <> NEW.deployment_id
        OR open_event.reason <> NEW.reason
        OR NEW.occurred_at < open_event.occurred_at THEN
        RAISE EXCEPTION 'INVALID_INFRASTRUCTURE_GAP_CLOSE';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS validate_infrastructure_gap_close_v1 ON infrastructure_gap_events;
CREATE CONSTRAINT TRIGGER validate_infrastructure_gap_close_v1
AFTER INSERT ON infrastructure_gap_events
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_infrastructure_gap_close_v1();

SELECT format('GRANT SELECT ON TABLE public.infrastructure_gap_events TO %I', :'mcp_role')
WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'mcp_role') \gexec
