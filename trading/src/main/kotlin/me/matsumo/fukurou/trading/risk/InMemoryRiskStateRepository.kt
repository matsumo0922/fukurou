package me.matsumo.fukurou.trading.risk

import java.time.Clock
import java.time.Instant

/** in-memory risk と paper ledger writer が共有する同期境界。 */
class InMemoryAccountStateBoundary {
    private val lock = Any()
    private var riskState: RiskState? = null
    private var openRiskReader: (() -> Boolean)? = null

    fun <T> read(block: () -> T): T = synchronized(lock, block)

    fun <T> write(block: () -> T): T = synchronized(lock, block)

    internal fun initializeRiskState(initialState: RiskState) {
        write {
            if (riskState == null) riskState = initialState
        }
    }

    internal fun currentRiskState(): RiskState = read {
        requireNotNull(riskState) { "in-memory risk_state was not initialized." }
    }

    internal fun currentRiskStateOrNull(): RiskState? = read { riskState }

    internal fun updateRiskState(mutation: (RiskState) -> RiskState): RiskState = write {
        val updated = mutation(currentRiskState())
        riskState = updated
        updated
    }

    internal fun updateRiskStateIfPresent(mutation: (RiskState) -> RiskState): RiskState? = write {
        riskState?.let(mutation)?.also { updated -> riskState = updated }
    }

    internal fun registerOpenRiskReader(reader: () -> Boolean) {
        write { openRiskReader = reader }
    }

    internal fun hasOpenRisk(): Boolean = read { openRiskReader?.invoke() == true }
}

/**
 * unit test と DB 未構成時のための in-memory risk_state repository。
 *
 * @param clock 初期状態の updatedAt を作る clock
 * @param initialState 初期 risk_state
 */
class InMemoryRiskStateRepository(
    clock: Clock = Clock.systemUTC(),
    initialState: RiskState = RiskState(updatedAt = Instant.now(clock)),
    internal val accountStateBoundary: InMemoryAccountStateBoundary = InMemoryAccountStateBoundary(),
) : RiskStateRepository {
    init {
        accountStateBoundary.initializeRiskState(initialState)
    }

    override suspend fun current(): Result<RiskState> {
        return accountStateBoundary.read { Result.success(accountStateBoundary.currentRiskState()) }
    }

    internal fun currentSnapshot(): RiskState = accountStateBoundary.currentRiskState()

    override suspend fun setHardHalt(reason: String, at: Instant): Result<RiskState> {
        return accountStateBoundary.write {
            runCatching {
                require(reason.isNotBlank()) { "HARD_HALT reason is required." }

                val previousState = accountStateBoundary.currentRiskState()
                accountStateBoundary.updateRiskState { currentState ->
                    currentState.copy(
                        state = RiskHaltState.HARD_HALT,
                        haltReason = reason,
                        haltAt = at,
                        hardHaltCleanupState = if (previousState.state == RiskHaltState.HARD_HALT) {
                            previousState.hardHaltCleanupState
                        } else {
                            HardHaltCleanupState.UNKNOWN
                        },
                        updatedAt = at,
                    )
                }

                accountStateBoundary.currentRiskState()
            }
        }
    }

    override suspend fun setSoftHalt(reason: String, at: Instant): Result<RiskState> {
        return accountStateBoundary.write {
            runCatching {
                require(reason.isNotBlank()) { "SOFT_HALT reason is required." }

                if (accountStateBoundary.currentRiskState().state == RiskHaltState.HARD_HALT) {
                    throw SoftHaltDowngradeRejectedException()
                }

                accountStateBoundary.updateRiskState { currentState ->
                    currentState.copy(
                        state = RiskHaltState.SOFT_HALT,
                        haltReason = reason,
                        haltAt = at,
                        hardHaltCleanupState = null,
                        updatedAt = at,
                    )
                }

                accountStateBoundary.currentRiskState()
            }
        }
    }

    override suspend fun resume(reason: String, at: Instant): Result<RiskState> {
        return accountStateBoundary.write {
            runCatching {
                require(reason.isNotBlank()) { "manual resume reason is required." }

                val currentState = accountStateBoundary.currentRiskState()
                val hasOpenRisk = accountStateBoundary.hasOpenRisk()

                if (currentState.state == RiskHaltState.HARD_HALT) {
                    val cleanupIsSafe = currentState.hardHaltCleanupState == HardHaltCleanupState.SAFE

                    if (!cleanupIsSafe || hasOpenRisk) {
                        if (cleanupIsSafe && hasOpenRisk) {
                            accountStateBoundary.updateRiskState { state ->
                                state.copy(hardHaltCleanupState = HardHaltCleanupState.UNKNOWN)
                            }
                        }

                        throw HardHaltCleanupIncompleteException()
                    }
                }

                accountStateBoundary.updateRiskState { state ->
                    state.copy(
                        state = RiskHaltState.RUNNING,
                        resumedAt = at,
                        resumedReason = reason,
                        hardHaltCleanupState = null,
                        updatedAt = at,
                    )
                }

                accountStateBoundary.currentRiskState()
            }
        }
    }

    /**
     * command audit 失敗時の rollback 用に risk_state snapshot を復元する。
     */
    suspend fun restore(state: RiskState): Result<Unit> {
        return accountStateBoundary.write {
            runCatching {
                accountStateBoundary.updateRiskState { state }
                Unit
            }
        }
    }
}
