CREATE TABLE IF NOT EXISTS llm_launch_maintenance (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    generation BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    deployment_id VARCHAR(96),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

INSERT INTO llm_launch_maintenance(singleton, generation, enabled)
VALUES (TRUE, 0, FALSE)
ON CONFLICT (singleton) DO NOTHING;

CREATE TABLE IF NOT EXISTS infrastructure_gaps (
    id UUID PRIMARY KEY,
    deployment_id VARCHAR(96) NOT NULL UNIQUE,
    reason VARCHAR(64) NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    payload_hash CHAR(64) NOT NULL,
    CHECK (closed_at IS NULL OR closed_at >= opened_at)
);

SELECT format('GRANT SELECT ON TABLE public.infrastructure_gaps TO %I', :'mcp_role')
WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'mcp_role') \gexec
