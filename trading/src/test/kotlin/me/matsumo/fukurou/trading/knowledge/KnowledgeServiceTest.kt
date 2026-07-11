package me.matsumo.fukurou.trading.knowledge

import me.matsumo.fukurou.trading.activity.DecisionRunSafetyDenial
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyDenialPage
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyDenialQuery
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyDenialReader
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyViolation
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.evaluation.InMemoryEvaluationRepository
import me.matsumo.fukurou.trading.evaluation.InMemoryLlmRunRepository
import me.matsumo.fukurou.trading.runner.SecretRedactor
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SafetyFloor feedback の Knowledge 整形を検証する。 */
class KnowledgeServiceTest {

    @Test
    fun recentLessons_keepsDeclaredAndMachineValuesSeparateAndForwardsBounds() {
        kotlinx.coroutines.runBlocking {
            val now = Instant.parse("2026-07-11T00:00:00Z")
            var capturedQuery: DecisionRunSafetyDenialQuery? = null
            val reader = object : DecisionRunSafetyDenialReader {
                override suspend fun readSafetyDenials(
                    query: DecisionRunSafetyDenialQuery,
                ): Result<DecisionRunSafetyDenialPage> {
                    capturedQuery = query
                    return Result.success(
                        DecisionRunSafetyDenialPage(
                            denials = listOf(
                                DecisionRunSafetyDenial(
                                    invocationId = "run-secret-value",
                                    deniedAt = now.minusSeconds(60),
                                    finalReason = "preview_order_rejected",
                                    decision = null,
                                    intent = null,
                                    falsification = null,
                                    safetyViolation = DecisionRunSafetyViolation(
                                        rule = "EXPECTED_VALUE_GATE",
                                        measuredValue = "secret-value 0.03357778",
                                        limitValue = "0.10",
                                        messageJa = "  EV が不足しています。  ",
                                        createdAt = now.minusSeconds(60),
                                    ),
                                ),
                            ),
                            truncated = false,
                        ),
                    )
                }
            }
            val service = KnowledgeService(
                decisionRepository = InMemoryDecisionRepository(Clock.fixed(now, ZoneOffset.UTC)),
                llmRunRepository = InMemoryLlmRunRepository(),
                evaluationRepository = InMemoryEvaluationRepository(),
                safetyDenialReader = reader,
                clock = Clock.fixed(now, ZoneOffset.UTC),
                redactor = SecretRedactor.fromEnvironment(mapOf("API_SECRET" to "secret-value")),
            )

            val result = service.getRecentLessons(
                KnowledgeRecentLessonsQuery(
                    symbol = TradingSymbol.BTC,
                    limit = 10,
                    lookbackDays = 30,
                ),
            ).getOrThrow()

            val denial = result.safetyFloorDenials.single()

            assertEquals(TradingSymbol.BTC, capturedQuery?.symbol)
            assertEquals(5, capturedQuery?.limit)
            assertEquals(null, denial.priorProposal)
            assertEquals("EXPECTED_VALUE_GATE", denial.machineOutcome.rule)
            assertEquals("[REDACTED] 0.03357778", denial.machineOutcome.measuredValue)
            assertEquals("EV が不足しています。", denial.machineOutcome.messageJa)
            assertEquals("preview_order_rejected", denial.finalReason)
            assertTrue(!result.safetyFloorDenialsTruncated)
        }
    }
}
