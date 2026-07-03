package me.matsumo.fukurou.trading.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.broker.PaperBroker
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.FalsificationSubmission
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.TradePlanDraft
import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import me.matsumo.fukurou.trading.runtime.TradingDatabaseConfig
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import me.matsumo.fukurou.trading.safety.SafetyFloorRule
import me.matsumo.fukurou.trading.safety.SafetyViolation
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.sql.SQLTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * integration test 用 Postgres image。
 */
private const val POSTGRES_IMAGE = "postgres:16-alpine"

/**
 * integration test 用 Hikari pool size。
 */
private const val HIKARI_POOL_SIZE = 4

/**
 * risk_state single row を削除する SQL。
 */
private const val DELETE_RISK_STATE_ROW_SQL = "DELETE FROM risk_state WHERE id = ?"

/**
 * command_event_log table を削除する SQL。
 */
private const val DROP_COMMAND_EVENT_LOG_TABLE_SQL = "DROP TABLE command_event_log"

/**
 * safety_violations 件数を読む SQL。
 */
private const val SELECT_SAFETY_VIOLATION_COUNT_SQL = "SELECT COUNT(*) FROM safety_violations"

/**
 * decision protocol table の件数を読む SQL。
 */
private const val SELECT_DECISION_PROTOCOL_COUNTS_SQL = """
    SELECT
        (SELECT COUNT(*) FROM decisions),
        (SELECT COUNT(*) FROM trade_plans),
        (SELECT COUNT(*) FROM trade_intents),
        (SELECT COUNT(*) FROM falsifications),
        (SELECT COUNT(*) FROM trade_intent_consumptions)
"""

/**
 * NO_TRADE decision の保存内容を読む SQL。
 */
private const val SELECT_NO_TRADE_DECISION_SQL = """
    SELECT
        estimated_win_probability,
        reason_ja,
        missing_data_ja,
        no_trade_conditions_ja
    FROM decisions
    WHERE action = 'NO_TRADE'
"""

/**
 * test 用 reconciler 完了 event の payload。
 */
private const val TEST_RECONCILER_COMPLETED_PAYLOAD = """
    {
        "pass": "loop",
        "state": "completed",
        "lastReconciledAt": "2026-07-02T00:00:00Z",
        "startupFullReconcileCompleted": true,
        "lastMarketDataAt": "2026-07-02T00:00:00Z"
    }
"""

/**
 * test 用 position 行を追加する SQL。
 */
private const val INSERT_TEST_POSITION_SQL = """
    INSERT INTO positions (
        id,
        trade_group_id,
        mode,
        symbol,
        side,
        status,
        opened_at,
        closed_at,
        size_btc,
        average_entry_price_jpy,
        current_price_jpy,
        current_stop_loss_jpy,
        current_take_profit_jpy,
        unrealized_pnl_jpy,
        unrealized_r,
        pyramid_add_count,
        highest_price_since_entry_jpy
    )
    VALUES (
        ?,
        ?,
        ?,
        'BTC',
        'LONG',
        'OPEN',
        ?,
        NULL,
        0.010000000000,
        10000000.00000000,
        10100000.00000000,
        9800000.00000000,
        NULL,
        1000.00000000,
        0.500000,
        0,
        10100000.00000000
    )
"""

/**
 * test 用 order 行を追加する SQL。
 */
private const val INSERT_TEST_ORDER_SQL = """
    INSERT INTO orders (
        id,
        position_id,
        trade_group_id,
        mode,
        symbol,
        side,
        order_type,
        status,
        size_btc,
        limit_price_jpy,
        trigger_price_jpy,
        protective_stop_price_jpy,
        take_profit_price_jpy,
        estimated_win_probability,
        reason_ja,
        created_at,
        updated_at
    )
    VALUES (
        ?,
        ?,
        ?,
        ?,
        'BTC',
        'SELL',
        'STOP',
        'OPEN',
        0.010000000000,
        NULL,
        9800000.00000000,
        NULL,
        NULL,
        NULL,
        'test',
        ?,
        ?
    )
"""

/**
 * test 用 execution 行を追加する SQL。
 */
private const val INSERT_TEST_EXECUTION_SQL = """
    INSERT INTO executions (
        id,
        order_id,
        position_id,
        mode,
        symbol,
        side,
        price_jpy,
        size_btc,
        fee_jpy,
        realized_pnl_jpy,
        liquidity,
        executed_at
    )
    VALUES (
        ?,
        NULL,
        ?,
        ?,
        'BTC',
        'SELL',
        10100000.00000000,
        0.010000000000,
        0.00000000,
        ?,
        'TAKER',
        ?
    )
"""

/**
 * Exposed/Postgres 実装の DB 契約を実 Postgres で検証するテスト。
 */
