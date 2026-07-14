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
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryDeadline
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryOutcome
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryRequest
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryRetryPermit
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryScan
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationOutcome
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationPopulationScope
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRepository
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
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
import java.util.concurrent.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
        LlmExecutionTerminationFenceRegistry.registerNoChildStarted(
            invocationId = "available",
            claimantToken = MISSING_CLAIMANT_TOKEN,
            observedAt = now,
        )

        assertTrue(service.tick().isFailure)
        assertFalse(LlmExecutionAdmissionHealth.isHealthy())

        assertEquals(1, service.tick().getOrThrow())
        assertTrue(LlmExecutionAdmissionHealth.isHealthy())
        assertEquals(
            LlmLaunchReservationStatus.FAILED,
            delegate.findExecutionClaim("available").getOrThrow()?.status,
        )
        assertEquals(0, LlmExecutionTerminationFenceRegistry.fenceCountForTest())
        assertEquals(0, LlmExecutionTerminationFenceRegistry.transitionLockCountForTest())
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
        assertEquals(1, repository.scanCount)
        assertEquals(100, repository.singleRecoveryCount)
        assertEquals(1, service.tick().getOrThrow())
        assertEquals(101, repository.recovered.size)
        assertEquals(2, repository.scanCount)
        assertEquals(101, repository.singleRecoveryCount)
        assertEquals(0, LlmExecutionTerminationFenceRegistry.transitionLockCountForTest())
    }

    @Test
    fun hundredCandidatePageHoldsEveryTransitionLockWhileSingleEntityRecoveryRuns() = runBlocking {
        val now = RECOVERY_INSTANT.plus(Duration.ofHours(1))
        val snapshots = (0 until 100).map { index ->
            val invocationId = "batch-${index.toString().padStart(3, '0')}"
            val claimantToken = "claim-token-${index.toString().padStart(3, '0')}"
            LlmExecutionTerminationFenceRegistry.registerNoChildStarted(
                invocationId = invocationId,
                claimantToken = claimantToken,
                observedAt = RECOVERY_INSTANT,
            )
            LlmExecutionClaimSnapshot(
                invocationId = invocationId,
                triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
                status = LlmLaunchReservationStatus.RUNNING,
                claimState = me.matsumo.fukurou.trading.daemon.LlmExecutionClaimState.CLAIMED,
                claimantToken = claimantToken,
                claimedAt = RECOVERY_INSTANT,
                heartbeatAt = RECOVERY_INSTANT,
                reservedAt = RECOVERY_INSTANT,
            )
        }
        val repository = BlockingSingleRecoveryRepository(snapshots)
        val recovery = async { recoveryService(repository, now).tick().getOrThrow() }
        repository.batchEntered.await()
        assertFalse(LlmExecutionAdmissionHealth.isHealthy())

        val childTransitionEntered = CompletableDeferred<Unit>()
        val childTransition = async {
            LlmExecutionTerminationFenceRegistry.withClaimTransition(
                invocationId = "batch-050",
                claimantToken = "claim-token-050",
            ) {
                childTransitionEntered.complete(Unit)
            }
        }

        delay(50)
        assertFalse(childTransitionEntered.isCompleted)
        repository.allowBatchCommit.complete(Unit)

        assertEquals(100, recovery.await())
        childTransition.await()
        assertTrue(childTransitionEntered.isCompleted)
        assertEquals(1, repository.scanCount)
        assertEquals(100, repository.singleRecoveryCount)
        assertEquals(100, repository.recovered.size)
        assertEquals(0, LlmExecutionTerminationFenceRegistry.fenceCountForTest())
        assertEquals(0, LlmExecutionTerminationFenceRegistry.transitionLockCountForTest())
    }

    @Test
    fun recoveryFailureKeepsHealthClosedAndRetriesSameCursorPage() = runBlocking {
        val now = RECOVERY_INSTANT.plus(Duration.ofHours(1))
        val snapshots = (0 until 100).map { index ->
            LlmExecutionClaimSnapshot(
                invocationId = "retry-${index.toString().padStart(3, '0')}",
                triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
                status = LlmLaunchReservationStatus.RUNNING,
                claimState = me.matsumo.fukurou.trading.daemon.LlmExecutionClaimState.AVAILABLE,
                claimantToken = null,
                claimedAt = null,
                heartbeatAt = null,
                reservedAt = RECOVERY_INSTANT.plusMillis(index.toLong()),
            )
        }
        val repository = CursorRetryRecoveryRepository(snapshots)
        val service = recoveryService(repository, now)

        assertTrue(service.tick().isFailure)
        assertFalse(LlmExecutionAdmissionHealth.isHealthy())
        assertEquals(listOf<String?>(null), repository.scanCursors)

        assertEquals(100, service.tick().getOrThrow())
        assertTrue(LlmExecutionAdmissionHealth.isHealthy())
        assertEquals(listOf<String?>(null, null), repository.scanCursors)
        assertEquals(100, repository.recovered.size)
    }

    @Test
    fun pendingIsStagedBeforeRecoveryThrowAndNextTickReconcilesBeforeScan() = runBlocking {
        val repository = PendingBeforeCallFailureRepository(
            snapshot = availableRecoverySnapshot("throw-before-call"),
            failure = IllegalStateException("mutation call failed"),
        )
        val service = recoveryService(repository, RECOVERY_INSTANT.plus(Duration.ofHours(1)))

        assertTrue(service.tick().isFailure)
        assertFalse(LlmExecutionAdmissionHealth.isHealthy())
        assertEquals(1, repository.recoveryCalls)
        assertEquals(0, repository.reconcileCalls)

        assertEquals(1, service.tick().getOrThrow())
        assertTrue(LlmExecutionAdmissionHealth.isHealthy())
        assertEquals(1, repository.recoveryCalls)
        assertEquals(1, repository.reconcileCalls)
        assertEquals(listOf(true), repository.reconcilePermitAvailability)
        assertTrue(repository.receivedSamePermit)
        assertEquals(2, repository.scanCalls)
    }

    @Test
    fun pendingIsStagedBeforeRecoveryCancellationAndNextTickRedriveConverges() = runBlocking {
        val repository = PendingBeforeCallFailureRepository(
            snapshot = availableRecoverySnapshot("cancel-before-call"),
            failure = CancellationException("cancel before mutation"),
        )
        val service = recoveryService(repository, RECOVERY_INSTANT.plus(Duration.ofHours(1)))

        assertTrue(service.tick().isFailure)
        assertFalse(LlmExecutionAdmissionHealth.isHealthy())

        assertEquals(1, service.tick().getOrThrow())
        assertTrue(LlmExecutionAdmissionHealth.isHealthy())
        assertEquals(1, repository.recoveryCalls)
        assertEquals(1, repository.reconcileCalls)
        assertEquals(listOf(true), repository.reconcilePermitAvailability)
        assertTrue(repository.receivedSamePermit)
        assertEquals(2, repository.scanCalls)
    }

    @Test
    fun inMemoryReconciliationDoesNotRestoreConsumedRetryPermit() = runBlocking {
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        reserve(repository, "consumed-budget", RECOVERY_INSTANT)
        val snapshot = requireNotNull(repository.findExecutionClaim("consumed-budget").getOrThrow())
        val request = LlmExecutionRecoveryRequest(
            invocationId = snapshot.invocationId,
            claimState = requireNotNull(snapshot.claimState),
            claimantToken = snapshot.claimantToken,
            observedHeartbeatAt = snapshot.heartbeatAt,
            observedReservedAt = snapshot.reservedAt,
            finishedAt = RECOVERY_INSTANT.plusSeconds(1_800),
            reason = "STALE_AVAILABLE_RESERVATION_RECOVERED",
            terminationFence = "NO_CHILD_STARTED",
        )
        val retryPermit = LlmExecutionRecoveryRetryPermit()
        assertTrue(retryPermit.tryConsume())

        val outcome = repository.reconcileStaleExecutionRecovery(
            request = request,
            deadline = LlmExecutionRecoveryDeadline.start(Duration.ofSeconds(5), System::nanoTime),
            retryPermit = retryPermit,
        ).getOrThrow()

        assertIs<LlmExecutionRecoveryOutcome.OutcomeUnknown>(outcome)
        assertFalse(retryPermit.tryConsume())
    }

    @Test
    fun consumedRetryPermitSurvivesCancellationAndPreventsThirdMutation() = runBlocking {
        val snapshot = availableRecoverySnapshot("post-consume-cancel")
        val repository = PostConsumeCancellationRepository(snapshot)
        val service = recoveryService(repository, RECOVERY_INSTANT.plus(Duration.ofHours(1)))
        LlmExecutionTerminationFenceRegistry.registerNoChildStarted(
            invocationId = snapshot.invocationId,
            claimantToken = MISSING_CLAIMANT_TOKEN,
            observedAt = RECOVERY_INSTANT,
        )

        assertTrue(service.tick().isFailure)
        assertFalse(LlmExecutionAdmissionHealth.isHealthy())
        assertEquals(2, repository.mutationEntries)
        assertEquals(1, repository.explicitRetryStarts)
        assertEquals(1, repository.scanCalls)
        assertEquals(1, LlmExecutionTerminationFenceRegistry.fenceCountForTest())

        repeat(2) {
            assertTrue(service.tick().isFailure)
            assertFalse(LlmExecutionAdmissionHealth.isHealthy())
            assertEquals(2, repository.mutationEntries)
            assertEquals(1, repository.explicitRetryStarts)
            assertEquals(1, repository.scanCalls)
            assertTrue(repository.receivedSamePermit)
            assertEquals(1, LlmExecutionTerminationFenceRegistry.fenceCountForTest())
        }
    }

    @Test
    fun latePageFailureReleasesCommittedClaimsAndRetryConvergesRemainingClaim() = runBlocking {
        val snapshots = (1..3).map { index -> claimedRecoverySnapshot("late-$index") }
        snapshots.forEach { snapshot ->
            val claimantToken = requireNotNull(snapshot.claimantToken)
            LlmExecutionAdmissionHealth.registerRecoveryBlocker(snapshot.invocationId, claimantToken)
            LlmExecutionTerminationFenceRegistry.registerNoChildStarted(
                invocationId = snapshot.invocationId,
                claimantToken = claimantToken,
                observedAt = RECOVERY_INSTANT,
            )
        }
        val repository = LatePageFailureRepository(snapshots)
        val service = recoveryService(repository, RECOVERY_INSTANT.plus(Duration.ofHours(1)))

        assertTrue(service.tick().isFailure)
        assertEquals(listOf("late-1", "late-2"), repository.recovered)
        assertEquals(listOf<String?>(null), repository.scanCursors)
        assertEquals(1, LlmExecutionTerminationFenceRegistry.fenceCountForTest())
        assertFalse(LlmExecutionAdmissionHealth.isHealthy())

        assertEquals(1, service.tick().getOrThrow())
        assertEquals(listOf("late-1", "late-2", "late-3"), repository.recovered)
        assertEquals(listOf<String?>(null, null), repository.scanCursors)
        assertEquals(0, LlmExecutionTerminationFenceRegistry.fenceCountForTest())
        assertEquals(0, LlmExecutionTerminationFenceRegistry.transitionLockCountForTest())
        assertTrue(LlmExecutionAdmissionHealth.isHealthy())
    }

    @Test
    fun monotonicDeadlineRejectsWorkInsideStartReserve() {
        var nowNanos = 1_000_000_000L
        val deadline = LlmExecutionRecoveryDeadline.start(Duration.ofSeconds(5)) { nowNanos }

        nowNanos += Duration.ofMillis(4_249).toNanos()
        assertEquals(751L, deadline.requireStartReserve({ nowNanos }, 750L))

        nowNanos += Duration.ofMillis(2).toNanos()
        assertFailsWith<me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryDeadlineExceededException> {
            deadline.requireStartReserve({ nowNanos }, 750L)
        }
    }

    @Test
    fun recoveryRequestFinishedAtIsNormalizedToDatabaseMillisecondPrecision() = runBlocking {
        val repository = KeysetRecoveryRepository(listOf(availableRecoverySnapshot("sub-millisecond")))
        val now = RECOVERY_INSTANT.plus(Duration.ofHours(1)).plusNanos(456_789)

        assertEquals(1, recoveryService(repository, now).tick().getOrThrow())
        assertEquals(Instant.ofEpochMilli(now.toEpochMilli()), repository.recoveryRequests.single().finishedAt)
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
            LlmExecutionTerminationFenceRegistry.withClaimTransition(
                invocationId = "child-start-lease",
                claimantToken = CLAIM_TOKEN,
            ) {
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
    override suspend fun scanStaleExecutionClaims(
        scan: LlmExecutionRecoveryScan,
        deadline: LlmExecutionRecoveryDeadline,
    ): Result<RecoverySnapshots> {
        return if (failedScans-- > 0) {
            Result.failure(IllegalStateException("db unavailable"))
        } else {
            delegate.scanStaleExecutionClaims(scan, deadline)
        }
    }
}

private class SlowRecoveryRepository(
    private val delegate: LlmLaunchReservationRepository,
) : LlmLaunchReservationRepository by delegate {
    override suspend fun scanStaleExecutionClaims(
        scan: LlmExecutionRecoveryScan,
        deadline: LlmExecutionRecoveryDeadline,
    ): Result<RecoverySnapshots> {
        delay(6_000)
        return delegate.scanStaleExecutionClaims(scan, deadline)
    }
}

private class HeartbeatRaceRepository(
    private val delegate: LlmLaunchReservationRepository,
    private val newHeartbeat: Instant,
) : LlmLaunchReservationRepository by delegate {
    override suspend fun recoverStaleExecutionClaim(
        request: LlmExecutionRecoveryRequest,
        deadline: LlmExecutionRecoveryDeadline,
        retryPermit: LlmExecutionRecoveryRetryPermit,
    ): Result<LlmExecutionRecoveryOutcome> {
        delegate.heartbeatExecutionClaim(request.invocationId, requireNotNull(request.claimantToken), newHeartbeat)
            .getOrThrow()
        return delegate.recoverStaleExecutionClaim(
            request = request,
            deadline = deadline,
            retryPermit = retryPermit,
        )
    }
}

private class KeysetRecoveryRepository(
    snapshots: List<LlmExecutionClaimSnapshot>,
) : LlmLaunchReservationRepository by InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository()) {
    private val remaining = snapshots.toMutableList()
    val recovered = mutableSetOf<String>()
    val recoveryRequests = mutableListOf<LlmExecutionRecoveryRequest>()
    var scanCount = 0
    var singleRecoveryCount = 0

    override suspend fun scanStaleExecutionClaims(
        scan: LlmExecutionRecoveryScan,
        deadline: LlmExecutionRecoveryDeadline,
    ): Result<RecoverySnapshots> {
        scanCount += 1
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

    override suspend fun recoverStaleExecutionClaim(
        request: LlmExecutionRecoveryRequest,
        deadline: LlmExecutionRecoveryDeadline,
        retryPermit: LlmExecutionRecoveryRetryPermit,
    ): Result<LlmExecutionRecoveryOutcome> {
        singleRecoveryCount += 1
        recoveryRequests += request
        remaining.removeAll { snapshot -> snapshot.invocationId == request.invocationId }
        recovered += request.invocationId
        return Result.success(LlmExecutionRecoveryOutcome.Recovered)
    }
}

private class BlockingSingleRecoveryRepository(
    private val snapshots: List<LlmExecutionClaimSnapshot>,
) : LlmLaunchReservationRepository by InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository()) {
    val batchEntered = CompletableDeferred<Unit>()
    val allowBatchCommit = CompletableDeferred<Unit>()
    val recovered = mutableSetOf<String>()
    var scanCount = 0
    var singleRecoveryCount = 0

    override suspend fun scanStaleExecutionClaims(
        scan: LlmExecutionRecoveryScan,
        deadline: LlmExecutionRecoveryDeadline,
    ): Result<RecoverySnapshots> {
        scanCount += 1
        return Result.success(
            snapshots.filterNot { snapshot -> snapshot.invocationId in recovered }.take(scan.limit),
        )
    }

    override suspend fun recoverStaleExecutionClaim(
        request: LlmExecutionRecoveryRequest,
        deadline: LlmExecutionRecoveryDeadline,
        retryPermit: LlmExecutionRecoveryRetryPermit,
    ): Result<LlmExecutionRecoveryOutcome> {
        singleRecoveryCount += 1
        if (singleRecoveryCount == 1) {
            batchEntered.complete(Unit)
            allowBatchCommit.await()
        }
        recovered += request.invocationId
        return Result.success(LlmExecutionRecoveryOutcome.Recovered)
    }
}

