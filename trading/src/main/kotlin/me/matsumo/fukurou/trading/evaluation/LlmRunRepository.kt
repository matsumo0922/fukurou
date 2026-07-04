package me.matsumo.fukurou.trading.evaluation

import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import java.time.Instant

/**
 * llm_runs に保存する起動中 status。
 */
const val LLM_RUN_STATUS_RUNNING = "RUNNING"

/**
 * llm_runs に保存する例外終了 status。
 */
const val LLM_RUN_STATUS_FAILED = "FAILED"

/**
 * llm_runs に保存するキャンセル終了 status。
 */
const val LLM_RUN_STATUS_CANCELLED = "CANCELLED"

/**
 * LLM runner の起動開始記録。
 *
 * @param invocationId runner 起動 ID
 * @param mode 取引 mode
 * @param symbol 取引対象 symbol
 * @param triggerKind daemon trigger 種別。手動起動では null
 * @param startedAt 起動開始時刻
 */
data class LlmRunStart(
    val invocationId: String,
    val mode: TradingMode,
    val symbol: TradingSymbol,
    val triggerKind: LlmDaemonTriggerKind?,
    val startedAt: Instant,
)

/**
 * LLM runner の終了記録。
 *
 * @param invocationId runner 起動 ID
 * @param status 最終 status
 * @param finishedAt 終了時刻
 * @param errorMessage redaction / truncate 済みのエラー message
 */
data class LlmRunFinish(
    val invocationId: String,
    val status: String,
    val finishedAt: Instant,
    val errorMessage: String?,
)

/**
 * llm_runs の読み取り model。
 *
 * @param invocationId runner 起動 ID
 * @param mode 取引 mode
 * @param symbol 取引対象 symbol
 * @param triggerKind daemon trigger 種別。手動起動では null
 * @param status runner status
 * @param startedAt 起動開始時刻
 * @param finishedAt 終了時刻
 * @param errorMessage redaction / truncate 済みのエラー message
 */
data class LlmRunRecord(
    val invocationId: String,
    val mode: TradingMode,
    val symbol: TradingSymbol,
    val triggerKind: LlmDaemonTriggerKind?,
    val status: String,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val errorMessage: String?,
)

/**
 * LLM runner の run-level 記録 repository。
 */
interface LlmRunRepository {
    /**
     * runner 起動直後の RUNNING 行を保存する。
     */
    suspend fun insertRunning(start: LlmRunStart): Result<Unit>

    /**
     * runner 終了時に status / finished_at / error_message を更新する。
     */
    suspend fun finish(finish: LlmRunFinish): Result<Unit>

    /**
     * invocation_id で run-level 記録を取得する。
     */
    suspend fun findByInvocationId(invocationId: String): Result<LlmRunRecord?>
}

/**
 * unit test と in-memory runtime 用の llm_runs repository。
 */
class InMemoryLlmRunRepository : LlmRunRepository {

    private val lock = Any()
    private val records = linkedMapOf<String, LlmRunRecord>()
    private val statusHistory = linkedMapOf<String, MutableList<String>>()

    override suspend fun insertRunning(start: LlmRunStart): Result<Unit> {
        return runCatching<Unit> {
            synchronized(lock) {
                records.putIfAbsent(
                    start.invocationId,
                    LlmRunRecord(
                        invocationId = start.invocationId,
                        mode = start.mode,
                        symbol = start.symbol,
                        triggerKind = start.triggerKind,
                        status = LLM_RUN_STATUS_RUNNING,
                        startedAt = start.startedAt,
                        finishedAt = null,
                        errorMessage = null,
                    ),
                )
                statusHistory.getOrPut(start.invocationId) { mutableListOf() }.add(LLM_RUN_STATUS_RUNNING)
            }
        }
    }

    override suspend fun finish(finish: LlmRunFinish): Result<Unit> {
        return runCatching<Unit> {
            synchronized(lock) {
                val currentRecord = records[finish.invocationId]

                if (currentRecord != null) {
                    records[finish.invocationId] = currentRecord.copy(
                        status = finish.status,
                        finishedAt = finish.finishedAt,
                        errorMessage = finish.errorMessage,
                    )
                }
                statusHistory.getOrPut(finish.invocationId) { mutableListOf() }.add(finish.status)
            }
        }
    }

    override suspend fun findByInvocationId(invocationId: String): Result<LlmRunRecord?> {
        return Result.success(
            synchronized(lock) {
                records[invocationId]
            },
        )
    }

    /**
     * 保存済み llm_runs を insertion order で返す。
     */
    fun records(): List<LlmRunRecord> {
        return synchronized(lock) {
            records.values.toList()
        }
    }

    /**
     * 指定 invocation_id の status 更新履歴を返す。
     */
    fun statusHistory(invocationId: String): List<String> {
        return synchronized(lock) {
            statusHistory[invocationId].orEmpty().toList()
        }
    }
}
