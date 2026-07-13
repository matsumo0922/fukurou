package me.matsumo.fukurou.trading.daemon

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.exchange.gmo.GmoExchangeStatus
import me.matsumo.fukurou.trading.exchange.gmo.GmoExchangeStatusReader
import me.matsumo.fukurou.trading.market.MarketDataParseException
import me.matsumo.fukurou.trading.market.MarketNetworkException
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** GMO maintenance availability の時刻境界・status・cache contract を検証する。 */
class LlmDaemonLaunchAvailabilityTest {

    @Test
    fun scheduledWindow_usesHalfOpenSaturdayJstBoundaryWithoutStatusRequest() = runBlocking {
        val reader = FakeStatusReader()
        val availability = GmoLlmDaemonLaunchAvailability(reader)
        val cases = listOf(
            "2026-07-10T23:59:59Z" to null,
            "2026-07-11T00:00:00Z" to LlmDaemonLaunchSuppressionReason.SCHEDULED_MAINTENANCE,
            "2026-07-11T01:59:59.999Z" to LlmDaemonLaunchSuppressionReason.SCHEDULED_MAINTENANCE,
            "2026-07-11T02:00:00Z" to null,
        )

        val reasons = cases.map { (instant, _) ->
            availability.scheduledSuppressionAt(Instant.parse(instant))
        }

        assertEquals(cases.map { (_, reason) -> reason }, reasons)
        assertEquals(0, reader.callCount)
    }

    @Test
    fun scheduledWindow_appliesOnHolidayAndDoesNotLeakAcrossWeek() {
        val availability = GmoLlmDaemonLaunchAvailability(FakeStatusReader())

        assertEquals(
            LlmDaemonLaunchSuppressionReason.SCHEDULED_MAINTENANCE,
            availability.scheduledSuppressionAt(Instant.parse("2026-03-21T01:00:00Z")),
        )
        assertEquals(null, availability.scheduledSuppressionAt(Instant.parse("2026-03-22T01:00:00Z")))
        assertEquals(
            LlmDaemonLaunchSuppressionReason.SCHEDULED_MAINTENANCE,
            availability.scheduledSuppressionAt(Instant.parse("2026-03-28T01:00:00Z")),
        )
    }

    @Test
    fun statusGate_mapsOpenMaintenanceAndPreopen() = runBlocking {
        val cases = listOf(
            GmoExchangeStatus.OPEN to null,
            GmoExchangeStatus.MAINTENANCE to LlmDaemonLaunchSuppressionReason.STATUS_MAINTENANCE,
            GmoExchangeStatus.PREOPEN to LlmDaemonLaunchSuppressionReason.STATUS_PREOPEN,
        )

        cases.forEach { (status, expected) ->
            val availability = GmoLlmDaemonLaunchAvailability(FakeStatusReader(Result.success(status)))

            assertEquals(expected, availability.statusSuppressionAt(Instant.parse("2026-07-13T00:00:00Z")))
        }
    }

    @Test
    fun statusGate_mapsTimeoutMalformedAndTransportSeparately() = runBlocking {
        val cases = listOf(
            MarketNetworkException("timeout", HttpTimeoutException("timeout")) to
                LlmDaemonLaunchSuppressionReason.STATUS_TIMEOUT,
            MarketDataParseException("malformed") to LlmDaemonLaunchSuppressionReason.STATUS_MALFORMED,
            MarketNetworkException("connection reset") to
                LlmDaemonLaunchSuppressionReason.STATUS_TRANSPORT_FAILURE,
        )

        cases.forEach { (throwable, expected) ->
            val availability = GmoLlmDaemonLaunchAvailability(FakeStatusReader(Result.failure(throwable)))

            assertEquals(expected, availability.statusSuppressionAt(Instant.parse("2026-07-13T00:00:00Z")))
        }
    }

    @Test
    fun statusGate_cachesSingleSuccessOrFailureEntryForSixtySeconds() = runBlocking {
        val reader = FakeStatusReader(Result.success(GmoExchangeStatus.MAINTENANCE))
        val availability = GmoLlmDaemonLaunchAvailability(reader, Duration.ofSeconds(60))
        val firstAt = Instant.parse("2026-07-13T00:00:00Z")

        assertEquals(
            LlmDaemonLaunchSuppressionReason.STATUS_MAINTENANCE,
            availability.statusSuppressionAt(firstAt),
        )
        reader.result = Result.failure(MarketDataParseException("malformed"))
        assertEquals(
            LlmDaemonLaunchSuppressionReason.STATUS_MAINTENANCE,
            availability.statusSuppressionAt(firstAt.plusSeconds(59)),
        )
        assertEquals(
            LlmDaemonLaunchSuppressionReason.STATUS_MALFORMED,
            availability.statusSuppressionAt(firstAt.plusSeconds(60)),
        )
        assertEquals(2, reader.callCount)
    }

    @Test
    fun statusGate_cachesOpenForSixtySeconds() = runBlocking {
        val reader = FakeStatusReader(Result.success(GmoExchangeStatus.OPEN))
        val availability = GmoLlmDaemonLaunchAvailability(reader, Duration.ofSeconds(60))
        val firstAt = Instant.parse("2026-07-13T00:00:00Z")

        assertEquals(null, availability.statusSuppressionAt(firstAt))
        reader.result = Result.success(GmoExchangeStatus.MAINTENANCE)
        assertEquals(null, availability.statusSuppressionAt(firstAt.plusSeconds(59)))
        assertEquals(
            LlmDaemonLaunchSuppressionReason.STATUS_MAINTENANCE,
            availability.statusSuppressionAt(firstAt.plusSeconds(60)),
        )
        assertEquals(2, reader.callCount)
    }
}

/** status availability test 用 reader。 */
private class FakeStatusReader(
    var result: Result<GmoExchangeStatus> = Result.success(GmoExchangeStatus.OPEN),
) : GmoExchangeStatusReader {
    var callCount: Int = 0

    override suspend fun readStatus(): Result<GmoExchangeStatus> {
        callCount += 1

        return result
    }
}
