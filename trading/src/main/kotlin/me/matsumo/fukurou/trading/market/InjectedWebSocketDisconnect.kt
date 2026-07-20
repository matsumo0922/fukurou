package me.matsumo.fukurou.trading.market

import java.util.UUID

/**
 * Issue #192 の注入切断が listener terminal boundary へ渡す固定 message。
 *
 * `sequence gap` を部分一致で含まないため、gap reason は `DISCONNECTED` に分類される。
 */
const val INJECTED_WEBSOCKET_DISCONNECT_MESSAGE = "issue-192 injected websocket disconnect"

/**
 * Issue #192 の注入切断で listener terminal boundary へ渡す typed failure。
 */
class InjectedWebSocketDisconnectException : RuntimeException(INJECTED_WEBSOCKET_DISCONNECT_MESSAGE)

/**
 * application が所有する active WebSocket session を1回だけ切断する結果。
 */
enum class InjectedWebSocketDisconnectOutcome {
    /** expected session の socket を abort し、terminal failure を1件届けた。 */
    DISCONNECTED,

    /** active session が存在しない。 */
    NO_ACTIVE_SESSION,

    /** active session ID が expected session ID と一致しない。 */
    SESSION_MISMATCH,

    /** 同じ session で既に注入切断を消費している。 */
    ALREADY_INJECTED,

    /** socket abort が失敗した。terminal failure は届けていない。 */
    ABORT_FAILED,
}

/**
 * expected session ID と厳密に一致する active WebSocket session だけを切断する狭い interface。
 *
 * REST client、他 session、container network、application process は対象にしない。
 */
fun interface InjectedWebSocketDisconnector {
    /**
     * expected session ID が active session と一致する場合だけ、socket abort と terminal failure 配送を1回行う。
     */
    fun disconnectActiveSession(expectedSessionId: UUID): InjectedWebSocketDisconnectOutcome
}
