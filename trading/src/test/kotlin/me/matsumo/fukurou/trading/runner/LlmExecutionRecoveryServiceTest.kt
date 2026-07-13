package me.matsumo.fukurou.trading.runner

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
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
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
