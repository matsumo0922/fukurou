package me.matsumo.fukurou.trading.invoker

import java.util.Collections
import java.util.IdentityHashMap

/**
 * 人間向け sink に出せる LLM failure の固定分類。
 *
 * @param category failure category
 * @param type path や message を含まない例外型
 */
internal data class SafeLlmFailure(
    val category: String,
    val type: String,
) {
    /**
     * human-facing log に埋め込む固定 field 文字列を返す。
     */
    fun toLogFields(): String {
        return "category=$category type=$type"
    }
}

/**
 * 元の例外を置き換えず provider 情報を内部伝播する marker。
 *
 * @param provider failure が発生した LLM provider
 * @param failureType path や message を含まない例外型
 */
private class LlmProviderFailureMarker(
    val provider: LlmProvider,
    val failureType: String,
) : Throwable("LLM invocation failure classified.", null, false, false)

/**
 * Codex の場合だけ、元の例外と cleanup failure を維持したまま provider 分類を付ける。
 */
internal fun Throwable.classifyLlmFailure(provider: LlmProvider): Throwable {
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
internal fun Throwable.classifyLlmFailure(providerName: String?): Throwable {
    val provider = LlmProvider.entries
        .firstOrNull { candidate -> candidate.name.equals(providerName, ignoreCase = true) }
        ?: return this

    return classifyLlmFailure(provider)
}

/**
 * Codex failure の場合だけ人間向けの固定分類を返す。
 */
internal fun Throwable.safeCodexFailureOrNull(): SafeLlmFailure? {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val marker = findProviderFailureMarker(LlmProvider.CODEX, visited) ?: return null

    return SafeLlmFailure(
        category = CODEX_INVOCATION_RESULT_UNAVAILABLE,
        type = marker.failureType,
    )
}

/**
 * path や message を含まない例外型へ正規化する。
 */
internal fun Throwable.safeExceptionType(): String {
    val typeName = javaClass.simpleName

    return typeName.takeIf { value -> SAFE_EXCEPTION_TYPE.matches(value) } ?: "Throwable"
}

private fun Throwable.findProviderFailureMarker(
    provider: LlmProvider,
    visited: MutableSet<Throwable>,
): LlmProviderFailureMarker? {
    if (!visited.add(this)) {
        return null
    }

    val directMarker = suppressed
        .filterIsInstance<LlmProviderFailureMarker>()
        .firstOrNull { marker -> marker.provider == provider }

    if (directMarker != null) {
        return directMarker
    }

    cause?.findProviderFailureMarker(provider, visited)?.let { marker -> return marker }

    return suppressed.firstNotNullOfOrNull { failure ->
        failure.findProviderFailureMarker(provider, visited)
    }
}

/**
 * Codex invocation failure の固定 category。
 */
internal const val CODEX_INVOCATION_RESULT_UNAVAILABLE = "INVOCATION_RESULT_UNAVAILABLE"

private val SAFE_EXCEPTION_TYPE = Regex("[A-Za-z][A-Za-z0-9]*")
