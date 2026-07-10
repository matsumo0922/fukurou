@file:Suppress("ImportOrdering", "LongMethod", "TooManyFunctions")

package me.matsumo.fukurou.trading.persistence

import me.matsumo.fukurou.trading.config.ActiveRuntimeConfigSnapshot
import me.matsumo.fukurou.trading.config.ActiveRuntimeConfigSource
import me.matsumo.fukurou.trading.config.DEFAULT_RUNTIME_CONFIG_VERSION_LIMIT
import me.matsumo.fukurou.trading.config.RuntimeConfigActivationResult
import me.matsumo.fukurou.trading.config.RuntimeConfigActiveVersionChangedException
import me.matsumo.fukurou.trading.config.RuntimeConfigAdminService
import me.matsumo.fukurou.trading.config.RuntimeConfigCandidateValidator
import me.matsumo.fukurou.trading.config.RuntimeConfigCatalog
import me.matsumo.fukurou.trading.config.RuntimeConfigDraftCreation
import me.matsumo.fukurou.trading.config.RuntimeConfigVersionDetail
import me.matsumo.fukurou.trading.config.RuntimeConfigVersionSummary
import me.matsumo.fukurou.trading.config.calculateRuntimeConfigHash
import me.matsumo.fukurou.trading.config.canonicalizeRuntimeConfigValue
import me.matsumo.fukurou.trading.config.requireValid
import me.matsumo.fukurou.trading.config.retiredRuntimeConfigKeys
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
 * draft runtime config の状態名。
 */
private const val RUNTIME_CONFIG_STATUS_DRAFT = "DRAFT"

/**
 * inactive runtime config の状態名。
 */
private const val RUNTIME_CONFIG_STATUS_INACTIVE = "INACTIVE"

/**
 * bootstrap 由来の runtime config 作成者。
 */
private const val RUNTIME_CONFIG_BOOTSTRAP_CREATED_BY = "bootstrap"

/**
 * WebUI 由来の runtime config 作成者。
 */
private const val RUNTIME_CONFIG_WEBUI_CREATED_BY = "webui"

/**
 * bootstrap 由来の runtime config note。
 */
private const val RUNTIME_CONFIG_BOOTSTRAP_NOTE = "code catalog defaults"

/**
 * catalog reconciliation 由来の runtime config note prefix。
 */
private const val RUNTIME_CONFIG_CATALOG_RECONCILIATION_NOTE_PREFIX = "code catalog reconciliation"

/**
 * active runtime config version を読む SQL。
 */
private const val SELECT_ACTIVE_RUNTIME_CONFIG_VERSION_SQL = """
    SELECT
        id,
        status,
        created_at,
        activated_at,
        created_by,
        note
    FROM runtime_config_versions
    WHERE status = ?
"""

/**
 * runtime config version 一覧を読む SQL。
 */
private const val SELECT_RUNTIME_CONFIG_VERSIONS_SQL = """
    SELECT
        id,
        status,
        created_at,
        activated_at,
        created_by,
        note
    FROM runtime_config_versions
    ORDER BY created_at DESC, id DESC
    LIMIT ?
"""

/**
 * runtime config version を ID で読む SQL。
 */
private const val SELECT_RUNTIME_CONFIG_VERSION_BY_ID_SQL = """
    SELECT
        id,
        status,
        created_at,
        activated_at,
        created_by,
        note
    FROM runtime_config_versions
    WHERE id = ?
"""

/**
 * runtime config version 件数を読む SQL。
 */
private const val COUNT_RUNTIME_CONFIG_VERSIONS_SQL = """
    SELECT COUNT(*)
    FROM runtime_config_versions
"""

/**
 * bootstrap 中の active version 書き換えを直列化する SQL。
 */
private const val LOCK_RUNTIME_CONFIG_VERSION_WRITERS_SQL = """
    LOCK TABLE runtime_config_versions IN SHARE ROW EXCLUSIVE MODE
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
 * retention 対象の runtime config version ID を読む SQL。
 */
private const val SELECT_PRUNABLE_RUNTIME_CONFIG_VERSION_IDS_SQL = """
    SELECT id
    FROM runtime_config_versions
    WHERE status = ?
    ORDER BY COALESCE(activated_at, created_at) DESC, created_at DESC, id DESC
    OFFSET ?
