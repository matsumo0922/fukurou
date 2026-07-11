@file:Suppress("ImportOrdering")

package me.matsumo.fukurou

import io.ktor.server.request.host
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.Duration
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/** browser 専用の read-only current context WebSocket を定義する。 */
internal fun Route.currentContextWebSocketRoutes(dependencies: EvaluationRouteDependencies) {
    webSocket("/ops/current-context/ws") {
        if (!originAllowed(call.request.headers["Origin"], call.request.host())) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin is not allowed"))
            return@webSocket
        }

        val sessionId = UUID.randomUUID().toString()
        var sequence = 0L
        var previous: List<CurrentContextSourceResponse>? = null
        while (true) {
            val sources = currentContextSources(dependencies)
            if (previous == null || previous != sources) {
                val envelope = CurrentContextEnvelopeResponse(
                    protocolVersion = 1,
                    type = if (previous == null) "SNAPSHOT" else "UPDATE",
                    sessionId = sessionId,
                    sequence = ++sequence,
                    sentAt = dependencies.clock.instant().toString(),
                    sources = sources,
                )
                send(Frame.Text(ApiJson.encodeToString(envelope)))
                previous = sources
            }
            delay(Duration.ofSeconds(5).toMillis())
        }
    }
}

private suspend fun currentContextSources(
    dependencies: EvaluationRouteDependencies,
): List<CurrentContextSourceResponse> {
    val now = dependencies.clock.instant()
    val quote = dependencies.latestMarketQuoteStore.snapshot()
    val risk = dependencies.riskStateRepository?.current()?.getOrNull()
    val databaseSources = dependencies.database?.let(::readDatabaseSources) ?: unavailableDatabaseSources()
    val quoteSource = if (quote == null) {
        CurrentContextSourceResponse("MARKET_QUOTE", null, "UNAVAILABLE", null)
    } else {
        val age = Duration.between(quote.observedAt, now).toMillis().coerceAtLeast(0)
        CurrentContextSourceResponse(
            source = "MARKET_QUOTE",
            observedAt = quote.observedAt.toString(),
            freshness = if (age <= 15_000) "FRESH" else "STALE",
            value = mapOf(
                "bidPriceJpy" to quote.bidPriceJpy.toPlainString(),
                "askPriceJpy" to quote.askPriceJpy.toPlainString(),
            ),
        )
    }
    val riskSource = CurrentContextSourceResponse(
        source = "RUNTIME_STATE",
        observedAt = now.toString(),
        freshness = if (risk == null) "UNAVAILABLE" else "FRESH",
        value = risk?.let { state ->
            mapOf(
                "mode" to "PAPER",
                "riskState" to state.state.name,
                "drawdownRatio" to state.drawdownRatio.toPlainString(),
            )
        },
    )

    return listOf(quoteSource, riskSource) + databaseSources
}

private fun readDatabaseSources(database: ExposedDatabase): List<CurrentContextSourceResponse> = runCatching {
    exposedTransaction(database) {
        val account = exec(
            "SELECT total_equity_jpy, btc_quantity * btc_mark_price_jpy, updated_at FROM paper_account WHERE id=1",
        ) { result ->
            if (!result.next()) return@exec null
            CurrentContextSourceResponse(
                source = "PAPER_ACCOUNT",
                observedAt = java.time.Instant.ofEpochMilli(result.getLong(3)).toString(),
                freshness = "FRESH",
                value = mapOf(
                    "equityJpy" to result.getBigDecimal(1).toPlainString(),
                    "exposureJpy" to result.getBigDecimal(2).toPlainString(),
                ),
            )
        }
        val latestRun = exec(
            "SELECT invocation_id, status, started_at FROM llm_runs ORDER BY started_at DESC LIMIT 1",
        ) { result ->
            if (!result.next()) return@exec null
            CurrentContextSourceResponse(
                source = "LATEST_LLM_RUN",
                observedAt = java.time.Instant.ofEpochMilli(result.getLong(3)).toString(),
                freshness = "FRESH",
                value = mapOf(
                    "invocationId" to result.getString(1),
                    "status" to result.getString(2),
                ),
            )
        }

        listOf(
            account ?: unavailableSource("PAPER_ACCOUNT"),
            latestRun ?: unavailableSource("LATEST_LLM_RUN"),
        )
    }
}.getOrElse { unavailableDatabaseSources() }

private fun unavailableDatabaseSources(): List<CurrentContextSourceResponse> = listOf(
    unavailableSource("PAPER_ACCOUNT"),
    unavailableSource("LATEST_LLM_RUN"),
)

private fun unavailableSource(source: String): CurrentContextSourceResponse =
    CurrentContextSourceResponse(source, null, "UNAVAILABLE", null)

private fun originAllowed(origin: String?, host: String): Boolean {
    if (origin == null) return true
    return runCatching { java.net.URI(origin).authority == host }.getOrDefault(false)
}

@Serializable
data class CurrentContextEnvelopeResponse(
    val protocolVersion: Int,
    val type: String,
    val sessionId: String,
    val sequence: Long,
    val sentAt: String,
    val sources: List<CurrentContextSourceResponse>,
)

@Serializable
data class CurrentContextSourceResponse(
    val source: String,
    val observedAt: String?,
    val freshness: String,
    val value: Map<String, String>?,
)
