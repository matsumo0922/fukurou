package me.matsumo.fukurou

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.matsumo.fukurou.trading.evaluation.LlmRunTerminalCause
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.ProcessRunStatus
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

private const val MONITORING_STATEMENT_TIMEOUT_SECONDS = 2
private const val MAX_PROVIDER_EVENT_ROWS = 1_000
private const val MAX_UNRESOLVED_INFRASTRUCTURE_GAPS = 1_000
private const val MAX_UNRESOLVED_MARKET_DATA_GAPS = 1_000

private val MonitoringEventJson = Json
private val KnownProviders = LlmProvider.entries.map { provider -> provider.name.lowercase() }.toSet()
private val KnownProviderPhases = LlmInvocationPhase.entries.map { phase -> phase.name.lowercase() }.toSet()
private val KnownProviderStatuses = ProcessRunStatus.entries.map { status -> status.name }.toSet() + "FAILED_TO_START"

/** 最新 daemon invocation terminal。 */
internal data class MonitoringDaemonTerminal(
    val occurredAt: Instant,
    val semantic: MonitoringDaemonTerminalSemantic,
)

/** unresolved gap の件数と oldest timestamp。 */
internal data class MonitoringGapAggregate(
    val marketDataCount: Int,
    val oldestMarketDataOpenedAt: Instant?,
    val infrastructureCount: Int,
    val oldestInfrastructureOpenedAt: Instant?,
)

/** monitoring DB read の狭い境界。 */
internal interface MonitoringRepository {
    suspend fun latestDaemonTerminal(): Result<MonitoringDaemonTerminal?>
    suspend fun providerOutcomes(
        fromInclusive: Instant,
        toExclusive: Instant,
    ): Result<List<MonitoringProviderOutcomeResponse>>
    suspend fun unresolvedGaps(): Result<MonitoringGapAggregate>
}

/** bounded query が証拠全体を読めなかったことを表す。 */
internal class MonitoringQueryBoundExceededException : RuntimeException()

/** 永続 event が monitoring contract として解釈不能なことを表す。 */
internal class MonitoringMalformedEventException : RuntimeException()

/** PostgreSQL から monitoring 用 aggregate だけを読む repository。 */
internal class ExposedMonitoringRepository(
    private val database: ExposedDatabase,
) : MonitoringRepository {

    override suspend fun latestDaemonTerminal(): Result<MonitoringDaemonTerminal?> = read {
        monitoringStatement(
            """
                SELECT ts, payload
                FROM command_event_log
                WHERE event_type = 'DAEMON_INVOCATION_COMPLETED'
                ORDER BY ts DESC, id ASC
                LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use query@{ result ->
                if (!result.next()) return@query null

                parseDaemonTerminal(
                    occurredAt = Instant.ofEpochMilli(result.getLong("ts")),
                    payload = result.getString("payload"),
                )
            }
        }
    }

    override suspend fun providerOutcomes(
        fromInclusive: Instant,
        toExclusive: Instant,
    ): Result<List<MonitoringProviderOutcomeResponse>> = read {
        monitoringStatement(
            """
                SELECT payload
                FROM command_event_log
                WHERE event_type = 'RUNNER_PHASE_COMPLETED'
                  AND ts >= ? AND ts < ?
                ORDER BY ts ASC, id ASC
                LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, fromInclusive.toEpochMilli())
            statement.setLong(2, toExclusive.toEpochMilli())
            statement.setInt(3, MAX_PROVIDER_EVENT_ROWS + 1)
            statement.executeQuery().use { result ->
                val payloads = buildList {
                    while (result.next()) add(result.getString("payload"))
                }
                if (payloads.size > MAX_PROVIDER_EVENT_ROWS) throw MonitoringQueryBoundExceededException()

                aggregateProviderOutcomes(payloads)
            }
        }
    }

    override suspend fun unresolvedGaps(): Result<MonitoringGapAggregate> = read {
        val marketData = selectUnresolvedMarketDataGaps()
        val infrastructure = selectUnresolvedInfrastructureGaps()

        MonitoringGapAggregate(
            marketDataCount = marketData.first,
            oldestMarketDataOpenedAt = marketData.second,
            infrastructureCount = infrastructure.first,
            oldestInfrastructureOpenedAt = infrastructure.second,
        )
    }

    private suspend fun <T> read(block: JdbcTransaction.() -> T): Result<T> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    exec("SET LOCAL statement_timeout='${MONITORING_STATEMENT_TIMEOUT_SECONDS}s'")
                    exec("SET LOCAL lock_timeout='1s'")
                    block()
                }
            }
        }
    }
}

private fun JdbcTransaction.selectUnresolvedMarketDataGaps(): Pair<Int, Instant?> {
    return monitoringStatement(
        """
            SELECT COUNT(*) AS gap_count, MIN(started_at) AS oldest_started_at
            FROM (
                SELECT started_at
                FROM market_data_gaps
                WHERE recovered_at IS NULL
                ORDER BY started_at ASC, id ASC
                LIMIT ?
            ) bounded_gaps
        """.trimIndent(),
    ).use { statement ->
        statement.setInt(1, MAX_UNRESOLVED_MARKET_DATA_GAPS + 1)
        statement.executeQuery().use { result ->
            check(result.next())
            val count = result.getLong("gap_count")
            if (count > MAX_UNRESOLVED_MARKET_DATA_GAPS) throw MonitoringQueryBoundExceededException()

            val oldest = result.getLong("oldest_started_at")
                .takeUnless { result.wasNull() }
                ?.let(Instant::ofEpochMilli)
            count.toInt() to oldest
        }
    }
}

