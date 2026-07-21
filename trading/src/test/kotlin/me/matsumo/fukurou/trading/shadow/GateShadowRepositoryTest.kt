package me.matsumo.fukurou.trading.shadow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GateShadowRepositoryTest {

    @Test
    fun inMemoryResolutionKeepsCrossedAfterUnknownRetry() = runBlocking {
        val repository = InMemoryGateShadowRepository()
        val observation = observationFixture()
        val unknown = resolutionFixture(observation.id, GateShadowOutcome.UNKNOWN)
        val crossed = resolutionFixture(observation.id, GateShadowOutcome.CROSSED)

        repository.appendObservation(observation).getOrThrow()
        repository.upsertResolution(unknown).getOrThrow()
        repository.upsertResolution(crossed).getOrThrow()
        repository.upsertResolution(unknown.copy(resolvedAt = Instant.parse("2026-07-21T00:03:00Z"))).getOrThrow()

        assertEquals(
            GateShadowOutcome.CROSSED,
            repository.findResolution(observation.id).getOrThrow()?.outcome,
        )
    }

    @Test
    fun gateShadowResultRethrowsCancellationException() {
        assertFailsWith<CancellationException> {
            gateShadowResult<Unit> { throw CancellationException("cancel gate-shadow write") }
        }
    }
}

private fun observationFixture(): GateShadowObservation {
    return GateShadowObservation(
        id = UUID.fromString("00000000-0000-0000-0000-000000000201"),
        orderId = UUID.fromString("00000000-0000-0000-0000-000000000202"),
        decisionId = null,
        opportunityEpisodeId = null,
        geometryHash = "geo_v1_test",
        symbol = "BTC",
        side = OrderSide.BUY,
        orderType = OrderType.LIMIT,
        sizeBtc = BigDecimal("0.001"),
        limitPriceJpy = BigDecimal("9900000"),
        triggerPriceJpy = null,
        stopPriceJpy = BigDecimal("9700000"),
        takeProfitPriceJpy = BigDecimal("10500000"),
        queueAheadBtc = BigDecimal.ZERO,
        marketDataSessionId = UUID.fromString("00000000-0000-0000-0000-000000000203"),
        startAdmissionOrdinal = 10L,
        windowStartTime = Instant.parse("2026-07-21T00:00:00Z"),
        dataQuality = ShadowDataQuality.OK,
        observedAt = Instant.parse("2026-07-21T00:00:01Z"),
    )
}

private fun resolutionFixture(observationId: UUID, outcome: GateShadowOutcome): GateShadowResolution {
    val crossed = outcome == GateShadowOutcome.CROSSED

    return GateShadowResolution(
        observationId = observationId,
        outcome = outcome,
        crossingEventSequence = if (crossed) 42L else null,
        crossingExchangeAt = if (crossed) Instant.parse("2026-07-21T00:01:00Z") else null,
        crossingPriceJpy = if (crossed) BigDecimal("9890000") else null,
        distanceJpy = if (crossed) BigDecimal("10000") else null,
        dataQuality = ShadowDataQuality.OK,
        resolvedAt = Instant.parse("2026-07-21T00:02:00Z"),
    )
}
