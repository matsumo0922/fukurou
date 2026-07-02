package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.AccountStatus
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.ProtectionStatus
import me.matsumo.fukurou.trading.reconciler.NoReconcilerStatusProvider
import me.matsumo.fukurou.trading.reconciler.ReconcilerStatus
import me.matsumo.fukurou.trading.reconciler.ReconcilerStatusProvider
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * paper ledger を読み取る Broker 実装。
 *
 * @param ledgerRepository paper ledger repository
 * @param riskStateRepository risk_state repository
 * @param reconcilerStatusProvider ProtectionReconciler 状態 provider
 * @param clock 当日実現損益の対象日算出に使う clock
 * @param tradingDateZone 当日判定に使う timezone
 */
class PaperBroker(
    private val ledgerRepository: PaperLedgerRepository,
    private val riskStateRepository: RiskStateRepository,
    private val reconcilerStatusProvider: ReconcilerStatusProvider = NoReconcilerStatusProvider,
    private val clock: Clock = Clock.systemUTC(),
    private val tradingDateZone: ZoneId = TRADING_DATE_ZONE,
) : Broker {

    override suspend fun getBalance(): Result<AccountSnapshot> {
        return ledgerRepository.getAccountSnapshot()
    }

    override suspend fun getPositions(): Result<List<Position>> {
        return ledgerRepository.getOpenPositions()
    }

    override suspend fun getOpenOrders(): Result<List<Order>> {
        return ledgerRepository.getOpenOrders()
    }

    override suspend fun getAccountStatus(): Result<AccountStatus> {
        return runCatching {
            val accountSnapshot = ledgerRepository.getAccountSnapshot().getOrThrow()
            val riskState = riskStateRepository.current().getOrThrow()
            val positions = ledgerRepository.getOpenPositions().getOrThrow()
            val openOrders = ledgerRepository.getOpenOrders().getOrThrow()
            val reconcilerStatus = reconcilerStatusProvider.snapshot()
            val today = LocalDate.now(clock.withZone(tradingDateZone))
            val todayRealizedPnlJpy = ledgerRepository.getRealizedPnlForDate(today).getOrThrow()

            AccountStatus(
                mode = accountSnapshot.mode,
                riskState = if (riskState.hardHalt) "HARD_HALT" else "RUNNING",
                drawdownRatio = riskState.drawdownRatio.toPlainString(),
                hardHalt = riskState.hardHalt,
                currentEquityJpy = accountSnapshot.totalEquityJpy,
                todayRealizedPnlJpy = todayRealizedPnlJpy.toPlainString(),
                protectionStatus = protectionStatus(positions, openOrders, reconcilerStatus),
            )
        }
    }

    private fun protectionStatus(
        positions: List<Position>,
        openOrders: List<Order>,
        reconcilerStatus: ReconcilerStatus,
    ): ProtectionStatus {
        val openPositions = positions.filter { position -> position.status == PositionStatus.OPEN }
        val activeStopOrderPositionIds = openOrders
            .filter { order -> order.isActiveProtectionStop() }
            .mapNotNull { order -> order.positionId }
            .toSet()
        val protectedPositionCount = openPositions.count { position ->
            position.currentStopLossJpy != null && position.positionId in activeStopOrderPositionIds
        }
        val unprotectedPositionCount = openPositions.size - protectedPositionCount
        val orphanStopCount = openOrders.count { order -> order.orderType == OrderType.STOP && order.positionId == null }
        val orphanTakeProfitCount = openOrders.count { order -> order.isTakeProfitCandidate() && order.positionId == null }
        val pendingCancelCount = openOrders.count { order -> order.status == OrderStatus.PENDING_CANCEL }

        return ProtectionStatus(
            protectedPositionCount = protectedPositionCount,
            unprotectedPositionCount = unprotectedPositionCount,
            orphanStopCount = orphanStopCount,
            orphanTakeProfitCount = orphanTakeProfitCount,
            pendingCancelCount = pendingCancelCount,
            lastReconciledAt = reconcilerStatus.lastReconciledAt?.toString(),
            lastMarketDataAt = reconcilerStatus.lastMarketDataAt?.toString(),
            tradingLockOwner = null,
        )
    }
}

private fun Order.isActiveProtectionStop(): Boolean {
    return side == OrderSide.SELL && orderType == OrderType.STOP && status == OrderStatus.OPEN
}

private fun Order.isTakeProfitCandidate(): Boolean {
    return orderType == OrderType.LIMIT && side == OrderSide.SELL
}

/**
 * 取引日判定に使う timezone。
 */
private val TRADING_DATE_ZONE = ZoneId.of("Asia/Tokyo")
