package me.matsumo.fukurou.trading.tool

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.invoker.isCodexProvider

/**
 * no-trade 系 failure payload を JSON 文字列として組み立てる。
 */
internal fun buildNoTradeFailurePayload(
    reason: String,
    cause: Throwable?,
    llmProvider: String?,
): String {
    val causeName = cause?.javaClass?.simpleName ?: "none"
    val message = cause?.message ?: ""
    val omitMessage = isCodexProvider(llmProvider)

    return buildJsonObject {
        put("reason", reason)
        put("cause", causeName)
        if (omitMessage) {
            put("messageOmitted", true)
        } else {
            put("message", message)
        }
        put("noTrade", true)
    }.toString()
}

/**
 * audit 失敗を元の失敗に添付し、呼び出し元へ返す主原因を保つ。
 */
internal fun Throwable.withSuppressedFailure(result: Result<Unit>): Throwable {
    result.exceptionOrNull()?.let { throwable -> addSuppressed(throwable) }

    return this
}
