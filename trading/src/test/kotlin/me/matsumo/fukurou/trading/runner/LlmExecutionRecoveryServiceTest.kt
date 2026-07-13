package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.daemon.InMemoryLlmLaunchReservationRepository
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealth
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimRequest
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimSnapshot
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryRequest
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryScan
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationOutcome
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRepository
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import me.matsumo.fukurou.trading.invoker.ProcessRunStatus
import me.matsumo.fukurou.trading.invoker.ProcessTreeTerminationProof
import me.matsumo.fukurou.trading.invoker.RenderedLlmCommand
import me.matsumo.fukurou.trading.invoker.ShellProcessRunner
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** current-process stale claim recovery の fence と競合を検証する。 */
class LlmExecutionRecoveryServiceTest {
    @AfterTest
    fun tearDown() {
        LlmExecutionAdmissionHealth.resetForTest()
        LlmExecutionTerminationFenceRegistry.resetForTest()
    }

    @Test
    fun dbRecovery_periodicTickRecoversAvailableWithoutRestart() = runBlocking {
        val now = RECOVERY_INSTANT
        val delegate = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        reserve(delegate, "available", now)
        val repository = FaultingRecoveryRepository(delegate, failedScans = 1)
        val service = recoveryService(repository, now.plusSeconds(1_800))

        assertTrue(service.tick().isFailure)
        assertFalse(LlmExecutionAdmissionHealth.isHealthy())

        assertEquals(1, service.tick().getOrThrow())
        assertTrue(LlmExecutionAdmissionHealth.isHealthy())
        assertEquals(
            LlmLaunchReservationStatus.FAILED,
            delegate.findExecutionClaim("available").getOrThrow()?.status,
        )
    }