class PostgresPersistenceIntegrationTest {

    @Test
    fun bootstrap_verify_schema_fails_closed_until_backend_bootstrap_creates_schema() = runPostgresTest {
        val bootstrap = TradingPersistenceBootstrap(database, fixedClock())

        assertTrue(bootstrap.verifySchema().isFailure)

        bootstrap.ensureSchema().getOrThrow()

        assertTrue(bootstrap.verifySchema().isSuccess)
        assertTrue(ExposedRiskStateRepository(database).current().isSuccess)
    }

    @Test
    fun bootstrap_initializes_empty_paper_account() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val runtime = TradingRuntimeFactory.postgres(
            config = tradingDatabaseConfig(),
            clock = fixedClock(),
        )

        try {
            val broker = runtime.broker
            val balance = broker.getBalance().getOrThrow()
            val accountStatus = broker.getAccountStatus().getOrThrow()
            val riskState = ExposedRiskStateRepository(database).current().getOrThrow()

            assertEquals(TradingMode.PAPER, balance.mode)
            assertEquals("100000.00000000", balance.cashJpy)
            assertEquals("100000.00000000", balance.totalEquityJpy)
            assertEquals("100000.00000000", balance.equityPeakJpy)
            assertEquals("100000.00000000", riskState.equityPeak.toPlainString())
            assertEquals("100000.00000000", accountStatus.currentEquityJpy)
            assertEquals("0", accountStatus.todayRealizedPnlJpy)
            assertEquals(0, broker.getPositions().getOrThrow().size)
            assertEquals(0, broker.getOpenOrders().getOrThrow().size)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun runtime_postgres_verifies_schema_without_running_ddl() = runPostgresTest {
        val missingSchemaResult = runCatching {
            TradingRuntimeFactory.postgres(
                config = tradingDatabaseConfig(),
                clock = fixedClock(),
            ).close()
        }

        assertTrue(missingSchemaResult.isFailure)

        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val runtime = TradingRuntimeFactory.postgres(
            config = tradingDatabaseConfig(),
            clock = fixedClock(),
        )

        assertTrue(runtime.broker.getBalance().isSuccess)

        runtime.close()
    }

    @Test
    fun runtime_postgres_reads_reconciler_freshness_from_command_event_log() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()
        ExposedCommandEventLog(database).append(
            CommandEvent(
                decisionRunContext = DecisionRunContext.EMPTY,
                toolName = "protection-reconciler",
                toolCallId = null,
                clientRequestId = null,
                eventType = CommandEventType.RECONCILER_PASS_COMPLETED,
                payload = TEST_RECONCILER_COMPLETED_PAYLOAD,
                occurredAt = fixedInstant(),
            ),
        ).getOrThrow()

        val runtime = TradingRuntimeFactory.postgres(
            config = tradingDatabaseConfig(),
            clock = fixedClock(),
        )

        try {
            val protectionStatus = runtime.broker.getAccountStatus().getOrThrow().protectionStatus

            assertEquals(fixedInstant().toString(), protectionStatus.lastReconciledAt)
            assertEquals(fixedInstant().toString(), protectionStatus.lastMarketDataAt)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun safety_violation_repository_persists_rejection_audit() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        ExposedSafetyViolationRepository(database).append(
            SafetyViolation(
                rule = SafetyFloorRule.MAX_RISK_PER_TRADE,
                messageJa = "test violation",
                measuredValue = "2001",
                limitValue = "2000",
                commandName = "place_order",
                commandId = UUID.randomUUID(),
                orderId = null,
                decisionRunId = "run-1",
                toolCallId = "tool-1",
                clientRequestId = "client-1",
                hardHaltRequired = false,
                payloadJson = """{"rule":"MAX_RISK_PER_TRADE"}""",
                createdAt = fixedInstant(),
            ),
        ).getOrThrow()

        assertEquals(1, selectSafetyViolationCount(database))
    }

    @Test
    fun decision_repository_persists_append_only_protocol_rows() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val repository = ExposedDecisionRepository(database, fixedClock())
        val enterResult = repository.submitDecision(enterDecisionSubmission()).getOrThrow()
        val intentId = requireNotNull(enterResult.tradeIntent?.intentId)

        val initialSnapshot = repository.entryIntentSafetySnapshot(
            intentId = intentId,
            observedAt = fixedInstant(),
            freshnessWindow = Duration.ofSeconds(120),
        ).getOrThrow()
        val falsification = repository.submitFalsification(
            FalsificationSubmission(
                intentId = intentId,
                verdict = FalsificationVerdict.APPROVED,
                llmProvider = "codex",
                reasonJa = "反証観点でも entry を拒否する理由が不足しています。",
            ),
        ).getOrThrow()
        val duplicateFalsification = repository.submitFalsification(
            FalsificationSubmission(
                intentId = intentId,
                verdict = FalsificationVerdict.REJECTED,
                llmProvider = "codex",
                reasonJa = "二重提出です。",
            ),
        )
        val approvedSnapshot = repository.entryIntentSafetySnapshot(
            intentId = intentId,
            observedAt = fixedInstant(),
            freshnessWindow = Duration.ofSeconds(120),
        ).getOrThrow()

        repository.appendIntentConsumption(intentId, UUID.randomUUID(), fixedInstant()).getOrThrow()
        repository.submitDecision(noTradeDecisionSubmission()).getOrThrow()

        val consumedFalsification = repository.submitFalsification(
            FalsificationSubmission(
                intentId = intentId,
                verdict = FalsificationVerdict.REJECTED,
                llmProvider = "codex",
                reasonJa = "消費済み intent です。",
            ),
        )
        val consumedSnapshot = repository.entryIntentSafetySnapshot(
            intentId = intentId,
            observedAt = fixedInstant(),
            freshnessWindow = Duration.ofSeconds(120),
        ).getOrThrow()
        val missingIntentFalsification = repository.submitFalsification(
            FalsificationSubmission(
                intentId = null,
                verdict = FalsificationVerdict.APPROVED,
                llmProvider = "codex",
                reasonJa = "intent がありません。",
            ),
        )
        val unknownIntentFalsification = repository.submitFalsification(
            FalsificationSubmission(
                intentId = UUID.randomUUID(),
                verdict = FalsificationVerdict.APPROVED,
                llmProvider = "codex",
                reasonJa = "未知の intent です。",
            ),
        )
        val overRevisionSubmission = enterDecisionSubmission()
        val overRevisionDecision = repository.submitDecision(
            overRevisionSubmission.copy(
                tradePlan = requireNotNull(overRevisionSubmission.tradePlan).copy(
                    revisionCount = 3,
                ),
            ),
        )
        val counts = selectDecisionProtocolCounts(database)
        val noTradeRow = selectNoTradeDecision(database)

        assertEquals(false, initialSnapshot?.freshApproved)
        assertEquals(FalsificationVerdict.APPROVED, falsification.verdict)
        assertTrue(duplicateFalsification.isFailure)
        assertEquals(true, approvedSnapshot?.freshApproved)
        assertEquals(true, consumedSnapshot?.consumed)
        assertTrue(consumedFalsification.isFailure)
        assertTrue(missingIntentFalsification.isFailure)
        assertTrue(unknownIntentFalsification.isFailure)
        assertTrue(overRevisionDecision.isFailure)
        assertEquals(DecisionProtocolCounts(2, 1, 1, 1, 1), counts)
        assertEquals("0.1200000000", noTradeRow.estimatedWinProbability)
        assertEquals("材料不足のため見送ります。", noTradeRow.reasonJa)
        assertTrue(noTradeRow.missingDataJa.contains("orderbook"))
        assertTrue(noTradeRow.noTradeConditionsJa.contains("出来高が戻るまで待つ"))
    }