private class CursorRetryRecoveryRepository(
    private val snapshots: List<LlmExecutionClaimSnapshot>,
) : LlmLaunchReservationRepository by InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository()) {
    val scanCursors = mutableListOf<String?>()
    val recovered = mutableSetOf<String>()
    private var recoveryAttempts = 0
    private var pendingRequest: LlmExecutionRecoveryRequest? = null

    override suspend fun scanStaleExecutionClaims(
        scan: LlmExecutionRecoveryScan,
        deadline: LlmExecutionRecoveryDeadline,
    ): Result<RecoverySnapshots> {
        scanCursors += scan.afterInvocationId
        return Result.success(
            snapshots.filterNot { snapshot -> snapshot.invocationId in recovered }.take(scan.limit),
        )
    }

    override suspend fun recoverStaleExecutionClaim(
        request: LlmExecutionRecoveryRequest,
        deadline: LlmExecutionRecoveryDeadline,
        retryPermit: LlmExecutionRecoveryRetryPermit,
    ): Result<LlmExecutionRecoveryOutcome> {
        recoveryAttempts += 1
        if (recoveryAttempts == 1) {
            pendingRequest = request
            return Result.failure(IllegalStateException("recovery unavailable"))
        }

        recovered += request.invocationId
        return Result.success(LlmExecutionRecoveryOutcome.Recovered)
    }

    override suspend fun reconcileStaleExecutionRecovery(
        request: LlmExecutionRecoveryRequest,
        deadline: LlmExecutionRecoveryDeadline,
        retryPermit: LlmExecutionRecoveryRetryPermit,
    ): Result<LlmExecutionRecoveryOutcome> {
        assertEquals(pendingRequest, request)
        recovered += request.invocationId
        pendingRequest = null
        return Result.success(LlmExecutionRecoveryOutcome.Recovered)
    }
}

