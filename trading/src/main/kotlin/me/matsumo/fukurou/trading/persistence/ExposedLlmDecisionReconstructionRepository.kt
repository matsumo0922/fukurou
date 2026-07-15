@file:Suppress("CyclomaticComplexMethod", "LongMethod", "TooManyFunctions")

package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.audit.LLM_AUDIT_PRUNE_BATCH_SIZE
import me.matsumo.fukurou.trading.audit.LlmAuditPruneBatchResult
import me.matsumo.fukurou.trading.audit.LlmDecisionEvidenceCoverageSummary
import me.matsumo.fukurou.trading.audit.LlmDecisionReconstruction
import me.matsumo.fukurou.trading.audit.LlmDecisionReconstructionClassification
import me.matsumo.fukurou.trading.audit.LlmDecisionReconstructionReason
import me.matsumo.fukurou.trading.audit.LlmDecisionReconstructionRepository
import me.matsumo.fukurou.trading.audit.LlmManifestJsonCodec
import me.matsumo.fukurou.trading.audit.MAX_RECONSTRUCTION_EVIDENCE_COUNT
import me.matsumo.fukurou.trading.audit.MAX_RECONSTRUCTION_EVIDENCE_PER_PHASE
import me.matsumo.fukurou.trading.audit.MAX_RECONSTRUCTION_PHASE_COUNT
import me.matsumo.fukurou.trading.audit.ManifestPersistencePolicy
import me.matsumo.fukurou.trading.audit.terminalEvidenceSourceTimestamp
import me.matsumo.fukurou.trading.audit.toTerminalEvidenceCanonicalString
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** PostgreSQLのinactive decision reconstruction / coverage / prune repository。 */
class ExposedLlmDecisionReconstructionRepository(
    private val database: Database,
) : LlmDecisionReconstructionRepository {
    override suspend fun findDecision(decisionId: UUID): Result<LlmDecisionReconstruction?> = readOnly {
        val decision = selectDecision(decisionId) ?: return@readOnly null

        if (decision.runStatus == RUNNING_STATUS) {
            return@readOnly decision.result(
                classification = LlmDecisionReconstructionClassification.PENDING,
                reason = LlmDecisionReconstructionReason.RUN_IN_PROGRESS,
            )
        }
        if (!decision.hasTerminalRun()) {
            return@readOnly decision.result(
                classification = LlmDecisionReconstructionClassification.INCOMPLETE,
                reason = LlmDecisionReconstructionReason.RUN_LIFECYCLE_INCOMPLETE,
            )
        }
        if (decision.activationBoundary == null || decision.createdAt < decision.activationBoundary) {
            return@readOnly decision.result(
                classification = LlmDecisionReconstructionClassification.LEGACY_PRE_EVIDENCE,
                reason = LlmDecisionReconstructionReason.PRE_EVIDENCE,
            )
        }
        val invocationId = decision.invocationId ?: return@readOnly decision.result(
            classification = LlmDecisionReconstructionClassification.INCOMPLETE,
            reason = LlmDecisionReconstructionReason.GRAPH_MISSING,
        )
        val core = selectCore(invocationId)
        val phases = selectPhases(invocationId)
        if (phases.size > MAX_RECONSTRUCTION_PHASE_COUNT) {
            return@readOnly decision.result(
                classification = LlmDecisionReconstructionClassification.INCOMPLETE,
                reason = LlmDecisionReconstructionReason.BOUND_EXCEEDED,
            )
        }
        val associations = selectAssociations(decisionId, invocationId)
        val evidenceRows = associations.filter { association -> association.evidenceId != null }
        val evidenceCount = evidenceRows.mapNotNull(AssociationRow::evidenceId).distinct().size
        val phaseCount = phases.size
        val phaseEvidenceBoundExceeded = evidenceRows
            .groupingBy(AssociationRow::phaseManifestId)
            .eachCount()
            .values
            .any { count -> count > MAX_RECONSTRUCTION_EVIDENCE_PER_PHASE }
        val reconstructionBoundExceeded = associations.size > MAX_RECONSTRUCTION_EVIDENCE_COUNT ||
            evidenceCount > MAX_RECONSTRUCTION_EVIDENCE_COUNT ||
            phaseEvidenceBoundExceeded
        if (reconstructionBoundExceeded) {
            return@readOnly decision.result(
                classification = LlmDecisionReconstructionClassification.INCOMPLETE,
                reason = LlmDecisionReconstructionReason.BOUND_EXCEEDED,
                phaseCount = phaseCount,
                evidenceCount = evidenceCount,
            )
        }
        val structuralReason = validateStructure(invocationId, core, phases, associations)
        if (structuralReason != null) {
            return@readOnly decision.result(
                classification = LlmDecisionReconstructionClassification.INCOMPLETE,
                reason = structuralReason,
                phaseCount = phaseCount,
                evidenceCount = evidenceCount,
            )
        }
        val hashReason = validateHashes(invocationId, requireNotNull(core), phases, associations)
        if (hashReason != null) {
            return@readOnly decision.result(
                classification = LlmDecisionReconstructionClassification.HASH_MISMATCH,
                reason = hashReason,
                phaseCount = phaseCount,
                evidenceCount = evidenceCount,
            )
        }

        decision.result(
            classification = LlmDecisionReconstructionClassification.COMPLETE,
            reason = null,
            phaseCount = phaseCount,
            evidenceCount = evidenceCount,
        )
    }

    override suspend fun summarizeCoverage(
        from: Instant,
        toExclusive: Instant,
    ): Result<LlmDecisionEvidenceCoverageSummary> {
        require(from < toExclusive) { "Coverage range must be non-empty." }

        return readOnly {
            executeUpdate(COVERAGE_JIT_SQL)
            jdbcConnection().prepareStatement(COVERAGE_SQL).use { statement ->
                statement.setLong(1, from.toEpochMilli())
                statement.setLong(2, toExclusive.toEpochMilli())
                statement.setLong(3, from.toEpochMilli())
                statement.setLong(4, toExclusive.toEpochMilli())
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "Coverage aggregate did not return a row." }
                    LlmDecisionEvidenceCoverageSummary(
                        from = from,
                        toExclusive = toExclusive,
                        decisionCount = rows.getLong("decision_count"),
                        terminalDecisionCount = rows.getLong("terminal_decision_count"),
                        structurallyCompleteDecisionCount = rows.getLong("structurally_complete_count"),
                        structurallyIncompleteDecisionCount = rows.getLong("structurally_incomplete_count"),
                        pendingDecisionCount = rows.getLong("pending_count"),
                        incompleteRunDecisionCount = rows.getLong("incomplete_run_count"),
                        legacyTerminalDecisionCount = rows.getLong("legacy_terminal_count"),
                        terminalNoDecisionRunCount = rows.getLong("terminal_no_decision_count"),
                    )
                }
            }
        }
    }

    override suspend fun pruneExpiredAuditRoots(now: Instant): Result<LlmAuditPruneBatchResult> = write {
        val cutoff = now.minus(RETENTION_DURATION)
        val rootIds = selectPruneCandidates(cutoff)
        if (rootIds.isEmpty()) return@write LlmAuditPruneBatchResult(0, false)

        deleteByRoots(DELETE_LINKS_SQL, rootIds)
        deleteByRoots(DELETE_COVERAGE_SQL, rootIds)
        deleteByRoots(DELETE_EVIDENCE_SQL, rootIds)
        deleteByRoots(DELETE_OBSERVATIONS_SQL, rootIds)
        deleteByRoots(DELETE_PHASES_SQL, rootIds)
        deleteByRoots(DELETE_RUN_MANIFESTS_SQL, rootIds)
        deleteByRoots(DELETE_MATERIAL_MANIFESTS_SQL, rootIds)
        deleteByRoots(DELETE_ROOTS_SQL, rootIds)

        LlmAuditPruneBatchResult(
            deletedRootCount = rootIds.size,
            hasMore = rootIds.size == LLM_AUDIT_PRUNE_BATCH_SIZE,
        )
    }

    private suspend fun <T> readOnly(block: JdbcTransaction.() -> T): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            transaction(
                transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
                readOnly = true,
                db = database,
            ) {
                executeUpdate(READ_TIMEOUT_SQL)
                block()
            }
        }
    }

    private suspend fun <T> write(block: JdbcTransaction.() -> T): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            transaction(database) {
                executeUpdate(PRUNE_LOCK_TIMEOUT_SQL)
                executeUpdate(READ_TIMEOUT_SQL)
                block()
            }
        }
    }
}

