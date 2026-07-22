package me.matsumo.fukurou.trading.shadow

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GateShadowResolverTest {

    // 8.1: 因果境界以前 / 別 session / activation 前（socket < windowStart）の crossing は分類に使わない。
    @Test
    fun keepsUnknownWhenOnlyPreBoundaryForeignSessionOrPreActivationCrossingsExist() = runBlocking {
        val repository = InMemoryGateShadowRepository()
        val observation = limitObservation(id = 1, startAdmissionOrdinal = 10L)
        repository.appendObservation(observation).getOrThrow()

        seedReceipt(repository, sessionA, ordinal = 5L, socketObservedAt = windowStart.plusSeconds(3600), priceJpy = "9800000")
        seedReceipt(repository, sessionB, ordinal = 15L, socketObservedAt = windowStart.plusSeconds(3600), priceJpy = "9800000")
        seedReceipt(repository, sessionA, ordinal = 16L, socketObservedAt = windowStart.minusSeconds(60), priceJpy = "9800000")

        resolver(repository).observe(afterSettle).getOrThrow()

        assertEquals(GateShadowOutcome.UNKNOWN, repository.findResolution(observation.id).getOrThrow()?.outcome)
    }

    // 8.3: 境界を跨ぐ in-window event で CROSSED と証拠を記録する。resolver は GateShadowRepository のみに依存し、
    // ledger / cash / position の型を持たないため paper 約定は構造的に作られない。
    @Test
    fun resolvesCrossedWithEvidenceWhenInWindowEventCrossesLimit() = runBlocking {
        val repository = InMemoryGateShadowRepository()
        val observation = limitObservation(id = 2, startAdmissionOrdinal = 10L)
        repository.appendObservation(observation).getOrThrow()

        seedReceipt(repository, sessionA, ordinal = 11L, socketObservedAt = windowStart.plusSeconds(3600), priceJpy = "9850000")

        resolver(repository).observe(afterSettle).getOrThrow()

        val resolution = repository.findResolution(observation.id).getOrThrow()

        assertEquals(GateShadowOutcome.CROSSED, resolution?.outcome)
        assertEquals(BigDecimal("9850000"), resolution?.crossingPriceJpy)
        assertEquals(11L, resolution?.crossingEventSequence)
    }

    // 8.4: settle 前（windowStart + horizon + grace 未経過）は走査せず resolution を書かない。
    @Test
    fun doesNotResolveBeforeSettlement() = runBlocking {
        val repository = InMemoryGateShadowRepository()
        val observation = limitObservation(id = 3, startAdmissionOrdinal = 10L)
        repository.appendObservation(observation).getOrThrow()

        seedReceipt(repository, sessionA, ordinal = 11L, socketObservedAt = windowStart.plusSeconds(3600), priceJpy = "9850000")

        val beforeSettle = windowEnd.plus(grace).minusSeconds(60)
        resolver(repository).observe(beforeSettle).getOrThrow()

        assertNull(repository.findResolution(observation.id).getOrThrow())
    }

    // 8.4: read 上限で未読を残す間は pending（resolution を書かず cursor を前進）。読み切ってクロス未発見で UNKNOWN。
    @Test
    fun keepsPendingUnderReceiptLimitThenResolvesUnknownAfterFullRead() = runBlocking {
        val repository = InMemoryGateShadowRepository()
        val observation = limitObservation(id = 4, startAdmissionOrdinal = 10L)
        repository.appendObservation(observation).getOrThrow()

        seedReceipt(repository, sessionA, ordinal = 11L, socketObservedAt = windowStart.plusSeconds(60), priceJpy = "9950000")
        seedReceipt(repository, sessionA, ordinal = 12L, socketObservedAt = windowStart.plusSeconds(120), priceJpy = "9950000")
        seedReceipt(repository, sessionA, ordinal = 13L, socketObservedAt = windowStart.plusSeconds(180), priceJpy = "9950000")

        val boundedResolver = resolver(repository, maxReceipts = 2)
        boundedResolver.observe(afterSettle).getOrThrow()

        assertNull(repository.findResolution(observation.id).getOrThrow())
        assertEquals(12L, repository.findScanProgress(observation.id).getOrThrow()?.lastScannedAdmissionOrdinal)

        boundedResolver.observe(afterSettle).getOrThrow()

        assertEquals(GateShadowOutcome.UNKNOWN, repository.findResolution(observation.id).getOrThrow()?.outcome)
    }

    // 8.9: pending の observation が後続 tick でクロスを発見すると CROSSED へ単調昇格する。
    @Test
    fun promotesPendingObservationToCrossedOnLaterTick() = runBlocking {
        val repository = InMemoryGateShadowRepository()
        val observation = limitObservation(id = 5, startAdmissionOrdinal = 10L)
        repository.appendObservation(observation).getOrThrow()

        seedReceipt(repository, sessionA, ordinal = 11L, socketObservedAt = windowStart.plusSeconds(60), priceJpy = "9950000")
        seedReceipt(repository, sessionA, ordinal = 12L, socketObservedAt = windowStart.plusSeconds(120), priceJpy = "9950000")
        seedReceipt(repository, sessionA, ordinal = 13L, socketObservedAt = windowStart.plusSeconds(180), priceJpy = "9850000")

        val boundedResolver = resolver(repository, maxReceipts = 2)
        boundedResolver.observe(afterSettle).getOrThrow()

        assertNull(repository.findResolution(observation.id).getOrThrow())

        boundedResolver.observe(afterSettle).getOrThrow()

        assertEquals(GateShadowOutcome.CROSSED, repository.findResolution(observation.id).getOrThrow()?.outcome)
    }

    // 8.7: 窓の終了境界（windowStart + horizon）より後の socket 時刻の crossing は CROSSED にしない。
    @Test
    fun ignoresCrossingObservedAfterHorizonEnd() = runBlocking {
        val repository = InMemoryGateShadowRepository()
        val observation = limitObservation(id = 6, startAdmissionOrdinal = 10L)
        repository.appendObservation(observation).getOrThrow()

        seedReceipt(repository, sessionA, ordinal = 11L, socketObservedAt = windowEnd.plusSeconds(3600), priceJpy = "9850000")

        resolver(repository).observe(afterSettle).getOrThrow()

        assertEquals(GateShadowOutcome.UNKNOWN, repository.findResolution(observation.id).getOrThrow()?.outcome)
    }

    // 8.8: LIMIT は境界価格ちょうど（eventPrice <= limit）で CROSSED。
    @Test
    fun resolvesCrossedAtExactLimitBoundary() = runBlocking {
        val repository = InMemoryGateShadowRepository()
        val observation = limitObservation(id = 7, startAdmissionOrdinal = 10L)
        repository.appendObservation(observation).getOrThrow()

        seedReceipt(repository, sessionA, ordinal = 11L, socketObservedAt = windowStart.plusSeconds(60), priceJpy = "9900000")

        resolver(repository).observe(afterSettle).getOrThrow()

        assertEquals(GateShadowOutcome.CROSSED, repository.findResolution(observation.id).getOrThrow()?.outcome)
    }

    // 8.8: STOP は境界価格ちょうど（eventPrice >= trigger）で CROSSED。
    @Test
    fun resolvesCrossedAtExactStopBoundary() = runBlocking {
        val repository = InMemoryGateShadowRepository()
        val observation = stopObservation(id = 8, startAdmissionOrdinal = 10L)
        repository.appendObservation(observation).getOrThrow()

        seedReceipt(repository, sessionA, ordinal = 11L, socketObservedAt = windowStart.plusSeconds(60), priceJpy = "10100000")

        resolver(repository).observe(afterSettle).getOrThrow()

        assertEquals(GateShadowOutcome.CROSSED, repository.findResolution(observation.id).getOrThrow()?.outcome)
    }

    // 8.8: LIMIT の境界を上回る価格（eventPrice > limit）はクロスせず UNKNOWN。
    @Test
    fun keepsUnknownWhenLimitPriceStaysAboveBoundary() = runBlocking {
        val repository = InMemoryGateShadowRepository()
        val observation = limitObservation(id = 9, startAdmissionOrdinal = 10L)
        repository.appendObservation(observation).getOrThrow()

        seedReceipt(repository, sessionA, ordinal = 11L, socketObservedAt = windowStart.plusSeconds(60), priceJpy = "9900001")

        resolver(repository).observe(afterSettle).getOrThrow()

        assertEquals(GateShadowOutcome.UNKNOWN, repository.findResolution(observation.id).getOrThrow()?.outcome)
    }
}

