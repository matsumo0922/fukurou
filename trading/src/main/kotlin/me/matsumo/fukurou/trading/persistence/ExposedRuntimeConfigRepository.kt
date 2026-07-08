package me.matsumo.fukurou.trading.persistence

import me.matsumo.fukurou.trading.config.ActiveRuntimeConfigSnapshot
import me.matsumo.fukurou.trading.config.ActiveRuntimeConfigSource
import me.matsumo.fukurou.trading.config.RuntimeConfigCatalog
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * active runtime config の状態名。
 */
private const val RUNTIME_CONFIG_STATUS_ACTIVE = "ACTIVE"

/**
 * bootstrap 由来の runtime config 作成者。
 */
private const val RUNTIME_CONFIG_BOOTSTRAP_CREATED_BY = "bootstrap"

/**
 * bootstrap 由来の runtime config note。
 */
private const val RUNTIME_CONFIG_BOOTSTRAP_NOTE = "code catalog defaults"

/**
 * active runtime config version を読む SQL。
 */
private const val SELECT_ACTIVE_RUNTIME_CONFIG_VERSION_SQL = """
    SELECT
        id,
        activated_at
    FROM runtime_config_versions
    WHERE status = ?
"""

/**
 * runtime config version 件数を読む SQL。
 */
private const val COUNT_RUNTIME_CONFIG_VERSIONS_SQL = """
    SELECT COUNT(*)
    FROM runtime_config_versions
"""

/**
 * runtime config values を読む SQL。
 */
private const val SELECT_RUNTIME_CONFIG_VALUES_SQL = """
    SELECT
        config_key,
        config_value
    FROM runtime_config_values
    WHERE version_id = ?
    ORDER BY config_key ASC
"""

/**
 * bootstrap active runtime config version を作る SQL。
 */
private const val INSERT_BOOTSTRAP_RUNTIME_CONFIG_VERSION_SQL = """
    INSERT INTO runtime_config_versions (
        id,
        status,
        created_at,
        activated_at,
        created_by,
        note
    )
    VALUES (?, ?, ?, ?, ?, ?)
    ON CONFLICT DO NOTHING
"""

/**
 * runtime config value を追加する SQL。
 */
private const val INSERT_RUNTIME_CONFIG_VALUE_SQL = """
    INSERT INTO runtime_config_values (
        version_id,
        config_key,
        config_value
    )
    VALUES (?, ?, ?)
    ON CONFLICT (version_id, config_key) DO NOTHING
"""

/**
 * active runtime config version を 1 つに制限する index を作る SQL。
 */
private const val ENSURE_RUNTIME_CONFIG_ACTIVE_UNIQUE_INDEX_SQL = """
    CREATE UNIQUE INDEX IF NOT EXISTS idx_runtime_config_versions_active_unique
    ON runtime_config_versions (status)
    WHERE status = 'ACTIVE'
"""

/**
 * runtime config values の key 検索 index を作る SQL。
 */
private const val ENSURE_RUNTIME_CONFIG_VALUES_KEY_INDEX_SQL = """
    CREATE INDEX IF NOT EXISTS idx_runtime_config_values_config_key
    ON runtime_config_values (config_key)
"""

/**
 * runtime_config_versions schema の存在を確認する SQL。
 */
private const val VERIFY_RUNTIME_CONFIG_VERSIONS_SCHEMA_SQL = """
    SELECT
        id,
        status,
        created_at,
        activated_at,
        created_by,
        note
    FROM runtime_config_versions
    LIMIT 0
"""

/**
 * runtime_config_values schema の存在を確認する SQL。
 */
private const val VERIFY_RUNTIME_CONFIG_VALUES_SCHEMA_SQL = """
    SELECT
        version_id,
        config_key,
        config_value
    FROM runtime_config_values
    LIMIT 0
"""

/**
 * runtime config index 存在を確認する SQL。
 */
private const val VERIFY_RUNTIME_CONFIG_INDEX_COUNT_SQL = """
    SELECT 1
    FROM pg_indexes
    WHERE schemaname = current_schema()
        AND (
            (
                tablename = 'runtime_config_versions'
                AND indexname = 'idx_runtime_config_versions_active_unique'
            )
            OR (
                tablename = 'runtime_config_values'
                AND indexname = 'idx_runtime_config_values_config_key'
            )
        )
    HAVING COUNT(*) = 2
"""

/**
 * runtime_config_versions / runtime_config_values を初期化する bootstrapper。
 *
 * @param database Exposed database
 * @param clock bootstrap timestamp に使う clock
 */
class RuntimeConfigPersistenceBootstrap(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * runtime config schema と初期 active version を用意する。
     */
    fun ensureSchema(): Result<Unit> {
        return runCatching {
            exposedTransaction(database) {
                @Suppress("DEPRECATION")
                SchemaUtils.createMissingTablesAndColumns(
                    RuntimeConfigVersionsTable,
                    RuntimeConfigValuesTable,
                    withLogs = false,
                )
                ensureRuntimeConfigIndexes()
                ensureInitialActiveRuntimeConfigVersion(Instant.now(clock))
            }
        }
    }
}

/**
 * Exposed/JDBC で active runtime config を読む repository。
 *
 * @param database Exposed database
 */
class ExposedRuntimeConfigRepository(
    private val database: ExposedDatabase,
) : ActiveRuntimeConfigSource {

    override fun activeSnapshot(): Result<ActiveRuntimeConfigSnapshot> {
        return runCatching {
            exposedTransaction(database) {
                val version = requireSingleActiveRuntimeConfigVersion()
                val values = selectRuntimeConfigValues(version.versionId)

                require(values.isNotEmpty()) {
                    "Active runtime config has no values."
                }

                ActiveRuntimeConfigSnapshot(
                    versionId = version.versionId.toString(),
                    activatedAt = version.activatedAt,
                    values = values,
                )
            }
        }
    }
}

