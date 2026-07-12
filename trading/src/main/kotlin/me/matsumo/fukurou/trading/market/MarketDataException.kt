package me.matsumo.fukurou.trading.market

/**
 * 市場データ取得失敗の再試行可否を表す分類。
 */
enum class MarketDataFailureKind {
    /**
     * 一時的な障害。retry や次回取得で復旧しうる。
     */
    TEMPORARY,

    /**
     * 恒久的な障害。入力や実装を直さない限り retry しても成功しない。
     */
    PERMANENT,
}

/**
 * 市場データ取得時の失敗を MCP response で分類するための基底例外。
 *
 * @param kind 一時的または恒久的な失敗分類
 */
sealed class MarketDataException(
    val kind: MarketDataFailureKind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * symbol / interval / limit / indicator params が不正なときの例外。
 */
class MarketInvalidRequestException(
    message: String,
    cause: Throwable? = null,
) : MarketDataException(MarketDataFailureKind.PERMANENT, message, cause)

/**
 * GMO Public API への network 接続が失敗したときの例外。
 */
class MarketNetworkException(
    message: String,
    cause: Throwable? = null,
) : MarketDataException(MarketDataFailureKind.TEMPORARY, message, cause)

/**
 * GMO Public API が HTTP 200 以外を返したときの例外。
 */
class GmoHttpException(
    val statusCode: Int,
    kind: MarketDataFailureKind,
    message: String,
) : MarketDataException(kind, message)

/**
 * GMO Public API が rate limit を示したときの例外。
 */
class GmoRateLimitException(
    message: String,
) : MarketDataException(MarketDataFailureKind.TEMPORARY, message)

/**
 * GMO Public request の監査保存に失敗したときの例外。
 */
class GmoRequestAuditException : MarketDataException(
    MarketDataFailureKind.PERMANENT,
    "GMO public request audit failed after execution.",
)

/**
 * GMO Public API の `status` が成功以外だったときの例外。
 */
class GmoApiStatusException(
    val status: Int,
    message: String,
) : MarketDataException(MarketDataFailureKind.PERMANENT, message)

/**
 * GMO Public API response の parse または必須 field 解決に失敗したときの例外。
 */
class MarketDataParseException(
    message: String,
    cause: Throwable? = null,
) : MarketDataException(MarketDataFailureKind.PERMANENT, message, cause)
