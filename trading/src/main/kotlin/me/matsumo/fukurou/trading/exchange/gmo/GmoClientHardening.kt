package me.matsumo.fukurou.trading.exchange.gmo

import java.time.Clock
import java.time.Duration
import kotlin.math.ceil

/**
 * GMO API request 前に rate-limit permit を取得する境界。
 */
fun interface GmoRequestRateLimiter {
    /**
     * 指定 endpoint の request permit を取得する。
     */
    fun acquirePermit(endpointName: String)
}

/**
 * retry / rate-limit 待機を行わない test 用 rate limiter。
 */
object NoopGmoRequestRateLimiter : GmoRequestRateLimiter {
    override fun acquirePermit(endpointName: String) = Unit
}

/**
 * retry / rate-limit の待機を差し替える境界。
 */
fun interface GmoSleeper {
    /**
     * 指定 duration だけ待機する。
     */
    fun sleep(duration: Duration)
}

/**
 * 現在 thread を sleep する既定 sleeper。
 */
object ThreadSleepingGmoSleeper : GmoSleeper {
    override fun sleep(duration: Duration) {
        Thread.sleep(duration.toMillis())
    }
}

/**
 * 単純な token bucket で GMO Public REST request を制限する rate limiter。
 *
 * @param config token bucket 設定
 * @param clock 経過時間の計算に使う clock
 * @param sleeper token 補充待ちに使う sleeper
 */
class GmoTokenBucketRateLimiter(
    private val config: GmoRateLimitConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val sleeper: GmoSleeper = ThreadSleepingGmoSleeper,
) : GmoRequestRateLimiter {

    private val lock = Any()
    private var availablePermits = config.burstSize.toDouble()
    private var lastRefillMillis = clock.millis()

    override fun acquirePermit(endpointName: String) {
        require(endpointName.isNotBlank()) {
            "endpointName must not be blank."
        }

        synchronized(lock) {
            refillPermits()

            while (availablePermits < REQUIRED_PERMIT) {
                sleeper.sleep(waitDurationUntilNextPermit())
                refillPermits()
            }

            availablePermits -= REQUIRED_PERMIT
        }
    }

    private fun refillPermits() {
        val currentMillis = clock.millis()
        val elapsedMillis = currentMillis - lastRefillMillis

        if (elapsedMillis <= 0) {
            return
        }

        val replenishedPermits = elapsedMillis.toDouble() * config.permitsPerSecond / MILLIS_PER_SECOND
        availablePermits = minOf(config.burstSize.toDouble(), availablePermits + replenishedPermits)
        lastRefillMillis = currentMillis
    }

    private fun waitDurationUntilNextPermit(): Duration {
        val missingPermits = REQUIRED_PERMIT - availablePermits
        val waitMillis = ceil(missingPermits * MILLIS_PER_SECOND / config.permitsPerSecond)
            .toLong()
            .coerceAtLeast(MIN_WAIT_MILLIS)

        return Duration.ofMillis(waitMillis)
    }
}

/**
 * 1 request に必要な token 数。
 */
private const val REQUIRED_PERMIT = 1.0

/**
 * 1 秒の millisecond 数。
 */
private const val MILLIS_PER_SECOND = 1000.0

/**
 * token 不足時の最小待機 millisecond。
 */
private const val MIN_WAIT_MILLIS = 1L
