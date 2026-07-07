package me.matsumo.fukurou.trading.persistence

import me.matsumo.fukurou.trading.feed.StableFeedCursor
import java.sql.PreparedStatement

internal fun stableFeedCursorCondition(
    timestampColumn: String,
    idColumn: String,
    cursor: StableFeedCursor,
): String {
    if (!cursor.includesSameTimestamp) {
        return "$timestampColumn < ?"
    }

    if (cursor.afterId == null) {
        return "($timestampColumn < ? OR $timestampColumn = ?)"
    }

    return "($timestampColumn < ? OR ($timestampColumn = ? AND CAST($idColumn AS TEXT) > ?))"
}

internal fun PreparedStatement.bindStableFeedCursor(startIndex: Int, cursor: StableFeedCursor): Int {
    var parameterIndex = startIndex

    setLong(parameterIndex, cursor.occurredAt.toEpochMilli())
    parameterIndex += 1

    if (!cursor.includesSameTimestamp) {
        return parameterIndex
    }

    setLong(parameterIndex, cursor.occurredAt.toEpochMilli())
    parameterIndex += 1

    val afterId = cursor.afterId ?: return parameterIndex

    setString(parameterIndex, afterId)
    parameterIndex += 1

    return parameterIndex
}