    @Test
    fun paper_ledger_repository_filters_rows_by_account_mode() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        insertLedgerRows(
            database = database,
            mode = "PAPER",
            realizedPnlJpy = "10.00000000",
        )
        insertLedgerRows(
            database = database,
            mode = "LIVE",
            realizedPnlJpy = "20.00000000",
        )

        val repository = ExposedPaperLedgerRepository(database)
        val positions = repository.getOpenPositions().getOrThrow()
        val orders = repository.getOpenOrders().getOrThrow()
        val executions = repository.getExecutions().getOrThrow()
        val realizedPnl = repository.getRealizedPnlForDate(LocalDate.of(2026, 7, 2)).getOrThrow()

        assertEquals(listOf(TradingMode.PAPER), positions.map { position -> position.mode })
        assertEquals(listOf(TradingMode.PAPER), orders.map { order -> order.mode })
        assertEquals(listOf(TradingMode.PAPER), executions.map { execution -> execution.mode })
        assertEquals("10.00000000", realizedPnl.toPlainString())
    }

    @Test
    fun paper_execution_persists_market_entry_then_stop_trigger() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val repository = ExposedPaperLedgerRepository(database)
        val decisionRepository = ExposedDecisionRepository(database, fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = ExposedRiskStateRepository(database),
            decisionRepository = decisionRepository,
            marketDataSource = PostgresFakeMarketDataSource,
            clock = fixedClock(),
        )
        val command = approvedPostgresEntryCommand(
            repository = decisionRepository,
            command = postgresEntryCommand(takeProfitPriceJpy = BigDecimal("10500000")),
        )

        broker.placeOrder(command).getOrThrow()
        repository.reconcile(stopTickSnapshot(), me.matsumo.fukurou.trading.broker.FillSimulator()).getOrThrow()

        val balance = broker.getBalance().getOrThrow()
        val positions = broker.getPositions().getOrThrow()
        val openOrders = broker.getOpenOrders().getOrThrow()
        val executions = repository.getExecutions().getOrThrow()

        assertEquals(0, balance.btcQuantity.toBigDecimal().compareTo(BigDecimal.ZERO))
        assertEquals(0, positions.size)
        assertEquals(0, openOrders.size)
        assertEquals(2, executions.size)
        assertEquals(listOf(OrderSide.BUY, OrderSide.SELL), executions.map { execution -> execution.side })
    }

    @Test
    fun paper_execution_returns_existing_place_order_for_same_client_request_id() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val repository = ExposedPaperLedgerRepository(database)
        val decisionRepository = ExposedDecisionRepository(database, fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = ExposedRiskStateRepository(database),
            decisionRepository = decisionRepository,
            marketDataSource = PostgresFakeMarketDataSource,
            clock = fixedClock(),
        )
        val command = approvedPostgresEntryCommand(
            repository = decisionRepository,
            command = postgresEntryCommand(
                takeProfitPriceJpy = BigDecimal("10500000"),
                clientRequestId = "entry-1",
            ),
        )

        val firstResult = broker.placeOrder(command).getOrThrow()
        val secondResult = broker.placeOrder(
            postgresEntryCommand(
                takeProfitPriceJpy = BigDecimal("10500000"),
                clientRequestId = "entry-1",
            ),
        ).getOrThrow()
        val executions = repository.getExecutions().getOrThrow()

        assertEquals(firstResult.orderIds, secondResult.orderIds)
        assertEquals(firstResult.positionIds, secondResult.positionIds)
        assertEquals(firstResult.executionIds, secondResult.executionIds)
        assertEquals(1, executions.size)
    }

    @Test
    fun paper_execution_persists_estimated_win_probability_for_resting_entry() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val repository = ExposedPaperLedgerRepository(database)
        val decisionRepository = ExposedDecisionRepository(database, fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = ExposedRiskStateRepository(database),
            decisionRepository = decisionRepository,
            marketDataSource = PostgresFakeMarketDataSource,
            clock = fixedClock(),
        )
        val command = approvedPostgresEntryCommand(
            repository = decisionRepository,
            command = postgresEntryCommand(
                orderType = OrderType.LIMIT,
                priceJpy = BigDecimal("9900000"),
                takeProfitPriceJpy = BigDecimal("10500000"),
                estimatedWinProbability = BigDecimal("0.73"),
            ),
        )

        broker.placeOrder(command).getOrThrow()

        val openOrder = repository.getOpenOrders().getOrThrow().single()

        assertEquals("0.7300000000", openOrder.estimatedWinProbability)
    }

    @Test
    fun paper_execution_persists_virtual_take_profit_then_stop_cancel() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val repository = ExposedPaperLedgerRepository(database)
        val decisionRepository = ExposedDecisionRepository(database, fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = ExposedRiskStateRepository(database),
            decisionRepository = decisionRepository,
            marketDataSource = PostgresFakeMarketDataSource,
            clock = fixedClock(),
        )
        val command = approvedPostgresEntryCommand(
            repository = decisionRepository,
            command = postgresEntryCommand(takeProfitPriceJpy = BigDecimal("10100000")),
        )

        broker.placeOrder(command).getOrThrow()
        repository.reconcile(takeProfitTickSnapshot(), me.matsumo.fukurou.trading.broker.FillSimulator()).getOrThrow()

        val positions = broker.getPositions().getOrThrow()
        val openOrders = broker.getOpenOrders().getOrThrow()
        val executions = repository.getExecutions().getOrThrow()

        assertEquals(0, positions.size)
        assertEquals(0, openOrders.size)
        assertEquals(2, executions.size)
    }

    @Test
    fun paper_reconcile_rounds_atr_trailing_stop_down_to_tick_size() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val repository = ExposedPaperLedgerRepository(database)
        val decisionRepository = ExposedDecisionRepository(database, fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = ExposedRiskStateRepository(database),
            decisionRepository = decisionRepository,
            marketDataSource = PostgresFakeMarketDataSource,
            clock = fixedClock(),
        )
        val command = approvedPostgresEntryCommand(
            repository = decisionRepository,
            command = postgresEntryCommand(takeProfitPriceJpy = BigDecimal("10500000")),
        )

        broker.placeOrder(command).getOrThrow()
        repository.reconcile(trailingTickSnapshot(), me.matsumo.fukurou.trading.broker.FillSimulator()).getOrThrow()

        val position = broker.getPositions().getOrThrow().single()
        val stopOrder = broker.getOpenOrders().getOrThrow().single()

        assertEquals("10099750.00000000", position.currentStopLossJpy)
        assertEquals("10099750.00000000", stopOrder.triggerPriceJpy)
    }

    @Test
    fun risk_state_repository_current_does_not_create_missing_single_row() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()
        deleteRiskStateRow(database)

        val currentResult = ExposedRiskStateRepository(database).current()

        assertTrue(currentResult.isFailure)
    }

    @Test
    fun risk_state_command_service_rolls_back_when_audit_append_fails() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val repository = ExposedRiskStateRepository(database)
        repository.setHardHalt("manual halt", fixedInstant()).getOrThrow()
        dropCommandEventLogTable(database)

        val resumeResult = ExposedRiskStateCommandService(database, fixedClock()).resume(
            reason = "manual resume",
            decisionRunContext = DecisionRunContext.EMPTY,
        )
        val riskState = repository.current().getOrThrow()

        assertTrue(resumeResult.isFailure)
        assertTrue(riskState.hardHalt)
    }

    @Test
    fun postgres_global_lock_times_out_when_lock_is_held() = runPostgresTest {
        val lock = PostgresGlobalTradingLock(
            dataSource = dataSource,
            clock = fixedClock(),
            lockTimeout = Duration.ofMillis(150),
            lockRetryDelay = Duration.ofMillis(10),
        )

        coroutineScope {
            val holderAcquired = CompletableDeferred<Unit>()
            val releaseHolder = CompletableDeferred<Unit>()
            val holderJob = launch {
                lock.withLock("holder") { lease ->
                    holderAcquired.complete(Unit)
                    releaseHolder.await()
                    lease.owner
                }
            }

            holderAcquired.await()

            val waiterResult = runCatching {
                lock.withLock("waiter") { lease -> lease.owner }
            }

            releaseHolder.complete(Unit)
            holderJob.join()

            val throwable = requireNotNull(waiterResult.exceptionOrNull())

            assertTrue(throwable is SQLTimeoutException)
        }
    }
}

