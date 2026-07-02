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
    val baseUrl: String = DEFAULT_GMO_PUBLIC_API_BASE_URL,
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val requestTimeout: Duration = Duration.ofSeconds(10),
    val symbolRulesCacheTtl: Duration = Duration.ofMinutes(10),
    val rateLimit: GmoRateLimitConfig = GmoRateLimitConfig(),
    val retry: GmoRetryConfig = GmoRetryConfig(),
) {
    companion object {
        /**
         * GMO Public API に必要な環境変数だけから client 設定を構築する。
         */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): GmoPublicClientConfig {
            val permitsPerSecond = environment.readInt(
                name = FUKUROU_GMO_PUBLIC_REST_PER_SECOND_ENV,
                defaultValue = DEFAULT_GMO_PUBLIC_REST_PER_SECOND,
            )
            val burstSize = environment.readInt(
                name = FUKUROU_GMO_PUBLIC_REST_BURST_ENV,
                defaultValue = permitsPerSecond,
            )

            return GmoPublicClientConfig(
                baseUrl = environment.readOptional(FUKUROU_GMO_PUBLIC_BASE_URL_ENV)
                    ?: DEFAULT_GMO_PUBLIC_API_BASE_URL,
                connectTimeout = environment.readDurationMillis(
                    name = FUKUROU_GMO_CONNECT_TIMEOUT_MS_ENV,
                    defaultValue = Duration.ofSeconds(5),
                ),
                requestTimeout = environment.readDurationMillis(
                    name = FUKUROU_GMO_REQUEST_TIMEOUT_MS_ENV,
                    defaultValue = Duration.ofSeconds(10),
                ),
                symbolRulesCacheTtl = environment.readDurationSeconds(
                    name = FUKUROU_GMO_SYMBOL_RULES_CACHE_TTL_SECONDS_ENV,
                    defaultValue = Duration.ofMinutes(10),
                ),
                rateLimit = GmoRateLimitConfig(
                    permitsPerSecond = permitsPerSecond,
                    burstSize = burstSize,
                ),
                retry = GmoRetryConfig(
                    maxAttempts = environment.readInt(
                        name = FUKUROU_GMO_RETRY_MAX_ATTEMPTS_ENV,
                        defaultValue = DEFAULT_GMO_RETRY_MAX_ATTEMPTS,
                    ),
                    initialBackoff = environment.readDurationMillis(
                        name = FUKUROU_GMO_RETRY_INITIAL_BACKOFF_MS_ENV,
                        defaultValue = DEFAULT_GMO_RETRY_INITIAL_BACKOFF,
                    ),
                    maxBackoff = environment.readDurationMillis(
                        name = FUKUROU_GMO_RETRY_MAX_BACKOFF_MS_ENV,
                        defaultValue = DEFAULT_GMO_RETRY_MAX_BACKOFF,
                    ),
                    backoffMultiplier = environment.readLong(
                        name = FUKUROU_GMO_RETRY_BACKOFF_MULTIPLIER_ENV,
                        defaultValue = DEFAULT_GMO_RETRY_BACKOFF_MULTIPLIER,
                    ),
                ),
            )
        }
    }
}

/**
 * GMO Public REST 呼び出しを GMO 制限より手前で絞る token bucket 設定。
 *
 * @param permitsPerSecond 1 秒あたりに許可する request 数
 * @param burstSize 短時間に許す最大 burst 数
 */
