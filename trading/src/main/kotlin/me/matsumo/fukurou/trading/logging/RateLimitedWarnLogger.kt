package me.matsumo.fukurou.trading.logging

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.logging.Logger

/**
 * warn log の既定 rate limit 間隔。
 */
private val DEFAULT_WARN_LOG_INTERVAL = Duration.ofSeconds(30)

/**
 * 同じ key の warning を一定間隔に抑制する logger。
 *
 * @param logger 出力先 logger
 * @param clock rate limit 判定に使う clock
 * @param interval 同じ key の log を再出力するまでの間隔
 */
class RateLimitedWarnLogger(
    private val logger: Logger,
    private val clock: Clock = Clock.systemUTC(),
    private val interval: Duration = DEFAULT_WARN_LOG_INTERVAL,
) {

    private val lastLoggedAtByKey = mutableMapOf<String, Instant>()

    /**
     * rate limit を通過した場合だけ warn log を出力する。
     */
    @Synchronized
    fun warn(
        key: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val now = Instant.now(clock)

        if (!shouldLog(key, now)) {
            return
        }

        lastLoggedAtByKey[key] = now

        if (throwable == null) {
            logger.warning(message)
            return
        }

        logger.logSafeWarning(message, throwable)
    }

    private fun shouldLog(key: String, now: Instant): Boolean {
        val lastLoggedAt = lastLoggedAtByKey[key] ?: return true
        val elapsed = Duration.between(lastLoggedAt, now)

        return !elapsed.minus(interval).isNegative
    }
}