/**
 * Postgres integration test の共有 context。
 *
 * @param container Testcontainers Postgres
 * @param dataSource Postgres DataSource
 * @param database Exposed database
 */
private class PostgresTestContext(
    private val container: FukurouPostgresContainer,
    val dataSource: HikariDataSource,
    val database: ExposedDatabase,
) {
    /**
     * container の接続情報を runtime config に変換する。
     */
    fun tradingDatabaseConfig(): TradingDatabaseConfig {
        return TradingDatabaseConfig(
            url = container.jdbcUrl,
            user = container.username,
            password = container.password,
        )
    }
}

/**
 * decision protocol 各 table の件数。
 *
 * @param decisions decisions 件数
 * @param tradePlans trade_plans 件数
 * @param tradeIntents trade_intents 件数
 * @param falsifications falsifications 件数
 * @param tradeIntentConsumptions trade_intent_consumptions 件数
 */
private data class DecisionProtocolCounts(
    val decisions: Int,
    val tradePlans: Int,
    val tradeIntents: Int,
    val falsifications: Int,
    val tradeIntentConsumptions: Int,
)

/**
 * NO_TRADE decision の保存確認行。
 *
 * @param estimatedWinProbability 推定勝率
 * @param reasonJa 判断理由
 * @param missingDataJa 不足データ JSON
 * @param noTradeConditionsJa 見送り条件 JSON
 */
