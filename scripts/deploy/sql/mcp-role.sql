\set ON_ERROR_STOP on

SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT',
    :'mcp_role',
    :'mcp_password'
) WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'mcp_role') \gexec

SELECT format(
    'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT',
    :'mcp_role',
    :'mcp_password'
) \gexec

SELECT format('REVOKE ALL PRIVILEGES ON DATABASE %I FROM PUBLIC', :'database_name') \gexec
SELECT format('REVOKE ALL PRIVILEGES ON DATABASE %I FROM %I', :'database_name', :'mcp_role') \gexec
SELECT format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO %I', :'database_name', :'app_role') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'database_name', :'mcp_role') \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('REVOKE ALL PRIVILEGES ON SCHEMA public FROM %I', :'mcp_role') \gexec
SELECT format('GRANT USAGE, CREATE ON SCHEMA public TO %I', :'app_role') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'mcp_role') \gexec

SELECT format('REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM %I', :'mcp_role') \gexec
SELECT format('REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM %I', :'mcp_role') \gexec
SELECT format('REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public FROM %I', :'mcp_role') \gexec

SELECT format(
    'REVOKE %s (%s) ON TABLE %I.%I FROM %s',
    privilege_type,
    string_agg(format('%I', column_name), ', ' ORDER BY column_name),
    table_schema,
    table_name,
    CASE WHEN grantee = 'PUBLIC' THEN 'PUBLIC' ELSE format('%I', :'mcp_role') END
)
FROM information_schema.column_privileges
WHERE grantee IN ('PUBLIC', :'mcp_role') AND table_schema = 'public'
GROUP BY table_schema, table_name, privilege_type, grantee \gexec
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;

SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public REVOKE ALL ON TABLES FROM %I', :'app_role', :'mcp_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public REVOKE ALL ON SEQUENCES FROM %I', :'app_role', :'mcp_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public REVOKE ALL ON FUNCTIONS FROM %I', :'app_role', :'mcp_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public REVOKE ALL ON TABLES FROM PUBLIC', :'app_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public REVOKE ALL ON SEQUENCES FROM PUBLIC', :'app_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public REVOKE ALL ON FUNCTIONS FROM PUBLIC', :'app_role') \gexec

SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE ALL ON TABLES FROM %I', :'app_role', :'mcp_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE ALL ON SEQUENCES FROM %I', :'app_role', :'mcp_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE ALL ON FUNCTIONS FROM %I', :'app_role', :'mcp_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE ALL ON TABLES FROM PUBLIC', :'app_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE ALL ON SEQUENCES FROM PUBLIC', :'app_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE ALL ON FUNCTIONS FROM PUBLIC', :'app_role') \gexec


SELECT DISTINCT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I%s REVOKE ALL ON %s FROM %s',
    owner_role.rolname,
    CASE
        WHEN default_acl.defaclnamespace = 0 THEN ''
        ELSE ' IN SCHEMA public'
    END,
    CASE default_acl.defaclobjtype
        WHEN 'r' THEN 'TABLES'
        WHEN 'S' THEN 'SEQUENCES'
        WHEN 'f' THEN 'FUNCTIONS'
    END,
    CASE WHEN exploded_acl.grantee = 0 THEN 'PUBLIC' ELSE format('%I', :'mcp_role') END
)
FROM pg_default_acl default_acl
JOIN pg_roles owner_role ON owner_role.oid = default_acl.defaclrole
CROSS JOIN LATERAL aclexplode(
    COALESCE(
        default_acl.defaclacl,
        acldefault(default_acl.defaclobjtype, default_acl.defaclrole)
    )
) exploded_acl
WHERE default_acl.defaclnamespace IN (0, 'public'::regnamespace)
  AND default_acl.defaclobjtype IN ('r', 'S', 'f')
  AND exploded_acl.grantee IN (0, (SELECT oid FROM pg_roles WHERE rolname = :'mcp_role')) \gexec

SELECT format('ALTER TABLE %I.%I OWNER TO %I', schemaname, tablename, :'app_role')
FROM pg_tables
WHERE schemaname = 'public' AND tableowner = :'mcp_role' \gexec
SELECT format('ALTER SEQUENCE %I.%I OWNER TO %I', n.nspname, c.relname, :'app_role')
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_roles r ON r.oid = c.relowner
WHERE n.nspname = 'public' AND c.relkind = 'S' AND r.rolname = :'mcp_role' \gexec
SELECT format('ALTER FUNCTION %s OWNER TO %I', p.oid::regprocedure, :'app_role')
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
JOIN pg_roles r ON r.oid = p.proowner
WHERE n.nspname = 'public' AND r.rolname = :'mcp_role' \gexec

SELECT format(
    'GRANT SELECT ON TABLE %s TO %I',
    string_agg(format('public.%I', table_name), ', '),
    :'mcp_role'
)
FROM (VALUES
    ('command_event_log'), ('paper_account'), ('positions'), ('orders'), ('risk_state'),
    ('executions'), ('market_data_sessions'), ('market_data_gaps'), ('trade_intents'),
    ('trade_plans'), ('falsifications'), ('trade_intent_consumptions'), ('decisions'),
    ('llm_runs'), ('evaluation_exclusions'), ('safety_violations')
) AS inventory(table_name) \gexec

SELECT format(
    'GRANT INSERT ON TABLE %s TO %I',
    string_agg(format('public.%I', table_name), ', '),
    :'mcp_role'
)
FROM (VALUES
    ('command_event_log'), ('decisions'), ('trade_plans'), ('trade_intents'), ('falsifications')
) AS inventory(table_name) \gexec

REVOKE EXECUTE ON FUNCTION pg_catalog.pg_try_advisory_lock(bigint) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION pg_catalog.pg_advisory_unlock(bigint) FROM PUBLIC;
SELECT format('GRANT EXECUTE ON FUNCTION pg_catalog.pg_try_advisory_lock(bigint) TO %I, %I', :'app_role', :'mcp_role') \gexec
SELECT format('GRANT EXECUTE ON FUNCTION pg_catalog.pg_advisory_unlock(bigint) TO %I, %I', :'app_role', :'mcp_role') \gexec

SELECT format('REVOKE %I FROM %I', roleid::regrole, :'mcp_role')
FROM pg_auth_members
WHERE member = (SELECT oid FROM pg_roles WHERE rolname = :'mcp_role') \gexec
SELECT format('REVOKE %I FROM %I', :'mcp_role', member::regrole)
FROM pg_auth_members
WHERE roleid = (SELECT oid FROM pg_roles WHERE rolname = :'mcp_role') \gexec
