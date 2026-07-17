package me.matsumo.fukurou

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue

/** durable receipt eligibility の production authority inventory を固定するテスト。 */
class DurableReceiptEligibilityWiringTest {

    @Test
    fun connected_postgres_owns_realtime_order_boundary_authority() {
        val runtime = repositoryRoot().read(
            "trading/src/main/kotlin/me/matsumo/fukurou/trading/runtime/TradingRuntime.kt",
        )
        val brokerRoot = runtime
            .substringAfter("private fun createPostgresBroker(")
            .substringBefore("private data class PostgresRuntimeConnection")

        assertTrue(brokerRoot.contains("ledgerRepository = ExposedPaperLedgerRepository("))
        assertTrue(brokerRoot.contains("reconcilerStatusProvider = resolvedReconcilerStatusProvider"))
        assertTrue(brokerRoot.contains("requireRealtimeIntegrityForRestingOrders = true"))
    }

    @Test
    fun reconciler_worker_owns_durable_receipt_and_exposed_event_consumer_authorities() {
        val worker = repositoryRoot().read(
            "fukurou/src/main/kotlin/me/matsumo/fukurou/ProtectionReconcilerWorker.kt",
        )
        val componentsRoot = worker
            .substringAfter("private fun ProtectionReconcilerWorkerInputs.createRuntimeComponents()")
            .substringBefore("internal fun createGmoMarketEventStream(")
        val repositoryRoot = worker
            .substringAfter("private fun ProtectionReconcilerWorkerInputs.createRepositories()")
            .substringBefore("private fun ProtectionReconcilerWorkerInputs.createBroker(")
        val brokerRoot = worker
            .substringAfter("private fun ProtectionReconcilerWorkerInputs.createBroker(")
            .substringBefore("private fun ProtectionReconcilerRuntimeComponents.createReconciler()")

        assertTrue(repositoryRoot.contains("ledgerRepository = ExposedPaperLedgerRepository("))
        assertTrue(repositoryRoot.contains("marketEventReceiptRepository = ExposedPaperMarketEventReceiptRepository(database)"))
        assertTrue(componentsRoot.contains("receiptRepository = repositories.marketEventReceiptRepository"))
        assertTrue(brokerRoot.contains("ledgerRepository = repositories.ledgerRepository"))
        assertTrue(brokerRoot.contains("requireRealtimeIntegrityForRestingOrders = true"))
    }

    private fun repositoryRoot(): Path {
        var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()

        while (!candidate.resolve("settings.gradle.kts").exists()) {
            candidate = candidate.parent ?: error("repository root was not found")
        }

        return candidate
    }

    private fun Path.read(relativePath: String): String {
        return Files.readString(resolve(relativePath))
    }
}
