package me.matsumo.fukurou.trading.invoker

import me.matsumo.fukurou.trading.logging.SafeLogFields
import me.matsumo.fukurou.trading.logging.SafeLoggableFailure
import me.matsumo.fukurou.trading.logging.safeLogFieldsOrNull

/**
 * 元の例外を置き換えず provider 情報を内部伝播する marker。
 *
 * @param provider failure が発生した LLM provider
 * @param failureType path や message を含まない例外型
 */
private class LlmProviderFailureMarker(
    val provider: LlmProvider,
    val failureType: String,
) : Throwable("LLM invocation failure classified.", null, false, false), SafeLoggableFailure {

    override fun safeLogFields(): SafeLogFields {
        return SafeLogFields(
            category = CODEX_INVOCATION_RESULT_UNAVAILABLE,
            type = failureType,
        )
    }
}

/** suppressed failure が provider 分類用の非cleanup markerか判定する。 */
internal fun Throwable.isLlmProviderFailureMarker(): Boolean = this is LlmProviderFailureMarker

/**
 * Codex の場合だけ、元の例外と cleanup failure を維持したまま provider 分類を付ける。
 */
internal fun <T : Throwable> T.classifyLlmFailure(provider: LlmProvider): T {
    if (provider != LlmProvider.CODEX) {
        return this
    }

    val alreadyClassified = suppressed
        .filterIsInstance<LlmProviderFailureMarker>()
        .any { marker -> marker.provider == provider }

    if (!alreadyClassified) {
        addSuppressed(
            LlmProviderFailureMarker(
                provider = provider,
                failureType = safeExceptionType(),
            ),
        )
    }

    return this
}

/**
 * provider 名が Codex の場合だけ元例外へ分類を付ける。
 */
internal fun <T : Throwable> T.classifyLlmFailure(providerName: String?): T {
    val provider = providerName.toLlmProviderOrNull()
        ?: return this

    return classifyLlmFailure(provider)
}

/**
 * Codex failure の場合だけ人間向けの固定分類を返す。
 */
internal fun Throwable.safeCodexFailureOrNull(): SafeLogFields? {
    return safeLogFieldsOrNull()
        ?.takeIf { fields -> fields.category == CODEX_INVOCATION_RESULT_UNAVAILABLE }
}

/**
 * path や message を含まない例外型へ正規化する。
 */
internal fun Throwable.safeExceptionType(): String {
    val typeName = javaClass.simpleName

    return typeName.takeIf { value -> SAFE_EXCEPTION_TYPE.matches(value) } ?: "Throwable"
}

/**
 * provider 名が Codex を表すか判定する。
 */
internal fun isCodexProvider(providerName: String?): Boolean {
    return providerName.toLlmProviderOrNull() == LlmProvider.CODEX
}

private fun String?.toLlmProviderOrNull(): LlmProvider? {
    return LlmProvider.entries.firstOrNull { provider -> provider.name.equals(this, ignoreCase = true) }
}

/**
 * Codex invocation failure の固定 category。
 */
internal const val CODEX_INVOCATION_RESULT_UNAVAILABLE = "INVOCATION_RESULT_UNAVAILABLE"

/**
 * Codex failure の永続化面へ保存する固定文言。
 */
internal const val CODEX_FAILURE_DETAILS_OMITTED = "Codex invocation failure details omitted."

private val SAFE_EXCEPTION_TYPE = Regex("[A-Za-z][A-Za-z0-9]*")