private class LatePageFailureRepository(
    snapshots: List<LlmExecutionClaimSnapshot>,
) : LlmLaunchReservationRepository by InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository()) {
    private val remaining = snapshots.toMutableList()
    val scanCursors = mutableListOf<String?>()
    val recovered = mutableListOf<String>()
    private var failedLateEntity = false
    private var pendingRequest: LlmExecutionRecoveryRequest? = null

    override suspend fun scanStaleExecutionClaims(
        scan: LlmExecutionRecoveryScan,
        deadline: LlmExecutionRecoveryDeadline,
    ): Result<RecoverySnapshots> {
        scanCursors += scan.afterInvocationId
        return Result.success(remaining.take(scan.limit))
    }

    override suspend fun recoverStaleExecutionClaim(
        request: LlmExecutionRecoveryRequest,
        deadline: LlmExecutionRecoveryDeadline,
        retryPermit: LlmExecutionRecoveryRetryPermit,
    ): Result<LlmExecutionRecoveryOutcome> {
        if (request.invocationId == "late-3" && !failedLateEntity) {
            failedLateEntity = true
            pendingRequest = request
            return Result.failure(IllegalStateException("late page failure"))
        }

        remaining.removeAll { snapshot -> snapshot.invocationId == request.invocationId }
        recovered += request.invocationId
        return Result.success(LlmExecutionRecoveryOutcome.Recovered)
    }

    override suspend fun reconcileStaleExecutionRecovery(
        request: LlmExecutionRecoveryRequest,
        deadline: LlmExecutionRecoveryDeadline,
        retryPermit: LlmExecutionRecoveryRetryPermit,
    ): Result<LlmExecutionRecoveryOutcome> {
        assertEquals(pendingRequest, request)
        remaining.removeAll { snapshot -> snapshot.invocationId == request.invocationId }
        recovered += request.invocationId
        pendingRequest = null
        return Result.success(LlmExecutionRecoveryOutcome.Recovered)
    }
}

