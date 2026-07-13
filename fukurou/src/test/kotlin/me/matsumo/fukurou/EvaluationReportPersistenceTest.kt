@file:Suppress("ImportOrdering")

package me.matsumo.fukurou

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationOutcome
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.persistence.ExposedLlmLaunchReservationRepository
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.persistence.ExposedMarketDataIntegrityRepository
import me.matsumo.fukurou.trading.market.MarketDataGapReason
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** evaluation report と shared LLM reservation の persistence contract を検証する。 */
class EvaluationReportPersistenceTest {

    @Test
    fun gapFirstAndRecoveryFirstBarriersPreventPublicationAndFailJobWithReservationAtomically() = runBlocking {
        if (!reportDockerAvailable()) return@runBlocking
        val container = ReportPostgresContainer().also { it.start() }
        try {
            val database = ExposedDatabase.connect(container.jdbcUrl, "org.postgresql.Driver", container.username, container.password)
            val clock = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC)
            TradingPersistenceBootstrap(database, clock).ensureSchema().getOrThrow()
            val epochId = currentReportEpoch(container)
            val config = TradingBotConfig.fromEnvironment(emptyMap())
            val persistence = EvaluationReportPersistence(
                database, config.runner, config.daemon.launchReservationStaleAfter, clock, config.mode, config.symbol,
            )
            val job = testJob("00000000-0000-0000-0000-000000000010")
            assertIs<LlmLaunchReservationOutcome.Reserved>(
                persistence.admit(job, "PRESET:30D|EPOCH:$epochId|COHORT:CURRENT").getOrThrow().reservationOutcome,
            )

            val integrity = ExposedMarketDataIntegrityRepository(database)
            val sessionId = java.util.UUID.randomUUID()
            integrity.beginSession(sessionId, clock.instant()).getOrThrow()
            integrity.markDisconnected(sessionId, MarketDataGapReason.PROCESS_RESTART, clock.instant().plusSeconds(1)).getOrThrow()
            kotlin.test.assertTrue(
                persistence.complete(
                    terminalRaceReport(job, "PRESET:30D|EPOCH:$epochId|COHORT:CURRENT"),
                    job,
                ).isFailure,
            )
            assertEquals("REQUESTED", persistence.job(job.jobId).getOrThrow()?.status)
            assertNotNull(
                ExposedLlmLaunchReservationRepository(database)
                    .findBlockingRunningReservation(LlmDaemonTriggerKind.MANUAL, clock.instant().minus(Duration.ofMinutes(30)))
                    .getOrThrow(),
            )
            assertReportPublicationCount(container, revisions = 0, pins = 0)

            repeat(2) { pass -> integrity.recoverStaleSession(clock.instant().plusSeconds(2 + pass.toLong())).getOrThrow() }

            assertEquals("FAILED", persistence.job(job.jobId).getOrThrow()?.status)
            assertEquals("MARKET_DATA_GAP", persistence.job(job.jobId).getOrThrow()?.failureCode)
            kotlin.test.assertTrue(
                persistence.complete(
                    terminalRaceReport(job, "PRESET:30D|EPOCH:$epochId|COHORT:CURRENT"),
                    job,
                ).isFailure,
            )
            assertNull(
                ExposedLlmLaunchReservationRepository(database)
                    .findBlockingRunningReservation(LlmDaemonTriggerKind.MANUAL, clock.instant().minus(Duration.ofMinutes(30)))
                    .getOrThrow(),
            )
            assertReportPublicationCount(container, revisions = 0, pins = 0)
        } finally {
            container.stop()
        }
    }

    @Test
    fun completeFirstAttemptPausedBeforeTransactionLosesToGapBarrierWithoutRevisionPinOrResurrection() = runBlocking {
        if (!reportDockerAvailable()) return@runBlocking
        val container = ReportPostgresContainer().also { it.start() }
        try {
            val database = ExposedDatabase.connect(container.jdbcUrl, "org.postgresql.Driver", container.username, container.password)
            val clock = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC)
            TradingPersistenceBootstrap(database, clock).ensureSchema().getOrThrow()
            val epochId = currentReportEpoch(container)
            val config = TradingBotConfig.fromEnvironment(emptyMap())
            val completeStarted = CountDownLatch(1)
            val allowCompleteTransaction = CountDownLatch(1)
            val persistence = EvaluationReportPersistence(
                database = database,
                runnerConfig = config.runner,
                staleAfter = config.daemon.launchReservationStaleAfter,
                clock = clock,
                mode = config.mode,
                symbol = config.symbol,
                beforeCompleteTransaction = {
                    completeStarted.countDown()
                    check(allowCompleteTransaction.await(30, TimeUnit.SECONDS))
                },
            )
            val scopeKey = "PRESET:30D|EPOCH:$epochId|COHORT:CURRENT"
            val job = testJob("00000000-0000-0000-0000-000000000011")
            assertIs<LlmLaunchReservationOutcome.Reserved>(persistence.admit(job, scopeKey).getOrThrow().reservationOutcome)

            val completion = async(Dispatchers.IO) { persistence.complete(terminalRaceReport(job, scopeKey), job) }
            kotlin.test.assertTrue(completeStarted.await(30, TimeUnit.SECONDS))
            val integrity = ExposedMarketDataIntegrityRepository(database)
            val sessionId = java.util.UUID.randomUUID()
            integrity.beginSession(sessionId, clock.instant()).getOrThrow()
            integrity.recordGap(sessionId, MarketDataGapReason.PROCESS_RESTART, clock.instant().plusSeconds(1)).getOrThrow()
            repeat(2) { pass -> integrity.recoverStaleSession(clock.instant().plusSeconds(2 + pass.toLong())).getOrThrow() }
            allowCompleteTransaction.countDown()

            kotlin.test.assertTrue(completion.await().isFailure)
            assertEquals("FAILED", persistence.job(job.jobId).getOrThrow()?.status)
            assertEquals("MARKET_DATA_GAP", persistence.job(job.jobId).getOrThrow()?.failureCode)
            assertNull(
                ExposedLlmLaunchReservationRepository(database)
                    .findBlockingRunningReservation(LlmDaemonTriggerKind.MANUAL, clock.instant().minus(Duration.ofMinutes(30)))
                    .getOrThrow(),
            )
            assertReportPublicationCount(container, revisions = 0, pins = 0)
        } finally {
            container.stop()
        }
    }

    @Test
    fun admission_serializesWithSharedReservationAndRecoversRejectedAndInterruptedJobs() = runBlocking {
        if (!reportDockerAvailable()) return@runBlocking
        val container = ReportPostgresContainer().also { it.start() }
        try {
            val database = ExposedDatabase.connect(container.jdbcUrl, "org.postgresql.Driver", container.username, container.password)
            val clock = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC)
            TradingPersistenceBootstrap(database, clock).ensureSchema().getOrThrow()
            val epochId = java.sql.DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
                connection.prepareStatement("SELECT current_epoch_id::text FROM paper_account WHERE id=1").use { statement ->
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getString(1)
                    }
                }
            }
            val config = TradingBotConfig.fromEnvironment(emptyMap())
            val persistence = EvaluationReportPersistence(
                database,
                config.runner,
                config.daemon.launchReservationStaleAfter,
                clock,
                config.mode,
                config.symbol,
            )
            val admissions = listOf(
                async(Dispatchers.IO) {
                    persistence.admit(testJob("00000000-0000-0000-0000-000000000001"), "PRESET:30D|EPOCH:$epochId|COHORT:CURRENT").getOrThrow()
                },
                async(Dispatchers.IO) {
                    persistence.admit(testJob("00000000-0000-0000-0000-000000000002"), "PRESET:30D|EPOCH:$epochId|COHORT:CURRENT").getOrThrow()
                },
            ).awaitAll()
            val first = admissions.single { admission -> admission.reservationOutcome is LlmLaunchReservationOutcome.Reserved }
            val second = admissions.single { admission -> admission.reservationOutcome is LlmLaunchReservationOutcome.Rejected }

            assertIs<LlmLaunchReservationOutcome.Reserved>(first.reservationOutcome)
            val rejected = assertIs<LlmLaunchReservationOutcome.Rejected>(second.reservationOutcome)
            assertEquals(first.job.jobId, rejected.activeReservation?.invocationId)
            assertEquals("REJECTED", persistence.job(second.job.jobId).getOrThrow()?.status)
            assertNotEquals(first.job.revisionNumber, second.job.revisionNumber)
            val reportReservation = ExposedLlmLaunchReservationRepository(database)
                .findBlockingRunningReservation(LlmDaemonTriggerKind.MANUAL, clock.instant().minus(Duration.ofMinutes(30)))
                .getOrThrow()
            assertEquals(LlmDaemonTriggerKind.EVALUATION_REPORT, reportReservation?.triggerKind)

            EvaluationReportPersistence(
                database, config.runner, config.daemon.launchReservationStaleAfter, clock, config.mode, config.symbol,
            )
            assertEquals("FAILED_PROCESS_INTERRUPTED", persistence.job(first.job.jobId).getOrThrow()?.failureCode)
            val blocker = ExposedLlmLaunchReservationRepository(database)
                .findBlockingRunningReservation(
                    me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind.MANUAL,
                    clock.instant().minus(Duration.ofMinutes(30)),
                ).getOrThrow()
            assertNull(blocker)

            val sharedRepository = ExposedLlmLaunchReservationRepository(database)
            sharedRepository.tryReserve(
                LlmLaunchReservationRequest(
                    invocationId = "scheduler-active",
                    triggerKind = LlmDaemonTriggerKind.MANUAL,
                    triggerKey = "manual:test",
                    reservedAt = clock.instant(),
                    runnerConfig = config.runner,
                    hourlyWindow = Duration.ofHours(1),
                    dailyWindow = Duration.ofDays(1),
                    activeReservationStaleAfter = config.daemon.launchReservationStaleAfter,
                    populationScope = me.matsumo.fukurou.trading.daemon.LlmLaunchReservationPopulationScope(
                        kind = "SYMBOL",
                        mode = config.mode,
                        symbol = config.symbol,
                    ),
                ),
            ).getOrThrow()
            val blockedReport = persistence.admit(
                testJob("00000000-0000-0000-0000-000000000003"),
                "PRESET:7D|EPOCH:$epochId|COHORT:CURRENT",
            ).getOrThrow()
            val schedulerBlock = assertIs<LlmLaunchReservationOutcome.Rejected>(blockedReport.reservationOutcome)
            assertEquals("scheduler-active", schedulerBlock.activeReservation?.invocationId)
            assertEquals(LlmDaemonTriggerKind.MANUAL, schedulerBlock.activeReservation?.triggerKind)
            val rateLimited = persistence.admit(
                testJob("00000000-0000-0000-0000-000000000004"),
                "PRESET:90D|EPOCH:$epochId|COHORT:CURRENT",
            ).getOrThrow()
            val rateRejection = assertIs<LlmLaunchReservationOutcome.Rejected>(rateLimited.reservationOutcome)
            assertEquals(me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRejectionReason.REPORT_RATE_LIMIT, rateRejection.reason)
            assertEquals("REPORT_RATE_LIMIT", persistence.job(rateLimited.job.jobId).getOrThrow()?.failureCode)
            assertEquals(listOf("REJECTED"), persistence.jobEvents(rateLimited.job.jobId).getOrThrow().map { event -> event.status })
            assertEquals(
                listOf("REQUESTED", "FAILED"),
                persistence.jobEvents(first.job.jobId).getOrThrow().map { event -> event.status },
            )
        } finally {
            container.stop()
        }
    }
}

