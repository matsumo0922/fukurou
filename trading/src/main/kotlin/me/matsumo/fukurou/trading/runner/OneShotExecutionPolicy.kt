package me.matsumo.fukurou.trading.runner

import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import java.time.Duration

/** one-shot claim の heartbeat と hard deadline を表す policy。 */
data class OneShotExecutionPolicy(
    val phaseTimeout: Duration,
    val phaseSubtotal: Duration,
    val finalizationGrace: Duration,
    val hardTimeout: Duration,
    val heartbeatInterval: Duration,
    val heartbeatMissAllowance: Duration,
    val processTerminationGrace: Duration,
    val persistenceTerminalTimeout: Duration,
) {
    companion object {
        /** guarded runner config から checked arithmetic で policy を構築する。 */
        fun from(config: LlmRunnerConfig): OneShotExecutionPolicy {
            val phaseSubtotal = config.perRunTimeout.checkedMultiply(CLAIMED_ONE_SHOT_PHASE_COUNT)
            val finalizationGrace = config.processTerminationGrace.checkedMultiply(2)
                .checkedAdd(config.persistenceTerminalTimeout)
            val hardTimeout = phaseSubtotal.checkedAdd(finalizationGrace)
            require(!hardTimeout.isZero && !hardTimeout.isNegative) { "one-shot hard timeout must be positive." }

            val heartbeatInterval = (hardTimeout.dividedBy(20)).coerceIn(
                MIN_HEARTBEAT_INTERVAL,
                MAX_HEARTBEAT_INTERVAL,
            )
            return OneShotExecutionPolicy(
                phaseTimeout = config.perRunTimeout,
                phaseSubtotal = phaseSubtotal,
                finalizationGrace = finalizationGrace,
                hardTimeout = hardTimeout,
                heartbeatInterval = heartbeatInterval,
                heartbeatMissAllowance = heartbeatInterval.checkedMultiply(3),
                processTerminationGrace = config.processTerminationGrace,
                persistenceTerminalTimeout = config.persistenceTerminalTimeout,
            )
        }
    }
}

/** claimed one-shot hard-timeout accounting の phase classification。 */
fun LlmInvocationPhase.isClaimedOneShotPhase(): Boolean = when (this) {
    LlmInvocationPhase.PRE_FILTER,
    LlmInvocationPhase.PROPOSER,
    LlmInvocationPhase.FALSIFIER,
    LlmInvocationPhase.RISK_REDUCTION_ONLY,
    -> true
    LlmInvocationPhase.REFLECTION,
    LlmInvocationPhase.EVALUATION_REPORT,
    -> false
}

private fun Duration.checkedMultiply(multiplier: Long): Duration = try {
    multipliedBy(multiplier)
} catch (throwable: ArithmeticException) {
    throw IllegalArgumentException("one-shot timeout multiplication overflowed.", throwable)
}

private fun Duration.checkedAdd(other: Duration): Duration = try {
    plus(other)
} catch (throwable: ArithmeticException) {
    throw IllegalArgumentException("one-shot timeout addition overflowed.", throwable)
}

private fun Duration.coerceIn(minimum: Duration, maximum: Duration): Duration = when {
    this < minimum -> minimum
    this > maximum -> maximum
    else -> this
}

private const val CLAIMED_ONE_SHOT_PHASE_COUNT = 3L
private val MIN_HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(5)
private val MAX_HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(30)
