package me.matsumo.fukurou.trading.evaluation

import kotlinx.coroutines.CancellationException

/** LLM run の終了理由を表す、永続化・wire 共通の安定コード。 */
enum class LlmRunTerminalCause {
    NORMAL_COMPLETION,
    NO_TRADE,
    SAFETY_DENIED,
    TIMED_OUT,
    RUNNER_FAILED,
    CALLER_CANCELLED,
    RESTART_INTERRUPTED,
    LEGACY_UNCLASSIFIED,
}

/** process timeout を文字列解析せず runner へ伝える typed failure。 */
class LlmInvocationTimedOutException(phaseName: String) : RuntimeException("$phaseName timed out")

/** runner 呼び出し境界で例外を終端原因へ正規化する。 */
fun terminalCauseForInvocationFailure(cause: Throwable?): LlmRunTerminalCause = when (cause) {
    null -> LlmRunTerminalCause.NORMAL_COMPLETION
    is CancellationException -> LlmRunTerminalCause.CALLER_CANCELLED
    is LlmInvocationTimedOutException -> LlmRunTerminalCause.TIMED_OUT
    else -> LlmRunTerminalCause.RUNNER_FAILED
}