private fun currentReportEpoch(container: ReportPostgresContainer): String {
    return java.sql.DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.prepareStatement("SELECT current_epoch_id::text FROM paper_account WHERE id=1").use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
    }
}

private fun assertReportPublicationCount(
    container: ReportPostgresContainer,
    revisions: Int,
    pins: Int,
) {
    java.sql.DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().executeQuery("SELECT COUNT(*) FROM evaluation_report_revisions").use { rows ->
            kotlin.test.assertTrue(rows.next())
            assertEquals(revisions, rows.getInt(1))
        }
        connection.createStatement().executeQuery("SELECT COUNT(*) FROM evaluation_report_pins").use { rows ->
            kotlin.test.assertTrue(rows.next())
            assertEquals(pins, rows.getInt(1))
        }
    }
}

private fun terminalRaceReport(job: EvaluationReportJobResponse, scopeKey: String): EvaluationReportResponse {
    return EvaluationReportResponse(
        jobId = job.jobId,
        revisionId = job.revisionId,
        revisionNumber = 1,
        scopeKey = scopeKey,
        status = "SUCCEEDED",
        period = EvaluationReportPeriodResponse("2026-07-01", "2026-07-12", "UTC"),
        inputAsOf = "2026-07-12T00:00:00Z",
        inputHash = "input-hash",
        snapshotId = java.util.UUID.randomUUID().toString(),
        generatedAt = "2026-07-12T00:00:00Z",
        provider = "fixture",
        model = "fixture",
        generation = EvaluationReportGenerationMetadataResponse("fixture", "fixture", null, null, null),
        title = "fixture",
        segments = emptyList(),
        claims = emptyList(),
        validation = emptyList(),
        facts = emptyList(),
        sources = emptyList(),
        chartIndex = emptyList(),
        outcomeRidge = OutcomeRidgeResponse(
            "fixture",
            "R_MULTIPLE",
            OutcomeRidgeDomainResponse("-1", "1", "1"),
            emptyList(),
            emptyList(),
        ),
        benchmark = ReportBenchmarkChartResponse(null, emptyList(), null, null, "EMPTY"),
        calibration = ReportCalibrationChartResponse("PROBABILITY", "fixture", emptyList(), "EMPTY"),
        performanceLattice = ReportPerformanceLatticeResponse("JPY", "fixture", emptyList(), "EMPTY"),
        integrity = EvaluationIntegrityResponse(0, 0, 0, 0, 0, emptyMap(), 0, 0, 0, null, false),
        truncated = false,
    )
}

private fun testJob(jobId: String) = EvaluationReportJobResponse(
    jobId = jobId,
    revisionId = java.util.UUID.randomUUID().toString(),
    status = "REQUESTED",
    stage = "ADMITTED",
)

private class ReportPostgresContainer : PostgreSQLContainer<ReportPostgresContainer>("postgres:16-alpine")

private fun reportDockerAvailable(): Boolean = runCatching {
    DockerClientFactory.instance().client().pingCmd().exec()
    true
}.getOrDefault(false)