private class PendingBeforeCallFailureRepository(
    private val snapshot: LlmExecutionClaimSnapshot,
    private val failure: Throwable,
) : LlmLaunchReservationRepository by InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository()) {
    private var resolved = false
    var scanCalls = 0
    var recoveryCalls = 0
    var reconcileCalls = 0
    val reconcilePermitAvailability = mutableListOf<Boolean>()
    var receivedSamePermit = false
    private var recoveryPermit: LlmExecutionRecoveryRetryPermit? = null

    override suspend fun scanStaleExecutionClaims(
        scan: LlmExecutionRecoveryScan,
        deadline: LlmExecutionRecoveryDeadline,
    ): Result<RecoverySnapshots> {
        scanCalls += 1
        return Result.success(if (resolved) emptyList() else listOf(snapshot))
    }

    override suspend fun recoverStaleExecutionClaim(
        request: LlmExecutionRecoveryRequest,
        deadline: LlmExecutionRecoveryDeadline,
        retryPermit: LlmExecutionRecoveryRetryPermit,
    ): Result<LlmExecutionRecoveryOutcome> {
        recoveryCalls += 1
        recoveryPermit = retryPermit
        return Result.failure(failure)
    }

    override suspend fun reconcileStaleExecutionRecovery(
        request: LlmExecutionRecoveryRequest,
        deadline: LlmExecutionRecoveryDeadline,
        retryPermit: LlmExecutionRecoveryRetryPermit,
    ): Result<LlmExecutionRecoveryOutcome> {
        reconcileCalls += 1
        receivedSamePermit = retryPermit === recoveryPermit
        reconcilePermitAvailability += retryPermit.isAvailable
        resolved = true
        return Result.success(LlmExecutionRecoveryOutcome.Recovered)
    }
}