    @Test
    fun periodicTickFailsClosedWithinFiveSecondWallClockBudget() = runBlocking {
        val delegate = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        val repository = SlowRecoveryRepository(delegate)
        val service = recoveryService(repository, RECOVERY_INSTANT)
        val startedAt = System.nanoTime()

        assertTrue(service.tick().isFailure)

        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)
        assertTrue(elapsed >= Duration.ofSeconds(5), "tick elapsed=$elapsed")
        assertTrue(elapsed < Duration.ofMillis(5_750), "tick elapsed=$elapsed")
        assertFalse(LlmExecutionAdmissionHealth.isHealthy())
    }

    @Test
    fun dbRecovery_periodicTickRecoversFencedClaimWithoutRestart() = runBlocking {
        val now = RECOVERY_INSTANT
        val delegate = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        reserve(delegate, "claimed-after-outage", now)
        claim(delegate, "claimed-after-outage", now)
        LlmExecutionTerminationFenceRegistry.registerNoChildStarted("claimed-after-outage", CLAIM_TOKEN, now)
        val repository = FaultingRecoveryRepository(delegate, failedScans = 1)
        val service = recoveryService(repository, now.plusSeconds(600))

        assertTrue(service.tick().isFailure)
        assertFalse(LlmExecutionAdmissionHealth.isHealthy())

        assertEquals(1, service.tick().getOrThrow())
        assertTrue(LlmExecutionAdmissionHealth.isHealthy())
        assertEquals(
            LlmLaunchReservationStatus.FAILED,
            delegate.findExecutionClaim("claimed-after-outage").getOrThrow()?.status,
        )
    }

    @Test
    fun claimedRecovery_requiresHardDeadlineThreeMissesAndTerminationFence() = runBlocking {
        val now = RECOVERY_INSTANT
        val clock = MutableClock(now.plusSeconds(579))
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        reserve(repository, "claimed", now)
        claim(repository, "claimed", now)
        val service = LlmExecutionRecoveryService(repository, OneShotExecutionPolicy.from(LlmRunnerConfig()), clock)

        assertEquals(0, service.tick().getOrThrow())
        assertTrue(LlmExecutionAdmissionHealth.isHealthy())

        clock.current = now.plusSeconds(580)
        assertEquals(0, service.tick().getOrThrow())
        assertFalse(LlmExecutionAdmissionHealth.isHealthy())
        assertEquals(
            LlmLaunchReservationStatus.RUNNING,
            repository.findExecutionClaim("claimed").getOrThrow()?.status,
        )

        LlmExecutionAdmissionHealth.recordHeartbeatResult("claimed", CLAIM_TOKEN, healthy = false)
        LlmExecutionTerminationFenceRegistry.registerNoChildStarted("claimed", CLAIM_TOKEN, now)
        assertEquals(1, service.tick().getOrThrow())
        assertTrue(LlmExecutionAdmissionHealth.isHealthy())
        assertEquals(
            LlmLaunchReservationStatus.FAILED,
            repository.findExecutionClaim("claimed").getOrThrow()?.status,
        )
    }

    @Test
    fun newerHeartbeatWinsRecoveryAndAdmissionDoesNotAutomaticallyResume() = runBlocking {
        val now = RECOVERY_INSTANT
        val delegate = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        reserve(delegate, "heartbeat-race", now)
        claim(delegate, "heartbeat-race", now)
        LlmExecutionTerminationFenceRegistry.registerNoChildStarted("heartbeat-race", CLAIM_TOKEN, now)
        val repository = HeartbeatRaceRepository(delegate, now.plusSeconds(580))
        val service = recoveryService(repository, now.plusSeconds(580))

        assertEquals(0, service.tick().getOrThrow())
        assertFalse(LlmExecutionAdmissionHealth.isHealthy())
        assertEquals(
            LlmLaunchReservationStatus.RUNNING,
            delegate.findExecutionClaim("heartbeat-race").getOrThrow()?.status,
        )
        assertEquals(
            now.plusSeconds(580),
            delegate.findExecutionClaim("heartbeat-race").getOrThrow()?.heartbeatAt,
        )
    }

    @Test
    fun keysetRecovery_recoversHundredThenRemainingOneWithoutSkipping() = runBlocking {
        val now = RECOVERY_INSTANT.plus(Duration.ofHours(1))
        val repository = KeysetRecoveryRepository(
            snapshots = (0..100).map { index ->
                LlmExecutionClaimSnapshot(
                    invocationId = "keyset-${index.toString().padStart(3, '0')}",
                    triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
                    status = LlmLaunchReservationStatus.RUNNING,
                    claimState = me.matsumo.fukurou.trading.daemon.LlmExecutionClaimState.AVAILABLE,
                    claimantToken = null,
                    claimedAt = null,
                    heartbeatAt = null,
                    reservedAt = RECOVERY_INSTANT.plusMillis(index.toLong()),
                )
            },
        )
        val service = recoveryService(repository, now)

        assertEquals(100, service.tick().getOrThrow())
        assertEquals(1, service.tick().getOrThrow())
        assertEquals(101, repository.recovered.size)
    }

    @Test
    fun linuxLiveChildHeartbeatOutage_recoversWithoutRestartOnlyAfterProcessGroupExit() = runBlocking {
        if (!Files.isExecutable(Path.of("/usr/bin/setsid"))) return@runBlocking
        val claimedAt = RECOVERY_INSTANT
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        reserve(repository, "live-child-outage", claimedAt)
        claim(repository, "live-child-outage", claimedAt)
        val tempDirectory = Files.createTempDirectory("fukurou-live-child-outage-test")
        val childPidFile = tempDirectory.resolve("child.pid")
        val command = RenderedLlmCommand(
            executable = "/bin/sh",
            args = listOf("-c", "(/bin/sleep 30) & echo $! > '$childPidFile'; wait"),
            environment = emptyMap(),
            workingDirectory = tempDirectory,
            timeout = Duration.ofSeconds(1),
            stdin = null,
        )
        val processResult = async { ShellProcessRunner(Duration.ofMillis(100)).run(command).getOrThrow() }
        repeat(100) {
            if (Files.exists(childPidFile)) return@repeat
            delay(10)
        }
        val childPid = Files.readString(childPidFile).trim().toLong()

        LlmExecutionAdmissionHealth.recordHeartbeatResult("live-child-outage", CLAIM_TOKEN, healthy = false)
        assertFalse(LlmExecutionAdmissionHealth.isHealthy())
        assertTrue(repository.tryReserve(launchRequest("blocked-during-outage", claimedAt.plusSeconds(1))).isFailure)

        val terminated = processResult.await()
        assertEquals(ProcessRunStatus.TIMED_OUT, terminated.status)
        assertEquals(ProcessTreeTerminationProof.PROVEN_EXITED, terminated.processTreeTerminationProof)
        LlmExecutionTerminationFenceRegistry.markProcessTreeExited(
            invocationId = "live-child-outage",
            claimantToken = CLAIM_TOKEN,
            observedAt = claimedAt.plusSeconds(1),
        )
        val service = recoveryService(repository, claimedAt.plusSeconds(600))

        assertEquals(1, service.tick().getOrThrow())
        assertTrue(LlmExecutionAdmissionHealth.isHealthy())
        assertEquals(
            LlmLaunchReservationStatus.FAILED,
            repository.findExecutionClaim("live-child-outage").getOrThrow()?.status,
        )
        assertFalse(isLinuxProcessRunning(childPid))
        assertTrue(repository.tryReserve(launchRequest("after-recovery", claimedAt.plusSeconds(601))).isSuccess)
    }

    @Test
    fun childStartLeaseWins_recoveryCannotCommitNoChildStarted() = runBlocking {
        val claimedAt = RECOVERY_INSTANT
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        reserve(repository, "child-start-lease", claimedAt)
        claim(repository, "child-start-lease", claimedAt)
        LlmExecutionTerminationFenceRegistry.registerNoChildStarted(
            invocationId = "child-start-lease",
            claimantToken = CLAIM_TOKEN,
            observedAt = claimedAt,
        )
        val leaseAcquired = CompletableDeferred<Unit>()
        val allowChildStart = CompletableDeferred<Unit>()
        val runnerTransition = async {
            LlmExecutionTerminationFenceRegistry.withClaimTransition("child-start-lease", CLAIM_TOKEN) {
                leaseAcquired.complete(Unit)
                allowChildStart.await()
                LlmExecutionTerminationFenceRegistry.markChildMayBeRunning("child-start-lease", CLAIM_TOKEN)
            }
        }
        leaseAcquired.await()
        val recovery = async { recoveryService(repository, claimedAt.plusSeconds(600)).tick().getOrThrow() }

        delay(50)
        assertFalse(recovery.isCompleted)
        allowChildStart.complete(Unit)
        runnerTransition.await()

        assertEquals(0, recovery.await())
        assertEquals(
            LlmLaunchReservationStatus.RUNNING,
            repository.findExecutionClaim("child-start-lease").getOrThrow()?.status,
        )
        assertFalse(LlmExecutionAdmissionHealth.isHealthy())
    }
}