data class GmoRateLimitConfig(
    val permitsPerSecond: Int = DEFAULT_GMO_PUBLIC_REST_PER_SECOND,
    val burstSize: Int = permitsPerSecond,
) {
    init {
        val permitsPerSecondIsConservative = permitsPerSecond <= DEFAULT_GMO_PUBLIC_REST_PER_SECOND
        val burstSizeIsConservative = burstSize <= DEFAULT_GMO_PUBLIC_REST_BURST

        require(permitsPerSecond > 0) {
            "permitsPerSecond must be greater than 0."
        }
        require(permitsPerSecondIsConservative) {
            "permitsPerSecond must be less than or equal to 10."
        }
        require(burstSize > 0) {
            "burstSize must be greater than 0."
        }
        require(burstSizeIsConservative) {
            "burstSize must be less than or equal to 10."
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
    val maxAttempts: Int = DEFAULT_GMO_RETRY_MAX_ATTEMPTS,
    val initialBackoff: Duration = DEFAULT_GMO_RETRY_INITIAL_BACKOFF,
    val maxBackoff: Duration = DEFAULT_GMO_RETRY_MAX_BACKOFF,
    val backoffMultiplier: Long = DEFAULT_GMO_RETRY_BACKOFF_MULTIPLIER,
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

/**
 * GMO Public API base URL の既定値。
 */
private const val DEFAULT_GMO_PUBLIC_API_BASE_URL = "https://api.coin.z.com/public"

/**
 * GMO Public REST client-side rate limit の既定値。
 */
private const val DEFAULT_GMO_PUBLIC_REST_PER_SECOND = 10

/**
 * GMO Public REST client-side burst limit の既定値。
 */
private const val DEFAULT_GMO_PUBLIC_REST_BURST = 10

/**
 * GMO retry max attempts の既定値。
 */
private const val DEFAULT_GMO_RETRY_MAX_ATTEMPTS = 3

/**
 * GMO retry initial backoff の既定値。
 */
private val DEFAULT_GMO_RETRY_INITIAL_BACKOFF: Duration = Duration.ofMillis(200)

/**
 * GMO retry max backoff の既定値。
 */
private val DEFAULT_GMO_RETRY_MAX_BACKOFF: Duration = Duration.ofSeconds(2)

/**
 * GMO retry backoff multiplier の既定値。
 */
private const val DEFAULT_GMO_RETRY_BACKOFF_MULTIPLIER = 2L

/**
 * GMO Public API base URL の環境変数名。
 */
private const val FUKUROU_GMO_PUBLIC_BASE_URL_ENV = "FUKUROU_GMO_PUBLIC_BASE_URL"

/**
 * GMO Public API connect timeout ms の環境変数名。
 */
private const val FUKUROU_GMO_CONNECT_TIMEOUT_MS_ENV = "FUKUROU_GMO_CONNECT_TIMEOUT_MS"

/**
 * GMO Public API request timeout ms の環境変数名。
 */
private const val FUKUROU_GMO_REQUEST_TIMEOUT_MS_ENV = "FUKUROU_GMO_REQUEST_TIMEOUT_MS"

/**
 * GMO symbol rules cache TTL seconds の環境変数名。
 */
private const val FUKUROU_GMO_SYMBOL_RULES_CACHE_TTL_SECONDS_ENV = "FUKUROU_GMO_SYMBOL_RULES_CACHE_TTL_SECONDS"

/**
 * GMO Public REST per-second limit の環境変数名。
 */
private const val FUKUROU_GMO_PUBLIC_REST_PER_SECOND_ENV = "FUKUROU_GMO_PUBLIC_REST_PER_SECOND"

/**
 * GMO Public REST burst limit の環境変数名。
 */
private const val FUKUROU_GMO_PUBLIC_REST_BURST_ENV = "FUKUROU_GMO_PUBLIC_REST_BURST"

/**
 * GMO retry max attempts の環境変数名。
 */
private const val FUKUROU_GMO_RETRY_MAX_ATTEMPTS_ENV = "FUKUROU_GMO_RETRY_MAX_ATTEMPTS"

/**
 * GMO retry initial backoff ms の環境変数名。
 */
private const val FUKUROU_GMO_RETRY_INITIAL_BACKOFF_MS_ENV = "FUKUROU_GMO_RETRY_INITIAL_BACKOFF_MS"

/**
 * GMO retry max backoff ms の環境変数名。
 */
private const val FUKUROU_GMO_RETRY_MAX_BACKOFF_MS_ENV = "FUKUROU_GMO_RETRY_MAX_BACKOFF_MS"

/**
 * GMO retry backoff multiplier の環境変数名。
 */
private const val FUKUROU_GMO_RETRY_BACKOFF_MULTIPLIER_ENV = "FUKUROU_GMO_RETRY_BACKOFF_MULTIPLIER"

private fun Map<String, String>.readInt(name: String, defaultValue: Int): Int {
    return readOptional(name)?.toInt() ?: defaultValue
}

private fun Map<String, String>.readLong(name: String, defaultValue: Long): Long {
    return readOptional(name)?.toLong() ?: defaultValue
}

private fun Map<String, String>.readDurationMillis(name: String, defaultValue: Duration): Duration {
    return readOptional(name)
        ?.toLong()
        ?.let { millis -> Duration.ofMillis(millis) }
        ?: defaultValue
}

private fun Map<String, String>.readDurationSeconds(name: String, defaultValue: Duration): Duration {
    return readOptional(name)
        ?.toLong()
        ?.let { seconds -> Duration.ofSeconds(seconds) }
        ?: defaultValue
}

private fun Map<String, String>.readOptional(name: String): String? {
    return this[name]
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
}
