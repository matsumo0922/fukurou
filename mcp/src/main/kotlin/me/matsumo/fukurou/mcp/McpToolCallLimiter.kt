package me.matsumo.fukurou.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.tool.GuardedToolCall
import me.matsumo.fukurou.trading.tool.ToolCallGuard
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP tool call の分類。
 */
enum class McpToolCallKind {
    /**
     * read-only tool call。
     */
    READ_ONLY,

    /**
     * decision 系 tool call。総数には数えるが trade 系 act 予算は消費しない。
     */
    DECISION,

    /**
     * trade 系 act tool call。
     */
    TRADE,
}

/**
 * MCP server instance ごとの tool call 上限を強制する counter。
 *
 * @param config runner 上限 config
 * @param toolCallGuard no-trade audit を保存する guard
 * @param allowedToolNames null で全 tool 許可、指定時は含まれない tool を拒否する
 * @param countedToolNames per-run 総数として数える MCP tool 名
 * @param actToolNames per-run act 数として数える MCP tool 名
 */
class McpToolCallLimiter(
    private val config: LlmRunnerConfig,
    private val toolCallGuard: ToolCallGuard,
    private val allowedToolNames: Set<String>? = null,
    private val countedToolNames: Set<String> = emptySet(),
    private val actToolNames: Set<String> = emptySet(),
    private val expiresAt: Instant? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val totalToolCallCount = AtomicInteger(0)
    private val actToolCallCount = AtomicInteger(0)
    private val initialCountMutex = Mutex()

    @Volatile
    private var initialCounts: McpToolCallCounts? = null

    /**
     * tool call 予算を 1 回消費し、超過時は no-trade audit 付きの失敗を返す。
     */
    suspend fun acquire(call: GuardedToolCall, kind: McpToolCallKind): Result<Unit> {
        rejectIfManifestExpired(call)?.let { result -> return result }
        rejectIfToolIsNotAllowed(call)?.let { result -> return result }

        val currentInitialCounts = loadInitialCounts(call).getOrElse { throwable ->
            return recordCountUnavailable(call, throwable)
        }
        val localTotalCount = totalToolCallCount.incrementAndGet()
        val localActCount = if (kind == McpToolCallKind.TRADE) {
            actToolCallCount.incrementAndGet()
        } else {
            actToolCallCount.get()
        }
        val totalCount = currentInitialCounts.totalToolCallCount + localTotalCount
        val actCount = currentInitialCounts.actToolCallCount + localActCount
        val totalExceeded = totalCount > config.maxToolCallsPerRun
        val actLimitTarget = kind == McpToolCallKind.TRADE
        val actExceeded = actLimitTarget && actCount > config.maxActToolCallsPerRun

        if (!totalExceeded && !actExceeded) {
            return Result.success(Unit)
        }

        val reason = if (totalExceeded) {
            "mcp_total_tool_call_limit_exceeded"
        } else {
            "mcp_act_tool_call_limit_exceeded"
        }
        val exception = ToolCallLimitExceededException(
            message = "$reason total=$totalCount/${config.maxToolCallsPerRun} act=$actCount/${config.maxActToolCallsPerRun}",
        )
        val auditResult = toolCallGuard.recordNoTradeExit(
            call = call,
            reason = reason,
            cause = exception,
        )

        auditResult.exceptionOrNull()?.let { throwable -> exception.addSuppressed(throwable) }

        return Result.failure(exception)
    }

    private suspend fun loadInitialCounts(call: GuardedToolCall): Result<McpToolCallCounts> {
        initialCounts?.let { counts -> return Result.success(counts) }

        return initialCountMutex.withLock {
            initialCounts?.let { counts -> return@withLock Result.success(counts) }

            val decisionRunId = call.decisionRunContext.decisionRunId
            val counts = if (decisionRunId == null) {
                McpToolCallCounts(
                    totalToolCallCount = 0,
                    actToolCallCount = 0,
                )
            } else {
                val totalCount = if (countedToolNames.isEmpty()) {
                    0
                } else {
                    toolCallGuard.countToolCallEvents(decisionRunId, countedToolNames)
                        .getOrElse { throwable -> return@withLock Result.failure(throwable) }
                }
                val actCount = if (actToolNames.isEmpty()) {
                    0
                } else {
                    toolCallGuard.countToolCallEvents(decisionRunId, actToolNames)
                        .getOrElse { throwable -> return@withLock Result.failure(throwable) }
                }

                McpToolCallCounts(
                    totalToolCallCount = totalCount,
                    actToolCallCount = actCount,
                )
            }

            initialCounts = counts

            Result.success(counts)
        }
    }

    private suspend fun recordCountUnavailable(call: GuardedToolCall, cause: Throwable): Result<Unit> {
        val exception = ToolCallLimitUnavailableException(
            message = "mcp_tool_call_count_unavailable",
            cause = cause,
        )
        val auditResult = toolCallGuard.recordNoTradeExit(
            call = call,
            reason = "mcp_tool_call_count_unavailable",
            cause = exception,
        )

        auditResult.exceptionOrNull()?.let { throwable -> exception.addSuppressed(throwable) }

        return Result.failure(exception)
    }

    private suspend fun rejectIfToolIsNotAllowed(call: GuardedToolCall): Result<Unit>? {
        val allowedNames = allowedToolNames ?: return null

        if (call.toolName in allowedNames) {
            return null
        }

        val exception = ToolCallNotAllowedException(
            message = "mcp_tool_not_allowed tool=${call.toolName}",
        )
        val auditResult = toolCallGuard.recordNoTradeExit(
            call = call,
            reason = "mcp_tool_not_allowed",
            cause = exception,
        )

        auditResult.exceptionOrNull()?.let { throwable -> exception.addSuppressed(throwable) }

        return Result.failure(exception)
    }

    private suspend fun rejectIfManifestExpired(call: GuardedToolCall): Result<Unit>? {
        val expiry = expiresAt ?: return null

        if (Instant.now(clock).isBefore(expiry)) return null

        val exception = ToolCallNotAllowedException("mcp_manifest_expired")
        val auditResult = toolCallGuard.recordNoTradeExit(
            call = call,
            reason = "mcp_manifest_expired",
            cause = exception,
        )
        auditResult.exceptionOrNull()?.let { throwable -> exception.addSuppressed(throwable) }

        return Result.failure(exception)
    }
}

/**
 * MCP server 起動時点で既に同じ decision run に記録されていた tool call 数。
 *
 * @param totalToolCallCount 総 tool call 数
 * @param actToolCallCount trade 系 act tool call 数
 */
private data class McpToolCallCounts(
    val totalToolCallCount: Int,
    val actToolCallCount: Int,
)

/**
 * MCP tool call 上限超過を表す例外。
 */
class ToolCallLimitExceededException(
    message: String,
) : RuntimeException(message)

/**
 * MCP tool call 使用量を監査ログから取得できなかったことを表す例外。
 */
class ToolCallLimitUnavailableException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

/**
 * MCP tool allowlist に含まれない tool 呼び出しを表す例外。
 */
class ToolCallNotAllowedException(
    message: String,
) : RuntimeException(message)