private fun JdbcTransaction.selectUnresolvedInfrastructureGaps(): Pair<Int, Instant?> {
    return monitoringStatement(
        """
            SELECT COUNT(*) AS gap_count, MIN(occurred_at) AS oldest_occurred_at
            FROM (
                SELECT opened.occurred_at
                FROM infrastructure_gap_events opened
                WHERE opened.boundary = 'OPEN'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM infrastructure_gap_events closed
                      WHERE closed.gap_id = opened.gap_id
                        AND closed.boundary = 'CLOSE'
                  )
                ORDER BY opened.occurred_at ASC, opened.event_id ASC
                LIMIT ?
            ) bounded_gaps
        """.trimIndent(),
    ).use { statement ->
        statement.setInt(1, MAX_UNRESOLVED_INFRASTRUCTURE_GAPS + 1)
        statement.executeQuery().use { result ->
            check(result.next())
            val count = result.getLong("gap_count")
            if (count > MAX_UNRESOLVED_INFRASTRUCTURE_GAPS) throw MonitoringQueryBoundExceededException()

            val oldest = result.getObject("oldest_occurred_at", OffsetDateTime::class.java)?.toInstant()
            count.toInt() to oldest
        }
    }
}

internal fun parseDaemonTerminal(occurredAt: Instant, payload: String): MonitoringDaemonTerminal {
    val payloadObject = payload.toJsonObjectOrMalformed()
    val finishedAt = payloadObject.requiredString("finishedAt").parseInstantOrMalformed()
    val semantic = parseDaemonTerminalSemantic(payloadObject.requiredString("status"))
    if (finishedAt.truncatedTo(ChronoUnit.MILLIS) != occurredAt) throw MonitoringMalformedEventException()

    return MonitoringDaemonTerminal(occurredAt, semantic)
}

internal fun parseDaemonTerminalSemantic(rawStatus: String): MonitoringDaemonTerminalSemantic {
    return when (rawStatus) {
        "pre_filter_no_change" -> MonitoringDaemonTerminalSemantic.NO_TRADE
        "unknown" -> MonitoringDaemonTerminalSemantic.LEGACY_UNCLASSIFIED
        else -> runCatching {
            LlmRunTerminalCause.valueOf(rawStatus)
            MonitoringDaemonTerminalSemantic.valueOf(rawStatus)
        }.getOrElse { throw MonitoringMalformedEventException() }
    }
}

internal fun aggregateProviderOutcomes(payloads: List<String>): List<MonitoringProviderOutcomeResponse> {
    val aggregates = linkedMapOf<String, IntArray>()

    payloads.forEach { payload ->
        val outcome = payload.toProviderOutcomeOrNull() ?: return@forEach
        val counts = aggregates.getOrPut(outcome.provider) { IntArray(3) }
        counts[0] += 1
        if (outcome.failed) counts[1] += 1
        if (outcome.authenticationFailed) counts[2] += 1
    }

    return aggregates.entries.sortedBy(Map.Entry<String, IntArray>::key).map { (provider, counts) ->
        MonitoringProviderOutcomeResponse(
            provider = provider,
            totalCount = counts[0],
            failureCount = counts[1],
            authenticationFailureCount = counts[2],
        )
    }
}

private data class ProviderOutcome(
    val provider: String,
    val failed: Boolean,
    val authenticationFailed: Boolean,
)

private fun String.toProviderOutcomeOrNull(): ProviderOutcome? {
    val root = toJsonObjectOrMalformed()
    val phase = root.requiredString("phase")
    val details = root["details"]?.let { element ->
        runCatching { element.jsonObject }.getOrElse { throw MonitoringMalformedEventException() }
    } ?: throw MonitoringMalformedEventException()
    val provider = details.optionalString("provider")

    if (provider == null && phase !in KnownProviderPhases) return null
    if (provider !in KnownProviders) throw MonitoringMalformedEventException()
    val knownProvider = requireNotNull(provider)
    val status = details.requiredString("status")
    if (status !in KnownProviderStatuses) throw MonitoringMalformedEventException()
    val exitCode = details.requiredString("exitCode").toExitCodeOrMalformed()
    val authenticationFailed = details.optionalString("authFailureSuspected").toAuthFailureOrMalformed()

    return ProviderOutcome(
        provider = knownProvider,
        failed = status != "EXITED" || exitCode != 0,
        authenticationFailed = authenticationFailed,
    )
}

private fun String.toExitCodeOrMalformed(): Int? {
    return if (this == "null") null else toIntOrNull() ?: throw MonitoringMalformedEventException()
}

private fun String?.toAuthFailureOrMalformed(): Boolean {
    return when (this) {
        null, "false" -> false
        "true" -> true
        else -> throw MonitoringMalformedEventException()
    }
}

private fun String.toJsonObjectOrMalformed(): JsonObject {
    return runCatching { MonitoringEventJson.parseToJsonElement(this).jsonObject }
        .getOrElse { throw MonitoringMalformedEventException() }
}

private fun JsonObject.requiredString(key: String): String {
    return optionalString(key) ?: throw MonitoringMalformedEventException()
}

private fun JsonObject.optionalString(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null

    return runCatching { element.jsonPrimitive.contentOrNull }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: throw MonitoringMalformedEventException()
}

private fun String.parseInstantOrMalformed(): Instant {
    return runCatching { Instant.parse(this) }.getOrElse { throw MonitoringMalformedEventException() }
}

private fun JdbcTransaction.monitoringStatement(sql: String): PreparedStatement {
    val jdbcConnection = connection.connection as Connection

    return jdbcConnection.prepareStatement(sql).also { statement ->
        statement.queryTimeout = MONITORING_STATEMENT_TIMEOUT_SECONDS
    }
}