"""

/**
 * active runtime config version を inactive にする SQL。
 */
private const val DEACTIVATE_RUNTIME_CONFIG_VERSION_SQL = """
    UPDATE runtime_config_versions
    SET status = ?
    WHERE id = ?
        AND status = ?
"""

/**
 * runtime config version を追加する SQL。
 */
private const val INSERT_RUNTIME_CONFIG_VERSION_SQL = """
    INSERT INTO runtime_config_versions (
        id,
        status,
        created_at,
        activated_at,
        created_by,
        note
    )
    VALUES (?, ?, ?, ?, ?, ?)
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
 * runtime config values を version ID で削除する SQL。
 */
private const val DELETE_RUNTIME_CONFIG_VALUES_BY_VERSION_ID_SQL = """
    DELETE FROM runtime_config_values
    WHERE version_id = ?
"""

/**
 * runtime config version を ID で削除する SQL。
 */
private const val DELETE_RUNTIME_CONFIG_VERSION_BY_ID_SQL = """
    DELETE FROM runtime_config_versions
    WHERE id = ?
"""

/**
 * active runtime config version を inactive にする SQL。
 */
private const val DEACTIVATE_ACTIVE_RUNTIME_CONFIG_VERSION_SQL = """
    UPDATE runtime_config_versions
    SET status = ?
    WHERE status = ?
"""

/**
 * runtime config version を active にする SQL。
 */
