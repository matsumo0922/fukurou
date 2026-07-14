package me.matsumo.fukurou.trading.persistence

import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryDeadline
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.PreparedStatement
import java.util.concurrent.Executor

private const val RECOVERY_ROLLBACK_RESERVE_MILLIS = 250L
private const val MAX_RECOVERY_LOCK_TIMEOUT_MILLIS = 2_000L
private val DIRECT_NETWORK_TIMEOUT_EXECUTOR = Executor { command -> command.run() }

/** lock wait と statement 実行時間を現在 transaction だけに制限する。 */
internal fun JdbcTransaction.applyTransactionTimeouts(lockTimeoutSeconds: Int, statementTimeoutSeconds: Int) {
    require(lockTimeoutSeconds > 0) { "lock timeout must be positive." }
    require(statementTimeoutSeconds > 0) { "statement timeout must be positive." }

    executeUpdate("SET LOCAL lock_timeout='${lockTimeoutSeconds}s'")
    executeUpdate("SET LOCAL statement_timeout='${statementTimeoutSeconds}s'")
}

/** recovery deadlineからstatement / lock / JDBC timeoutを再計算する。 */
internal fun JdbcTransaction.applyRecoveryTransactionDeadline(
    deadline: LlmExecutionRecoveryDeadline,
    nanoTime: () -> Long,
): RecoveryStatementBudget {
    val connection = jdbcConnection()
    val remainingMillis = deadline.requireStartReserve(nanoTime, RECOVERY_ROLLBACK_RESERVE_MILLIS + 1L)
    val statementTimeoutMillis = remainingMillis - RECOVERY_ROLLBACK_RESERVE_MILLIS
    val lockTimeoutMillis = minOf(
        MAX_RECOVERY_LOCK_TIMEOUT_MILLIS,
        statementTimeoutMillis - 1L,
    ).coerceAtLeast(1L)
    val networkTimeoutMillis = remainingMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val queryTimeoutSeconds = ((statementTimeoutMillis + 999L) / 1_000L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

    connection.setNetworkTimeout(DIRECT_NETWORK_TIMEOUT_EXECUTOR, networkTimeoutMillis)
    executeUpdate("SET LOCAL lock_timeout='${lockTimeoutMillis}ms'")
    executeUpdate("SET LOCAL statement_timeout='${statementTimeoutMillis}ms'")

    return RecoveryStatementBudget(
        statementTimeoutMillis = statementTimeoutMillis,
        lockTimeoutMillis = lockTimeoutMillis,
        queryTimeoutSeconds = queryTimeoutSeconds,
        networkTimeoutMillis = networkTimeoutMillis,
    )
}

/** statement直前にdeadlineを再armしてPreparedStatementを作る。 */
internal fun JdbcTransaction.prepareRecoveryStatement(
    sql: String,
    deadline: LlmExecutionRecoveryDeadline,
    nanoTime: () -> Long,
): PreparedStatement {
    val budget = applyRecoveryTransactionDeadline(deadline, nanoTime)

    return jdbcConnection().prepareStatement(sql).also { statement ->
        statement.queryTimeout = budget.queryTimeoutSeconds
    }
}

/** transaction return直前にcommitへ残りdeadlineをarmする。 */
internal fun JdbcTransaction.armRecoveryCommitDeadline(deadline: LlmExecutionRecoveryDeadline, nanoTime: () -> Long) {
    applyRecoveryTransactionDeadline(deadline, nanoTime)
}

/** recovery statementへ適用した単調減少budget。 */
internal data class RecoveryStatementBudget(
    val statementTimeoutMillis: Long,
    val lockTimeoutMillis: Long,
    val queryTimeoutSeconds: Int,
    val networkTimeoutMillis: Int,
)
