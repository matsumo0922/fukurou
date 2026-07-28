package me.matsumo.fukurou.trading.tool

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.domain.QueueSnapshotDiagnostics

/**
 * no-trade 系 failure payload を JSON 文字列として組み立てる。
 *
 * message は allowlist へ完全一致した場合だけ残す。
 */
internal fun buildNoTradeFailurePayload(reason: String, cause: Throwable?): String {
    val causeName = cause?.javaClass?.simpleName ?: "none"
    val persistableMessage = cause?.message?.takeIf { message -> message.isPersistableDiagnostic() }

    return buildJsonObject {
        put("reason", reason)
        put("cause", causeName)
        persistableMessage?.let { message -> put("message", message) }
        put("noTrade", true)
    }.toString()
}

/**
 * audit payload へ保存してよい診断文言か判定する。
 *
 * 判定は prefix ではなく完全一致で行う。guard は任意の `Throwable` を cause として受けるため、
 * prefix 判定では外部由来の例外が同じ接頭辞を持つ message を構築でき、その後続へ secret を混入できてしまう。
 * 完全一致であれば、通過する値はコードが定義した定数そのものに限られる。
 */
private fun String.isPersistableDiagnostic(): Boolean {
    return this in QueueSnapshotDiagnostics.PERSISTABLE_MESSAGES
}

/**
 * audit 失敗を元の失敗に添付し、呼び出し元へ返す主原因を保つ。
 */
internal fun Throwable.withSuppressedFailure(result: Result<Unit>): Throwable {
    result.exceptionOrNull()?.let { throwable -> addSuppressed(throwable) }

    return this
}
