package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateManifest
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** PostgreSQL immutable material-state manifest repository。 */
class ExposedDecisionMaterialStateRepository(private val database: Database) : DecisionMaterialStateRepository {
    override suspend fun append(manifest: DecisionMaterialStateManifest): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            transaction(database) {
                jdbcConnection().prepareStatement(
                    "INSERT INTO decision_material_state_manifests " +
                        "(invocation_id, captured_at, schema_version, content_hash, manifest_json) " +
                        "VALUES (?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, manifest.invocationId)
                    statement.setLong(2, manifest.capturedAt.toEpochMilli())
                    statement.setInt(3, manifest.schemaVersion)
                    statement.setString(4, manifest.canonicalContentHash)
                    statement.setString(5, manifest.toString())
                    statement.executeUpdate()
                }
                Unit
            }
        }
    }
}
