package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/** Exposed paper ledger reader が共有する read-only transaction wrapper。 */
internal suspend fun <T> readLedgerResult(
    database: ExposedDatabase,
    beforeRead: () -> Unit = {},
    read: JdbcTransaction.() -> T,
): Result<T> {
    return withContext(Dispatchers.IO) {
        runCatching {
            beforeRead()

            exposedTransaction(database) {
                read()
            }
        }
    }
}
