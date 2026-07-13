package me.matsumo.fukurou.trading.decision.identity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.broker.InMemoryPaperLedgerRepository
import me.matsumo.fukurou.trading.persistence.jdbcConnection
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

/** material manifest 用 coherent bounded account reader。 */
interface DecisionAccountSnapshotReader {
    suspend fun read(): Result<MaterialAccountSnapshot>
}

/** in-memory risk stateとledgerを共通writer boundary内でbounded captureする。 */
class InMemoryDecisionAccountSnapshotReader(
    private val ledgerRepository: InMemoryPaperLedgerRepository,
    private val riskStateRepository: InMemoryRiskStateRepository,
) : DecisionAccountSnapshotReader {
    override suspend fun read(): Result<MaterialAccountSnapshot> = runCatching {
        require(ledgerRepository.accountStateBoundary === riskStateRepository.accountStateBoundary) {
            "In-memory decision account sources do not share the writer coherence boundary."
        }
        val (riskState, snapshot) = ledgerRepository.accountStateBoundary.read {
            riskStateRepository.currentSnapshot().state.name to
                ledgerRepository.readDecisionAccountSnapshot(MAX_POSITION_QUERY_ROWS, MAX_ORDER_QUERY_ROWS)
        }

        MaterialAccountSnapshot(
            riskState = riskState,
            availableJpy = snapshot.account.cashJpy.toBigDecimalOrNull(),
            equityJpy = snapshot.account.totalEquityJpy.toBigDecimalOrNull(),
            positions = snapshot.positions.take(MAX_MATERIAL_POSITIONS).map { position ->
                MaterialLedgerFact(position.positionId, position.status.name, position.side.name, null)
            },
            openOrders = snapshot.openOrders.take(MAX_MATERIAL_ORDERS).map { order ->
                MaterialLedgerFact(order.orderId, order.status.name, order.side.name, order.orderType.name)
            },
            positionMetadata = inMemoryMetadata(snapshot.observedAt, snapshot.positions.size, MAX_MATERIAL_POSITIONS),
            orderMetadata = inMemoryMetadata(snapshot.observedAt, snapshot.openOrders.size, MAX_MATERIAL_ORDERS),
        )
    }

    private fun inMemoryMetadata(
        observedAt: Instant,
        rowCount: Int,
        materialLimit: Int,
    ): MaterialSourceMetadata {
        return MaterialSourceMetadata(
            observedAt = observedAt,
            provenance = "IN_MEMORY_LEDGER_MUTEX",
            truncated = rowCount > materialLimit,
            totalCount = if (rowCount > materialLimit) null else rowCount,
        )
    }
}

/** PostgreSQL REPEATABLE READ READ ONLY transactionで bounded captureする reader。 */
class ExposedDecisionAccountSnapshotReader(private val database: Database) : DecisionAccountSnapshotReader {
    override suspend fun read(): Result<MaterialAccountSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            transaction(
                transactionIsolation = java.sql.Connection.TRANSACTION_REPEATABLE_READ,
                readOnly = true,
                db = database,
            ) {
                val riskState = jdbcConnection().prepareStatement(RISK_STATE_SQL).use { statement ->
                    statement.executeQuery().use { result ->
                        require(result.next()) { "risk_state row is required." }
                        result.getString(1)
                    }
                }
                val account = jdbcConnection().prepareStatement(ACCOUNT_SQL).use { statement ->
                    statement.executeQuery().use { result ->
                        require(result.next()) { "paper_account row is required." }
                        result.getBigDecimal(1) to result.getBigDecimal(2)
                    }
                }
                val positions = jdbcConnection().prepareStatement(POSITIONS_SQL).use { statement ->
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) {
                                add(MaterialLedgerFact(result.getString(1), result.getString(2), result.getString(3), null))
                            }
                        }
                    }
                }
                val orders = jdbcConnection().prepareStatement(ORDERS_SQL).use { statement ->
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) {
                                add(
                                    MaterialLedgerFact(
                                        id = result.getString(1),
                                        status = result.getString(2),
                                        side = result.getString(3),
                                        type = result.getString(4),
                                    ),
                                )
                            }
                        }
                    }
                }

                MaterialAccountSnapshot(
                    riskState = riskState,
                    availableJpy = account.first,
                    equityJpy = account.second,
                    positions = positions.take(MAX_MATERIAL_POSITIONS),
                    openOrders = orders.take(MAX_MATERIAL_ORDERS),
                    positionMetadata = sqlMetadata(positions.size, MAX_MATERIAL_POSITIONS),
                    orderMetadata = sqlMetadata(orders.size, MAX_MATERIAL_ORDERS),
                )
            }
        }
    }

    private fun sqlMetadata(rowCount: Int, materialLimit: Int): MaterialSourceMetadata {
        return MaterialSourceMetadata(
            observedAt = Instant.now(),
            provenance = "POSTGRES_REPEATABLE_READ_READ_ONLY",
            truncated = rowCount > materialLimit,
            totalCount = if (rowCount > materialLimit) null else rowCount,
        )
    }
}

internal const val MAX_MATERIAL_POSITIONS = 32
internal const val MAX_MATERIAL_ORDERS = 64
internal const val MAX_POSITION_QUERY_ROWS = MAX_MATERIAL_POSITIONS + 1
internal const val MAX_ORDER_QUERY_ROWS = MAX_MATERIAL_ORDERS + 1
internal const val POSITIONS_SQL =
    "SELECT id, status, side FROM positions WHERE status='OPEN' ORDER BY opened_at, id LIMIT 33"
internal const val ORDERS_SQL =
    "SELECT id, status, side, order_type FROM orders WHERE status IN ('OPEN','PENDING_CANCEL') " +
        "ORDER BY created_at, id LIMIT 65"
private const val ACCOUNT_SQL = "SELECT cash_jpy, total_equity_jpy FROM paper_account WHERE id=1"
private const val RISK_STATE_SQL = "SELECT state FROM risk_state WHERE id=1"
