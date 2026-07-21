package me.matsumo.fukurou.trading.shadow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/** gate-shadow observation の runtime channel 容量。 */
internal const val GATE_SHADOW_OBSERVATION_CHANNEL_CAPACITY = 256

/**
 * TTL 失効 capture payload を保存経路へ渡す境界。
 */
interface GateShadowObservationSink {

    /** observation を保存経路へ渡す。 */
    suspend fun enqueue(observations: List<GateShadowObservation>)
}

/**
 * bounded channel を単一 coroutine で drain する非同期 sink。
 *
 * channel 満杯時は observation を drop し、正本との reconciliation に回す。
 */
class AsyncGateShadowObservationSink(
    scope: CoroutineScope,
    private val repository: GateShadowRepository,
    capacity: Int = GATE_SHADOW_OBSERVATION_CHANNEL_CAPACITY,
) : GateShadowObservationSink {

    private val channel = Channel<GateShadowObservation>(capacity)
    private val droppedObservationCounter = AtomicLong()

    /** channel 満杯または close 後に drop した observation 数。 */
    internal val droppedObservationCount: Long
        get() = droppedObservationCounter.get()

    init {
        require(capacity > 0) { "Gate-shadow observation channel capacity must be positive." }

        scope.launch {
            for (observation in channel) {
                appendObservation(observation)
            }
        }.invokeOnCompletion { cause -> channel.close(cause) }
    }

    override suspend fun enqueue(observations: List<GateShadowObservation>) {
        observations.forEach { observation ->
            val result = channel.trySend(observation)

            if (result.isFailure) {
                droppedObservationCounter.incrementAndGet()
                gateShadowSinkLogger.log(
                    Level.WARNING,
                    "gate-shadow observation channel full or closed; dropping capture: orderId=${observation.orderId}",
                    result.exceptionOrNull(),
                )
            }
        }
    }

    private suspend fun appendObservation(observation: GateShadowObservation) {
        val result = try {
            repository.appendObservation(observation)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }

        result.exceptionOrNull()?.let { failure ->
            gateShadowSinkLogger.log(
                Level.WARNING,
                "gate-shadow observation async persistence failed: orderId=${observation.orderId}",
                failure,
            )
        }
    }
}

/**
 * 呼び出し coroutine で保存を完了する deterministic sink。
 *
 * repository 単体テストと DB を持たない経路で使用する。
 */
class DirectGateShadowObservationSink(
    private val repository: GateShadowRepository,
) : GateShadowObservationSink {

    override suspend fun enqueue(observations: List<GateShadowObservation>) {
        observations.forEach { observation ->
            val result = try {
                repository.appendObservation(observation)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Result.failure(throwable)
            }

            result.exceptionOrNull()?.let { failure ->
                gateShadowSinkLogger.log(
                    Level.WARNING,
                    "gate-shadow observation persistence failed after TTL cancel commit: orderId=${observation.orderId}",
                    failure,
                )
            }
        }
    }
}

private val gateShadowSinkLogger = Logger.getLogger(GateShadowObservationSink::class.java.name)
