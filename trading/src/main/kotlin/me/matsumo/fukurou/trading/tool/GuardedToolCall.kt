package me.matsumo.fukurou.trading.tool

import me.matsumo.fukurou.trading.audit.DecisionRunContext

/**
 * MCP tool call を audit と no-trade guard に渡すための envelope。
 *
 * @param toolName tool 名
 * @param toolCallId tool call 単位の ID
 * @param clientRequestId 呼び出し元が任意で渡す追跡 ID
 * @param decisionRunContext LLM 起動と prompt 系の監査コンテキスト
 * @param payload JSON 文字列 payload
 */
data class GuardedToolCall(
    val toolName: String,
    val toolCallId: String,
    val clientRequestId: String?,
    val decisionRunContext: DecisionRunContext,
    val payload: String,
)

/**
 * 新規取引をせずに呼び出し元へ戻ることを表す例外。
 */
class NoTradeExitException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