private const val ACTIVATE_RUNTIME_CONFIG_VERSION_SQL = """
    UPDATE runtime_config_versions
    SET
        status = ?,
        activated_at = ?
    WHERE id = ?
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
     * runtime config schema と active version を用意する。
     *
     * active snapshot に code-owned catalog key が不足している場合は、既存値を保持した
     * complete snapshot を新しい active version として作成する。明示的に退役した key は
     * 新しい active version から除去し、それ以外の unknown key は拒否する。
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
                ensureActiveRuntimeConfigVersion(Instant.now(clock))
            }
        }
    }
}

/**
 * Exposed/JDBC で active runtime config を読む repository。
 *
 * @param database Exposed database
 * @param clock version timestamp に使う clock
 * @param environment typed config validation に使う process environment
 */
class ExposedRuntimeConfigRepository(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val environment: Map<String, String> = System.getenv(),
) : ActiveRuntimeConfigSource, RuntimeConfigAdminService {

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
                    activatedAt = requireNotNull(version.activatedAt) {
                        "Active runtime config version must have activated_at."
                    },
                    values = values,
                )
            }
        }
    }

    override fun listVersions(limit: Int): Result<List<RuntimeConfigVersionSummary>> {
        return runCatching {
            exposedTransaction(database) {
                selectRuntimeConfigVersions(limit)
                    .map { row -> row.toSummary(selectRuntimeConfigValues(row.versionId)) }
            }
        }
    }

    override fun createDraft(request: RuntimeConfigDraftCreation): Result<RuntimeConfigVersionDetail> {
        return runCatching {
            exposedTransaction(database) {
                val baseVersion = request.baseVersionId
                    ?.let { versionId -> requireRuntimeConfigVersion(versionId) }
                    ?: requireSingleActiveRuntimeConfigVersion()
                val baseValues = selectRuntimeConfigValues(baseVersion.versionId)
                val mergedValues = baseValues + validateDraftPatchValues(request.values)
                val draftId = UUID.randomUUID()
                val now = Instant.now(clock)
                val createdBy = request.createdBy.trim().ifBlank { RUNTIME_CONFIG_WEBUI_CREATED_BY }

                insertRuntimeConfigVersion(
                    RuntimeConfigVersionInsert(
                        versionId = draftId,
                        status = RUNTIME_CONFIG_STATUS_DRAFT,
                        createdAt = now,
                        activatedAt = null,
                        createdBy = createdBy,
                        note = request.note?.trim()?.takeIf { note -> note.isNotEmpty() },
                    ),
                )
                insertRuntimeConfigValues(
                    versionId = draftId,
                    values = mergedValues,
                )

                val draft = requireRuntimeConfigVersion(draftId.toString())
                val validation = RuntimeConfigCandidateValidator.validate(mergedValues, environment).validation
                val detail = RuntimeConfigVersionDetail(
                    version = draft.toSummary(mergedValues),
                    values = mergedValues,
                    validation = validation,
                )

                pruneRuntimeConfigVersions()

                detail
            }
        }
    }

    override fun validateVersion(versionId: String): Result<RuntimeConfigVersionDetail> {
        return runCatching {
            exposedTransaction(database) {
                val version = requireRuntimeConfigVersion(versionId)
                val values = selectRuntimeConfigValues(version.versionId)

                RuntimeConfigVersionDetail(
                    version = version.toSummary(values),
                    values = values,
                    validation = RuntimeConfigCandidateValidator.validate(values, environment).validation,
                )
            }
        }
    }

    override fun activateDraft(versionId: String): Result<RuntimeConfigActivationResult> {
        return activateVersion(
            versionId = versionId,
            allowedStatuses = setOf(RUNTIME_CONFIG_STATUS_DRAFT),
            expectedActiveVersionId = null,
        )
    }

    override fun activateDraftIfActive(
        versionId: String,
        expectedActiveVersionId: String,
    ): Result<RuntimeConfigActivationResult> {
        return activateVersion(
            versionId = versionId,
            allowedStatuses = setOf(RUNTIME_CONFIG_STATUS_DRAFT),
            expectedActiveVersionId = expectedActiveVersionId,
        )
    }

    override fun rollbackToVersion(versionId: String): Result<RuntimeConfigActivationResult> {
        return activateVersion(
            versionId = versionId,
            allowedStatuses = setOf(RUNTIME_CONFIG_STATUS_INACTIVE),
            expectedActiveVersionId = null,
        )
    }

    private fun activateVersion(
        versionId: String,
        allowedStatuses: Set<String>,
        expectedActiveVersionId: String?,
    ): Result<RuntimeConfigActivationResult> {
        return runCatching {
            exposedTransaction(database) {
                lockRuntimeConfigVersionWriters()
                val targetVersion = requireRuntimeConfigVersion(versionId)

                require(targetVersion.status in allowedStatuses) {
                    "runtime config version ${targetVersion.versionId} status ${targetVersion.status} cannot be activated"
                }

                val values = selectRuntimeConfigValues(targetVersion.versionId)
                val validation = RuntimeConfigCandidateValidator.validate(values, environment).validation
                validation.requireValid()

                val previousActiveVersionId = requireSingleActiveRuntimeConfigVersion().versionId.toString()

                if (expectedActiveVersionId != null && previousActiveVersionId != expectedActiveVersionId) {
                    throw RuntimeConfigActiveVersionChangedException()
                }

                val now = Instant.now(clock)
                deactivateActiveRuntimeConfigVersion()
                activateRuntimeConfigVersion(targetVersion.versionId, now)

                val activeVersion = requireRuntimeConfigVersion(targetVersion.versionId.toString())
                val result = RuntimeConfigActivationResult(
                    activeVersion = activeVersion.toSummary(values),
                    previousActiveVersionId = previousActiveVersionId,
                    validation = validation,
                )

                pruneRuntimeConfigVersions()

                result
            }
        }
    }
}

internal fun JdbcTransaction.ensureRuntimeConfigIndexes() {
    executeUpdate(ENSURE_RUNTIME_CONFIG_ACTIVE_UNIQUE_INDEX_SQL)
    executeUpdate(ENSURE_RUNTIME_CONFIG_VALUES_KEY_INDEX_SQL)
}

internal fun JdbcTransaction.ensureActiveRuntimeConfigVersion(now: Instant) {
    lockRuntimeConfigVersionWriters()

    if (countRuntimeConfigVersions() == 0) {
        val versionId = UUID.randomUUID()

        insertRuntimeConfigVersion(
            versionId = versionId,
            now = now,
            note = RUNTIME_CONFIG_BOOTSTRAP_NOTE,
        )
        insertRuntimeConfigValues(
            versionId = versionId,
            values = RuntimeConfigCatalog.runtimeDefaultValues(),
        )
    }

    ensureActiveRuntimeConfigVersionValues(now)
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

private fun JdbcTransaction.lockRuntimeConfigVersionWriters() {
    executeUpdate(LOCK_RUNTIME_CONFIG_VERSION_WRITERS_SQL)
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

private fun JdbcTransaction.selectRuntimeConfigVersions(limit: Int): List<RuntimeConfigVersionRow> {
    return jdbcConnection().prepareStatement(SELECT_RUNTIME_CONFIG_VERSIONS_SQL).use { statement ->
        statement.setInt(1, limit.coerceIn(1, 100))
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.toRuntimeConfigVersionRow())
                }
            }
        }
    }
}

private fun JdbcTransaction.requireRuntimeConfigVersion(versionId: String): RuntimeConfigVersionRow {
    val uuid = runCatching { UUID.fromString(versionId) }.getOrNull()
    require(uuid != null) {
        "runtime config version id is invalid"
    }

    return requireRuntimeConfigVersion(uuid)
}

private fun JdbcTransaction.requireRuntimeConfigVersion(versionId: UUID): RuntimeConfigVersionRow {
    return jdbcConnection().prepareStatement(SELECT_RUNTIME_CONFIG_VERSION_BY_ID_SQL).use { statement ->
        statement.setObject(1, versionId)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) {
                "runtime config version was not found"
            }

            resultSet.toRuntimeConfigVersionRow()
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

private fun JdbcTransaction.ensureActiveRuntimeConfigVersionValues(now: Instant) {
    val activeVersion = requireSingleActiveRuntimeConfigVersion()
    val values = selectRuntimeConfigValues(activeVersion.versionId)

    require(values.isNotEmpty()) {
        "Active runtime config has no values."
    }

    val defaultValues = RuntimeConfigCatalog.runtimeDefaultValues()
    val catalogKeyDiff = computeRuntimeConfigCatalogKeyDiff(values, defaultValues.keys)

    val unexpectedKeys = catalogKeyDiff.unknownKeys - retiredRuntimeConfigKeys
    val retiredKeys = catalogKeyDiff.unknownKeys intersect retiredRuntimeConfigKeys

    require(unexpectedKeys.isEmpty()) {
        "Active runtime config contains catalog-incompatible keys: ${unexpectedKeys.sorted()}"
    }
    if (catalogKeyDiff.missingKeys.isNotEmpty() || retiredKeys.isNotEmpty()) {
        reconcileActiveRuntimeConfigVersionValues(
            activeVersion = activeVersion,
            values = values,
            defaultValues = defaultValues,
            reconciliation = RuntimeConfigCatalogReconciliation(
                missingKeys = catalogKeyDiff.missingKeys,
                retiredKeys = retiredKeys,
            ),
            now = now,
        )
    }
}

private fun JdbcTransaction.verifyActiveRuntimeConfigVersionValues() {
    val activeVersion = requireSingleActiveRuntimeConfigVersion()
    val values = selectRuntimeConfigValues(activeVersion.versionId)

    require(values.isNotEmpty()) {
        "Active runtime config has no values."
    }

    val catalogKeyDiff = computeRuntimeConfigCatalogKeyDiff(
        values = values,
        expectedKeys = RuntimeConfigCatalog.runtimeDefaultValues().keys,
    )

    require(catalogKeyDiff.unknownKeys.isEmpty()) {
        "Active runtime config contains catalog-incompatible keys: ${catalogKeyDiff.unknownKeys.sorted()}"
    }
    require(catalogKeyDiff.missingKeys.isEmpty()) {
        "Active runtime config is missing catalog keys: ${catalogKeyDiff.missingKeys.sorted()}"
    }
}

private fun computeRuntimeConfigCatalogKeyDiff(
    values: Map<String, String>,
    expectedKeys: Set<String>,
): RuntimeConfigCatalogKeyDiff {
    val activeKeys = values.keys

    return RuntimeConfigCatalogKeyDiff(
        unknownKeys = activeKeys - expectedKeys,
        missingKeys = expectedKeys - activeKeys,
    )
}

private fun JdbcTransaction.reconcileActiveRuntimeConfigVersionValues(
    activeVersion: RuntimeConfigVersionRow,
    values: Map<String, String>,
    defaultValues: Map<String, String>,
    reconciliation: RuntimeConfigCatalogReconciliation,
    now: Instant,
) {
    val versionId = UUID.randomUUID()
    val completeValues = (defaultValues + values) - reconciliation.retiredKeys

    deactivateRuntimeConfigVersion(activeVersion.versionId)
    insertRuntimeConfigVersion(
        versionId = versionId,
        now = now,
        note = runtimeConfigCatalogReconciliationNote(
            missingKeys = reconciliation.missingKeys,
            retiredKeys = reconciliation.retiredKeys,
        ),
    )
    insertRuntimeConfigValues(
        versionId = versionId,
        values = completeValues,
    )
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

private fun JdbcTransaction.pruneRuntimeConfigVersions() {
    val prunableIds = listOf(
        RUNTIME_CONFIG_STATUS_DRAFT,
        RUNTIME_CONFIG_STATUS_INACTIVE,
    ).flatMap { status -> selectPrunableRuntimeConfigVersionIds(status) }

    prunableIds.forEach { versionId ->
        deleteRuntimeConfigValues(versionId)
        deleteRuntimeConfigVersion(versionId)
    }
}

private fun JdbcTransaction.selectPrunableRuntimeConfigVersionIds(status: String): List<UUID> {
    return jdbcConnection().prepareStatement(SELECT_PRUNABLE_RUNTIME_CONFIG_VERSION_IDS_SQL).use { statement ->
        statement.setString(1, status)
        statement.setInt(2, DEFAULT_RUNTIME_CONFIG_VERSION_LIMIT)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.getObject("id", UUID::class.java))
                }
            }
        }
    }
}

private fun JdbcTransaction.deleteRuntimeConfigValues(versionId: UUID) {
    jdbcConnection().prepareStatement(DELETE_RUNTIME_CONFIG_VALUES_BY_VERSION_ID_SQL).use { statement ->
        statement.setObject(1, versionId)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.deleteRuntimeConfigVersion(versionId: UUID) {
    jdbcConnection().prepareStatement(DELETE_RUNTIME_CONFIG_VERSION_BY_ID_SQL).use { statement ->
        statement.setObject(1, versionId)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.insertRuntimeConfigVersion(
    versionId: UUID,
    now: Instant,
    note: String,
) {
    jdbcConnection().prepareStatement(INSERT_RUNTIME_CONFIG_VERSION_SQL).use { statement ->
        statement.setObject(1, versionId)
        statement.setString(2, RUNTIME_CONFIG_STATUS_ACTIVE)
        statement.setLong(3, now.toEpochMilli())
        statement.setLong(4, now.toEpochMilli())
        statement.setString(5, RUNTIME_CONFIG_BOOTSTRAP_CREATED_BY)
        statement.setString(6, note)

        val updatedCount = statement.executeUpdate()

        require(updatedCount == 1) {
            "Expected to insert one active runtime config version, but inserted $updatedCount."
        }
    }
}

private fun JdbcTransaction.deactivateRuntimeConfigVersion(versionId: UUID) {
    jdbcConnection().prepareStatement(DEACTIVATE_RUNTIME_CONFIG_VERSION_SQL).use { statement ->
        statement.setString(1, RUNTIME_CONFIG_STATUS_INACTIVE)
        statement.setObject(2, versionId)
        statement.setString(3, RUNTIME_CONFIG_STATUS_ACTIVE)

        val updatedCount = statement.executeUpdate()

        require(updatedCount == 1) {
            "Expected to deactivate one active runtime config version, but updated $updatedCount."
        }
    }
}

private fun JdbcTransaction.insertRuntimeConfigVersion(insert: RuntimeConfigVersionInsert) {
    jdbcConnection().prepareStatement(INSERT_RUNTIME_CONFIG_VERSION_SQL).use { statement ->
        statement.setObject(1, insert.versionId)
        statement.setString(2, insert.status)
        statement.setLong(3, insert.createdAt.toEpochMilli())
        if (insert.activatedAt == null) {
            statement.setNull(4, java.sql.Types.BIGINT)
        } else {
            statement.setLong(4, insert.activatedAt.toEpochMilli())
        }
        statement.setString(5, insert.createdBy)
        statement.setString(6, insert.note)
        statement.executeUpdate()
    }
}

private data class RuntimeConfigVersionInsert(
    val versionId: UUID,
    val status: String,
    val createdAt: Instant,
    val activatedAt: Instant?,
    val createdBy: String,
    val note: String?,
)

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

private fun runtimeConfigCatalogReconciliationNote(missingKeys: Set<String>, retiredKeys: Set<String>): String {
    val changes = buildList {
        if (missingKeys.isNotEmpty()) add("added=${missingKeys.sorted().joinToString(",")}")
        if (retiredKeys.isNotEmpty()) add("removed=${retiredKeys.sorted().joinToString(",")}")
    }

    return "$RUNTIME_CONFIG_CATALOG_RECONCILIATION_NOTE_PREFIX: ${changes.joinToString(";")}"
}

private fun JdbcTransaction.deactivateActiveRuntimeConfigVersion() {
    jdbcConnection().prepareStatement(DEACTIVATE_ACTIVE_RUNTIME_CONFIG_VERSION_SQL).use { statement ->
        statement.setString(1, RUNTIME_CONFIG_STATUS_INACTIVE)
        statement.setString(2, RUNTIME_CONFIG_STATUS_ACTIVE)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.activateRuntimeConfigVersion(versionId: UUID, now: Instant) {
    jdbcConnection().prepareStatement(ACTIVATE_RUNTIME_CONFIG_VERSION_SQL).use { statement ->
        statement.setString(1, RUNTIME_CONFIG_STATUS_ACTIVE)
        statement.setLong(2, now.toEpochMilli())
        statement.setObject(3, versionId)
        statement.executeUpdate()
    }
}

private fun validateDraftPatchValues(values: Map<String, String>): Map<String, String> {
    val runtimeItems = RuntimeConfigCatalog.runtimeItems().associateBy { item -> item.key }

    values.keys.forEach { key ->
        val item = runtimeItems[key]

        require(item != null) {
            "runtime config key is unknown: $key"
        }
        require(item.editable) {
            "runtime config key is read-only: $key"
        }
    }

    return values.mapValues { (key, value) ->
        canonicalizeRuntimeConfigValue(
            item = requireNotNull(runtimeItems[key]),
            value = value.trim(),
        )
    }
}

private fun ResultSet.toRuntimeConfigVersionRow(): RuntimeConfigVersionRow {
    val activatedAtMillis = getLong("activated_at")
    val activatedAt = if (wasNull()) null else Instant.ofEpochMilli(activatedAtMillis)

    return RuntimeConfigVersionRow(
        versionId = getObject("id", UUID::class.java),
        status = getString("status"),
        createdAt = Instant.ofEpochMilli(getLong("created_at")),
        activatedAt = activatedAt,
        createdBy = getString("created_by"),
        note = getString("note"),
    )
}

private data class RuntimeConfigVersionRow(
    val versionId: UUID,
    val status: String,
    val createdAt: Instant,
    val activatedAt: Instant?,
    val createdBy: String,
    val note: String?,
) {
    fun toSummary(values: Map<String, String>): RuntimeConfigVersionSummary {
        return RuntimeConfigVersionSummary(
            id = versionId.toString(),
            status = status,
            createdAt = createdAt.toString(),
            activatedAt = activatedAt?.toString(),
            createdBy = createdBy,
            note = note,
            hash = calculateRuntimeConfigHash(values),
        )
    }
}

private data class RuntimeConfigCatalogKeyDiff(
    val unknownKeys: Set<String>,
    val missingKeys: Set<String>,
)

/**
 * active runtime config の catalog reconciliation 対象。
 *
 * @param missingKeys catalog default から追加する key
 * @param retiredKeys active snapshot から除去する明示的な退役 key
 */
private data class RuntimeConfigCatalogReconciliation(
    val missingKeys: Set<String>,
    val retiredKeys: Set<String>,
)
