package me.matsumo.fukurou.trading.safety

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * SafetyFloor の margin 観測を保存する境界。
 *
 * append-only。report と全 evaluation point は不可分に保存し、途中まで保存された
 * 観測を残さない。書き込みの失敗は取引判断を変えてはならないため、呼び出し側が
 * `Result` を握って続行する。
 */
interface SafetyFloorMarginRepository {

    /**
     * 観測を保存する。
     *
     * report と全 evaluation point を 1 つの単位として保存し、いずれかが失敗した
     * 場合は 1 件も残さない。
     */
    suspend fun append(report: SafetyFloorObservationReport): Result<Unit>

    /**
     * 観測を ID で取得する。
     */
    suspend fun find(id: UUID): Result<SafetyFloorObservationReport?>
}

/**
 * margin 観測の保存に失敗したことを表す例外。
 *
 * @param stage 失敗した段階
 */
class SafetyFloorMarginPersistenceException(
    val stage: SafetyFloorMarginPersistenceStage,
    cause: Throwable,
) : RuntimeException(null, cause)

/**
 * margin 観測の保存段階。
 */
enum class SafetyFloorMarginPersistenceStage {
    /** report 行の書き込み。 */
    REPORT,

    /** evaluation point 行の書き込み。 */
    OBSERVATIONS,

    /** 読み出し。 */
    READ,
}

/**
 * `CancellationException` を握り潰さずに `Result` へ包む。
 *
 * coroutine のキャンセルを観測の失敗として扱うと、キャンセル中に取引処理を
 * 続行させてしまうため、必ず再 throw する。
 */
internal inline fun <T> safetyFloorMarginResult(
    stage: SafetyFloorMarginPersistenceStage,
    block: () -> T,
): Result<T> {
    return try {
        Result.success(block())
    } catch (throwable: CancellationException) {
        throw throwable
    } catch (throwable: SafetyFloorMarginPersistenceException) {
        Result.failure(throwable)
    } catch (throwable: Throwable) {
        Result.failure(SafetyFloorMarginPersistenceException(stage, throwable))
    }
}

/**
 * DB を持たない runtime 向けの in-memory 実装。
 */
class InMemorySafetyFloorMarginRepository : SafetyFloorMarginRepository {

    private val mutex = Mutex()
    private val reports = mutableMapOf<UUID, SafetyFloorObservationReport>()

    override suspend fun append(report: SafetyFloorObservationReport): Result<Unit> {
        return mutex.withLock {
            safetyFloorMarginResult(SafetyFloorMarginPersistenceStage.REPORT) {
                val existing = reports[report.id]

                require(existing == null || existing == report) {
                    "Margin observation report is append-only."
                }

                reports[report.id] = report
            }
        }
    }

    override suspend fun find(id: UUID): Result<SafetyFloorObservationReport?> {
        return mutex.withLock {
            safetyFloorMarginResult(SafetyFloorMarginPersistenceStage.READ) { reports[id] }
        }
    }

    /** 保存済みの観測を全件返す。テスト用。 */
    suspend fun all(): List<SafetyFloorObservationReport> {
        return mutex.withLock { reports.values.toList() }
    }
}
