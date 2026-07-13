package me.matsumo.fukurou.trading.daemon

/**
 * HARD_HALT 中で LLM 起動しなかった理由。
 */
internal const val LLM_DAEMON_SKIP_HARD_HALT = "hard_halt"

/**
 * SOFT_HALT 中かつ flat のため LLM 起動しなかった理由。
 */
internal const val LLM_DAEMON_SKIP_SOFT_HALT_FLAT = "soft_halt_flat"

/**
 * 起動予約の拒否理由を API / audit の skip reason に変換する。
 */
@Suppress("CyclomaticComplexMethod")
internal fun LlmLaunchReservationRejectionReason.toDaemonSkipReason(): String {
    return when (this) {
        LlmLaunchReservationRejectionReason.TRIGGER_ALREADY_ATTEMPTED -> "trigger_already_attempted"
        LlmLaunchReservationRejectionReason.HARD_HALT -> LLM_DAEMON_SKIP_HARD_HALT
        LlmLaunchReservationRejectionReason.CONCURRENT_INVOCATION -> "concurrent_invocation"
        LlmLaunchReservationRejectionReason.REPORT_RATE_LIMIT -> "report_rate_limit"
        LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_HOUR -> "max_invocations_per_hour_exceeded"
        LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_DAY -> "max_invocations_per_day_exceeded"
        LlmLaunchReservationRejectionReason.ENTRY_FILL_HOURLY_RESERVE -> "entry_fill_hourly_reserve_protected"
        LlmLaunchReservationRejectionReason.ENTRY_FILL_DAILY_RESERVE -> "entry_fill_daily_reserve_protected"
        LlmLaunchReservationRejectionReason.STOP_PROXIMITY_HOURLY_RESERVE -> "stop_proximity_hourly_reserve_protected"
        LlmLaunchReservationRejectionReason.STOP_PROXIMITY_DAILY_RESERVE -> "stop_proximity_daily_reserve_protected"
        LlmLaunchReservationRejectionReason.INSUFFICIENT_REFLECTION_HOURLY_HEADROOM ->
            "reflection_hourly_headroom_insufficient"
        LlmLaunchReservationRejectionReason.INSUFFICIENT_REFLECTION_DAILY_HEADROOM ->
            "reflection_daily_headroom_insufficient"
        LlmLaunchReservationRejectionReason.INSUFFICIENT_EVALUATION_HOURLY_HEADROOM ->
            "evaluation_hourly_headroom_insufficient"
        LlmLaunchReservationRejectionReason.INSUFFICIENT_EVALUATION_DAILY_HEADROOM ->
            "evaluation_daily_headroom_insufficient"
    }
}