internal fun JdbcTransaction.ensureRuntimeConfigIndexes() {
    executeUpdate(ENSURE_RUNTIME_CONFIG_ACTIVE_UNIQUE_INDEX_SQL)
    executeUpdate(ENSURE_RUNTIME_CONFIG_VALUES_KEY_INDEX_SQL)
}

internal fun JdbcTransaction.ensureInitialActiveRuntimeConfigVersion(now: Instant) {
    if (countRuntimeConfigVersions() == 0) {
        val versionId = UUID.randomUUID()

        insertBootstrapRuntimeConfigVersion(versionId, now)
        insertRuntimeConfigValues(
            versionId = versionId,
            values = RuntimeConfigCatalog.runtimeDefaultValues(),
        )
    }

    verifyActiveRuntimeConfigVersionValues()
}

internal fun JdbcTransaction.verifyRuntimeConfigSchema() {
    verifySchemaBySql(
        sql = VERIFY_RUNTIME_CONFIG_VERSIONS_SCHEMA_SQL,
        missingMessage = "runtime_config_versions schema was not initialized.",
    )
    verifySchemaBySql(
        sql = VERIFY_RUNTIME_CONFIG_VALUES_SCHEMA_SQL,
        missingMessage = "runtime_config_values schema was not initialized.",
    )
    verifyExistsBySql(
        sql = VERIFY_RUNTIME_CONFIG_INDEX_COUNT_SQL,
        missingMessage = "runtime config indexes were not initialized.",
    )
    verifyActiveRuntimeConfigVersionValues()
}

private fun JdbcTransaction.countRuntimeConfigVersions(): Int {
    return jdbcConnection().prepareStatement(COUNT_RUNTIME_CONFIG_VERSIONS_SQL).use { statement ->
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.getInt(1) else 0
        }
    }
}

private fun JdbcTransaction.selectActiveRuntimeConfigVersions(): List<RuntimeConfigVersionRow> {
    return jdbcConnection().prepareStatement(SELECT_ACTIVE_RUNTIME_CONFIG_VERSION_SQL).use { statement ->
        statement.setString(1, RUNTIME_CONFIG_STATUS_ACTIVE)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.toRuntimeConfigVersionRow())
                }
            }
        }
    }
}

private fun JdbcTransaction.requireSingleActiveRuntimeConfigVersion(): RuntimeConfigVersionRow {
    val versions = selectActiveRuntimeConfigVersions()

    require(versions.size == 1) {
        "Expected exactly one active runtime config version, but found ${versions.size}."
    }

    return versions.single()
}

private fun JdbcTransaction.verifyActiveRuntimeConfigVersionValues() {
    val activeVersion = requireSingleActiveRuntimeConfigVersion()
    val values = selectRuntimeConfigValues(activeVersion.versionId)

    require(values.isNotEmpty()) {
        "Active runtime config has no values."
    }

    val expectedKeys = RuntimeConfigCatalog.runtimeDefaultValues().keys
    val activeKeys = values.keys
    val unknownKeys = activeKeys - expectedKeys
    val missingKeys = expectedKeys - activeKeys

    require(unknownKeys.isEmpty()) {
        "Active runtime config contains catalog-incompatible keys: ${unknownKeys.sorted()}"
    }
    require(missingKeys.isEmpty()) {
        "Active runtime config is missing catalog keys: ${missingKeys.sorted()}"
    }
}

private fun JdbcTransaction.selectRuntimeConfigValues(versionId: UUID): Map<String, String> {
    return jdbcConnection().prepareStatement(SELECT_RUNTIME_CONFIG_VALUES_SQL).use { statement ->
        statement.setObject(1, versionId)
        statement.executeQuery().use { resultSet ->
            buildMap {
                while (resultSet.next()) {
                    put(resultSet.getString("config_key"), resultSet.getString("config_value"))
                }
            }
        }
    }
}

private fun JdbcTransaction.insertBootstrapRuntimeConfigVersion(versionId: UUID, now: Instant) {
    jdbcConnection().prepareStatement(INSERT_BOOTSTRAP_RUNTIME_CONFIG_VERSION_SQL).use { statement ->
        statement.setObject(1, versionId)
        statement.setString(2, RUNTIME_CONFIG_STATUS_ACTIVE)
        statement.setLong(3, now.toEpochMilli())
        statement.setLong(4, now.toEpochMilli())
        statement.setString(5, RUNTIME_CONFIG_BOOTSTRAP_CREATED_BY)
        statement.setString(6, RUNTIME_CONFIG_BOOTSTRAP_NOTE)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.insertRuntimeConfigValues(versionId: UUID, values: Map<String, String>) {
    jdbcConnection().prepareStatement(INSERT_RUNTIME_CONFIG_VALUE_SQL).use { statement ->
        values.toSortedMap().forEach { (key, value) ->
            statement.setObject(1, versionId)
            statement.setString(2, key)
            statement.setString(3, value)
            statement.addBatch()
        }
        statement.executeBatch()
    }
}

private fun ResultSet.toRuntimeConfigVersionRow(): RuntimeConfigVersionRow {
    val activatedAtMillis = getLong("activated_at")

    require(!wasNull()) {
        "Active runtime config version must have activated_at."
    }

    return RuntimeConfigVersionRow(
        versionId = getObject("id", UUID::class.java),
        activatedAt = Instant.ofEpochMilli(activatedAtMillis),
    )
}

private data class RuntimeConfigVersionRow(
    val versionId: UUID,
    val activatedAt: Instant,
)