private data class NoTradeDecisionRow(
    val estimatedWinProbability: String,
    val reasonJa: String,
    val missingDataJa: String,
    val noTradeConditionsJa: String,
)

/**
 * repository test 用 ENTER decision を作る。
 */
private fun enterDecisionSubmission(): DecisionSubmission {
    return DecisionSubmission(
        invocationId = "run-1",
        llmProvider = "claude",
        promptHash = "prompt-hash",
        systemPromptVersion = "system-prompt-v1",
        marketSnapshotId = "snapshot-1",
        action = DecisionAction.ENTER,
        setupTags = listOf("breakout", "trend-follow"),
        estimatedWinProbability = BigDecimal("0.73"),
        expectedRMultiple = BigDecimal("1.80"),
        roundTripCostR = BigDecimal("0.05"),
        toolEvidenceIds = listOf("tool-1", "tool-2"),
        factCheckJson = """{"ticker":true}""",
        selfReviewJson = """{"reasonsNotToTrade":["spread"]}""",
        reasonJa = "ブレイク継続を狙います。",
        missingDataJa = emptyList(),
        noTradeConditionsJa = emptyList(),
        entryIntent = EntryIntentDraft(
            symbol = TradingSymbol.BTC,
            side = OrderSide.BUY,
            orderType = OrderType.MARKET,
            sizeBtc = BigDecimal("0.0050"),
            priceJpy = null,
            protectiveStopPriceJpy = BigDecimal("9700000"),
            takeProfitPriceJpy = BigDecimal("10500000"),
        ),
        tradePlan = TradePlanDraft(
            parentTradePlanId = null,
            revisionCount = 0,
            symbol = TradingSymbol.BTC,
            thesisJa = "1時間足の上昇継続に乗る。",
            invalidationConditionsJa = listOf("直近安値割れ", "出来高急減"),
            targetPriceJpy = BigDecimal("10500000"),
            timeStopAt = fixedInstant().plusSeconds(3600),
            setupTags = listOf("breakout", "trend-follow"),
        ),
    )
}