private val sessionA: UUID = UUID.fromString("00000000-0000-0000-0000-0000000a0001")
private val sessionB: UUID = UUID.fromString("00000000-0000-0000-0000-0000000b0002")
private val windowStart: Instant = Instant.parse("2026-07-21T00:00:00Z")
private val horizon: Duration = Duration.ofHours(24)
private val grace: Duration = Duration.ofSeconds(300)
private val windowEnd: Instant = windowStart.plus(horizon)
private val afterSettle: Instant = windowEnd.plus(grace).plusSeconds(60)

private fun resolver(repository: InMemoryGateShadowRepository, maxReceipts: Int = 1000): GateShadowResolver {
    return GateShadowResolver(
        repository = repository,
        horizon = horizon,
        settlementGrace = grace,
        maxObservationsPerTick = 100,
        maxReceiptsPerObservation = maxReceipts,
    )
}

private fun limitObservation(id: Int, startAdmissionOrdinal: Long): GateShadowObservation {
    return observationFixture(
        id = id,
        orderType = OrderType.LIMIT,
        limitPriceJpy = BigDecimal("9900000"),
        triggerPriceJpy = null,
        startAdmissionOrdinal = startAdmissionOrdinal,
    )
}

private fun stopObservation(id: Int, startAdmissionOrdinal: Long): GateShadowObservation {
    return observationFixture(
        id = id,
        orderType = OrderType.STOP,
        limitPriceJpy = null,
        triggerPriceJpy = BigDecimal("10100000"),
        startAdmissionOrdinal = startAdmissionOrdinal,
    )
}