private fun JdbcTransaction.selectDecision(decisionId: UUID): DecisionRow? =
    jdbcConnection().prepareStatement(SELECT_DECISION_SQL).use { statement ->
        statement.setObject(1, decisionId)
        statement.executeQuery().use { rows ->
            if (!rows.next()) return@use null
            DecisionRow(
                decisionId = decisionId,
                invocationId = rows.getString("invocation_id"),
                createdAt = Instant.ofEpochMilli(rows.getLong("created_at")),
                runStatus = rows.getString("run_status"),
                runFinishedAt = rows.nullableLong("finished_at")?.let(Instant::ofEpochMilli),
                runTerminalCause = rows.getString("terminal_cause"),
                activationBoundary = rows.nullableLong("activated_at")?.let(Instant::ofEpochMilli),
            )
        }
    }

private fun JdbcTransaction.selectCore(invocationId: String): CoreRow =
    jdbcConnection().prepareStatement(SELECT_CORE_SQL).use { statement ->
        statement.setString(1, invocationId)
        statement.executeQuery().use { rows ->
            check(rows.next()) { "Core query did not return a row." }
            CoreRow(
                rootId = rows.getString("root_id"),
                rootKind = rows.getString("root_kind"),
                runRootId = rows.getString("run_root_id"),
                runMaterialInvocationId = rows.getString("run_material_invocation_id"),
                runMaterialContentHash = rows.getString("run_material_content_hash"),
                runContentHash = rows.getString("run_content_hash"),
                runManifestJson = rows.getString("run_manifest_json"),
                materialContentHash = rows.getString("material_content_hash"),
                materialManifestJson = rows.getString("material_manifest_json"),
            )
        }
    }

