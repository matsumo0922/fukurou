package me.matsumo.fukurou.trading.safety

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SafetyFloor 違反を append-only で保存する repository。
 */
interface SafetyViolationRepository {
    /**
     * violation を保存する。
     */
    suspend fun append(violation: SafetyViolation): Result<Unit>
}

/**
 * unit test と明示 injection 用の SafetyFloor 違反 repository。
 */
class InMemorySafetyViolationRepository : SafetyViolationRepository {

    private val mutex = Mutex()
    private val storedViolations = mutableListOf<SafetyViolation>()

    override suspend fun append(violation: SafetyViolation): Result<Unit> {
        mutex.withLock {
            storedViolations += violation
        }

        return Result.success(Unit)
    }

    /**
     * 保存済み violation snapshot を返す。
     */
    suspend fun violations(): List<SafetyViolation> {
        return mutex.withLock { storedViolations.toList() }
    }
}