private class MutableClock(var current: Instant) : Clock() {
    override fun instant(): Instant = current
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId): Clock = this
}

private class FaultingRecoveryRepository(
    private val delegate: LlmLaunchReservationRepository,
    private var failedScans: Int,
) : LlmLaunchReservationRepository by delegate {
    override suspend fun scanStaleExecutionClaims(scan: LlmExecutionRecoveryScan): Result<RecoverySnapshots> {
        return if (failedScans-- > 0) {
            Result.failure(IllegalStateException("db unavailable"))
        } else {
            delegate.scanStaleExecutionClaims(scan)
        }
    }
}

private class SlowRecoveryRepository(
    private val delegate: LlmLaunchReservationRepository,
) : LlmLaunchReservationRepository by delegate {
    override suspend fun scanStaleExecutionClaims(scan: LlmExecutionRecoveryScan): Result<RecoverySnapshots> {
        delay(6_000)
        return delegate.scanStaleExecutionClaims(scan)
    }
}

private class HeartbeatRaceRepository(
    private val delegate: LlmLaunchReservationRepository,
    private val newHeartbeat: Instant,
) : LlmLaunchReservationRepository by delegate {
    override suspend fun recoverStaleExecutionClaim(request: LlmExecutionRecoveryRequest): Result<Boolean> {
        delegate.heartbeatExecutionClaim(request.invocationId, requireNotNull(request.claimantToken), newHeartbeat)
            .getOrThrow()
        return delegate.recoverStaleExecutionClaim(request)
    }

    override suspend fun recoverStaleExecutionClaims(
        requests: List<LlmExecutionRecoveryRequest>,
    ): Result<Set<String>> = runCatching {
        requests.mapNotNullTo(mutableSetOf()) { request ->
            request.invocationId.takeIf { recoverStaleExecutionClaim(request).getOrThrow() }
        }
    }
}

