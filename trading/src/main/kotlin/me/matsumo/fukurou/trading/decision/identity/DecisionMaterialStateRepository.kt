package me.matsumo.fukurou.trading.decision.identity

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** immutable material-state manifest の永続境界。 */
interface DecisionMaterialStateRepository {
    /** invocation ごとに1件だけ manifest を保存する。 */
    suspend fun append(manifest: DecisionMaterialStateManifest): Result<Unit>

    /** invocation の immutable manifest を返す。 */
    suspend fun find(invocationId: String): Result<DecisionMaterialStateManifest?> = Result.success(null)
}

/** DB 未構成 runtime 用の immutable manifest repository。 */
class InMemoryDecisionMaterialStateRepository : DecisionMaterialStateRepository {
    private val mutex = Mutex()
    private val manifests = mutableMapOf<String, DecisionMaterialStateManifest>()

    override suspend fun append(manifest: DecisionMaterialStateManifest): Result<Unit> = runCatching {
        mutex.withLock {
            require(manifests.putIfAbsent(manifest.invocationId, manifest) == null) {
                "material manifest already exists for invocation."
            }
        }
    }

    override suspend fun find(invocationId: String): Result<DecisionMaterialStateManifest?> = runCatching {
        mutex.withLock { manifests[invocationId] }
    }
}
