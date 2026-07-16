package me.matsumo.fukurou.trading.audit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateManifest
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateRepository
import me.matsumo.fukurou.trading.decision.identity.InMemoryDecisionMaterialStateRepository
import me.matsumo.fukurou.trading.persistence.persistedSnapshotHash
import me.matsumo.fukurou.trading.persistence.requireValidSnapshotHash

/** Stage 1 immutable input manifest の append boundary。 */
interface LlmInputManifestRepository {
    suspend fun appendRoot(root: LlmInvocationAuditRoot): Result<Unit>

    suspend fun appendRunWithMaterial(
        materialManifest: DecisionMaterialStateManifest,
        runManifest: LlmRunInputManifest,
    ): Result<Unit>

    suspend fun appendPhase(manifest: LlmPhaseInputManifest): Result<Unit>

    suspend fun appendObservation(observation: LlmPhaseObservation): Result<Unit>

    suspend fun findRoot(rootId: String): Result<LlmInvocationAuditRoot?>

    suspend fun findRun(invocationId: String): Result<LlmRunInputManifest?>

    suspend fun findPhase(phaseManifestId: String): Result<LlmPhaseInputManifest?>

    suspend fun findObservation(phaseManifestId: String): Result<LlmPhaseObservation?>
}

/** standard material snapshot の失敗境界。 */
enum class StandardMaterialSnapshotStage {
    CAPTURE,
    VALIDATION,
    HASH_SERIALIZATION,
    PERSISTENCE,
}

/** message を永続化対象へ持ち込まない standard material snapshot failure。 */
class StandardMaterialSnapshotException(
    val stage: StandardMaterialSnapshotStage,
    cause: Throwable,
) : RuntimeException(null, cause)

/** standard material snapshot の validation/hash 準備結果。 */
internal data class PreparedStandardMaterialSnapshot(
    val materialHash: String,
    val runJson: String,
)

/** repository 実装間で共有する standard material snapshot の validation。 */
internal suspend fun validateStandardMaterialSnapshot(
    materialManifest: DecisionMaterialStateManifest,
    runManifest: LlmRunInputManifest,
    knownSecretValues: Set<String>,
): Result<Unit> = standardMaterialSnapshotResult(StandardMaterialSnapshotStage.VALIDATION) {
    ManifestPersistencePolicy.validateMaterial(materialManifest, knownSecretValues)
    require(runManifest.rootId == runManifest.invocationId) { "run manifest root/invocation mismatch." }
    require(materialManifest.invocationId == runManifest.invocationId) {
        "run manifest material scope mismatch."
    }
    require(runManifest.materialInvocationId == materialManifest.invocationId) {
        "run manifest material reference mismatch."
    }
    ManifestPersistencePolicy.validateRun(runManifest, knownSecretValues)
}

/** repository 実装間で共有する standard material snapshot の hash/serialization 準備。 */
internal suspend fun prepareStandardMaterialSnapshot(
    materialManifest: DecisionMaterialStateManifest,
    runManifest: LlmRunInputManifest,
): Result<PreparedStandardMaterialSnapshot> = standardMaterialSnapshotResult(
    StandardMaterialSnapshotStage.HASH_SERIALIZATION,
) {
    materialManifest.requireValidSnapshotHash()
    val materialHash = materialManifest.persistedSnapshotHash()
    require(runManifest.materialContentHash == materialHash) {
        "run manifest material hash mismatch."
    }
    val runJson = LlmManifestJsonCodec.encode(runManifest)
    require(LlmManifestJsonCodec.contentHash(runManifest) == runManifest.canonicalContentHash) {
        "run manifest canonical hash mismatch."
    }
    PreparedStandardMaterialSnapshot(
        materialHash = materialHash,
        runJson = runJson,
    )
}

internal suspend inline fun <T> standardMaterialSnapshotResult(
    stage: StandardMaterialSnapshotStage,
    crossinline block: suspend () -> T,
): Result<T> {
    return try {
        Result.success(block())
    } catch (throwable: CancellationException) {
        throw throwable
    } catch (throwable: StandardMaterialSnapshotException) {
        Result.failure(throwable)
    } catch (throwable: Throwable) {
        Result.failure(StandardMaterialSnapshotException(stage, throwable))
    }
}