private fun JdbcTransaction.selectPhases(invocationId: String): List<PhaseRow> =
    jdbcConnection().prepareStatement(SELECT_PHASES_SQL).use { statement ->
        statement.setString(1, invocationId)
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        PhaseRow(
                            phaseManifestId = rows.getString("phase_manifest_id"),
                            rootId = rows.getString("root_id"),
                            invocationId = rows.getString("invocation_id"),
                            runManifestInvocationId = rows.getString("run_manifest_invocation_id"),
                            runManifestContentHash = rows.getString("run_manifest_content_hash"),
                            materialInvocationId = rows.getString("material_invocation_id"),
                            materialContentHash = rows.getString("material_content_hash"),
                            phase = rows.getString("phase"),
                            effectiveInvocationHash = rows.getString("effective_invocation_hash"),
                            manifestJson = rows.getString("manifest_json"),
                            observationContentHash = rows.getString("observation_content_hash"),
                            observationJson = rows.getString("observation_json"),
                        ),
                    )
                }
            }
        }
    }

private fun JdbcTransaction.selectAssociations(decisionId: UUID, invocationId: String): List<AssociationRow> =
    jdbcConnection().prepareStatement(SELECT_ASSOCIATIONS_SQL).use { statement ->
        statement.setObject(1, decisionId)
        statement.setObject(2, decisionId)
        statement.setObject(3, decisionId)
        statement.setObject(4, decisionId)
        statement.setString(5, invocationId)
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        AssociationRow(
                            phaseManifestId = rows.getString("phase_manifest_id"),
                            phase = rows.getString("phase"),
                            evidenceId = rows.getObject("evidence_id", UUID::class.java),
                            evidencePhaseManifestId = rows.getString("evidence_phase_manifest_id"),
                            evidenceOrdinal = rows.nullableInt("evidence_ordinal"),
                            responseJson = rows.getString("response_json"),
                            responseHash = rows.getString("response_hash"),
                            sourceTimestamp = rows.nullableLong("source_timestamp")?.let(Instant::ofEpochMilli),
                            sourceTimestampStatus = rows.getString("source_timestamp_status"),
                            evidenceState = rows.getString("evidence_state"),
                            linkOrdinal = rows.nullableInt("link_ordinal"),
                            linkTargetValid = rows.getBoolean("link_target_valid"),
                            coverageStatus = rows.getString("coverage_status"),
                            coverageIncompleteReason = rows.getString("coverage_incomplete_reason"),
                            coverageEntityKind = rows.getString("coverage_entity_kind"),
                            coverageEntityId = rows.getObject("coverage_entity_id", UUID::class.java),
                            coverageTargetValid = rows.getBoolean("coverage_target_valid"),
                        ),
                    )
                }
            }
        }
    }

