package me.matsumo.fukurou.trading.logging

import java.util.Collections
import java.util.IdentityHashMap

/**
 * 人間向け sink に出せる failure の固定 field。
 *
 * @param category failure category
 * @param type path や message を含まない例外型
 */
internal data class SafeLogFields(
    val category: String,
    val type: String,
) {
    /**
     * human-facing log に埋め込む固定 field 文字列を返す。
     */
    fun format(): String {
        return "category=$category type=$type"
    }
}

/**
 * provider 固有 failure が logging 層へ安全な field だけを公開する境界。
 */
internal interface SafeLoggableFailure {
    /**
     * 人間向け sink に出せる固定 field を返す。
     */
    fun safeLogFields(): SafeLogFields
}

/**
 * cause / suppressed を含む failure graph から安全な log field を探す。
 */
internal fun Throwable.safeLogFieldsOrNull(): SafeLogFields? {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())

    return findSafeLogFields(visited)
}

private fun Throwable.findSafeLogFields(visited: MutableSet<Throwable>): SafeLogFields? {
    if (!visited.add(this)) {
        return null
    }

    if (this is SafeLoggableFailure) {
        return safeLogFields()
    }

    cause?.findSafeLogFields(visited)?.let { fields -> return fields }

    return suppressed.firstNotNullOfOrNull { failure -> failure.findSafeLogFields(visited) }
}
