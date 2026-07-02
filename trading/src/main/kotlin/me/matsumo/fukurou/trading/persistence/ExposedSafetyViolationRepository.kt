package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.safety.SafetyViolation
import me.matsumo.fukurou.trading.safety.SafetyViolationRepository
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * SafetyFloor 違反を保存する SQL。
 */
private const val INSERT_SAFETY_VIOLATION_SQL = """
    INSERT INTO safety_violations (
        id,
        decision_run_id,
        tool_call_id,
        client_request_id,
        tool_name,
        command_id,
        order_id,
        rule,
        message_ja,
        measured_value,
        limit_value,
        hard_halt_required,
        payload,
        created_at
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

/**
 * Exposed/JDBC で SafetyFloor 違反を保存する repository。
 *
 * @param database Exposed database
 */
class ExposedSafetyViolationRepository(
    private val database: ExposedDatabase,
) : SafetyViolationRepository {

    override suspend fun append(violation: SafetyViolation): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    insertSafetyViolation(violation)
                }
            }
        }
    }
}

/**
 * safety_violations へ 1 行追加する。
 */
internal fun JdbcTransaction.insertSafetyViolation(violation: SafetyViolation) {
    jdbcConnection().prepareStatement(INSERT_SAFETY_VIOLATION_SQL).use { statement ->
        statement.setObject(1, violation.id)
        statement.setString(2, violation.decisionRunId)
        statement.setString(3, violation.toolCallId)
        statement.setString(4, violation.clientRequestId)
        statement.setString(5, violation.commandName)
        statement.setObject(6, violation.commandId)
        statement.setObject(7, violation.orderId)
        statement.setString(8, violation.rule.name)
        statement.setString(9, violation.messageJa)
        statement.setString(10, violation.measuredValue)
        statement.setString(11, violation.limitValue)
        statement.setBoolean(12, violation.hardHaltRequired)
        statement.setString(13, violation.payloadJson)
        statement.setLong(14, violation.createdAt.toEpochMilli())
        statement.executeUpdate()
    }
}
