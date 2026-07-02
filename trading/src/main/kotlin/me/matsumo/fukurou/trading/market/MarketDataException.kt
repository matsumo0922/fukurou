package me.matsumo.fukurou.trading.market

/**
 * 市場データ取得時の失敗を MCP response で分類するための基底例外。
 */
sealed class MarketDataException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * symbol / interval / limit / indicator params が不正なときの例外。
 */
class MarketInvalidRequestException(
    message: String,
) : MarketDataException(message)

/**
 * GMO Public API への network 接続が失敗したときの例外。
 */
class MarketNetworkException(
    message: String,
    cause: Throwable? = null,
) : MarketDataException(message, cause)

/**
 * GMO Public API が HTTP 200 以外を返したときの例外。
 */
class GmoHttpException(
    val statusCode: Int,
    message: String,
) : MarketDataException(message)

/**
 * GMO Public API が rate limit を示したときの例外。
 */
class GmoRateLimitException(
    message: String,
) : MarketDataException(message)

/**
 * GMO Public API の `status` が成功以外だったときの例外。
 */
class GmoApiStatusException(
    val status: Int,
    message: String,
) : MarketDataException(message)

/**
 * GMO Public API response の parse または必須 field 解決に失敗したときの例外。
 */
class MarketDataParseException(
    message: String,
    cause: Throwable? = null,
) : MarketDataException(message, cause)
