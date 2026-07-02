package me.matsumo.fukurou.trading.exchange.gmo

import java.time.Duration

/**
 * GMO Public REST client の timeout / retry / rate-limit 設定。
 *
 * @param baseUrl GMO Public API base URL
 * @param connectTimeout HTTP 接続 timeout
 * @param requestTimeout HTTP request 全体 timeout
 * @param symbolRulesCacheTtl symbol rules cache の TTL
 * @param rateLimit Public REST 呼び出しの rate-limit 設定
 * @param retry 一時的な失敗に対する retry 設定
 */
data class GmoPublicClientConfig(
    val baseUrl: String = "https://api.coin.z.com/public",
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val requestTimeout: Duration = Duration.ofSeconds(10),
    val symbolRulesCacheTtl: Duration = Duration.ofMinutes(10),
    val rateLimit: GmoRateLimitConfig = GmoRateLimitConfig(),
    val retry: GmoRetryConfig = GmoRetryConfig(),
)

/**
 * GMO Public REST 呼び出しを GMO 制限より手前で絞る token bucket 設定。
 *
 * @param permitsPerSecond 1 秒あたりに許可する request 数
 * @param burstSize 短時間に許す最大 burst 数
 */
data class GmoRateLimitConfig(
    val permitsPerSecond: Int = 10,
    val burstSize: Int = permitsPerSecond,
) {
    init {
        require(permitsPerSecond > 0) {
            "permitsPerSecond must be greater than 0."
        }
        require(burstSize > 0) {
            "burstSize must be greater than 0."
        }
    }
}

/**
 * 一時的な GMO Public API 失敗に対する指数 backoff retry 設定。
 *
 * @param maxAttempts 初回を含む最大試行回数
 * @param initialBackoff 初回 retry 前の待機時間
 * @param maxBackoff retry 待機時間の上限
 * @param backoffMultiplier 待機時間に掛ける倍率
 */
data class GmoRetryConfig(
    val maxAttempts: Int = 3,
    val initialBackoff: Duration = Duration.ofMillis(200),
    val maxBackoff: Duration = Duration.ofSeconds(2),
    val backoffMultiplier: Long = 2,
) {
    init {
        require(maxAttempts > 0) {
            "maxAttempts must be greater than 0."
        }
        require(!initialBackoff.isNegative && !initialBackoff.isZero) {
            "initialBackoff must be greater than 0."
        }
        require(!maxBackoff.isNegative && !maxBackoff.isZero) {
            "maxBackoff must be greater than 0."
        }
        require(backoffMultiplier > 1) {
            "backoffMultiplier must be greater than 1."
        }
    }
}