private fun validateStructure(
    invocationId: String,
    core: CoreRow?,
    phases: List<PhaseRow>,
    associations: List<AssociationRow>,
): LlmDecisionReconstructionReason? {
    if (core == null) return LlmDecisionReconstructionReason.GRAPH_MISSING
    val corePayloadIsMissing = listOf(core.rootId, core.runManifestJson, core.materialManifestJson).any { it == null }
    if (corePayloadIsMissing) return LlmDecisionReconstructionReason.GRAPH_MISSING
    val coreBindingIsValid = core.rootId == invocationId &&
        core.rootKind == DECISION_ROOT_KIND &&
        core.runRootId == invocationId &&
        core.runMaterialInvocationId == invocationId
    if (!coreBindingIsValid) return LlmDecisionReconstructionReason.ASSOCIATION_MISMATCH
    if (phases.isEmpty()) return LlmDecisionReconstructionReason.GRAPH_MISSING
    if (phases.map(PhaseRow::phaseManifestId).toSet().size != phases.size) {
        return LlmDecisionReconstructionReason.ASSOCIATION_MISMATCH
    }
    val phaseBindingIsValid = phases.all { phase ->
        phase.rootId == invocationId &&
            phase.invocationId == invocationId &&
            phase.runManifestInvocationId == invocationId &&
            phase.materialInvocationId == invocationId &&
            phase.observationJson != null
    }
    if (!phaseBindingIsValid) return LlmDecisionReconstructionReason.GRAPH_MISSING

    val associationsByPhase = associations.groupBy(AssociationRow::phaseManifestId)
    if (associationsByPhase.keys != phases.map(PhaseRow::phaseManifestId).toSet()) {
        return LlmDecisionReconstructionReason.ASSOCIATION_MISMATCH
    }
    phases.forEach { phase ->
        val rows = associationsByPhase.getValue(phase.phaseManifestId)
        val coverageIsValid = rows.all { row ->
            row.coverageTargetValid &&
                row.coverageStatus == CAPTURED_STATUS &&
                row.coverageIncompleteReason == null
        }
        val coverageTargets = rows.map { row -> row.coverageEntityKind to row.coverageEntityId }.toSet()
        if (!coverageIsValid || coverageTargets.size != 1) {
            return LlmDecisionReconstructionReason.ASSOCIATION_MISMATCH
        }
        val evidenceRows = rows.filter { row -> row.evidenceId != null }
        if (evidenceRows.mapNotNull(AssociationRow::evidenceId).toSet().size != evidenceRows.size) {
            return LlmDecisionReconstructionReason.ASSOCIATION_MISMATCH
        }
        val expectedOrdinals = evidenceRows.indices.toList()
        val evidenceOrdinals = evidenceRows.mapNotNull(AssociationRow::evidenceOrdinal).sorted()
        val evidenceBindingsAreValid = evidenceRows.all { row ->
            row.evidencePhaseManifestId == phase.phaseManifestId &&
                row.evidenceOrdinal == row.linkOrdinal &&
                row.linkTargetValid &&
                row.evidenceState == CAPTURED_STATUS
        }
        if (evidenceOrdinals != expectedOrdinals || !evidenceBindingsAreValid) {
            return LlmDecisionReconstructionReason.ASSOCIATION_MISMATCH
        }
    }

    return null
}

