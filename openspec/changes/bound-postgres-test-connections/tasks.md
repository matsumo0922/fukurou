## 1. Test connection contract

- [x] 1.1 Add module-local Testcontainers PostgreSQL timeout helpers with 10-second connect and 30-second socket read bounds
- [x] 1.2 Add Docker-independent contract tests for URL parameters, structural overrides, and an unresponsive authentication socket

## 2. Fixture migration

- [x] 2.1 Apply the timeout helper to the finite `:trading` PostgreSQL container inventory
- [x] 2.2 Apply the timeout helper to the finite `:fukurou` PostgreSQL container inventory
- [x] 2.3 Apply the timeout helper to the finite `:mcp` PostgreSQL container inventory
- [x] 2.4 Make the MCP wrong-password test assert SQLSTATE `28P01` without malformed or duplicate URL parameters
- [x] 2.5 Retry only transient `runPostgresTest` DataSource initialization once before the test body starts

## 3. Verification and documentation

- [x] 3.1 Verify the PostgreSQL container inventory has no unbounded construction path and run targeted tests
- [ ] 3.2 Run full test, detekt, and build validation under the validation lease
- [x] 3.3 Search README and docs for affected fixture and timeout descriptions and update only stale current-state documentation
