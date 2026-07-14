package me.matsumo.fukurou.trading.market

import java.time.Duration
import java.util.UUID
import kotlin.time.TimeSource

/** market streamをsession UUIDとは独立して識別する安定したresource identity。 */
data class MarketStreamIdentity(
    val provider: String,
    val symbol: String,
    val channel: String,
)

/** durable ingress sessionの有限状態。 */
enum class DurableIngressSessionState {
    STARTING,
    CONNECTED,
    STOPPING,
    DISCONNECTED,
}

/** durable ingressで記録するgap source。 */
enum class DurableIngressGapSource {
    DATABASE_FAILURE,
    SEQUENCE_GAP,
    BACKPRESSURE,
    PROCESS_RESTART,
    TRANSPORT_ERROR,
}

/** durable registration全体で共有する単一monotonic deadline。 */
class IngressOperationDeadline private constructor(
    private val mark: TimeSource.Monotonic.ValueTimeMark,
    val budget: Duration,
) {
    /** deadlineまでの残時間。期限到達後はzeroを返す。 */
    fun remaining(): Duration {
        val remaining = budget.minusMillis(mark.elapsedNow().inWholeMilliseconds)

        return remaining.coerceAtLeast(Duration.ZERO)
    }

    /** DB operationを開始できる残時間を返す。 */
    fun requireRemaining(): Duration {
        return remaining().also { check(!it.isZero) { "Durable ingress operation deadline expired." } }
    }

    companion object {
        val DEFAULT_BUDGET: Duration = Duration.ofSeconds(5)

        /** monotonic clockを一度だけ読み、operation全体のdeadlineを作る。 */
        fun start(
            budget: Duration = DEFAULT_BUDGET,
            timeSource: TimeSource.Monotonic = TimeSource.Monotonic,
        ): IngressOperationDeadline {
            require(!budget.isNegative && !budget.isZero) { "budget must be greater than 0." }

            return IngressOperationDeadline(timeSource.markNow(), budget)
        }
    }
}

/** durable ingress sessionの登録値。 */
data class DurableIngressSession(
    val sessionId: UUID,
    val identity: MarketStreamIdentity,
    val state: DurableIngressSessionState,
    val lastReceivedSequence: Long,
)

/** default-off transportが利用するdurable mutation contract。 */
interface DurableMarketEventIngress {
    suspend fun begin(
        sessionId: UUID,
        identity: MarketStreamIdentity,
        deadline: IngressOperationDeadline,
    ): Result<Unit>

    suspend fun activate(sessionId: UUID, deadline: IngressOperationDeadline): Result<Boolean>

    suspend fun registerReceived(
        sessionId: UUID,
        sequence: Long,
        deadline: IngressOperationDeadline,
    ): Result<Boolean>

    suspend fun disconnect(
        sessionId: UUID,
        source: DurableIngressGapSource,
        deadline: IngressOperationDeadline,
    ): Result<Unit>
}
