package me.matsumo.fukurou

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.PreparedStatement
import java.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

private const val MONITORING_STATEMENT_TIMEOUT_SECONDS = 2
private const val MAX_PROVIDER_EVENT_ROWS = 1_000
private const val MAX_INFRASTRUCTURE_GAP_EVENT_ROWS = 1_000
private const val MAX_UNRESOLVED_MARKET_DATA_GAPS = 1_000

private val MonitoringEventJson = Json
private val KnownProviders = setOf("claude", "codex")
private val KnownProviderPhases = setOf(
    "pre_filter",
    "proposer",
    "falsifier",
    "risk_reduction_only",
    "reflection",
    "evaluation_report",
)
private val KnownProviderStatuses = setOf("EXITED", "TIMED_OUT", "FAILED_TO_START")

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
    suspend fun providerOutcomes(fromInclusive: Instant, toExclusive: Instant): Result<List<MonitoringProviderOutcomeResponse>>
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
        val infrastructureEvents = selectInfrastructureGapEvents()
        val infrastructure = aggregateInfrastructureGaps(infrastructureEvents)

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
            FROM market_data_gaps
            WHERE recovered_at IS NULL
        """.trimIndent(),
    ).use { statement ->
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

private data class InfrastructureGapEvent(
    val gapId: String,
    val boundary: String,
    val occurredAt: Instant,
)

private fun JdbcTransaction.selectInfrastructureGapEvents(): List<InfrastructureGapEvent> {
    return monitoringStatement(
        """
            SELECT gap_id::text, boundary, occurred_at
            FROM infrastructure_gap_events
            ORDER BY occurred_at DESC, CASE boundary WHEN 'CLOSE' THEN 0 ELSE 1 END, event_id ASC
            LIMIT ?
        """.trimIndent(),
    ).use { statement ->
        statement.setInt(1, MAX_INFRASTRUCTURE_GAP_EVENT_ROWS + 1)
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) {
                    add(
                        InfrastructureGapEvent(
                            gapId = result.getString(1),
                            boundary = result.getString(2),
                            occurredAt = result.getObject(3, java.time.OffsetDateTime::class.java).toInstant(),
                        ),
                    )
                }
            }.also { events ->
                if (events.size > MAX_INFRASTRUCTURE_GAP_EVENT_ROWS) {
                    throw MonitoringQueryBoundExceededException()
                }
            }
        }
    }
}

private fun parseDaemonTerminal(occurredAt: Instant, payload: String): MonitoringDaemonTerminal {
    val payloadObject = payload.toJsonObjectOrMalformed()
    val finishedAt = payloadObject.requiredString("finishedAt").parseInstantOrMalformed()
    val semantic = parseDaemonTerminalSemantic(payloadObject.requiredString("status"))
    if (finishedAt != occurredAt) throw MonitoringMalformedEventException()

    return MonitoringDaemonTerminal(occurredAt, semantic)
}

internal fun parseDaemonTerminalSemantic(rawStatus: String): MonitoringDaemonTerminalSemantic {
    return when (rawStatus) {
        "pre_filter_no_change" -> MonitoringDaemonTerminalSemantic.NO_TRADE
        "unknown" -> MonitoringDaemonTerminalSemantic.LEGACY_UNCLASSIFIED
        else -> MonitoringDaemonTerminalSemantic.entries.firstOrNull { candidate -> candidate.name == rawStatus }
            ?: throw MonitoringMalformedEventException()
    }
}

internal fun aggregateProviderOutcomes(payloads: List<String>): List<MonitoringProviderOutcomeResponse> {
    val aggregates = linkedMapOf<String, IntArray>()

    payloads.forEach { payload ->
        val root = payload.toJsonObjectOrMalformed()
        val phase = root.requiredString("phase")
        val details = root["details"]?.let { element ->
            runCatching { element.jsonObject }.getOrElse { throw MonitoringMalformedEventException() }
        } ?: throw MonitoringMalformedEventException()
        val provider = details.optionalString("provider")

        if (provider == null && phase !in KnownProviderPhases) return@forEach
        if (provider !in KnownProviders) throw MonitoringMalformedEventException()
        val knownProvider = requireNotNull(provider)

        val status = details.requiredString("status")
        if (status !in KnownProviderStatuses) throw MonitoringMalformedEventException()
        val exitCode = details.requiredString("exitCode")
        val parsedExitCode = if (exitCode == "null") null else exitCode.toIntOrNull()
            ?: throw MonitoringMalformedEventException()
        val authFailure = when (val raw = details.optionalString("authFailureSuspected")) {
            null, "false" -> false
            "true" -> true
            else -> throw MonitoringMalformedEventException()
        }
        val failed = status != "EXITED" || parsedExitCode != 0
        val counts = aggregates.getOrPut(knownProvider) { IntArray(3) }
        counts[0] += 1
        if (failed) counts[1] += 1
        if (authFailure) counts[2] += 1
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

private fun aggregateInfrastructureGaps(events: List<InfrastructureGapEvent>): Pair<Int, Instant?> {
    val latestByGap = linkedMapOf<String, InfrastructureGapEvent>()
    events.forEach { event ->
        if (event.boundary !in setOf("OPEN", "CLOSE")) throw MonitoringMalformedEventException()
        latestByGap.putIfAbsent(event.gapId, event)
    }
    val unresolved = latestByGap.values.filter { event -> event.boundary == "OPEN" }

    return unresolved.size to unresolved.minOfOrNull(InfrastructureGapEvent::occurredAt)
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
    return runCatching { element.jsonPrimitive.contentOrNull }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: throw MonitoringMalformedEventException()
}

private fun String.parseInstantOrMalformed(): Instant {
    return runCatching { Instant.parse(this) }.getOrElse { throw MonitoringMalformedEventException() }
}

private fun JdbcTransaction.monitoringStatement(sql: String): PreparedStatement {
    val jdbcConnection = connection.connection as java.sql.Connection

    return jdbcConnection.prepareStatement(sql).also { statement ->
        statement.queryTimeout = MONITORING_STATEMENT_TIMEOUT_SECONDS
    }
}
