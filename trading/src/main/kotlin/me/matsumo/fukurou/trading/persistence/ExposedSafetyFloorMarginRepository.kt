package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.safety.EvaluationPointId
import me.matsumo.fukurou.trading.safety.MarginUnit
import me.matsumo.fukurou.trading.safety.NaReason
import me.matsumo.fukurou.trading.safety.ObservedVerdict
import me.matsumo.fukurou.trading.safety.RuleObservation
import me.matsumo.fukurou.trading.safety.RuleStatus
import me.matsumo.fukurou.trading.safety.SafetyFloorCallSite
import me.matsumo.fukurou.trading.safety.SafetyFloorEvaluationPath
import me.matsumo.fukurou.trading.safety.SafetyFloorMarginPersistenceStage
import me.matsumo.fukurou.trading.safety.SafetyFloorMarginRepository
import me.matsumo.fukurou.trading.safety.SafetyFloorObservationReport
import me.matsumo.fukurou.trading.safety.SafetyFloorRule
import me.matsumo.fukurou.trading.safety.safetyFloorMarginResult
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * PostgreSQL の SafetyFloor margin observation repository。
 *
 * report と全 evaluation point を 1 つの transaction で書き込む。いずれかが失敗した
 * 場合は transaction ごと rollback し、部分的な観測を残さない。
 */