/**
 * repository test 用 NO_TRADE decision を作る。
 */
private fun noTradeDecisionSubmission(): DecisionSubmission {
    return DecisionSubmission(
        invocationId = "run-2",
        llmProvider = "claude",
        promptHash = "prompt-hash",
        systemPromptVersion = "system-prompt-v1",
        marketSnapshotId = "snapshot-2",
        action = DecisionAction.NO_TRADE,
        setupTags = emptyList(),
        estimatedWinProbability = BigDecimal("0.12"),
        expectedRMultiple = null,
        roundTripCostR = null,
        toolEvidenceIds = listOf("tool-3"),
        factCheckJson = """{"ticker":true}""",
        selfReviewJson = """{"reasonsNotToTrade":["出来高不足"]}""",
        reasonJa = "材料不足のため見送ります。",
        missingDataJa = listOf("orderbook"),
        noTradeConditionsJa = listOf("出来高が戻るまで待つ"),
        entryIntent = null,
        tradePlan = null,
    )
}

private suspend fun approvedPostgresEntryCommand(
    repository: DecisionRepository,
    command: PlaceOrderCommand,
): PlaceOrderCommand {
    val decisionResult = repository.submitDecision(entryDecisionSubmission(command)).getOrThrow()
    val intentId = requireNotNull(decisionResult.tradeIntent?.intentId)

    repository.submitFalsification(
        FalsificationSubmission(
            intentId = intentId,
            verdict = FalsificationVerdict.APPROVED,
            llmProvider = "codex",
            reasonJa = "integration test では反証後に承認します。",
        ),
    ).getOrThrow()

    return command.copy(intentId = intentId)
}

private fun entryDecisionSubmission(command: PlaceOrderCommand): DecisionSubmission {
    return DecisionSubmission(
        invocationId = "run-entry",
        llmProvider = "claude",
        promptHash = "prompt-hash",
        systemPromptVersion = "system-prompt-v1",
        marketSnapshotId = "snapshot-entry",
        action = DecisionAction.ENTER,
        setupTags = listOf("integration-entry"),
        estimatedWinProbability = command.estimatedWinProbability,
        expectedRMultiple = BigDecimal("1.80"),
        roundTripCostR = BigDecimal("0.05"),
        toolEvidenceIds = listOf("tool-entry"),
        factCheckJson = """{"ticker":true}""",
        selfReviewJson = """{"reasonsNotToTrade":[]}""",
        reasonJa = command.reasonJa,
        missingDataJa = emptyList(),
        noTradeConditionsJa = emptyList(),
        entryIntent = EntryIntentDraft(
            symbol = command.symbol,
            side = command.side,
            orderType = command.orderType,
            sizeBtc = command.sizeBtc,
            priceJpy = command.priceJpy,
            protectiveStopPriceJpy = command.protectiveStopPriceJpy,
            takeProfitPriceJpy = command.takeProfitPriceJpy,
        ),
        tradePlan = TradePlanDraft(
            parentTradePlanId = null,
            revisionCount = 0,
            symbol = command.symbol,
            thesisJa = "integration entry の仮説です。",
            invalidationConditionsJa = listOf("保護 stop 到達"),
            targetPriceJpy = command.takeProfitPriceJpy,
            timeStopAt = fixedInstant().plusSeconds(3600),
            setupTags = listOf("integration-entry"),
        ),
    )
}

