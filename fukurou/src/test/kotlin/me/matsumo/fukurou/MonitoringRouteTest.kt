package me.matsumo.fukurou

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.matsumo.fukurou.trading.config.LlmDaemonConfig
import me.matsumo.fukurou.trading.daemon.LlmDaemonTickOutcome
import me.matsumo.fukurou.trading.daemon.LlmDaemonTickStatus
import me.matsumo.fukurou.trading.daemon.MutableLlmDaemonTickStatus
import me.matsumo.fukurou.trading.market.MarketDataConnectionState
import me.matsumo.fukurou.trading.reconciler.MutableReconcilerStatus
import me.matsumo.fukurou.trading.reconciler.ReconcilerStatus
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** operations monitoring route と source isolation contract を検証するテスト。 */
class MonitoringRouteTest {
    @Test
    fun endpointReturnsVersionedComponentLocalSnapshotWithoutSecretFields() = testApplication {
        val service = monitoringService(
            repository = FakeMonitoringRepository(
                gapResult = Result.failure(IllegalStateException("jdbc:postgresql://secret-host password=secret")),
            ),
        )
        application {
            module(
                readinessProbe = { true },
                revision = "0123456789abcdef0123456789abcdef01234567",
                opsMonitoringService = service,
            )
        }

        val monitoring = client.get("/ops/monitoring")
        val ready = client.get("/health/ready")
        val body = monitoring.bodyAsText()
        val root = Json.parseToJsonElement(body).jsonObject

        assertEquals(HttpStatusCode.OK, monitoring.status)
        assertEquals(HttpStatusCode.OK, ready.status)
        assertEquals(1, root.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("AVAILABLE", root.getValue("daemon").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals("UNKNOWN", root.getValue("gaps").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(
            "GAP_QUERY_FAILED",
            root.getValue("gaps").jsonObject.getValue("reason").jsonPrimitive.content,
        )
        assertEquals("AVAILABLE", root.getValue("reconciler").jsonObject.getValue("state").jsonPrimitive.content)
        assertFalse(body.contains("secret-host"))
        assertFalse(body.contains("password"))
        assertFalse(body.contains("invocationId"))
    }

    @Test
    fun openApiDocumentsMonitoringContractAndStableReasons() = testApplication {
        application { module(readinessProbe = { true }) }

        val document = Json.parseToJsonElement(client.get("/openapi.json").bodyAsText()).jsonObject
        val paths = document.getValue("paths").jsonObject
        val schemas = document.getValue("components").jsonObject.getValue("schemas").jsonObject
        val operation = paths.getValue("/ops/monitoring").jsonObject.getValue("get").jsonObject
        val reasons = schemas.getValue("MonitoringDaemonResponse").jsonObject
            .getValue("properties").jsonObject
            .getValue("reason").jsonObject
            .getValue("enum").jsonArray
            .map { value -> value.jsonPrimitive.content }

        assertEquals("運用監視 snapshot を取得する", operation.getValue("summary").jsonPrimitive.content)
        assertTrue(reasons.contains("BACKUP_PROJECTION_NOT_ACTIVATED"))
        assertTrue(reasons.contains("PROVIDER_QUERY_BOUND_EXCEEDED"))
    }

    @Test
    fun providerAggregationExcludesDeterministicPhaseAndTreatsAbsentAuthAsFalse() {
        val deterministic = """{"phase":"stale_order_sweep","durationMillis":1,"details":{"status":"DONE"}}"""
        val provider = providerPayload(provider = "claude", authFailure = null)
        val failed = providerPayload(provider = "codex", authFailure = "true", status = "TIMED_OUT", exitCode = "null")

        val outcomes = aggregateProviderOutcomes(listOf(deterministic, provider, failed))

        assertEquals(
            listOf(
                MonitoringProviderOutcomeResponse("claude", 1, 0, 0),
                MonitoringProviderOutcomeResponse("codex", 1, 1, 1),
            ),
            outcomes,
        )
    }

    @Test
    fun malformedProviderEventFailsWholeProviderAggregateClosed() {
        val malformed = providerPayload(provider = "unknown", authFailure = null)

        assertFailsWith<MonitoringMalformedEventException> {
            aggregateProviderOutcomes(listOf(providerPayload("claude", null), malformed))
        }
    }

    @Test
    fun daemonTerminalMapsPreFilterSkipToNoTradeSemantic() {
        assertEquals(
            MonitoringDaemonTerminalSemantic.NO_TRADE,
            parseDaemonTerminalSemantic("pre_filter_no_change"),
        )
        assertEquals(
            MonitoringDaemonTerminalSemantic.LEGACY_UNCLASSIFIED,
            parseDaemonTerminalSemantic("unknown"),
        )
    }

    @Test
    fun preTerminalProcessKillBecomesUnknownAfterRunningProjectionIsStale() = testApplication {
        val observedAt = Instant.parse("2026-07-19T03:00:00Z")
        val directory = createTempDirectory("stale-backup-projection")
        val projection = directory.resolve(BACKUP_MONITORING_PROJECTION_FILE_NAME)
        Files.writeString(projection, runningProjection())
        val service = monitoringService(
            repository = FakeMonitoringRepository(),
            observedAt = observedAt,
            projectionPath = projection,
        )
        application {
            module(
                readinessProbe = { true },
                opsMonitoringService = service,
            )
        }

        val response = client.get("/ops/monitoring")
        val backupRestore = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            .getValue("backupRestore").jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("UNKNOWN", backupRestore.getValue("state").jsonPrimitive.content)
        assertEquals(
            "BACKUP_PROJECTION_STALE_RUNNING",
            backupRestore.getValue("reason").jsonPrimitive.content,
        )
    }

    private fun monitoringService(
        repository: MonitoringRepository,
        observedAt: Instant = Instant.parse("2026-07-19T00:30:00Z"),
        projectionPath: Path = createTempDirectory("monitoring-route").resolve("absent.json"),
    ): DefaultMonitoringSnapshotService {
        val tick = MutableLlmDaemonTickStatus().apply {
            record(LlmDaemonTickStatus(observedAt.minusSeconds(1), LlmDaemonTickOutcome.SKIPPED))
        }
        val reconciler = MutableReconcilerStatus().apply {
            updateMarketData(
                ReconcilerStatus(
                    lastMaintenanceAt = observedAt.minusSeconds(2),
                    lastTransportActivityAt = observedAt.minusSeconds(3),
                    marketDataState = MarketDataConnectionState.CONNECTED,
                ),
            )
        }
        return DefaultMonitoringSnapshotService(
            revision = "0123456789abcdef0123456789abcdef01234567",
            daemonConfig = LlmDaemonConfig(enabled = true),
            tickStatusProvider = tick,
            reconcilerStatusProvider = reconciler,
            repository = repository,
            backupProjectionReader = BackupMonitoringProjectionReader(projectionPath),
            clock = Clock.fixed(observedAt, ZoneOffset.UTC),
        )
    }
}

/** application-readable backup projection の strict parse contract を検証するテスト。 */
class BackupMonitoringProjectionReaderTest {
    @Test
    fun validProjectionIsReadAndUnknownFieldFailsClosed() {
        val directory = createTempDirectory("backup-projection")
        val file = directory.resolve(BACKUP_MONITORING_PROJECTION_FILE_NAME)
        Files.writeString(file, validProjection())

        val projection = BackupMonitoringProjectionReader(file).read().getOrThrow()

        assertEquals(1, projection.schemaVersion)
        assertEquals(MonitoringServiceTerminalSemantic.SUCCESS, projection.backup.service?.terminalSemantic)

        Files.writeString(file, validProjection().replaceFirst("{", "{\"repository\":\"secret\","))
        val failure = BackupMonitoringProjectionReader(file).read().exceptionOrNull()
        assertIs<BackupProjectionReadException>(failure)
        assertEquals(MonitoringUnknownReason.BACKUP_PROJECTION_MALFORMED, failure.reason)
    }

    @Test
    fun symlinkAndOversizeProjectionFailClosed() {
        val directory = createTempDirectory("backup-projection-invalid")
        val target = directory.resolve("target.json")
        Files.writeString(target, validProjection())
        val link = directory.resolve("link.json")
        Files.createSymbolicLink(link, target)

        val symlinkFailure = BackupMonitoringProjectionReader(link).read().exceptionOrNull()
        assertIs<BackupProjectionReadException>(symlinkFailure)
        assertEquals(MonitoringUnknownReason.BACKUP_PROJECTION_NOT_REGULAR, symlinkFailure.reason)

        val oversized = directory.resolve("oversized.json")
        Files.writeString(oversized, "x".repeat(65_537))
        val oversizedFailure = BackupMonitoringProjectionReader(oversized).read().exceptionOrNull()
        assertIs<BackupProjectionReadException>(oversizedFailure)
        assertEquals(MonitoringUnknownReason.BACKUP_PROJECTION_OVERSIZED, oversizedFailure.reason)
    }
}

private class FakeMonitoringRepository(
    private val daemonResult: Result<MonitoringDaemonTerminal?> = Result.success(
        MonitoringDaemonTerminal(
            Instant.parse("2026-07-19T00:20:00Z"),
            MonitoringDaemonTerminalSemantic.NO_TRADE,
        ),
    ),
    private val providerResult: Result<List<MonitoringProviderOutcomeResponse>> = Result.success(emptyList()),
    private val gapResult: Result<MonitoringGapAggregate> = Result.success(MonitoringGapAggregate(0, null, 0, null)),
) : MonitoringRepository {
    override suspend fun latestDaemonTerminal(): Result<MonitoringDaemonTerminal?> = daemonResult

    override suspend fun providerOutcomes(
        fromInclusive: Instant,
        toExclusive: Instant,
    ): Result<List<MonitoringProviderOutcomeResponse>> = providerResult

    override suspend fun unresolvedGaps(): Result<MonitoringGapAggregate> = gapResult
}

private fun providerPayload(
    provider: String,
    authFailure: String?,
    status: String = "EXITED",
    exitCode: String = "0",
): String {
    val auth = authFailure?.let { value -> ",\"authFailureSuspected\":\"$value\"" }.orEmpty()
    return """{"phase":"proposer","durationMillis":1,"details":{"provider":"$provider","status":"$status","exitCode":"$exitCode"$auth}}"""
}

private fun validProjection(): String {
    val invocation = "0123456789abcdef0123456789abcdef"
    val boot = "01234567-89ab-cdef-0123-456789abcdef"
    return """
        {
          "schemaVersion": 1,
          "publishedAt": "2026-07-19T00:10:00Z",
          "backup": {
            "service": {
              "state": "TERMINAL",
              "startedAt": "2026-07-19T00:00:00Z",
              "terminalAt": "2026-07-19T00:09:00Z",
              "terminalSemantic": "SUCCESS",
              "invocationId": "$invocation",
              "bootId": "$boot"
            },
            "lastAttempt": {
              "attemptedAt": "2026-07-19T00:00:00Z",
              "resultCode": "SUCCESS",
              "invocationId": "$invocation",
              "bootId": "$boot"
            },
            "lastSuccessAt": "2026-07-19T00:00:00Z"
          },
          "restore": {"service": null, "lastAttempt": null, "lastSuccessAt": null}
        }
    """.trimIndent()
}

private fun runningProjection(): String {
    val invocation = "0123456789abcdef0123456789abcdef"
    val boot = "01234567-89ab-cdef-0123-456789abcdef"
    return """
        {
          "schemaVersion": 1,
          "publishedAt": "2026-07-19T00:00:00Z",
          "backup": {
            "service": {
              "state": "RUNNING",
              "startedAt": "2026-07-19T00:00:00Z",
              "terminalAt": null,
              "terminalSemantic": null,
              "invocationId": "$invocation",
              "bootId": "$boot"
            },
            "lastAttempt": null,
            "lastSuccessAt": null
          },
          "restore": {"service": null, "lastAttempt": null, "lastSuccessAt": null}
        }
    """.trimIndent()
}
