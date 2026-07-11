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
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** evaluation report と shared LLM reservation の persistence contract を検証する。 */
class EvaluationReportPersistenceTest {

    @Test
    fun admission_serializesWithSharedReservationAndRecoversRejectedAndInterruptedJobs() = runBlocking {
        if (!reportDockerAvailable()) return@runBlocking
        val container = ReportPostgresContainer().also { it.start() }
        try {
            val database = ExposedDatabase.connect(container.jdbcUrl, "org.postgresql.Driver", container.username, container.password)
            val clock = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC)
            TradingPersistenceBootstrap(database, clock).ensureSchema().getOrThrow()
            val config = TradingBotConfig.fromEnvironment(emptyMap())
            val persistence = EvaluationReportPersistence(
                database,
                config.runner,
                config.daemon.launchReservationStaleAfter,
                clock,
            )
            val admissions = listOf(
                async(Dispatchers.IO) { persistence.admit(testJob("00000000-0000-0000-0000-000000000001"), "PRESET:30D").getOrThrow() },
                async(Dispatchers.IO) { persistence.admit(testJob("00000000-0000-0000-0000-000000000002"), "PRESET:30D").getOrThrow() },
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

            EvaluationReportPersistence(database, config.runner, config.daemon.launchReservationStaleAfter, clock)
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
                ),
            ).getOrThrow()
            val blockedReport = persistence.admit(
                testJob("00000000-0000-0000-0000-000000000003"),
                "PRESET:7D",
            ).getOrThrow()
            val schedulerBlock = assertIs<LlmLaunchReservationOutcome.Rejected>(blockedReport.reservationOutcome)
            assertEquals("scheduler-active", schedulerBlock.activeReservation?.invocationId)
            assertEquals(LlmDaemonTriggerKind.MANUAL, schedulerBlock.activeReservation?.triggerKind)
        } finally {
            container.stop()
        }
    }
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
