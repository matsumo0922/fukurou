## 1. Test connection contract

- [x] 1.1 Add module-local bounded PostgreSQL base containers with 10-second connect, 30-second login, and 300-second socket read bounds
- [x] 1.2 Add Docker-independent contract tests for URL parameters, structural overrides, and an unresponsive authentication socket

## 2. Fixture migration

- [x] 2.1 Migrate the finite `:trading` PostgreSQL fixture inventory to the bounded base container
- [x] 2.2 Migrate the finite `:fukurou` PostgreSQL fixture inventory to the bounded base container
- [x] 2.3 Migrate the finite `:mcp` PostgreSQL fixture inventory to the bounded base container
- [x] 2.4 Make the MCP wrong-password test assert SQLSTATE `28P01` without malformed or duplicate URL parameters
- [x] 2.5 Retry transient `runPostgresTest` DataSource initialization at most twice before the test body starts

## 3. Verification and documentation

- [x] 3.1 Verify the PostgreSQL container inventory has no unbounded construction path and run targeted tests
- [x] 3.2 Run full test, detekt, and build validation under the validation lease
- [x] 3.3 Search README and docs for affected fixture and timeout descriptions and update only stale current-state documentation