class ExposedSafetyFloorMarginRepository(
    private val database: Database,
) : SafetyFloorMarginRepository {

    override suspend fun append(report: SafetyFloorObservationReport): Result<Unit> {
        return withContext(Dispatchers.IO) {
            safetyFloorMarginResult(SafetyFloorMarginPersistenceStage.REPORT) {
                transaction(database) {
                    // 監査書き込みが lock 待ちで注文処理を長時間ブロックしないよう、
                    // 短い timeout を transaction 局所で設定する。statement_timeout は文ごとに
                    // 効くため、child は 1 文の multi-row INSERT にまとめて文数を 2 に抑える。
                    applyWriteTimeouts()
                    insertReport(report)
                    insertObservations(report.id, report.observations, report.observedAt)
                }
            }
        }
    }

    private fun JdbcTransaction.applyWriteTimeouts() {
        jdbcConnection().createStatement().use { statement ->
            statement.execute("SET LOCAL lock_timeout = '2s'")
            statement.execute("SET LOCAL statement_timeout = '2s'")
        }
    }

    override suspend fun find(id: UUID): Result<SafetyFloorObservationReport?> {
        return withContext(Dispatchers.IO) {
            safetyFloorMarginResult(SafetyFloorMarginPersistenceStage.READ) {
                transaction(database) { selectReport(id) }
            }
        }
    }

    private fun JdbcTransaction.insertReport(report: SafetyFloorObservationReport) {
        jdbcConnection().prepareStatement(INSERT_REPORT_SQL).use { statement ->
            statement.setObject(1, report.id)
            statement.setString(2, report.path.name)
            statement.setString(3, report.callSite.name)
            statement.setNullableString(4, report.decisionRunId)
            statement.setObject(5, report.commandId)
            statement.setString(6, report.verdict.name)
            statement.setNullableString(7, report.rejectedRule?.name)
            statement.setString(8, report.policyVersion)
            statement.setNullableString(9, report.runtimeConfigVersion)
            statement.setInt(10, report.observationSchemaVersion)
            statement.setBoolean(11, report.divergence)
            statement.setBoolean(12, report.collectionFailed)
            statement.setLong(13, report.observedAt.toEpochMilli())
            statement.executeUpdate()
        }
    }

    /**
     * evaluation point を 1 文の multi-row INSERT で書き込む。
     *
     * statement_timeout は文ごとに効くため、逐次 INSERT だと文数分の deadline が積み上がる。
     * 1 文にまとめることで、注文経路をブロックしうる時間を transaction あたり実質 2 文分に抑える。
     */
    private fun JdbcTransaction.insertObservations(
        reportId: UUID,
        observations: List<RuleObservation>,
        observedAt: Instant,
    ) {
        if (observations.isEmpty()) return

        val rowPlaceholders = List(observations.size) { OBSERVATION_ROW_PLACEHOLDER }.joinToString(", ")
        val sql = "$INSERT_OBSERVATIONS_PREFIX $rowPlaceholders"

        jdbcConnection().prepareStatement(sql).use { statement ->
            observations.forEachIndexed { index, observation ->
                val base = index * OBSERVATION_COLUMN_COUNT
                statement.setObject(base + 1, UUID.randomUUID())
                statement.setObject(base + 2, reportId)
                statement.setString(base + 3, observation.point.rule.name)
                statement.setString(base + 4, observation.point.pointId)
                statement.setString(base + 5, observation.status.name)
                statement.setNullableString(base + 6, observation.naReason?.name)
                statement.setNullableBigDecimal(base + 7, observation.marginValue)
                statement.setNullableString(base + 8, observation.point.marginUnit?.name)
                statement.setLong(base + 9, observedAt.toEpochMilli())
            }
            statement.executeUpdate()
        }
    }

    private fun JdbcTransaction.selectReport(id: UUID): SafetyFloorObservationReport? {
        val observations = jdbcConnection().prepareStatement(SELECT_OBSERVATIONS_SQL).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { resultSet -> readObservationRows(resultSet) }
        }

        return jdbcConnection().prepareStatement(SELECT_REPORT_SQL).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) readReportRow(resultSet, observations) else null
            }
        }
    }

    private fun readReportRow(
        resultSet: ResultSet,
        observations: List<RuleObservation>,
    ): SafetyFloorObservationReport {
        return SafetyFloorObservationReport(
            id = resultSet.getObject("id", UUID::class.java),
            path = SafetyFloorEvaluationPath.valueOf(resultSet.getString("evaluation_path")),
            callSite = SafetyFloorCallSite.valueOf(resultSet.getString("call_site")),
            decisionRunId = resultSet.getString("decision_run_id"),
            commandId = resultSet.getObject("command_id", UUID::class.java),
            verdict = ObservedVerdict.valueOf(resultSet.getString("verdict")),
            rejectedRule = resultSet.getString("rejected_rule")?.let(SafetyFloorRule::valueOf),
            policyVersion = resultSet.getString("policy_version"),
            runtimeConfigVersion = resultSet.getString("runtime_config_version"),
            observationSchemaVersion = resultSet.getInt("observation_schema_version"),
            divergence = resultSet.getBoolean("divergence"),
            collectionFailed = resultSet.getBoolean("collection_failed"),
            observations = observations,
            observedAt = Instant.ofEpochMilli(resultSet.getLong("observed_at")),
        )
    }

    private fun readObservationRows(resultSet: ResultSet): List<RuleObservation> {
        val observations = mutableListOf<RuleObservation>()

        while (resultSet.next()) {
            val point = EvaluationPointId(
                rule = SafetyFloorRule.valueOf(resultSet.getString("rule")),
                pointId = resultSet.getString("point_id"),
                marginUnit = resultSet.getString("margin_unit")?.let(MarginUnit::valueOf),
            )

            observations += RuleObservation(
                point = point,
                status = RuleStatus.valueOf(resultSet.getString("status")),
                naReason = resultSet.getString("na_reason")?.let(NaReason::valueOf),
                marginValue = resultSet.getBigDecimal("margin_value"),
            )
        }

        return observations
    }

    private fun PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) setNull(index, java.sql.Types.VARCHAR) else setString(index, value)
    }

    private fun PreparedStatement.setNullableBigDecimal(index: Int, value: BigDecimal?) {
        if (value == null) setNull(index, java.sql.Types.NUMERIC) else setBigDecimal(index, value)
    }

    private companion object {
        const val INSERT_REPORT_SQL = """
            INSERT INTO safety_floor_margin_reports (
                id, evaluation_path, call_site, decision_run_id, command_id, verdict,
                rejected_rule, policy_version, runtime_config_version, observation_schema_version,
                divergence, collection_failed, observed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        const val OBSERVATION_COLUMN_COUNT = 9

        const val OBSERVATION_ROW_PLACEHOLDER = "(?, ?, ?, ?, ?, ?, ?, ?, ?)"

        const val INSERT_OBSERVATIONS_PREFIX = """
            INSERT INTO safety_floor_rule_margins (
                id, report_id, rule, point_id, status, na_reason, margin_value, margin_unit, observed_at
            ) VALUES
        """

        const val SELECT_REPORT_SQL =
            "SELECT * FROM safety_floor_margin_reports WHERE id = ?"

        const val SELECT_OBSERVATIONS_SQL =
            "SELECT * FROM safety_floor_rule_margins WHERE report_id = ? ORDER BY rule, point_id"
    }
}