private fun postgresEntryCommand(
    orderType: OrderType = OrderType.MARKET,
    priceJpy: BigDecimal? = null,
    takeProfitPriceJpy: BigDecimal,
    estimatedWinProbability: BigDecimal = BigDecimal("0.95"),
    clientRequestId: String? = null,
): PlaceOrderCommand {
    return PlaceOrderCommand(
        commandId = UUID.randomUUID(),
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = orderType,
        sizeBtc = BigDecimal("0.0050"),
        priceJpy = priceJpy,
        tradeGroupId = null,
        protectiveStopPriceJpy = BigDecimal("9700000"),
        takeProfitPriceJpy = takeProfitPriceJpy,
        estimatedWinProbability = estimatedWinProbability,
        reasonJa = "integration entry",
        auditContext = PaperTradeAuditContext.EMPTY.copy(clientRequestId = clientRequestId),
    )
}

private fun stopTickSnapshot(): TickSnapshot {
    return TickSnapshot(
        symbol = "BTC",
        observedAt = fixedInstant(),
        lastPrice = "9700000",
        bidPrice = "9690000",
        askPrice = "9700000",
        symbolRules = postgresSymbolRules(),
    )
}

private fun takeProfitTickSnapshot(): TickSnapshot {
    return TickSnapshot(
        symbol = "BTC",
        observedAt = fixedInstant(),
        lastPrice = "10100000",
        bidPrice = "10100000",
        askPrice = "10110000",
        symbolRules = postgresSymbolRules(),
    )
}

private fun trailingTickSnapshot(): TickSnapshot {
    return TickSnapshot(
        symbol = "BTC",
        observedAt = fixedInstant(),
        lastPrice = "10100000",
        bidPrice = "10100000",
        askPrice = "10110000",
        symbolRules = trailingSymbolRules(),
        atr14Jpy = "123.456789",
    )
}

private fun postgresSymbolRules(): SymbolRules {
    return SymbolRules(
        symbol = "BTC",
        minOrderSize = "0.0001",
        sizeStep = "0.0001",
        tickSize = "1",
        takerFee = "0.0005",
        makerFee = "-0.0001",
    )
}

private fun trailingSymbolRules(): SymbolRules {
    return SymbolRules(
        symbol = "BTC",
        minOrderSize = "0.0001",
        sizeStep = "0.0001",
        tickSize = "10",
        takerFee = "0.0005",
        makerFee = "-0.0001",
    )
}

/**
 * Postgres paper execution test 用 fake market data。
 */
private object PostgresFakeMarketDataSource : MarketDataSource {
    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        return Result.success(
            Ticker(
                symbol = symbol.apiSymbol,
                last = "10000000",
                bid = "9990000",
                ask = "10000000",
                high = "10100000",
                low = "9900000",
                volume = "1.0",
                timestamp = fixedInstant().toString(),
            ),
        )
    }

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> {
        return Result.success(emptyList())
    }

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun getTrades(symbol: TradingSymbol, limit: Int): Result<List<RecentTrade>> {
        return Result.success(emptyList())
    }

    override suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules> {
        return Result.success(postgresSymbolRules())
    }
}

/**
 * fukurou integration test 用 Postgres container。
 */
private class FukurouPostgresContainer : PostgreSQLContainer<FukurouPostgresContainer>(POSTGRES_IMAGE)

/**
 * Docker が利用できる場合だけ Postgres integration test を実行する。
 */
private fun runPostgresTest(
    block: suspend PostgresTestContext.() -> Unit,
) = runBlocking {
    if (!isDockerAvailable()) {
        println("Skipping Postgres integration test because Docker is unavailable.")
        return@runBlocking
    }

    val container = FukurouPostgresContainer()
    container.start()

    try {
        val dataSource = createDataSource(container)

        try {
            val database = ExposedDatabase.connect(dataSource)
            val context = PostgresTestContext(
                container = container,
                dataSource = dataSource,
                database = database,
            )

            context.block()
        } finally {
            dataSource.close()
        }
    } finally {
        container.stop()
    }
}

/**
 * Docker daemon が利用可能かを返す。
 */
private fun isDockerAvailable(): Boolean {
    return runCatching {
        DockerClientFactory.instance().isDockerAvailable
    }.getOrDefault(false)
}

/**
 * test container 用 DataSource を作る。
 */
private fun createDataSource(container: FukurouPostgresContainer): HikariDataSource {
    val hikariConfig = HikariConfig().apply {
        jdbcUrl = container.jdbcUrl
        username = container.username
        password = container.password
        maximumPoolSize = HIKARI_POOL_SIZE
    }

    return HikariDataSource(hikariConfig)
}

/**
 * risk_state single row を削除する。
 */
private fun deleteRiskStateRow(database: ExposedDatabase) {
    exposedTransaction(database) {
        jdbcConnection().prepareStatement(DELETE_RISK_STATE_ROW_SQL).use { statement ->
            statement.setInt(1, RISK_STATE_SINGLE_ROW_ID)
            statement.executeUpdate()
        }
    }
}

/**
 * command_event_log table を削除する。
 */