private fun validateHashes(
    invocationId: String,
    core: CoreRow,
    phases: List<PhaseRow>,
    associations: List<AssociationRow>,
): LlmDecisionReconstructionReason? {
    val material = runCatching { requireNotNull(core.materialManifestJson).toMaterialManifest() }
        .getOrElse { return LlmDecisionReconstructionReason.MALFORMED_CANONICAL_VALUE }
    val run = runCatching { LlmManifestJsonCodec.decodeRun(requireNotNull(core.runManifestJson)) }
        .getOrElse { return LlmDecisionReconstructionReason.MALFORMED_CANONICAL_VALUE }
    val contentMatches = core.materialContentHash == material.persistedSnapshotHash() &&
        core.runContentHash == LlmManifestJsonCodec.contentHash(run)
    if (!contentMatches) return LlmDecisionReconstructionReason.CONTENT_HASH_MISMATCH
    val coreReferencesMatch = material.invocationId == invocationId &&
        run.invocationId == invocationId &&
        run.rootId == invocationId &&
        run.materialInvocationId == invocationId &&
        run.materialContentHash == core.materialContentHash &&
        core.runMaterialContentHash == core.materialContentHash
    if (!coreReferencesMatch) return LlmDecisionReconstructionReason.REFERENCE_HASH_MISMATCH

    phases.forEach { row ->
        val phase = runCatching { LlmManifestJsonCodec.decodePhase(requireNotNull(row.manifestJson)) }
            .getOrElse { return LlmDecisionReconstructionReason.MALFORMED_CANONICAL_VALUE }
        val observation = runCatching { LlmManifestJsonCodec.decodeObservation(requireNotNull(row.observationJson)) }
            .getOrElse { return LlmDecisionReconstructionReason.MALFORMED_CANONICAL_VALUE }
        if (row.effectiveInvocationHash != LlmManifestJsonCodec.effectiveInvocationHash(phase) ||
            row.observationContentHash != ManifestPersistencePolicy.sha256(requireNotNull(row.observationJson))
        ) {
            return LlmDecisionReconstructionReason.CONTENT_HASH_MISMATCH
        }
        val phaseReferencesMatch = phase.phaseManifestId == row.phaseManifestId &&
            phase.invocationId == invocationId &&
            phase.rootId == invocationId &&
            phase.runManifestInvocationId == invocationId &&
            phase.runManifestContentHash == core.runContentHash &&
            row.runManifestContentHash == core.runContentHash &&
            phase.materialInvocationId == invocationId &&
            phase.materialContentHash == core.materialContentHash &&
            row.materialContentHash == core.materialContentHash &&
            observation.phaseManifestId == row.phaseManifestId
        if (!phaseReferencesMatch) return LlmDecisionReconstructionReason.REFERENCE_HASH_MISMATCH
    }

    associations.filter { row -> row.evidenceId != null }.forEach { row ->
        val response = runCatching { Json.parseToJsonElement(requireNotNull(row.responseJson)) }
            .getOrElse { return LlmDecisionReconstructionReason.MALFORMED_CANONICAL_VALUE }
        val canonical = response.toTerminalEvidenceCanonicalString()
        if (canonical != row.responseJson || ManifestPersistencePolicy.sha256(canonical) != row.responseHash) {
            return LlmDecisionReconstructionReason.CONTENT_HASH_MISMATCH
        }
        val sourceTimestamp = response.terminalEvidenceSourceTimestamp()
        if (sourceTimestamp.value != row.sourceTimestamp || sourceTimestamp.status.name != row.sourceTimestampStatus) {
            return LlmDecisionReconstructionReason.CONTENT_HASH_MISMATCH
        }
    }

    return null
}

private fun JdbcTransaction.selectPruneCandidates(cutoff: Instant): List<String> =
    jdbcConnection().prepareStatement(SELECT_PRUNE_CANDIDATES_SQL).use { statement ->
        statement.setLong(1, cutoff.toEpochMilli())
        statement.setInt(2, LLM_AUDIT_PRUNE_BATCH_SIZE)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }

private fun JdbcTransaction.deleteByRoots(sql: String, rootIds: List<String>) {
    jdbcConnection().prepareStatement(sql).use { statement ->
        statement.setArray(1, jdbcConnection().createArrayOf("varchar", rootIds.toTypedArray()))
        statement.executeUpdate()
    }
}

private fun ResultSet.nullableLong(name: String): Long? = getObject(name)?.let { getLong(name) }

private fun ResultSet.nullableInt(name: String): Int? = getObject(name)?.let { getInt(name) }

private fun DecisionRow.hasTerminalRun(): Boolean =
    runStatus != null && runStatus != RUNNING_STATUS && runFinishedAt != null && runTerminalCause != null

private fun DecisionRow.result(
    classification: LlmDecisionReconstructionClassification,
    reason: LlmDecisionReconstructionReason?,
    phaseCount: Int = 0,
    evidenceCount: Int = 0,
): LlmDecisionReconstruction = LlmDecisionReconstruction(
    decisionId = decisionId,
    invocationId = invocationId,
    classification = classification,
    reason = reason,
    phaseCount = phaseCount,
    evidenceCount = evidenceCount,
)

private data class DecisionRow(
    val decisionId: UUID,
    val invocationId: String?,
    val createdAt: Instant,
    val runStatus: String?,
    val runFinishedAt: Instant?,
    val runTerminalCause: String?,
    val activationBoundary: Instant?,
)

private data class CoreRow(
    val rootId: String?,
    val rootKind: String?,
    val runRootId: String?,
    val runMaterialInvocationId: String?,
    val runMaterialContentHash: String?,
    val runContentHash: String?,
    val runManifestJson: String?,
    val materialContentHash: String?,
    val materialManifestJson: String?,
)

private data class PhaseRow(
    val phaseManifestId: String,
    val rootId: String,
    val invocationId: String,
    val runManifestInvocationId: String?,
    val runManifestContentHash: String?,
    val materialInvocationId: String?,
    val materialContentHash: String?,
    val phase: String,
    val effectiveInvocationHash: String,
    val manifestJson: String,
    val observationContentHash: String?,
    val observationJson: String?,
)