private class PostConsumeCancellationRepository(
    private val snapshot: LlmExecutionClaimSnapshot,
) : LlmLaunchReservationRepository by InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository()) {
    var mutationEntries = 0
    var explicitRetryStarts = 0
    var scanCalls = 0
    var receivedSamePermit = false
    private var recoveryPermit: LlmExecutionRecoveryRetryPermit? = null

    override suspend fun scanStaleExecutionClaims(
        scan: LlmExecutionRecoveryScan,
        deadline: LlmExecutionRecoveryDeadline,
    ): Result<RecoverySnapshots> {
        scanCalls += 1
        return Result.success(listOf(snapshot))
    }

    override suspend fun recoverStaleExecutionClaim(
        request: LlmExecutionRecoveryRequest,
        deadline: LlmExecutionRecoveryDeadline,
        retryPermit: LlmExecutionRecoveryRetryPermit,
    ): Result<LlmExecutionRecoveryOutcome> {
        recoveryPermit = retryPermit
        mutationEntries += 1
        check(retryPermit.tryConsume())
        explicitRetryStarts += 1
        mutationEntries += 1

        return Result.failure(CancellationException("cancel after explicit retry permit consumption"))
    }

    override suspend fun reconcileStaleExecutionRecovery(
        request: LlmExecutionRecoveryRequest,
        deadline: LlmExecutionRecoveryDeadline,
        retryPermit: LlmExecutionRecoveryRetryPermit,
    ): Result<LlmExecutionRecoveryOutcome> {
        receivedSamePermit = retryPermit === recoveryPermit
        check(!retryPermit.tryConsume())

        return Result.success(
            LlmExecutionRecoveryOutcome.OutcomeUnknown(
                IllegalStateException("explicit retry outcome remains unknown"),
            ),
        )
    }
}

