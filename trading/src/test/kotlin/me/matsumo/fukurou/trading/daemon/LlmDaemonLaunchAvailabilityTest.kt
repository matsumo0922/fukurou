package me.matsumo.fukurou.trading.daemon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.exchange.gmo.GmoExchangeStatus
import me.matsumo.fukurou.trading.exchange.gmo.GmoExchangeStatusReader
import me.matsumo.fukurou.trading.exchange.gmo.GmoMonotonicTimeSource
import me.matsumo.fukurou.trading.market.GmoApiStatusException
import me.matsumo.fukurou.trading.market.GmoRequestAuditException
import me.matsumo.fukurou.trading.market.MarketDataParseException
import me.matsumo.fukurou.trading.market.MarketNetworkException
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
            GmoApiStatusException(1, "ERR-5201") to LlmDaemonLaunchSuppressionReason.STATUS_MALFORMED,
            GmoRequestAuditException() to LlmDaemonLaunchSuppressionReason.STATUS_TRANSPORT_FAILURE,
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
        val monotonicTimeSource = MutableMonotonicTimeSource()
        val availability = GmoLlmDaemonLaunchAvailability(
            statusReader = reader,
            cacheTtl = Duration.ofSeconds(60),
            monotonicTimeSource = monotonicTimeSource,
        )
        val firstAt = Instant.parse("2026-07-13T00:00:00Z")

        assertEquals(
            LlmDaemonLaunchSuppressionReason.STATUS_MAINTENANCE,
            availability.statusSuppressionAt(firstAt),
        )
        reader.result = Result.failure(MarketDataParseException("malformed"))
        monotonicTimeSource.advance(Duration.ofSeconds(59))
        assertEquals(
            LlmDaemonLaunchSuppressionReason.STATUS_MAINTENANCE,
            availability.statusSuppressionAt(firstAt.minusSeconds(3_600)),
        )
        monotonicTimeSource.advance(Duration.ofSeconds(1))
        assertEquals(
            LlmDaemonLaunchSuppressionReason.STATUS_MALFORMED,
            availability.statusSuppressionAt(firstAt.minusSeconds(7_200)),
        )
        assertEquals(2, reader.callCount)
    }

    @Test
    fun statusGate_cachesOpenForSixtySeconds() = runBlocking {
        val reader = FakeStatusReader(Result.success(GmoExchangeStatus.OPEN))
        val monotonicTimeSource = MutableMonotonicTimeSource()
        val availability = GmoLlmDaemonLaunchAvailability(
            statusReader = reader,
            cacheTtl = Duration.ofSeconds(60),
            monotonicTimeSource = monotonicTimeSource,
        )
        val firstAt = Instant.parse("2026-07-13T00:00:00Z")

        assertEquals(null, availability.statusSuppressionAt(firstAt))
        reader.result = Result.success(GmoExchangeStatus.MAINTENANCE)
        monotonicTimeSource.advance(Duration.ofSeconds(59))
        assertEquals(null, availability.statusSuppressionAt(firstAt.minusSeconds(1)))
        monotonicTimeSource.advance(Duration.ofSeconds(1))
        assertEquals(
            LlmDaemonLaunchSuppressionReason.STATUS_MAINTENANCE,
            availability.statusSuppressionAt(firstAt.minusSeconds(2)),
        )
        assertEquals(2, reader.callCount)
    }

    @Test
    fun statusGate_concurrentCallersShareOneRequestAndCacheEntry() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        var callCount = 0
        val availability = GmoLlmDaemonLaunchAvailability(
            statusReader = GmoExchangeStatusReader {
                callCount += 1
                requestStarted.complete(Unit)
                releaseRequest.await()
                Result.success(GmoExchangeStatus.OPEN)
            },
            monotonicTimeSource = MutableMonotonicTimeSource(),
        )
        val observedAt = Instant.parse("2026-07-13T00:00:00Z")
        val callers = List(8) {
            async { availability.statusSuppressionAt(observedAt) }
        }

        requestStarted.await()
        releaseRequest.complete(Unit)

        assertEquals(List(8) { null }, callers.awaitAll())
        assertEquals(1, callCount)
    }

    @Test
    fun statusGate_rethrowsThrownOrReturnedCancellationWithoutCaching() = runBlocking {
        val cancellations = listOf(
            GmoExchangeStatusReader { throw CancellationException("thrown") },
            GmoExchangeStatusReader { Result.failure(CancellationException("returned")) },
        )
        val observedAt = Instant.parse("2026-07-13T00:00:00Z")

        cancellations.forEach { cancellingReader ->
            var callCount = 0
            val availability = GmoLlmDaemonLaunchAvailability(
                statusReader = GmoExchangeStatusReader {
                    callCount += 1
                    if (callCount == 1) cancellingReader.readStatus() else Result.success(GmoExchangeStatus.OPEN)
                },
                monotonicTimeSource = MutableMonotonicTimeSource(),
            )

            assertFailsWith<CancellationException> {
                availability.statusSuppressionAt(observedAt)
            }
            assertEquals(null, availability.statusSuppressionAt(observedAt))
            assertEquals(2, callCount)
        }
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

/** cache TTL test 用 monotonic time source。 */
private class MutableMonotonicTimeSource : GmoMonotonicTimeSource {
    private var currentNanos: Long = 0

    override fun nanoTime(): Long = currentNanos

    fun advance(duration: Duration) {
        currentNanos += duration.toNanos()
    }
}
