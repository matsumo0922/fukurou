package me.matsumo.fukurou.trading.persistence

import me.matsumo.fukurou.trading.broker.PaperLedgerOrderRepository
import me.matsumo.fukurou.trading.domain.Order
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/** Exposed/JDBC で paper ledger の order 履歴を読む repository。 */
internal class ExposedPaperLedgerOrderReader(
    private val database: ExposedDatabase,
) : PaperLedgerOrderRepository {
    override suspend fun findOrdersByTradeGroupId(tradeGroupId: UUID): Result<List<Order>> {
        return readLedgerResult(database) { selectOrdersByTradeGroupId(tradeGroupId.toString()) }
    }
}
