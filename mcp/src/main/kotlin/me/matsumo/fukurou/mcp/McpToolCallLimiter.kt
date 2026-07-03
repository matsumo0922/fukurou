package me.matsumo.fukurou.mcp

import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.tool.GuardedToolCall
import me.matsumo.fukurou.trading.tool.ToolCallGuard
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
 */
class McpToolCallLimiter(
    private val config: LlmRunnerConfig,
    private val toolCallGuard: ToolCallGuard,
    private val allowedToolNames: Set<String>? = null,
) {
    private val totalToolCallCount = AtomicInteger(0)
    private val actToolCallCount = AtomicInteger(0)

    /**
     * tool call 予算を 1 回消費し、超過時は no-trade audit 付きの失敗を返す。
     */
    suspend fun acquire(call: GuardedToolCall, kind: McpToolCallKind): Result<Unit> {
        rejectIfToolIsNotAllowed(call)?.let { result -> return result }

        val totalCount = totalToolCallCount.incrementAndGet()
        val actCount = if (kind == McpToolCallKind.TRADE) {
            actToolCallCount.incrementAndGet()
        } else {
            actToolCallCount.get()
        }
        val totalExceeded = totalCount > config.maxToolCallsPerRun
        val actExceeded = actCount > config.maxActToolCallsPerRun

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
}

/**
 * MCP tool call 上限超過を表す例外。
 */
class ToolCallLimitExceededException(
    message: String,
) : RuntimeException(message)

/**
 * MCP tool allowlist に含まれない tool 呼び出しを表す例外。
 */
class ToolCallNotAllowedException(
    message: String,
) : RuntimeException(message)
