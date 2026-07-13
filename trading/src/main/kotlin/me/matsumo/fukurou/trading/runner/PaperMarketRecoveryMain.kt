@file:Suppress("ImportOrdering")

package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.evaluation.LlmRunStart
import me.matsumo.fukurou.trading.persistence.ExposedLlmRunRepository
import me.matsumo.fukurou.trading.persistence.ExposedMarketDataIntegrityRepository
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant
import kotlin.system.exitProcess

/** C0 recovery と token-aware fixture を production image 内の同じ repository 配線で実行する。 */
fun main(arguments: Array<String>) {
    val exitCode = runBlocking { runPaperMarketRecoveryCommand(arguments, System.getenv()) }
    if (exitCode != 0) exitProcess(exitCode)
}

@Serializable
private data class RecoveryOutput(
    val operation: String,
    val status: String,
    val count: Int,
    val applied: Int = 0,
    val unknown: Int = 0,
    val remaining: Int = 0,
)

internal suspend fun runPaperMarketRecoveryCommand(
    arguments: Array<String>,
    environment: Map<String, String>,
    stdout: (String) -> Unit = { message -> System.out.println(message) },
    stderr: (String) -> Unit = { message -> System.err.println(message) },
): Int {
    return runCatching {
        val database = connectRecoveryDatabase(environment)
        TradingPersistenceBootstrap(database).ensureSchema().getOrThrow()
        when (arguments.firstOrNull()?.uppercase()) {
            "RECOVER" -> {
                val summary = ExposedMarketDataIntegrityRepository(database)
                    .recoverStaleSessionWithSummary(Instant.now())
                    .getOrThrow()
                RecoveryOutput(
                    operation = "RECOVER",
                    status = summary.state,
                    count = summary.processed,
                    applied = summary.applied,
                    unknown = summary.unknown,
                    remaining = summary.remaining,
                )
            }
            "FIXTURE_CREATE" -> {
                requireFixtureDatabase(environment)
                val invocationId = arguments.getOrNull(1) ?: error("fixture invocation ID is required.")
                ExposedLlmRunRepository(database).insertRunning(
                    LlmRunStart(
                        invocationId = invocationId,
                        mode = TradingMode.PAPER,
                        symbol = TradingSymbol.BTC,
                        triggerKind = LlmDaemonTriggerKind.MANUAL,
                        startedAt = Instant.now(),
                        runtimeConfigVersionId = null,
                        runtimeConfigHash = null,
                    ),
                ).getOrThrow()
                RecoveryOutput("FIXTURE_CREATE", "CREATED", 1)
            }
            else -> error("command must be RECOVER or FIXTURE_CREATE.")
        }
    }.fold(
        onSuccess = { output ->
            stdout(Json.encodeToString(output))
            if (output.status in setOf("ALL_APPLIED", "CREATED")) 0 else 2
        },
        onFailure = { failure ->
            stderr(Json.encodeToString(RecoveryOutput("UNKNOWN", failure::class.simpleName ?: "ERROR", 0)))
            1
        },
    )
}

private fun connectRecoveryDatabase(environment: Map<String, String>): Database {
    val url = requireNotNull(environment["DB_URL"]) { "DB_URL is required." }
    val user = requireNotNull(environment["DB_USER"]) { "DB_USER is required." }
    val password = requireNotNull(environment["DB_PASSWORD"]) { "DB_PASSWORD is required." }
    return Database.connect(url = url, driver = "org.postgresql.Driver", user = user, password = password)
}

private fun requireFixtureDatabase(environment: Map<String, String>) {
    check(environment["FUKUROU_FIXTURE_DATABASE"] == "true") {
        "FIXTURE_CREATE requires FUKUROU_FIXTURE_DATABASE=true."
    }
    val url = environment["DB_URL"].orEmpty().lowercase()
    check("canary" in url || "test" in url) { "FIXTURE_CREATE only accepts disposable canary/test databases." }
}
