package me.matsumo.fukurou.trading.activity

import me.matsumo.fukurou.trading.domain.PaperOrderCancelReason
import me.matsumo.fukurou.trading.domain.PaperOrderLifecyclePolicy
import me.matsumo.fukurou.trading.domain.RestingEntryExpiryState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaperOrderLifecyclePolicyTest {

    @Test
    fun cancelReasonWireCodesRoundTripAndRejectUnknownValues() {
        PaperOrderCancelReason.entries.forEach { reason ->
            assertEquals(reason, PaperOrderCancelReason.fromWireCode(reason.wireCode))
        }
        assertFailsWith<IllegalStateException> {
            PaperOrderCancelReason.fromWireCode("unknown_cancel_reason")
        }
    }

    @Test
    fun expiryStateUsesInclusiveGraceAndMarksOnlyLaterOrdersOverdue() {
        val expiresAt = Instant.parse("2026-07-10T00:00:00Z")

        assertEquals(
            RestingEntryExpiryState.ACTIVE,
            PaperOrderLifecyclePolicy.expiryState(expiresAt, expiresAt.minusMillis(1)),
        )
        assertEquals(
            RestingEntryExpiryState.EXPIRING,
            PaperOrderLifecyclePolicy.expiryState(expiresAt, expiresAt),
        )
        assertEquals(
            RestingEntryExpiryState.EXPIRING,
            PaperOrderLifecyclePolicy.expiryState(
                expiresAt,
                expiresAt.plus(PaperOrderLifecyclePolicy.cancellationGrace),
            ),
        )
        assertEquals(
            RestingEntryExpiryState.OVERDUE,
            PaperOrderLifecyclePolicy.expiryState(
                expiresAt,
                expiresAt.plus(PaperOrderLifecyclePolicy.cancellationGrace).plusMillis(1),
            ),
        )
    }

    @Test
    fun delayedTtlCancellationIsExcludedFromStrategyEvaluation() {
        val logicalExpiry = Instant.parse("2026-07-10T00:00:00Z")
        val delayedOrder = ttlCanceledOrder(
            expiredAt = logicalExpiry,
            canceledAt = logicalExpiry.plus(PaperOrderLifecyclePolicy.cancellationGrace).plusSeconds(1),
        ).withStrategyEvaluation()

        assertFalse(delayedOrder.strategyEvaluationEligible)
        assertEquals(
            StrategyEvaluationExclusionReason.LIFECYCLE_MONITORING_DELAY,
            delayedOrder.strategyEvaluationExclusionReason,
        )
        assertEquals(11, delayedOrder.lifecycleDelaySeconds)

        val normalOrder = ttlCanceledOrder(
            expiredAt = logicalExpiry,
            canceledAt = logicalExpiry.plus(PaperOrderLifecyclePolicy.cancellationGrace),
        ).withStrategyEvaluation()
        assertTrue(normalOrder.strategyEvaluationEligible)
        assertNull(normalOrder.strategyEvaluationExclusionReason)
        assertEquals(10, normalOrder.lifecycleDelaySeconds)
    }

    private fun ttlCanceledOrder(expiredAt: Instant, canceledAt: Instant): DecisionRunOrder {
        return DecisionRunOrder(
            orderId = "order-1",
            intentId = "intent-1",
            positionId = null,
            tradeGroupId = "group-1",
            side = "BUY",
            orderType = "LIMIT",
            status = "CANCELED",
            sizeBtc = "0.001",
            limitPriceJpy = "10000000",
            reasonJa = "expired",
            expiresAt = expiredAt,
            expiredAt = expiredAt,
            canceledAt = canceledAt,
            cancelReason = PaperOrderCancelReason.TTL_EXPIRY,
            createdAt = expiredAt.minusSeconds(60),
        )
    }
}