private fun claimedRecoverySnapshot(invocationId: String): LlmExecutionClaimSnapshot {
    return LlmExecutionClaimSnapshot(
        invocationId = invocationId,
        triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
        status = LlmLaunchReservationStatus.RUNNING,
        claimState = me.matsumo.fukurou.trading.daemon.LlmExecutionClaimState.CLAIMED,
        claimantToken = "$invocationId-token",
        claimedAt = RECOVERY_INSTANT,
        heartbeatAt = RECOVERY_INSTANT,
        reservedAt = RECOVERY_INSTANT,
    )
}

private fun availableRecoverySnapshot(invocationId: String): LlmExecutionClaimSnapshot {
    return LlmExecutionClaimSnapshot(
        invocationId = invocationId,
        triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
        status = LlmLaunchReservationStatus.RUNNING,
        claimState = me.matsumo.fukurou.trading.daemon.LlmExecutionClaimState.AVAILABLE,
        claimantToken = null,
        claimedAt = null,
        heartbeatAt = null,
        reservedAt = RECOVERY_INSTANT,
    )
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
        populationScope = recoveryPopulationScope(),
    )
}

private fun recoveryPopulationScope(): LlmLaunchReservationPopulationScope {
    return LlmLaunchReservationPopulationScope(
        kind = "SYMBOL",
        mode = TradingMode.PAPER,
        symbol = TradingSymbol.BTC,
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
            populationScope = recoveryPopulationScope(),
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
