package me.matsumo.fukurou.trading.evaluation

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.broker.Broker
import me.matsumo.fukurou.trading.config.KillCriterionConfig
import me.matsumo.fukurou.trading.logging.logSafeWarning
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.risk.RiskStateCommandService
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.logging.Logger

/**
 * 評価成績に基づき既存 HARD_HALT へ接続する kill 基準 evaluator。
 *
 * @param config kill 基準設定
 * @param riskStateRepository 現在の HARD_HALT 状態を読む repository
 * @param riskStateCommandService HARD_HALT を有効化する既存 service
 * @param commandEventLog 監査イベント保存先
 * @param broker HARD_HALT 到達時に掃引する broker
 * @param statsSource closed trade 数と PF を返す stats source
 * @param clock throttle と audit timestamp に使う clock
 * @param logger stats 取得失敗の warn 出力先
 */
class KillCriterionEvaluator(
    private val config: KillCriterionConfig = KillCriterionConfig(),
    private val riskStateRepository: RiskStateRepository,
    private val riskStateCommandService: RiskStateCommandService,
    private val commandEventLog: CommandEventLog,
    private val broker: Broker,
    private val statsSource: suspend () -> Result<KillCriterionStats>,
    private val clock: Clock = Clock.systemUTC(),
    private val logger: Logger = Logger.getLogger(KillCriterionEvaluator::class.java.name),
) {

    private var lastEvaluatedAt: Instant? = null

    /**
     * kill 基準を評価し、到達時は audit -> HARD_HALT -> sweep の順で実行する。
     */
    suspend fun evaluate(tickSnapshot: TickSnapshot): Result<Unit> {
        return try {
            val currentRiskState = riskStateRepository.current().getOrThrow()

            if (currentRiskState.state == RiskHaltState.HARD_HALT) {
                return Result.success(Unit)
            }
            if (throttled()) {
                return Result.success(Unit)
            }

            val previousEvaluatedAt = lastEvaluatedAt
            lastEvaluatedAt = Instant.now(clock)
            val stats = statsSource().getOrElse { throwable ->
                logger.logSafeWarning("KillCriterionEvaluator stats source failed.", throwable)

                return Result.success(Unit)
            }
            val breached = breached(stats)

            if (!breached) {
                return Result.success(Unit)
            }

            enforceBreach(stats, tickSnapshot).onFailure {
                lastEvaluatedAt = previousEvaluatedAt
            }
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    private suspend fun enforceBreach(stats: KillCriterionStats, tickSnapshot: TickSnapshot): Result<Unit> {
        return runCatching {
            val reason = breachReason(stats)

            appendBreachAudit(stats, reason)
            riskStateCommandService.setHardHalt(reason, DecisionRunContext.EMPTY).getOrThrow()
            broker.sweepHardHalt(reason, tickSnapshot).getOrThrow()
        }
    }

    private fun throttled(): Boolean {
        val previousEvaluatedAt = lastEvaluatedAt ?: return false
        val elapsed = Duration.between(previousEvaluatedAt, Instant.now(clock))

        return elapsed < KILL_EVALUATION_THROTTLE
    }

    private fun breached(stats: KillCriterionStats): Boolean {
        val profitFactor = stats.profitFactor ?: return false
        val enoughTrades = stats.closedTrades >= config.minClosedTrades
        val pfBelowThreshold = profitFactor < config.minProfitFactor

        return enoughTrades && pfBelowThreshold
    }

    private suspend fun appendBreachAudit(stats: KillCriterionStats, reason: String) {
        commandEventLog.append(
            CommandEvent(
                decisionRunContext = DecisionRunContext.EMPTY,
                toolName = KILL_CRITERION_TOOL_NAME,
                toolCallId = null,
                clientRequestId = null,
                eventType = CommandEventType.KILL_CRITERION_BREACHED,
                payload = buildBreachPayload(stats, reason),
                occurredAt = Instant.now(clock),
            ),
        ).onFailure { throwable ->
            logger.logSafeWarning("KillCriterionEvaluator breach audit append failed.", throwable)
        }
    }

    private fun breachReason(stats: KillCriterionStats): String {
        val profitFactor = stats.profitFactor?.toPlainString() ?: "null"

        return "評価 kill 基準に到達しました: closedTrades=${stats.closedTrades}, profitFactor=$profitFactor < ${config.minProfitFactor.toPlainString()}"
    }

    private fun buildBreachPayload(stats: KillCriterionStats, reason: String): String {
        return buildJsonObject {
            put("reason", reason)
            put("closedTrades", stats.closedTrades)
            put("minClosedTrades", config.minClosedTrades)
            put("profitFactor", stats.profitFactor?.toPlainString())
            put("minProfitFactor", config.minProfitFactor.toPlainString())
        }.toString()
    }
}

/**
 * kill 基準 audit の論理 tool 名。
 */
private const val KILL_CRITERION_TOOL_NAME = "kill_criterion"

/**
 * kill 基準の in-memory 評価 throttle。
 */
private val KILL_EVALUATION_THROTTLE = Duration.ofMinutes(5)
