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
internal fun LlmLaunchReservationRejectionReason.toDaemonSkipReason(): String {
    return when (this) {
        LlmLaunchReservationRejectionReason.HARD_HALT -> LLM_DAEMON_SKIP_HARD_HALT
        LlmLaunchReservationRejectionReason.CONCURRENT_INVOCATION -> "concurrent_invocation"
        LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_HOUR -> "max_invocations_per_hour_exceeded"
        LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_DAY -> "max_invocations_per_day_exceeded"
        LlmLaunchReservationRejectionReason.INSUFFICIENT_REFLECTION_HOURLY_HEADROOM ->
            "reflection_hourly_headroom_insufficient"
        LlmLaunchReservationRejectionReason.INSUFFICIENT_REFLECTION_DAILY_HEADROOM ->
            "reflection_daily_headroom_insufficient"
    }
}
