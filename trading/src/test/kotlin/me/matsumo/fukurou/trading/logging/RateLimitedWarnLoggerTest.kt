package me.matsumo.fukurou.trading.logging

import me.matsumo.fukurou.trading.market.GmoRateLimitException
import me.matsumo.fukurou.trading.market.GmoRequestAuditException
import me.matsumo.fukurou.trading.market.MarketNetworkException
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** human-facing warning の failure sanitization を検証するテスト。 */
class RateLimitedWarnLoggerTest {

    @Test
    fun warn_sanitizesGmoRateLimitMessage() {
        val handler = CapturingLogHandler()
        val logger = recordingLogger(handler)
        val warnLogger = RateLimitedWarnLogger(
            logger = logger,
            clock = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC),
        )

        warnLogger.warn(
            key = "gmo-rate-limit",
            message = "GMO request failed.",
            throwable = IllegalStateException(
                "sentinel-outer-rate-message /private/outer-rate-path",
                GmoRateLimitException("sentinel-rate-message /private/rate-path"),
            ),
        )

        val record = handler.records.single()
        val rendered = record.message + record.thrown?.stackTraceToString().orEmpty()

        assertNull(record.thrown)
        assertTrue(rendered.contains("outerType=IllegalStateException"))
        assertTrue(rendered.contains("category=GMO_RATE_LIMITED"))
        assertTrue(rendered.contains("type=GmoRateLimitException"))
        assertFalse(rendered.contains("sentinel-outer-rate-message"))
        assertFalse(rendered.contains("outer-rate-path"))
        assertFalse(rendered.contains("sentinel-rate-message"))
        assertFalse(rendered.contains("rate-path"))
    }

    @Test
    fun warn_sanitizesGmoAuditFailureGraphWithoutDiscardingDiagnostics() {
        val handler = CapturingLogHandler()
        val logger = recordingLogger(handler)
        val rawFailure = IOException("sentinel-message /private/sentinel-path/key.json")
        val networkFailure = MarketNetworkException("network sentinel", rawFailure)
        val auditFailure = GmoRequestAuditException().apply { addSuppressed(networkFailure) }
        val outerFailure = IllegalArgumentException("sentinel-outer-message /private/outer-path").apply {
            addSuppressed(auditFailure)
        }
        val warnLogger = RateLimitedWarnLogger(
            logger = logger,
            clock = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC),
        )

        warnLogger.warn(
            key = "gmo-audit",
            message = "GMO request failed.",
            throwable = outerFailure,
        )

        val record = handler.records.single()
        val rendered = record.message + record.thrown?.stackTraceToString().orEmpty()

        assertNull(record.thrown)
        assertTrue(rendered.contains("outerType=IllegalArgumentException"))
        assertTrue(rendered.contains("category=GMO_REQUEST_AUDIT_FAILED"))
        assertTrue(rendered.contains("type=GmoRequestAuditException"))
        assertFalse(rendered.contains("sentinel-outer-message"))
        assertFalse(rendered.contains("outer-path"))
        assertFalse(rendered.contains("sentinel-message"))
        assertFalse(rendered.contains("sentinel-path"))
        assertSame(auditFailure, outerFailure.suppressed.single())
        assertSame(networkFailure, auditFailure.suppressed.single())
        assertSame(rawFailure, networkFailure.cause)
    }

    @Test
    fun directWarning_sanitizesGmoAuditFailureGraph() {
        val handler = CapturingLogHandler()
        val logger = recordingLogger(handler)
        val auditFailure = GmoRequestAuditException().apply {
            addSuppressed(IllegalStateException("sentinel-direct-message /private/direct-path"))
        }

        logger.logSafeWarning("Direct boundary failed.", auditFailure)

        val record = handler.records.single()
        val rendered = record.message + record.thrown?.stackTraceToString().orEmpty()

        assertEquals(null, record.thrown)
        assertTrue(rendered.contains("outerType=GmoRequestAuditException"))
        assertTrue(rendered.contains("category=GMO_REQUEST_AUDIT_FAILED"))
        assertFalse(rendered.contains("sentinel-direct-message"))
        assertFalse(rendered.contains("direct-path"))
    }

    @Test
    fun directWarning_keepsOrdinaryExceptionLogging() {
        val handler = CapturingLogHandler()
        val logger = recordingLogger(handler)
        val failure = IllegalStateException("ordinary failure")

        logger.logSafeWarning("Direct boundary failed.", failure)

        val record = handler.records.single()

        assertEquals("Direct boundary failed.", record.message)
        assertSame(failure, record.thrown)
    }
}

private fun recordingLogger(handler: Handler): Logger {
    return Logger.getAnonymousLogger().apply {
        useParentHandlers = false
        addHandler(handler)
    }
}

private class CapturingLogHandler : Handler() {
    val records = mutableListOf<LogRecord>()

    override fun publish(record: LogRecord) {
        records += record
    }

    override fun flush() = Unit

    override fun close() = Unit
}