/** DB 未構成 runtime 用 manifest repository。 */
class InMemoryLlmInputManifestRepository(
    private val materialRepository: DecisionMaterialStateRepository = InMemoryDecisionMaterialStateRepository(),
    private val knownSecretValues: Set<String> = emptySet(),
) : LlmInputManifestRepository {
    private val mutex = Mutex()
    private val roots = mutableMapOf<String, LlmInvocationAuditRoot>()
    private val runs = mutableMapOf<String, LlmRunInputManifest>()
    private val phases = mutableMapOf<String, LlmPhaseInputManifest>()
    private val observations = mutableMapOf<String, LlmPhaseObservation>()

    override suspend fun appendRoot(root: LlmInvocationAuditRoot): Result<Unit> =
        appendImmutable(roots, root.rootId, root, "audit root")

    override suspend fun appendRunWithMaterial(
        materialManifest: DecisionMaterialStateManifest,
        runManifest: LlmRunInputManifest,
    ): Result<Unit> {
        validateStandardMaterialSnapshot(materialManifest, runManifest, knownSecretValues)
            .getOrElse { return Result.failure(it) }

        prepareStandardMaterialSnapshot(materialManifest, runManifest)
            .getOrElse { return Result.failure(it) }

        return standardMaterialSnapshotResult(StandardMaterialSnapshotStage.PERSISTENCE) {
            require(findRoot(runManifest.rootId).getOrThrow() != null) { "run manifest audit root is missing." }
            materialRepository.append(materialManifest).getOrThrow()
            appendImmutable(runs, runManifest.invocationId, runManifest, "run manifest").getOrThrow()
        }
    }

    override suspend fun appendPhase(manifest: LlmPhaseInputManifest): Result<Unit> {
        return runCatching {
            ManifestPersistencePolicy.validatePhase(manifest, knownSecretValues)
            require(manifest.rootId == manifest.invocationId) { "phase manifest root/invocation mismatch." }
            require(findRoot(manifest.rootId).getOrThrow() != null) { "phase manifest audit root is missing." }
            val runManifest = manifest.runManifestInvocationId?.let { invocationId ->
                findRun(invocationId).getOrThrow()
            }
            val materialManifest = manifest.materialInvocationId?.let { invocationId ->
                materialRepository.find(invocationId).getOrThrow()
            }
            manifest.requireValidReferences(runManifest, materialManifest)
            require(LlmManifestJsonCodec.effectiveInvocationHash(manifest) == manifest.effectiveInvocationHash) {
                "phase manifest effective invocation hash mismatch."
            }
        }.fold(
            onSuccess = { appendImmutable(phases, manifest.phaseManifestId, manifest, "phase manifest") },
            onFailure = { Result.failure(it) },
        )
    }

    override suspend fun appendObservation(observation: LlmPhaseObservation): Result<Unit> {
        return runCatching {
            ManifestPersistencePolicy.validateObservation(observation, knownSecretValues)
        }.fold(
            onSuccess = { appendImmutable(observations, observation.phaseManifestId, observation, "phase observation") },
            onFailure = { Result.failure(it) },
        )
    }

    override suspend fun findRoot(rootId: String): Result<LlmInvocationAuditRoot?> =
        runCatching { mutex.withLock { roots[rootId] } }

    override suspend fun findRun(invocationId: String): Result<LlmRunInputManifest?> =
        runCatching { mutex.withLock { runs[invocationId] } }

    override suspend fun findPhase(phaseManifestId: String): Result<LlmPhaseInputManifest?> =
        runCatching { mutex.withLock { phases[phaseManifestId] } }

    override suspend fun findObservation(phaseManifestId: String): Result<LlmPhaseObservation?> =
        runCatching { mutex.withLock { observations[phaseManifestId] } }

    private suspend fun <T> appendImmutable(
        values: MutableMap<String, T>,
        key: String,
        value: T,
        label: String,
    ): Result<Unit> = runCatching {
        mutex.withLock {
            val existing = values[key]
            require(existing == null || existing == value) { "$label content mismatch." }
            if (existing == null) values[key] = value
        }
    }
}

internal fun LlmPhaseInputManifest.requireValidReferences(
    runManifest: LlmRunInputManifest?,
    materialManifest: DecisionMaterialStateManifest?,
) {
    val isDecisionPhase = phase in DECISION_PHASES_WITH_RUN
    if (!isDecisionPhase) {
        require(runManifestInvocationId == null && runManifestContentHash == null) {
            "non-decision phase cannot reference a run manifest."
        }
        require(materialInvocationId == null && materialContentHash == null) {
            "non-decision phase cannot reference a material manifest."
        }
        require(notApplicableReason != null) { "non-decision phase requires a typed N/A reason." }

        return
    }

    require(notApplicableReason == null) { "decision phase cannot use a typed N/A reason." }
    require(runManifestInvocationId == invocationId && materialInvocationId == invocationId) {
        "decision phase manifest reference ID mismatch."
    }
    require(runManifest != null && materialManifest != null) { "decision phase manifest reference is missing." }
    require(runManifest.rootId == rootId && runManifest.materialInvocationId == materialInvocationId) {
        "decision phase manifest reference root mismatch."
    }
    require(runManifestContentHash == runManifest.canonicalContentHash) {
        "decision phase run manifest hash mismatch."
    }
    require(materialContentHash == materialManifest.persistedSnapshotHash()) {
        "decision phase material manifest hash mismatch."
    }
}

private val DECISION_PHASES_WITH_RUN = setOf(
    me.matsumo.fukurou.trading.invoker.LlmInvocationPhase.PROPOSER,
    me.matsumo.fukurou.trading.invoker.LlmInvocationPhase.FALSIFIER,
    me.matsumo.fukurou.trading.invoker.LlmInvocationPhase.RISK_REDUCTION_ONLY,
)