private data class AssociationRow(
    val phaseManifestId: String,
    val phase: String,
    val evidenceId: UUID?,
    val evidencePhaseManifestId: String?,
    val evidenceOrdinal: Int?,
    val responseJson: String?,
    val responseHash: String?,
    val sourceTimestamp: Instant?,
    val sourceTimestampStatus: String?,
    val evidenceState: String?,
    val linkOrdinal: Int?,
    val linkTargetValid: Boolean,
    val coverageStatus: String?,
    val coverageIncompleteReason: String?,
    val coverageEntityKind: String?,
    val coverageEntityId: UUID?,
    val coverageTargetValid: Boolean,
)

private const val SELECT_DECISION_SQL = """
    SELECT d.invocation_id,d.created_at,r.status AS run_status,r.finished_at,r.terminal_cause,b.activated_at
    FROM decisions d
    LEFT JOIN llm_runs r ON r.invocation_id=d.invocation_id
    LEFT JOIN llm_tool_evidence_activation_boundaries b ON b.schema_version=1
    WHERE d.id=?
"""

private const val SELECT_CORE_SQL = """
    SELECT root.root_id,root.root_kind,run.root_id AS run_root_id,
        run.material_invocation_id AS run_material_invocation_id,
        run.material_content_hash AS run_material_content_hash,run.content_hash AS run_content_hash,
        run.manifest_json AS run_manifest_json,material.content_hash AS material_content_hash,
        material.manifest_json AS material_manifest_json
    FROM (VALUES (?::varchar)) requested(invocation_id)
    LEFT JOIN llm_invocation_audit_roots root ON root.root_id=requested.invocation_id
    LEFT JOIN llm_run_input_manifests run ON run.invocation_id=requested.invocation_id
    LEFT JOIN decision_material_state_manifests material ON material.invocation_id=requested.invocation_id
"""

private const val SELECT_PHASES_SQL = """
    SELECT phase.phase_manifest_id,phase.root_id,phase.invocation_id,phase.run_manifest_invocation_id,
        phase.run_manifest_content_hash,phase.material_invocation_id,phase.material_content_hash,phase.phase,
        phase.effective_invocation_hash,phase.manifest_json,
        observation.content_hash AS observation_content_hash,observation.observation_json
    FROM llm_phase_input_manifests phase
    LEFT JOIN llm_phase_observations observation ON observation.phase_manifest_id=phase.phase_manifest_id
    WHERE phase.invocation_id=? AND phase.phase IN ('PROPOSER','FALSIFIER','RISK_REDUCTION_ONLY')
    ORDER BY phase.phase_manifest_id
    LIMIT 4
"""

private const val SELECT_ASSOCIATIONS_SQL = """
    SELECT phase.phase_manifest_id,phase.phase,evidence.id AS evidence_id,
        evidence.phase_manifest_id AS evidence_phase_manifest_id,evidence.ordinal AS evidence_ordinal,
        evidence.response_json,evidence.response_hash,evidence.source_timestamp,evidence.source_timestamp_status,
        evidence.state AS evidence_state,link.ordinal AS link_ordinal,
        CASE WHEN phase.phase='FALSIFIER' THEN
            link.entity_kind='FALSIFICATION' AND EXISTS (
                SELECT 1 FROM falsifications f JOIN trade_intents intent ON intent.id=f.intent_id
                WHERE f.id=link.entity_id AND intent.decision_id=?
            )
        ELSE link.entity_kind='DECISION' AND link.entity_id=? END AS link_target_valid,
        coverage.status AS coverage_status,coverage.incomplete_reason AS coverage_incomplete_reason,
        coverage.entity_kind AS coverage_entity_kind,coverage.entity_id AS coverage_entity_id,
        CASE WHEN phase.phase='FALSIFIER' THEN
            coverage.entity_kind='FALSIFICATION' AND EXISTS (
                SELECT 1 FROM falsifications f JOIN trade_intents intent ON intent.id=f.intent_id
                WHERE f.id=coverage.entity_id AND intent.decision_id=?
            )
        ELSE coverage.entity_kind='DECISION' AND coverage.entity_id=? END AS coverage_target_valid
    FROM llm_phase_input_manifests phase
    LEFT JOIN llm_tool_evidence evidence ON evidence.phase_manifest_id=phase.phase_manifest_id
    LEFT JOIN llm_terminal_evidence_links link ON link.evidence_id=evidence.id
    LEFT JOIN llm_decision_phase_evidence_coverage coverage ON coverage.phase_manifest_id=phase.phase_manifest_id
    WHERE phase.invocation_id=? AND phase.phase IN ('PROPOSER','FALSIFIER','RISK_REDUCTION_ONLY')
    ORDER BY phase.phase_manifest_id,evidence.ordinal,evidence.id,link.ordinal
    LIMIT 145
"""

