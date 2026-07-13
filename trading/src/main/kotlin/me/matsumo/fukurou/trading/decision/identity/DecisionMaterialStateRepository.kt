package me.matsumo.fukurou.trading.decision.identity

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** immutable material-state manifest の永続境界。 */
interface DecisionMaterialStateRepository {
    /** invocation ごとに1件だけ manifest を保存する。 */
    suspend fun append(manifest: DecisionMaterialStateManifest): Result<Unit>

    /** invocation の immutable manifest を返す。 */
    suspend fun find(invocationId: String): Result<DecisionMaterialStateManifest?> = Result.success(null)

    /** open episode の固定 projection context を返す。 */
    suspend fun findOpenEpisodeContext(symbol: String): Result<DecisionMaterialProjectionContext?> =
        Result.success(null)

    /** in-memory runtime で開始した episode context を固定する。 */
    suspend fun recordOpenEpisodeContext(symbol: String, context: DecisionMaterialProjectionContext): Result<Unit> =
        Result.success(Unit)
}

/** DB 未構成 runtime 用の immutable manifest repository。 */
class InMemoryDecisionMaterialStateRepository : DecisionMaterialStateRepository {
    private val mutex = Mutex()
    private val manifests = mutableMapOf<String, DecisionMaterialStateManifest>()
    private val episodeContexts = mutableMapOf<String, DecisionMaterialProjectionContext>()

    override suspend fun append(manifest: DecisionMaterialStateManifest): Result<Unit> = runCatching {
        mutex.withLock {
            val existing = manifests[manifest.invocationId]
            require(existing == null || existing == manifest) { "material manifest content mismatch." }
            if (existing == null) manifests[manifest.invocationId] = manifest
        }
    }

    override suspend fun find(invocationId: String): Result<DecisionMaterialStateManifest?> = runCatching {
        mutex.withLock { manifests[invocationId] }
    }

    override suspend fun findOpenEpisodeContext(symbol: String): Result<DecisionMaterialProjectionContext?> =
        runCatching { mutex.withLock { episodeContexts[symbol] } }

    override suspend fun recordOpenEpisodeContext(
        symbol: String,
        context: DecisionMaterialProjectionContext,
    ): Result<Unit> = runCatching {
        mutex.withLock {
            if (symbol !in episodeContexts) episodeContexts[symbol] = context
        }
    }
}
