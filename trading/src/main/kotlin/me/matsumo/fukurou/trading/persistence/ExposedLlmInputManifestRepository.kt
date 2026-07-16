package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.audit.LlmAuditRootKind
import me.matsumo.fukurou.trading.audit.LlmInputManifestRepository
import me.matsumo.fukurou.trading.audit.LlmInputPersistenceException
import me.matsumo.fukurou.trading.audit.LlmInputPersistenceStage
import me.matsumo.fukurou.trading.audit.LlmInvocationAuditRoot
import me.matsumo.fukurou.trading.audit.LlmManifestJsonCodec
import me.matsumo.fukurou.trading.audit.LlmPhaseInputManifest
import me.matsumo.fukurou.trading.audit.LlmPhaseObservation
import me.matsumo.fukurou.trading.audit.LlmRunInputManifest
import me.matsumo.fukurou.trading.audit.ManifestPersistencePolicy
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateManifest
import me.matsumo.fukurou.trading.runner.SecretRedactor
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.PreparedStatement
import java.time.Instant

/** PostgreSQL append-only LLM input manifest repository。 */
class ExposedLlmInputManifestRepository(
    private val database: Database,
    private val knownSecretValues: Set<String> = SecretRedactor.knownSecretValuesFromEnvironment(System.getenv()),
) : LlmInputManifestRepository {
    override suspend fun appendRoot(root: LlmInvocationAuditRoot): Result<Unit> = write {
        appendImmutable(
            insertSql = INSERT_ROOT_SQL,
            key = root.rootId,
            expectedHash = "${root.kind.name}:${root.capturedAt.toEpochMilli()}",
            selectHashSql = SELECT_ROOT_HASH_SQL,
        ) { statement ->
            statement.setString(1, root.rootId)
            statement.setString(2, root.kind.name)
            statement.setLong(3, root.capturedAt.toEpochMilli())
        }
    }

    override suspend fun appendRunWithMaterial(
        materialManifest: DecisionMaterialStateManifest,
        runManifest: LlmRunInputManifest,
    ): Result<Unit> {
        var stage = LlmInputPersistenceStage.MATERIAL_PERSISTENCE

        val result = write {
            materialManifest.requireValidSnapshotHash()
            ManifestPersistencePolicy.validateMaterial(materialManifest, knownSecretValues)
            stage = LlmInputPersistenceStage.RUN_MANIFEST_PERSISTENCE
            require(runManifest.rootId == runManifest.invocationId) { "run manifest root/invocation mismatch." }
            require(materialManifest.invocationId == runManifest.invocationId) {
                "run manifest material scope mismatch."
            }
            require(runManifest.materialInvocationId == materialManifest.invocationId) {
                "run manifest material reference mismatch."
            }
            require(runManifest.materialContentHash == materialManifest.persistedSnapshotHash()) {
                "run manifest material hash mismatch."
            }
            ManifestPersistencePolicy.validateRun(runManifest, knownSecretValues)
            require(LlmManifestJsonCodec.contentHash(runManifest) == runManifest.canonicalContentHash) {
                "run manifest canonical hash mismatch."
            }
            requireReferenced(SELECT_ROOT_EXISTS_SQL, runManifest.rootId)
            stage = LlmInputPersistenceStage.MATERIAL_PERSISTENCE
            appendMaterial(materialManifest)
            stage = LlmInputPersistenceStage.RUN_MANIFEST_PERSISTENCE
            appendImmutable(
                INSERT_RUN_SQL,
                runManifest.invocationId,
                runManifest.canonicalContentHash,
                SELECT_RUN_HASH_SQL,
            ) { statement ->
                statement.setString(1, runManifest.invocationId)
                statement.setString(2, runManifest.rootId)
                statement.setString(3, runManifest.materialInvocationId)
                statement.setString(4, runManifest.materialContentHash)
                statement.setInt(5, runManifest.schemaVersion)
                statement.setString(6, runManifest.canonicalContentHash)
                statement.setLong(7, runManifest.capturedAt.toEpochMilli())
                statement.setString(8, LlmManifestJsonCodec.encode(runManifest))
            }
        }

        return result.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { throwable ->
                Result.failure(
                    if (throwable is LlmInputPersistenceException) {
                        throwable
                    } else {
                        LlmInputPersistenceException(stage, throwable)
                    },
                )
            },
        )
    }

    override suspend fun appendPhase(manifest: LlmPhaseInputManifest): Result<Unit> = write {
        ManifestPersistencePolicy.validatePhase(manifest, knownSecretValues)
        require(manifest.rootId == manifest.invocationId) { "phase manifest root/invocation mismatch." }
        requireReferenced(SELECT_ROOT_EXISTS_SQL, manifest.rootId)
        validatePhaseReferences(manifest)
        val json = LlmManifestJsonCodec.encode(manifest)
        require(LlmManifestJsonCodec.effectiveInvocationHash(manifest) == manifest.effectiveInvocationHash) {
            "phase manifest effective invocation hash mismatch."
        }
        appendImmutable(
            INSERT_PHASE_SQL,
            manifest.phaseManifestId,
            manifest.effectiveInvocationHash,
            SELECT_PHASE_HASH_SQL,
        ) { statement ->
            statement.setString(1, manifest.phaseManifestId)
            statement.setString(2, manifest.rootId)
            statement.setString(3, manifest.invocationId)
            statement.setString(4, manifest.runManifestInvocationId)
            statement.setString(5, manifest.runManifestContentHash)
            statement.setString(6, manifest.materialInvocationId)
            statement.setString(7, manifest.materialContentHash)
            statement.setString(8, manifest.phase.name)
            statement.setString(9, manifest.effectiveInvocationHash)
            statement.setLong(10, manifest.capturedAt.toEpochMilli())
            statement.setString(11, json)
        }
    }

    override suspend fun appendObservation(observation: LlmPhaseObservation): Result<Unit> = write {
        ManifestPersistencePolicy.validateObservation(observation, knownSecretValues)
        val json = LlmManifestJsonCodec.encode(observation)
        val contentHash = ManifestPersistencePolicy.sha256(json)
        appendImmutable(
            INSERT_OBSERVATION_SQL,
            observation.phaseManifestId,
            contentHash,
            SELECT_OBSERVATION_HASH_SQL,
        ) { statement ->
            statement.setString(1, observation.phaseManifestId)
            statement.setString(2, observation.modelCoverageStatus.name)
            statement.setString(3, observation.effortCoverageStatus.name)
            statement.setLong(4, observation.terminatedAt.toEpochMilli())
            statement.setString(5, contentHash)
            statement.setString(6, json)
        }
    }

    override suspend fun findRoot(rootId: String): Result<LlmInvocationAuditRoot?> = read {
        jdbcConnection().prepareStatement(SELECT_ROOT_SQL).use { statement ->
            statement.setString(1, rootId)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                LlmInvocationAuditRoot(
                    rootId = rootId,
                    kind = LlmAuditRootKind.valueOf(result.getString(1)),
                    capturedAt = Instant.ofEpochMilli(result.getLong(2)),
                )
            }
        }
    }

    override suspend fun findRun(invocationId: String): Result<LlmRunInputManifest?> = readJson(
        SELECT_RUN_JSON_SQL,
        invocationId,
        LlmManifestJsonCodec::decodeRun,
    )

    override suspend fun findPhase(phaseManifestId: String): Result<LlmPhaseInputManifest?> = readJson(
        SELECT_PHASE_JSON_SQL,
        phaseManifestId,
        LlmManifestJsonCodec::decodePhase,
    )

    override suspend fun findObservation(phaseManifestId: String): Result<LlmPhaseObservation?> = readJson(
        SELECT_OBSERVATION_JSON_SQL,
        phaseManifestId,
        LlmManifestJsonCodec::decodeObservation,
    )

    private suspend fun <T> readJson(
        sql: String,
        key: String,
        decode: (String) -> T,
    ): Result<T?> = read {
        jdbcConnection().prepareStatement(sql).use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { result -> if (result.next()) decode(result.getString(1)) else null }
        }
    }

    private suspend fun write(block: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) { runCatching { transaction(database) { block() } } }

    private suspend fun <T> read(block: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> T): Result<T> =
        withContext(Dispatchers.IO) { runCatching { transaction(database) { block() } } }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.appendImmutable(
        insertSql: String,
        key: String,
        expectedHash: String,
        selectHashSql: String,
        bind: (PreparedStatement) -> Unit,
    ) {
        val inserted = jdbcConnection().prepareStatement(insertSql).use { statement ->
            bind(statement)
            statement.executeUpdate()
        }
        if (inserted == 1) return

        val actualHash = jdbcConnection().prepareStatement(selectHashSql).use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { result ->
                require(result.next()) { "immutable manifest conflict row disappeared." }
                result.getString(1)
            }
        }
        require(actualHash == expectedHash) { "immutable manifest content mismatch." }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.requireReferencedHash(
        selectSql: String,
        key: String,
        expectedHash: String,
    ) {
        val actualHash = jdbcConnection().prepareStatement(selectSql).use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { result ->
                require(result.next()) { "referenced immutable manifest is missing." }
                result.getString(1)
            }
        }
        require(actualHash == expectedHash) { "referenced immutable manifest hash mismatch." }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.requireReferenced(selectSql: String, key: String) {
        val exists = jdbcConnection().prepareStatement(selectSql).use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { result -> result.next() }
        }
        require(exists) { "referenced immutable manifest is missing." }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.validatePhaseReferences(
        manifest: LlmPhaseInputManifest,
    ) {
        val decisionPhase = manifest.phase in setOf(
            me.matsumo.fukurou.trading.invoker.LlmInvocationPhase.PROPOSER,
            me.matsumo.fukurou.trading.invoker.LlmInvocationPhase.FALSIFIER,
            me.matsumo.fukurou.trading.invoker.LlmInvocationPhase.RISK_REDUCTION_ONLY,
        )
        if (!decisionPhase) {
            require(manifest.runManifestInvocationId == null && manifest.runManifestContentHash == null)
            require(manifest.materialInvocationId == null && manifest.materialContentHash == null)
            require(manifest.notApplicableReason != null)

            return
        }

        require(manifest.notApplicableReason == null)
        require(manifest.runManifestInvocationId == manifest.invocationId)
        require(manifest.materialInvocationId == manifest.invocationId)
        requireRunReference(manifest)
        requireReferencedHash(
            SELECT_MATERIAL_HASH_SQL,
            requireNotNull(manifest.materialInvocationId),
            requireNotNull(manifest.materialContentHash),
        )
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.requireRunReference(manifest: LlmPhaseInputManifest) {
        val runInvocationId = requireNotNull(manifest.runManifestInvocationId)
        jdbcConnection().prepareStatement(SELECT_RUN_REFERENCE_SQL).use { statement ->
            statement.setString(1, runInvocationId)
            statement.executeQuery().use { result ->
                require(result.next()) { "referenced immutable manifest is missing." }
                require(result.getString(1) == manifest.rootId) {
                    "referenced run manifest root mismatch."
                }
                require(result.getString(2) == manifest.materialInvocationId) {
                    "referenced run manifest material scope mismatch."
                }
                require(result.getString(3) == manifest.runManifestContentHash) {
                    "referenced immutable manifest hash mismatch."
                }
            }
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.appendMaterial(manifest: DecisionMaterialStateManifest) {
        val inserted = jdbcConnection().prepareStatement(INSERT_MATERIAL_SQL).use { statement ->
            statement.setString(1, manifest.invocationId)
            statement.setLong(2, manifest.capturedAt.toEpochMilli())
            statement.setInt(3, manifest.schemaVersion)
            statement.setString(4, manifest.persistedSnapshotHash())
            statement.setString(5, manifest.materialProjection)
            statement.setString(6, manifest.toJson())
            statement.executeUpdate()
        }
        if (inserted == 1) return

        requireReferencedHash(
            selectSql = SELECT_MATERIAL_HASH_SQL,
            key = manifest.invocationId,
            expectedHash = manifest.persistedSnapshotHash(),
        )
    }
}

private const val INSERT_ROOT_SQL = "INSERT INTO llm_invocation_audit_roots(root_id,root_kind,captured_at) " +
    "VALUES(?,?,?) ON CONFLICT(root_id) DO NOTHING"
private const val SELECT_ROOT_HASH_SQL = "SELECT root_kind || ':' || captured_at::text " +
    "FROM llm_invocation_audit_roots WHERE root_id=?"
private const val SELECT_ROOT_SQL = "SELECT root_kind,captured_at FROM llm_invocation_audit_roots WHERE root_id=?"
private const val SELECT_ROOT_EXISTS_SQL = "SELECT 1 FROM llm_invocation_audit_roots WHERE root_id=?"
private const val SELECT_MATERIAL_HASH_SQL =
    "SELECT content_hash FROM decision_material_state_manifests WHERE invocation_id=?"
private const val INSERT_MATERIAL_SQL = "INSERT INTO decision_material_state_manifests(" +
    "invocation_id,captured_at,schema_version,content_hash,material_projection,manifest_json" +
    ") VALUES(?,?,?,?,?,?) ON CONFLICT(invocation_id) DO NOTHING"
private const val INSERT_RUN_SQL = "INSERT INTO llm_run_input_manifests(" +
    "invocation_id,root_id,material_invocation_id,material_content_hash,schema_version,content_hash,captured_at,manifest_json" +
    ") VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(invocation_id) DO NOTHING"
private const val SELECT_RUN_HASH_SQL = "SELECT content_hash FROM llm_run_input_manifests WHERE invocation_id=?"
private const val SELECT_RUN_REFERENCE_SQL =
    "SELECT root_id,material_invocation_id,content_hash FROM llm_run_input_manifests WHERE invocation_id=?"
private const val SELECT_RUN_JSON_SQL = "SELECT manifest_json FROM llm_run_input_manifests WHERE invocation_id=?"
private const val INSERT_PHASE_SQL = "INSERT INTO llm_phase_input_manifests(" +
    "phase_manifest_id,root_id,invocation_id,run_manifest_invocation_id,run_manifest_content_hash," +
    "material_invocation_id,material_content_hash,phase,effective_invocation_hash,captured_at,manifest_json" +
    ") VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(phase_manifest_id) DO NOTHING"
private const val SELECT_PHASE_HASH_SQL =
    "SELECT effective_invocation_hash FROM llm_phase_input_manifests WHERE phase_manifest_id=?"
private const val SELECT_PHASE_JSON_SQL = "SELECT manifest_json FROM llm_phase_input_manifests WHERE phase_manifest_id=?"
private const val INSERT_OBSERVATION_SQL = "INSERT INTO llm_phase_observations(" +
    "phase_manifest_id,model_coverage_status,effort_coverage_status,terminated_at,content_hash,observation_json" +
    ") VALUES(?,?,?,?,?,?) ON CONFLICT(phase_manifest_id) DO NOTHING"
private const val SELECT_OBSERVATION_HASH_SQL =
    "SELECT content_hash FROM llm_phase_observations WHERE phase_manifest_id=?"
private const val SELECT_OBSERVATION_JSON_SQL =
    "SELECT observation_json FROM llm_phase_observations WHERE phase_manifest_id=?"
