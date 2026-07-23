package me.matsumo.fukurou.trading.persistence

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

/**
 * `llm_launch_maintenance` singleton 行を row lock 付きで読む SQL。
 */
private const val SELECT_LAUNCH_MAINTENANCE_ENABLED_FOR_UPDATE_SQL = """
    SELECT enabled
    FROM llm_launch_maintenance
    WHERE singleton = TRUE
    FOR UPDATE
"""

/**
 * deploy migration 中の launch maintenance が有効かどうかを row lock 付きで読む。
 * `maintenance-cas` の UPDATE と同じ行ロックで serialize されるため、
 * 呼び出し元の transaction 内で risk_state の後にこの関数を呼ぶこと。
 */
internal fun JdbcTransaction.isLaunchMaintenanceActive(): Boolean {
    return jdbcConnection().prepareStatement(SELECT_LAUNCH_MAINTENANCE_ENABLED_FOR_UPDATE_SQL).use { statement ->
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "llm_launch_maintenance single row was not initialized." }

            resultSet.getBoolean("enabled")
        }
    }
}