private fun observationFixture(
    id: Int,
    orderType: OrderType,
    limitPriceJpy: BigDecimal?,
    triggerPriceJpy: BigDecimal?,
    startAdmissionOrdinal: Long,
): GateShadowObservation {
    return GateShadowObservation(
        id = uuid(id),
        orderId = uuid(id + 1000),
        decisionId = null,
        opportunityEpisodeId = null,
        geometryHash = "geo_v1_test",
        symbol = "BTC",
        side = OrderSide.BUY,
        orderType = orderType,
        sizeBtc = BigDecimal("0.001"),
        limitPriceJpy = limitPriceJpy,
        triggerPriceJpy = triggerPriceJpy,
        stopPriceJpy = BigDecimal("9700000"),
        takeProfitPriceJpy = BigDecimal("10500000"),
        queueAheadBtc = BigDecimal.ZERO,
        marketDataSessionId = sessionA,
        startAdmissionOrdinal = startAdmissionOrdinal,
        windowStartTime = windowStart,
        dataQuality = ShadowDataQuality.OK,
        observedAt = windowStart.plusSeconds(1),
    )
}

private suspend fun seedReceipt(
    repository: InMemoryGateShadowRepository,
    sessionId: UUID,
    ordinal: Long,
    socketObservedAt: Instant,
    priceJpy: String,
) {
    repository.appendReceipt(
        GateShadowReceipt(
            sessionId = sessionId,
            admissionOrdinal = ordinal,
            socketObservedAt = socketObservedAt,
            sourceSequence = ordinal,
            normalizedPayload = payloadJson(priceJpy),
        ),
    )
}

private fun payloadJson(priceJpy: String): String {
    return """{"exchangeAt":"2026-07-21T12:00:00Z","priceJpy":"$priceJpy","side":"SELL","sizeBtc":"0.01","symbol":"BTC"}"""
}

private fun uuid(seed: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
