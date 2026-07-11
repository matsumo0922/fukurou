@file:Suppress("ImportOrdering")

package me.matsumo.fukurou

import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.server.routing.openapi.describe
import io.ktor.openapi.jsonSchema
import io.ktor.utils.io.ExperimentalKtorApi
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.Duration
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/** browser 専用の read-only current context WebSocket を定義する。 */
@OptIn(ExperimentalKtorApi::class)
internal fun Route.currentContextWebSocketRoutes(dependencies: EvaluationRouteDependencies) {
    webSocket("/ops/current-context/ws") {
        val requestOrigin = call.request.origin
        if (!originAllowed(call.request.headers["Origin"], requestOrigin.scheme, requestOrigin.serverHost, requestOrigin.serverPort)) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin is not allowed"))
            return@webSocket
        }

        var sequence = 0L
        val connectionSessionId = UUID.randomUUID().toString()
        var previous: List<CurrentContextSourceResponse>? = null
        while (true) {
            val sources = currentContextSources(dependencies)
            val envelope = CurrentContextEnvelopeResponse(
                protocolVersion = 1,
                type = when {
                    previous == null -> "SNAPSHOT"
                    previous != sources -> "UPDATE"
                    else -> "HEARTBEAT"
                },
                sessionId = connectionSessionId,
                sequence = ++sequence,
                sentAt = dependencies.clock.instant().toString(),
                sources = if (previous == sources) emptyList() else sources,
            )
            val encodedEnvelope = ApiJson.encodeToString(envelope)
            runCatching {
                withTimeout(dependencies.currentContextSendTimeoutMillis) {
                    dependencies.currentContextSendOverride?.invoke(encodedEnvelope)
                        ?: send(Frame.Text(encodedEnvelope))
                }
            }.getOrElse {
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "current context client is too slow"))
                return@webSocket
            }
            previous = sources
            delay(Duration.ofSeconds(5).toMillis())
        }
    }.describe {
        summary = "現在の read-only 運用 context を WebSocket 配信する"
        description = "protocolVersion=1、connection-scoped sessionId、session内で単調増加するsequence、SNAPSHOT/UPDATE/HEARTBEAT envelope と source ごとの observedAt/receivedAt/staleAfterMillis/freshness を配信します。Originは必須かつsame-originです。45秒無応答または slow client は再接続が必要です。"
        tag("評価レポート")
        responses {
            io.ktor.http.HttpStatusCode.SwitchingProtocols {
                schema = jsonSchema<CurrentContextEnvelopeResponse>()
            }
        }
    }
}

private suspend fun currentContextSources(
    dependencies: EvaluationRouteDependencies,
): List<CurrentContextSourceResponse> {
    val now = dependencies.clock.instant()
    val quote = dependencies.latestMarketQuoteStore.snapshot()
    val risk = dependencies.riskStateRepository?.current()?.getOrNull()
    val databaseSources = dependencies.database?.let { database -> readDatabaseSources(database, now) }
        ?: unavailableDatabaseSources(now)
    val quoteSource = if (quote == null) {
        unavailableSource("MARKET_QUOTE", now)
    } else {
        val age = Duration.between(quote.observedAt, now).toMillis().coerceAtLeast(0)
        CurrentContextSourceResponse(
            source = "MARKET_QUOTE",
            observedAt = quote.observedAt.toString(),
            receivedAt = now.toString(),
            staleAfterMillis = CURRENT_CONTEXT_STALE_AFTER_MILLIS,
            freshness = if (age <= 15_000) "FRESH" else "STALE",
            value = mapOf(
                "bidPriceJpy" to quote.bidPriceJpy.toPlainString(),
                "askPriceJpy" to quote.askPriceJpy.toPlainString(),
            ),
        )
    }
    val riskSource = CurrentContextSourceResponse(
        source = "RUNTIME_STATE",
        observedAt = risk?.updatedAt?.toString(),
        receivedAt = now.toString(),
        staleAfterMillis = CURRENT_CONTEXT_STALE_AFTER_MILLIS,
        freshness = risk?.updatedAt?.let { observed -> freshness(observed, now, CURRENT_CONTEXT_STALE_AFTER_MILLIS) }
            ?: "UNAVAILABLE",
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

private fun readDatabaseSources(
    database: ExposedDatabase,
    now: java.time.Instant,
): List<CurrentContextSourceResponse> = runCatching {
    exposedTransaction(database) {
        val account = exec(
            "SELECT total_equity_jpy, btc_quantity * btc_mark_price_jpy, updated_at FROM paper_account WHERE id=1",
        ) { result ->
            if (!result.next()) return@exec null
            val observedAt = java.time.Instant.ofEpochMilli(result.getLong(3))
            CurrentContextSourceResponse(
                source = "PAPER_ACCOUNT",
                observedAt = observedAt.toString(),
                receivedAt = now.toString(),
                staleAfterMillis = 30_000,
                freshness = freshness(observedAt, now, 30_000),
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
            val observedAt = java.time.Instant.ofEpochMilli(result.getLong(3))
            CurrentContextSourceResponse(
                source = "LATEST_LLM_RUN",
                observedAt = observedAt.toString(),
                receivedAt = now.toString(),
                staleAfterMillis = 30_000,
                freshness = freshness(observedAt, now, 30_000),
                value = mapOf(
                    "invocationId" to result.getString(1),
                    "status" to result.getString(2),
                ),
            )
        }

        listOf(
            account ?: unavailableSource("PAPER_ACCOUNT", now),
            latestRun ?: unavailableSource("LATEST_LLM_RUN", now),
        )
    }
}.getOrElse { unavailableDatabaseSources(now) }

private fun unavailableDatabaseSources(now: java.time.Instant): List<CurrentContextSourceResponse> = listOf(
    unavailableSource("PAPER_ACCOUNT", now),
    unavailableSource("LATEST_LLM_RUN", now),
)

private fun unavailableSource(source: String, now: java.time.Instant): CurrentContextSourceResponse {
    return CurrentContextSourceResponse(
        source,
        null,
        now.toString(),
        CURRENT_CONTEXT_STALE_AFTER_MILLIS,
        "UNAVAILABLE",
        null,
    )
}

private fun freshness(
    observedAt: java.time.Instant,
    receivedAt: java.time.Instant,
    staleAfterMillis: Long,
): String {
    return if (Duration.between(observedAt, receivedAt).toMillis().coerceAtLeast(0) <= staleAfterMillis) {
        "FRESH"
    } else {
        "STALE"
    }
}

internal fun originAllowed(
    origin: String?,
    scheme: String,
    host: String,
    port: Int,
): Boolean {
    if (origin == null) return false
    return runCatching {
        val uri = java.net.URI(origin)
        val originPort = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
        uri.scheme == scheme && uri.host.equals(host, ignoreCase = true) && originPort == port
    }.getOrDefault(false)
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
    val receivedAt: String,
    val staleAfterMillis: Long,
    val freshness: String,
    val value: Map<String, String>?,
)
private const val CURRENT_CONTEXT_STALE_AFTER_MILLIS = 15_000L
