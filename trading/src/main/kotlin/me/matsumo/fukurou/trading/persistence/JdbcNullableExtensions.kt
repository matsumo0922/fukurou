package me.matsumo.fukurou.trading.persistence

import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.util.UUID

/**
 * nullable string parameter を PreparedStatement へ設定する。
 */
internal fun PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) {
        setString(index, null)
        return
    }

    setString(index, value)
}

/**
 * nullable decimal parameter を PreparedStatement へ設定する。
 */
internal fun PreparedStatement.setNullableBigDecimal(index: Int, value: BigDecimal?) {
    if (value == null) {
        setNull(index, Types.NUMERIC)
        return
    }

    setBigDecimal(index, value)
}

/**
 * nullable long parameter を PreparedStatement へ設定する。
 */
internal fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) {
        setNull(index, Types.BIGINT)
        return
    }

    setLong(index, value)
}

/**
 * nullable UUID column を取得する。
 */
internal fun ResultSet.getNullableUuid(columnName: String): UUID? {
    val value = getObject(columnName, UUID::class.java)

    return if (wasNull()) null else value
}

/**
 * nullable Instant column を取得する。
 */
internal fun ResultSet.getNullableInstant(columnName: String): Instant? {
    val epochMillis = getLong(columnName)

    return if (wasNull()) null else Instant.ofEpochMilli(epochMillis)
}

/**
 * nullable decimal column を取得する。
 */
internal fun ResultSet.getNullableBigDecimal(columnName: String): BigDecimal? {
    val value = getBigDecimal(columnName)

    return if (wasNull()) null else value
}

/**
 * nullable long column を取得する。
 */
internal fun ResultSet.getNullableLong(columnName: String): Long? {
    val value = getLong(columnName)

    return if (wasNull()) null else value
}
