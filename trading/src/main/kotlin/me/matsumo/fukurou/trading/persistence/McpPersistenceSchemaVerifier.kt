package me.matsumo.fukurou.trading.persistence

import javax.sql.DataSource

/** MCP tool inventory に必要な table だけを read-only query で検証する。 */
class McpPersistenceSchemaVerifier(private val dataSource: DataSource) {
    fun verify(): Result<Unit> = runCatching {
        dataSource.connection.use { connection ->
            connection.isReadOnly = true
            REQUIRED_TABLES.forEach { table ->
                connection.prepareStatement("SELECT 1 FROM $table LIMIT 0").use { statement ->
                    statement.executeQuery().close()
                }
            }
        }
    }
}

/** Proposer/Falsifier の repository call graph が読む table inventory。 */
val MCP_REQUIRED_TABLES = setOf(
    "command_event_log",
    "paper_account",
    "positions",
    "orders",
    "risk_state",
    "executions",
    "market_data_sessions",
    "market_data_gaps",
    "trade_intents",
    "trade_plans",
    "falsifications",
    "trade_intent_consumptions",
    "decisions",
    "llm_runs",
    "evaluation_exclusions",
    "safety_violations",
)

private val REQUIRED_TABLES = MCP_REQUIRED_TABLES.sorted()