private fun dropCommandEventLogTable(database: ExposedDatabase) {
    exposedTransaction(database) {
        jdbcConnection().prepareStatement(DROP_COMMAND_EVENT_LOG_TABLE_SQL).use { statement ->
            statement.executeUpdate()
        }
    }
}

/**
 * safety_violations 件数を読む。
 */
private fun selectSafetyViolationCount(database: ExposedDatabase): Int {
    return exposedTransaction(database) {
        jdbcConnection().prepareStatement(SELECT_SAFETY_VIOLATION_COUNT_SQL).use { statement ->
            statement.executeQuery().use { resultSet ->
                require(resultSet.next()) { "safety_violations count did not return a row." }

                resultSet.getInt(1)
            }
        }
    }
}

/**
 * decision protocol 各 table の件数を読む。
 */
private fun selectDecisionProtocolCounts(database: ExposedDatabase): DecisionProtocolCounts {
    return exposedTransaction(database) {
        jdbcConnection().prepareStatement(SELECT_DECISION_PROTOCOL_COUNTS_SQL).use { statement ->
            statement.executeQuery().use { resultSet ->
                require(resultSet.next()) { "decision protocol counts did not return a row." }

                DecisionProtocolCounts(
                    decisions = resultSet.getInt(1),
                    tradePlans = resultSet.getInt(2),
                    tradeIntents = resultSet.getInt(3),
                    falsifications = resultSet.getInt(4),
                    tradeIntentConsumptions = resultSet.getInt(5),
                )
            }
        }
    }
}

/**
 * NO_TRADE decision の保存内容を読む。
 */
private fun selectNoTradeDecision(database: ExposedDatabase): NoTradeDecisionRow {
    return exposedTransaction(database) {
        jdbcConnection().prepareStatement(SELECT_NO_TRADE_DECISION_SQL).use { statement ->
            statement.executeQuery().use { resultSet ->
                require(resultSet.next()) { "NO_TRADE decision did not return a row." }

                NoTradeDecisionRow(
                    estimatedWinProbability = resultSet.getBigDecimal("estimated_win_probability").toPlainString(),
                    reasonJa = resultSet.getString("reason_ja"),
                    missingDataJa = resultSet.getString("missing_data_ja"),
                    noTradeConditionsJa = resultSet.getString("no_trade_conditions_ja"),
                )
            }
        }
    }
}

/**
 * mode filter 検証用 ledger 行を追加する。
 */
private fun insertLedgerRows(
    database: ExposedDatabase,
    mode: String,
    realizedPnlJpy: String,
) {
    val positionId = UUID.randomUUID()
    val tradeGroupId = UUID.randomUUID()

    exposedTransaction(database) {
        insertTestPosition(positionId, tradeGroupId, mode)
        insertTestOrder(positionId, tradeGroupId, mode)
        insertTestExecution(positionId, mode, realizedPnlJpy)
    }
}

/**
 * mode filter 検証用 position 行を追加する。
 */
private fun JdbcTransaction.insertTestPosition(
    positionId: UUID,
    tradeGroupId: UUID,
    mode: String,
) {
    jdbcConnection().prepareStatement(INSERT_TEST_POSITION_SQL).use { statement ->
        statement.setObject(1, positionId)
        statement.setObject(2, tradeGroupId)
        statement.setString(3, mode)
        statement.setLong(4, fixedInstant().toEpochMilli())
        statement.executeUpdate()
    }
}

/**
 * mode filter 検証用 order 行を追加する。
 */
private fun JdbcTransaction.insertTestOrder(
    positionId: UUID,
    tradeGroupId: UUID,
    mode: String,
) {
    jdbcConnection().prepareStatement(INSERT_TEST_ORDER_SQL).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, positionId)
        statement.setObject(3, tradeGroupId)
        statement.setString(4, mode)
        statement.setLong(5, fixedInstant().toEpochMilli())
        statement.setLong(6, fixedInstant().toEpochMilli())
        statement.executeUpdate()
    }
}

/**
 * mode filter 検証用 execution 行を追加する。
 */
private fun JdbcTransaction.insertTestExecution(
    positionId: UUID,
    mode: String,
    realizedPnlJpy: String,
) {
    jdbcConnection().prepareStatement(INSERT_TEST_EXECUTION_SQL).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, positionId)
        statement.setString(3, mode)
        statement.setBigDecimal(4, realizedPnlJpy.toBigDecimal())
        statement.setLong(5, fixedInstant().toEpochMilli())
        statement.executeUpdate()
    }
}

/**
 * Postgres integration test 用の固定時刻を返す。
 */
private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T00:00:00Z")
}

/**
 * Postgres integration test 用の固定 clock を返す。
 */
private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}
