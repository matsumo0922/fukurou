package me.matsumo.fukurou.trading.evaluation

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