private class KeysetRecoveryRepository(
    snapshots: List<LlmExecutionClaimSnapshot>,
) : LlmLaunchReservationRepository by InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository()) {
    private val remaining = snapshots.toMutableList()
    val recovered = mutableSetOf<String>()

    override suspend fun scanStaleExecutionClaims(scan: LlmExecutionRecoveryScan): Result<RecoverySnapshots> {
        return Result.success(
            remaining.asSequence()
                .filter { snapshot ->
                    val cursorId = scan.afterInvocationId
                    cursorId == null || snapshot.invocationId > cursorId
                }
                .take(scan.limit)
                .toList(),
        )
    }

    override suspend fun recoverStaleExecutionClaims(
        requests: List<LlmExecutionRecoveryRequest>,
    ): Result<Set<String>> {
        val invocationIds = requests.mapTo(mutableSetOf(), LlmExecutionRecoveryRequest::invocationId)
        remaining.removeAll { snapshot -> snapshot.invocationId in invocationIds }
        recovered += invocationIds
        return Result.success(invocationIds)
    }
}

private fun recoveryService(repository: LlmLaunchReservationRepository, now: Instant): LlmExecutionRecoveryService {
    return LlmExecutionRecoveryService(
        repository = repository,
        policy = OneShotExecutionPolicy.from(LlmRunnerConfig()),
        clock = Clock.fixed(now, ZoneId.of("UTC")),
    )
}

private fun launchRequest(invocationId: String, now: Instant): LlmLaunchReservationRequest {
    return LlmLaunchReservationRequest(
        invocationId = invocationId,
        triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
        triggerKey = invocationId,
        reservedAt = now,
        runnerConfig = LlmRunnerConfig(),
        hourlyWindow = Duration.ofHours(1),
        dailyWindow = Duration.ofDays(1),
        activeReservationStaleAfter = Duration.ofMinutes(30),
    )
}

private fun isLinuxProcessRunning(processId: Long): Boolean {
    val stat = runCatching { Files.readString(Path.of("/proc/$processId/stat")) }.getOrNull() ?: return false
    return stat.substringAfterLast(") ").firstOrNull() != 'Z'
}

private suspend fun reserve(
    repository: LlmLaunchReservationRepository,
    invocationId: String,
    now: Instant,
) {
    val outcome = repository.tryReserve(
        LlmLaunchReservationRequest(
            invocationId = invocationId,
            triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
            triggerKey = invocationId,
            reservedAt = now,
            runnerConfig = LlmRunnerConfig(),
            hourlyWindow = Duration.ofHours(1),
            dailyWindow = Duration.ofDays(1),
            activeReservationStaleAfter = Duration.ofMinutes(10),
        ),
    ).getOrThrow()
    require(outcome is LlmLaunchReservationOutcome.Reserved)
}

private suspend fun claim(
    repository: LlmLaunchReservationRepository,
    invocationId: String,
    now: Instant,
) {
    repository.claimForExecution(
        LlmExecutionClaimRequest(
            invocationId = invocationId,
            triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
            claimantToken = CLAIM_TOKEN,
            claimedAt = now,
        ),
    )
}

private const val CLAIM_TOKEN = "claim-token"
private val RECOVERY_INSTANT: Instant = Instant.parse("2026-01-01T00:00:00Z")
private typealias RecoverySnapshots = List<LlmExecutionClaimSnapshot>
