package me.matsumo.fukurou.trading.risk

import java.time.Clock
import java.time.Instant

/** in-memory risk と paper ledger writer が共有する同期境界。 */
class InMemoryAccountStateBoundary {
    private val lock = Any()

    fun <T> read(block: () -> T): T = synchronized(lock, block)

    fun <T> write(block: () -> T): T = synchronized(lock, block)
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
    private var storedState = initialState

    override suspend fun current(): Result<RiskState> {
        return accountStateBoundary.read { Result.success(storedState) }
    }

    internal fun currentSnapshot(): RiskState = accountStateBoundary.read { storedState }

    override suspend fun setHardHalt(reason: String, at: Instant): Result<RiskState> {
        return accountStateBoundary.write {
            runCatching {
                require(reason.isNotBlank()) { "HARD_HALT reason is required." }

                storedState = storedState.copy(
                    state = RiskHaltState.HARD_HALT,
                    haltReason = reason,
                    haltAt = at,
                    updatedAt = at,
                )

                storedState
            }
        }
    }

    override suspend fun setSoftHalt(reason: String, at: Instant): Result<RiskState> {
        return accountStateBoundary.write {
            runCatching {
                require(reason.isNotBlank()) { "SOFT_HALT reason is required." }

                if (storedState.state == RiskHaltState.HARD_HALT) {
                    throw SoftHaltDowngradeRejectedException()
                }

                storedState = storedState.copy(
                    state = RiskHaltState.SOFT_HALT,
                    haltReason = reason,
                    haltAt = at,
                    updatedAt = at,
                )

                storedState
            }
        }
    }

    override suspend fun resume(reason: String, at: Instant): Result<RiskState> {
        return accountStateBoundary.write {
            runCatching {
                require(reason.isNotBlank()) { "manual resume reason is required." }

                storedState = storedState.copy(
                    state = RiskHaltState.RUNNING,
                    resumedAt = at,
                    resumedReason = reason,
                    updatedAt = at,
                )

                storedState
            }
        }
    }

    /**
     * command audit 失敗時の rollback 用に risk_state snapshot を復元する。
     */
    suspend fun restore(state: RiskState): Result<Unit> {
        return accountStateBoundary.write {
            runCatching {
                storedState = state
            }
        }
    }
}
