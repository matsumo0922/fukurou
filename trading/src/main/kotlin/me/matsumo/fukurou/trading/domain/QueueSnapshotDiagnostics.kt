package me.matsumo.fukurou.trading.domain

/**
 * resting BUY LIMIT の queue snapshot が取得できず注文を fail-closed にしたときの診断文言。
 *
 * 値はいずれも変数展開を持たないリテラルであり、外部入力を含まない。
 * no-trade audit payload は、この集合との完全一致だけを保存対象にする。
 */
object QueueSnapshotDiagnostics {

    /** realtime market-data session が接続していない。 */
    const val SESSION_NOT_CONNECTED = "QUEUE_SNAPSHOT_UNAVAILABLE: realtime market-data session is not connected."

    /** realtime market-data session ID を取得できない。 */
    const val SESSION_UNAVAILABLE = "QUEUE_SNAPSHOT_UNAVAILABLE: realtime market-data session is unavailable."

    /** realtime market-data session がまだ event を受け取っていない。 */
    const val SESSION_HAS_NO_EVENT =
        "QUEUE_SNAPSHOT_UNAVAILABLE: realtime market-data session has not received an event."

    /** realtime market-data session の最終 event が freshness window を超えている。 */
    const val SESSION_STALE = "QUEUE_SNAPSHOT_UNAVAILABLE: realtime market-data session is stale."

    /** 注文作成中に realtime market-data session が入れ替わった。 */
    const val SESSION_CHANGED = "QUEUE_SNAPSHOT_UNAVAILABLE: realtime market-data session changed during order creation."

    /** orderbook 取得が失敗した。 */
    const val ORDERBOOK_REQUEST_FAILED = "QUEUE_SNAPSHOT_UNAVAILABLE: orderbook request failed."

    /** orderbook に解釈できる bid depth がない。 */
    const val ORDERBOOK_NO_VALID_BID_DEPTH = "QUEUE_SNAPSHOT_UNAVAILABLE: orderbook has no valid bid depth."

    /** 指値が返却された bid depth の圏外にある。 */
    const val LIMIT_OUTSIDE_BID_DEPTH = "QUEUE_SNAPSHOT_UNAVAILABLE: limit price is outside returned bid depth."

    /** ledger 側で market-data session が接続していない。 */
    const val LEDGER_SESSION_NOT_CONNECTED = "QUEUE_SNAPSHOT_UNAVAILABLE: market-data session is not connected."

    /** 注文作成中に ledger 側の market-data session が進行した。 */
    const val LEDGER_SESSION_ADVANCED =
        "QUEUE_SNAPSHOT_UNAVAILABLE: market-data session advanced during order creation."

    /**
     * audit payload へ保存してよい診断文言の集合。
     *
     * 判定は prefix ではなく完全一致で行う。guard は任意の `Throwable` を cause として受けるため、
     * prefix 判定では外部由来の例外が同じ接頭辞を持つ message を構築でき、その後続に secret を混入できてしまう。
     */
    val PERSISTABLE_MESSAGES: Set<String> = setOf(
        SESSION_NOT_CONNECTED,
        SESSION_UNAVAILABLE,
        SESSION_HAS_NO_EVENT,
        SESSION_STALE,
        SESSION_CHANGED,
        ORDERBOOK_REQUEST_FAILED,
        ORDERBOOK_NO_VALID_BID_DEPTH,
        LIMIT_OUTSIDE_BID_DEPTH,
        LEDGER_SESSION_NOT_CONNECTED,
        LEDGER_SESSION_ADVANCED,
    )
}