private const val COVERAGE_SQL = """
    WITH boundary AS (
        SELECT activated_at FROM llm_tool_evidence_activation_boundaries WHERE schema_version=1
    ), decision_population AS (
        SELECT d.id,d.invocation_id,d.created_at,r.status,r.finished_at,r.terminal_cause,b.activated_at,
            COALESCE(r.status='RUNNING',FALSE) AS pending,
            COALESCE(r.status<>'RUNNING' AND r.finished_at IS NOT NULL AND r.terminal_cause IS NOT NULL,FALSE) AS terminal
        FROM decisions d
        LEFT JOIN llm_runs r ON r.invocation_id=d.invocation_id
        LEFT JOIN boundary b ON TRUE
        WHERE d.created_at>=? AND d.created_at<? AND d.invocation_id IS NOT NULL
    ), classified AS (
        SELECT population.*,
            terminal AND (activated_at IS NULL OR created_at<activated_at) AS legacy,
            terminal AND activated_at IS NOT NULL AND created_at>=activated_at AS post_boundary,
            terminal AND activated_at IS NOT NULL AND created_at>=activated_at AND
                EXISTS (SELECT 1 FROM llm_invocation_audit_roots root
                    WHERE root.root_id=population.invocation_id AND root.root_kind='DECISION_ATTEMPT') AND
                EXISTS (SELECT 1 FROM llm_run_input_manifests run
                    WHERE run.invocation_id=population.invocation_id AND run.root_id=population.invocation_id
                        AND run.material_invocation_id=population.invocation_id) AND
                EXISTS (SELECT 1 FROM decision_material_state_manifests material
                    WHERE material.invocation_id=population.invocation_id) AND
                (SELECT COUNT(*) FROM llm_phase_input_manifests phase
                    WHERE phase.invocation_id=population.invocation_id
                        AND phase.phase IN ('PROPOSER','FALSIFIER','RISK_REDUCTION_ONLY')) BETWEEN 1 AND 3 AND
                NOT EXISTS (
                    SELECT 1 FROM llm_phase_input_manifests phase
                    WHERE phase.invocation_id=population.invocation_id
                        AND phase.phase IN ('PROPOSER','FALSIFIER','RISK_REDUCTION_ONLY')
                        AND (
                            NOT EXISTS (SELECT 1 FROM llm_phase_observations observation
                                WHERE observation.phase_manifest_id=phase.phase_manifest_id) OR
                            NOT EXISTS (
                                SELECT 1 FROM llm_decision_phase_evidence_coverage coverage
                                WHERE coverage.phase_manifest_id=phase.phase_manifest_id
                                    AND coverage.status='TERMINAL_BUNDLE_CAPTURED'
                                    AND coverage.incomplete_reason IS NULL
                                    AND ((phase.phase<>'FALSIFIER' AND coverage.entity_kind='DECISION'
                                            AND coverage.entity_id=population.id)
                                        OR (phase.phase='FALSIFIER' AND coverage.entity_kind='FALSIFICATION'
                                            AND EXISTS (SELECT 1 FROM falsifications f
                                                JOIN trade_intents intent ON intent.id=f.intent_id
                                                WHERE f.id=coverage.entity_id AND intent.decision_id=population.id)))
                            ) OR
                            (SELECT COUNT(*) FROM llm_decision_phase_evidence_coverage coverage
                                WHERE coverage.phase_manifest_id=phase.phase_manifest_id)<>1 OR
                            (SELECT COUNT(*) FROM llm_tool_evidence evidence
                                WHERE evidence.phase_manifest_id=phase.phase_manifest_id)>48 OR
                            EXISTS (
                                SELECT 1 FROM llm_tool_evidence evidence
                                WHERE evidence.phase_manifest_id=phase.phase_manifest_id AND (
                                    evidence.state<>'TERMINAL_BUNDLE_CAPTURED' OR
                                    (SELECT COUNT(*) FROM llm_terminal_evidence_links link
                                        WHERE link.evidence_id=evidence.id)<>1 OR
                                    NOT EXISTS (
                                        SELECT 1 FROM llm_terminal_evidence_links link
                                        WHERE link.evidence_id=evidence.id AND link.ordinal=evidence.ordinal
                                            AND ((phase.phase<>'FALSIFIER' AND link.entity_kind='DECISION'
                                                    AND link.entity_id=population.id)
                                                OR (phase.phase='FALSIFIER' AND link.entity_kind='FALSIFICATION'
                                                    AND EXISTS (SELECT 1 FROM falsifications f
                                                        JOIN trade_intents intent ON intent.id=f.intent_id
                                                        WHERE f.id=link.entity_id AND intent.decision_id=population.id)))
                                    )
                                )
                            )
                        )
                ) AS structurally_complete
        FROM decision_population population
    ), no_decision AS (
        SELECT COUNT(*) AS count
        FROM llm_runs run
        WHERE run.started_at>=? AND run.started_at<?
            AND run.status<>'RUNNING' AND run.finished_at IS NOT NULL AND run.terminal_cause IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM decisions decision WHERE decision.invocation_id=run.invocation_id)
    )
    SELECT COUNT(*) AS decision_count,
        COUNT(*) FILTER (WHERE terminal) AS terminal_decision_count,
        COUNT(*) FILTER (WHERE post_boundary AND structurally_complete) AS structurally_complete_count,
        COUNT(*) FILTER (WHERE post_boundary AND NOT structurally_complete) AS structurally_incomplete_count,
        COUNT(*) FILTER (WHERE pending) AS pending_count,
        COUNT(*) FILTER (WHERE NOT pending AND NOT terminal) AS incomplete_run_count,
        COUNT(*) FILTER (WHERE legacy) AS legacy_terminal_count,
        (SELECT count FROM no_decision) AS terminal_no_decision_count
    FROM classified
"""

private const val SELECT_PRUNE_CANDIDATES_SQL = """
    SELECT root.root_id
    FROM llm_invocation_audit_roots root
    JOIN llm_tool_evidence_activation_boundaries boundary ON boundary.schema_version=1
    WHERE root.root_kind='DECISION_ATTEMPT'
        AND root.captured_at>=boundary.activated_at
        AND root.captured_at<?
    ORDER BY root.captured_at,root.root_id
    LIMIT ? FOR UPDATE OF root SKIP LOCKED
"""

private const val DELETE_LINKS_SQL = """
    DELETE FROM llm_terminal_evidence_links link USING llm_tool_evidence evidence,llm_phase_input_manifests phase
    WHERE link.evidence_id=evidence.id AND evidence.phase_manifest_id=phase.phase_manifest_id
        AND phase.root_id=ANY(?::varchar[])
"""

private const val DELETE_COVERAGE_SQL = """
    DELETE FROM llm_decision_phase_evidence_coverage coverage USING llm_phase_input_manifests phase
    WHERE coverage.phase_manifest_id=phase.phase_manifest_id AND phase.root_id=ANY(?::varchar[])
"""

private const val DELETE_EVIDENCE_SQL = """
    DELETE FROM llm_tool_evidence evidence USING llm_phase_input_manifests phase
    WHERE evidence.phase_manifest_id=phase.phase_manifest_id AND phase.root_id=ANY(?::varchar[])
"""

private const val DELETE_OBSERVATIONS_SQL = """
    DELETE FROM llm_phase_observations observation USING llm_phase_input_manifests phase
    WHERE observation.phase_manifest_id=phase.phase_manifest_id AND phase.root_id=ANY(?::varchar[])
"""

private const val DELETE_PHASES_SQL =
    "DELETE FROM llm_phase_input_manifests WHERE root_id=ANY(?::varchar[])"

private const val DELETE_RUN_MANIFESTS_SQL =
    "DELETE FROM llm_run_input_manifests WHERE root_id=ANY(?::varchar[])"

private const val DELETE_MATERIAL_MANIFESTS_SQL =
    "DELETE FROM decision_material_state_manifests WHERE invocation_id=ANY(?::varchar[])"

private const val DELETE_ROOTS_SQL =
    "DELETE FROM llm_invocation_audit_roots WHERE root_id=ANY(?::varchar[])"

private const val RUNNING_STATUS = "RUNNING"
private const val DECISION_ROOT_KIND = "DECISION_ATTEMPT"
private const val CAPTURED_STATUS = "TERMINAL_BUNDLE_CAPTURED"
private val RETENTION_DURATION: Duration = Duration.ofDays(30)
private const val READ_TIMEOUT_SQL = "SET LOCAL statement_timeout='2s'"
private const val COVERAGE_JIT_SQL = "SET LOCAL jit=off"
private const val PRUNE_LOCK_TIMEOUT_SQL = "SET LOCAL lock_timeout='1s'"
