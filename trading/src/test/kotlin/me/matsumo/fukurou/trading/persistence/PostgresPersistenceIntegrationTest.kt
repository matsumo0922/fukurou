package me.matsumo.fukurou.trading.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.broker.ClosePositionCommand
import me.matsumo.fukurou.trading.broker.FillSimulator
import me.matsumo.fukurou.trading.broker.InMemoryPaperLedgerRepository
import me.matsumo.fukurou.trading.broker.PaperBroker
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.config.RuntimeConfigCatalog
import me.matsumo.fukurou.trading.config.RuntimeConfigResolver
import me.matsumo.fukurou.trading.config.calculateRuntimeConfigHash
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationFinish
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationOutcome
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRejectionReason
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.FalsificationSubmission
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.decision.TradePlanDraft
import me.matsumo.fukurou.trading.domain.AccountSnapshot
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
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotReason
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotRecord
import me.matsumo.fukurou.trading.evaluation.EvaluationMath
import me.matsumo.fukurou.trading.evaluation.EvaluationPeriod
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LlmRunFinish
import me.matsumo.fukurou.trading.evaluation.LlmRunStart
import me.matsumo.fukurou.trading.evaluation.toEquitySnapshotRecord
import me.matsumo.fukurou.trading.feed.StableFeedCursor
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.runtime.TradingDatabaseConfig
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import me.matsumo.fukurou.trading.safety.SafetyFloorRule
import me.matsumo.fukurou.trading.safety.SafetyViolation
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.sql.SQLTimeoutException
import java.sql.Types
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
 * risk_state の state column を削除する SQL。
 */
private const val DROP_RISK_STATE_STATE_COLUMN_SQL = "ALTER TABLE risk_state DROP COLUMN state"

/**
 * risk_state の rollback 互換列を意図的に古い状態へ戻す SQL。
 */
private const val FORCE_RISK_STATE_COLUMNS_SQL = """
    UPDATE risk_state
    SET
        state = ?,
        hard_halt = ?
    WHERE id = ?
"""

/**
 * risk_state の state / hard_halt を読む SQL。
 */
private const val SELECT_RISK_STATE_COLUMNS_SQL = """
    SELECT
        state,
        hard_halt
    FROM risk_state
    WHERE id = ?
"""

/**
 * command_event_log table を削除する SQL。
 */
private const val DROP_COMMAND_EVENT_LOG_TABLE_SQL = "DROP TABLE command_event_log"

/**
 * llm_runs table を削除する SQL。
 */
private const val DROP_LLM_RUNS_TABLE_SQL = "DROP TABLE llm_runs"

/**
 * active runtime config version を無効化する SQL。
 */
private const val DEACTIVATE_RUNTIME_CONFIG_VERSIONS_SQL = """
    UPDATE runtime_config_versions
    SET status = 'INACTIVE'
"""

/**
 * runtime config values を削除する SQL。
 */
private const val DELETE_RUNTIME_CONFIG_VALUES_SQL = "DELETE FROM runtime_config_values"

/**
 * runtime config value を key 単位で削除する SQL。
 */
private const val DELETE_RUNTIME_CONFIG_VALUE_SQL = """
    DELETE FROM runtime_config_values
    WHERE config_key = ?
"""

/**
 * equity_snapshots table を削除する SQL。
 */
private const val DROP_EQUITY_SNAPSHOTS_TABLE_SQL = "DROP TABLE equity_snapshots"

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
 * command_event_log 集計 index 件数を読む SQL。
 */
private const val SELECT_COMMAND_EVENT_LOG_INDEX_COUNT_SQL = """
    SELECT COUNT(*)
    FROM pg_indexes
    WHERE schemaname = current_schema()
        AND tablename = 'command_event_log'
        AND indexname IN (
            'idx_command_event_log_ts_decision_run',
            'idx_command_event_log_run_event_tool'
        )
"""

/**
 * orders.client_request_id unique index 件数を読む SQL。
 */
private const val SELECT_ORDERS_CLIENT_REQUEST_ID_INDEX_COUNT_SQL = """
    SELECT COUNT(*)
    FROM pg_indexes
    WHERE schemaname = current_schema()
        AND tablename = 'orders'
        AND indexname = 'idx_orders_client_request_id_unique'
"""

/**
 * LLM 起動予約 index 件数を読む SQL。
 */
private const val SELECT_LLM_LAUNCH_RESERVATION_INDEX_COUNT_SQL = """
    SELECT COUNT(*)
    FROM pg_indexes
    WHERE schemaname = current_schema()
        AND tablename = 'llm_launch_reservations'
        AND indexname IN (
            'idx_llm_launch_reservations_invocation_id_unique',
            'idx_llm_launch_reservations_trigger_key_reserved_at',
            'idx_llm_launch_reservations_status_reserved_at'
        )
"""

/**
 * llm_runs index 件数を読む SQL。
 */
private const val SELECT_LLM_RUN_INDEX_COUNT_SQL = """
    SELECT COUNT(*)
    FROM pg_indexes
    WHERE schemaname = current_schema()
        AND tablename = 'llm_runs'
        AND indexname = 'idx_llm_runs_started_at'
"""

/**
 * equity_snapshots index 件数を読む SQL。
 */
private const val SELECT_EQUITY_SNAPSHOT_INDEX_COUNT_SQL = """
    SELECT COUNT(*)
    FROM pg_indexes
    WHERE schemaname = current_schema()
        AND tablename = 'equity_snapshots'
        AND indexname IN (
            'idx_equity_snapshots_captured_at',
            'idx_equity_snapshots_daily_unique',
            'idx_equity_snapshots_bootstrap_unique'
        )
"""

/**
 * reason 別 equity_snapshots 件数を読む SQL。
 */
private const val SELECT_EQUITY_SNAPSHOT_COUNT_BY_REASON_SQL = """
    SELECT COUNT(*)
    FROM equity_snapshots
    WHERE reason = ?
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
 * position watermark を読む SQL。
 */
private const val SELECT_POSITION_WATERMARK_SQL = """
    SELECT
        highest_price_since_entry_jpy,
        lowest_price_since_entry_jpy
    FROM positions
    WHERE id = ?
"""

/**
 * order の position link を読む SQL。
 */
private const val SELECT_ORDER_POSITION_ID_SQL = """
    SELECT
        position_id
    FROM orders
    WHERE id = ?
"""

/**
 * lowest watermark backfill test 用 position 行を追加する SQL。
 */
private const val INSERT_BACKFILL_POSITION_SQL = """
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
        'PAPER',
        'BTC',
        'LONG',
        ?,
        ?,
        NULL,
        0.010000000000,
        10000000.00000000,
        ?,
        9800000.00000000,
        NULL,
        0.00000000,
        0,
        0,
        10100000.00000000
    )
"""

/**
 * lowest watermark backfill 冪等性検証用の更新 SQL。
 */
private const val UPDATE_LOWEST_WATERMARK_SQL = """
    UPDATE positions
    SET lowest_price_since_entry_jpy = ?
    WHERE id = ?
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
        highest_price_since_entry_jpy,
        lowest_price_since_entry_jpy
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
        10100000.00000000,
        10000000.00000000
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
 * Activity context join 検証用 order 行を追加する SQL。
 */
private const val INSERT_ACTIVITY_CONTEXT_ORDER_SQL = """
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
        decision_run_id,
        created_at,
        updated_at
    )
    VALUES (
        ?,
        ?,
        ?,
        'PAPER',
        'BTC',
        ?,
        ?,
        'FILLED',
        0.010000000000,
        ?,
        ?,
        NULL,
        ?,
        0.950000000000,
        ?,
        ?,
        ?,
        ?
    )
"""

/**
 * Activity context join 検証用 execution 行を追加する SQL。
 */
private const val INSERT_ACTIVITY_CONTEXT_EXECUTION_SQL = """
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
        ?,
        ?,
        'PAPER',
        'BTC',
        ?,
        ?,
        0.010000000000,
        10.00000000,
        ?,
        'TAKER',
        ?
    )
"""

/**
 * Obsidian range query test 用 closed position 行を追加する SQL。
 */
private const val INSERT_OBSIDIAN_CLOSED_POSITION_SQL = """
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
        highest_price_since_entry_jpy,
        lowest_price_since_entry_jpy,
        decision_run_id
    )
    VALUES (
        ?,
        ?,
        'PAPER',
        'BTC',
        'LONG',
        'CLOSED',
        ?,
        ?,
        0.005000000000,
        10000000.00000000,
        10100000.00000000,
        NULL,
        NULL,
        0.00000000,
        0,
        0,
        10100000.00000000,
        10000000.00000000,
        ?
    )
"""

/**
 * Obsidian range query test 用 execution 行を追加する SQL。
 */
private const val INSERT_OBSIDIAN_EXECUTION_SQL = """
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
        ?,
        ?,
        'PAPER',
        'BTC',
        ?,
        ?,
        0.005000000000,
        ?,
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

        repeat(3) {
            bootstrap.ensureSchema().getOrThrow()
        }

        assertTrue(bootstrap.verifySchema().isSuccess)
        assertTrue(ExposedRiskStateRepository(database).current().isSuccess)
        assertEquals(2, selectCommandEventLogIndexCount(database))
        assertEquals(1, selectOrdersClientRequestIdIndexCount(database))
        assertEquals(3, selectLlmLaunchReservationIndexCount(database))
        assertEquals(1, selectLlmRunIndexCount(database))
        assertEquals(3, selectEquitySnapshotIndexCount(database))
        assertEquals(1, selectEquitySnapshotCountByReason(database, EquitySnapshotReason.BOOTSTRAP))
    }

    @Test
    fun bootstrap_createsActiveRuntimeConfigSnapshotFromCatalogDefaults() = runPostgresTest {
        val expectedValues = RuntimeConfigCatalog.runtimeDefaultValues()

        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val snapshot = ExposedRuntimeConfigRepository(database).activeSnapshot().getOrThrow()

        assertEquals(expectedValues, snapshot.values)
        assertEquals(calculateRuntimeConfigHash(expectedValues), snapshot.hash)
    }

    @Test
    fun runtimeConfigBootstrapDoesNotRecreateActiveWhenExistingVersionHasNoActive() = runPostgresTest {
        RuntimeConfigPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()
        deactivateRuntimeConfigVersions(database)

        assertTrue(RuntimeConfigPersistenceBootstrap(database, fixedClock()).ensureSchema().isFailure)
        assertTrue(TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().isFailure)
        assertTrue(ExposedRuntimeConfigRepository(database).activeSnapshot().isFailure)
    }

    @Test
    fun runtimeConfigBootstrapDoesNotBackfillDefaultsWhenActiveValuesAreEmpty() = runPostgresTest {
        RuntimeConfigPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()
        deleteRuntimeConfigValues(database)

        assertTrue(RuntimeConfigPersistenceBootstrap(database, fixedClock()).ensureSchema().isFailure)
        assertTrue(TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().isFailure)
        assertTrue(ExposedRuntimeConfigRepository(database).activeSnapshot().isFailure)
    }

    @Test
    fun runtimeConfigResolveFailsClosedWhenActiveValueIsMissing() = runPostgresTest {
        RuntimeConfigPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()
        deleteRuntimeConfigValue(database, "runner.maxToolCallsPerRun")

        assertTrue(RuntimeConfigPersistenceBootstrap(database, fixedClock()).ensureSchema().isFailure)
        assertTrue(
            RuntimeConfigResolver(
                ExposedRuntimeConfigRepository(database),
            ).resolve(emptyMap()).isFailure,
        )
    }

    @Test
    fun bootstrap_backfills_legacy_hard_halt_state_idempotently() = runPostgresTest {
        val bootstrap = TradingPersistenceBootstrap(database, fixedClock())

        bootstrap.ensureSchema().getOrThrow()
        forceRiskStateColumns(
            database = database,
            state = RiskHaltState.RUNNING,
            hardHalt = true,
        )

        repeat(2) {
            bootstrap.ensureSchema().getOrThrow()
        }

        val columns = selectRiskStateColumns(database)

        assertEquals(RiskHaltState.HARD_HALT, columns.state)
        assertEquals(true, columns.hardHalt)
    }

    @Test
    fun verify_schema_fails_closed_when_risk_state_state_column_is_missing() = runPostgresTest {
        val bootstrap = TradingPersistenceBootstrap(database, fixedClock())

        bootstrap.ensureSchema().getOrThrow()
        dropRiskStateStateColumn(database)

        assertTrue(bootstrap.verifySchema().isFailure)
    }

    @Test
    fun risk_state_command_service_dual_writes_state_and_legacy_hard_halt() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val service = ExposedRiskStateCommandService(database, fixedClock())

        service.setHardHalt("manual hard halt", DecisionRunContext.EMPTY).getOrThrow()
        assertEquals(
            expected = RiskStateColumns(RiskHaltState.HARD_HALT, hardHalt = true),
            actual = selectRiskStateColumns(database),
        )

        service.resume("operator confirmed recovery", DecisionRunContext.EMPTY).getOrThrow()
        assertEquals(
            expected = RiskStateColumns(RiskHaltState.RUNNING, hardHalt = false),
            actual = selectRiskStateColumns(database),
        )

        service.setSoftHalt("manual soft halt", DecisionRunContext.EMPTY).getOrThrow()
        assertEquals(
            expected = RiskStateColumns(RiskHaltState.SOFT_HALT, hardHalt = false),
            actual = selectRiskStateColumns(database),
        )

        service.resume("soft halt recovery", DecisionRunContext.EMPTY).getOrThrow()
        assertEquals(
            expected = RiskStateColumns(RiskHaltState.RUNNING, hardHalt = false),
            actual = selectRiskStateColumns(database),
        )
    }

    @Test
    fun paper_ledger_writer_resynchronizes_legacy_hard_halt_from_state() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        forceRiskStateColumns(
            database = database,
            state = RiskHaltState.RUNNING,
            hardHalt = true,
        )

        val decisionRepository = ExposedDecisionRepository(database, fixedClock())
        val broker = PaperBroker(
            ledgerRepository = ExposedPaperLedgerRepository(database),
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

        val columns = selectRiskStateColumns(database)

        assertEquals(RiskHaltState.RUNNING, columns.state)
        assertEquals(false, columns.hardHalt)
    }

    @Test
    fun bootstrap_equitySnapshotRejectsDuplicateBootstrapRowsInPostgresPath() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val account = ExposedPaperLedgerRepository(database).getAccountSnapshot().getOrThrow()
        val repository = ExposedEquitySnapshotRepository(database)
        val duplicateBootstrap = account.toEquitySnapshotRecord(
            id = UUID.randomUUID(),
            reason = EquitySnapshotReason.BOOTSTRAP,
            tradingDate = LocalDate.of(2026, 7, 2),
            capturedAt = fixedInstant().plusSeconds(60),
        )

        assertTrue(repository.append(duplicateBootstrap).isFailure)
        assertEquals(1, selectEquitySnapshotCountByReason(database, EquitySnapshotReason.BOOTSTRAP))
    }

    @Test
    fun llm_run_repository_roundTripsStartedAndFinishedRunInPostgresPath() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val repository = ExposedLlmRunRepository(database)
        val startedAt = fixedInstant()
        val finishedAt = fixedInstant().plusSeconds(12)
        val start = LlmRunStart(
            invocationId = "postgres-llm-run-round-trip",
            mode = TradingMode.PAPER,
            symbol = TradingSymbol.BTC,
            triggerKind = LlmDaemonTriggerKind.ECONOMIC_EVENT,
            startedAt = startedAt,
        )
        val finish = LlmRunFinish(
            invocationId = start.invocationId,
            mode = start.mode,
            symbol = start.symbol,
            triggerKind = start.triggerKind,
            status = "NO_TRADE",
            startedAt = start.startedAt,
            finishedAt = finishedAt,
            errorMessage = "redacted-error",
        )

        repository.insertRunning(start).getOrThrow()
        repository.finish(finish).getOrThrow()

        val record = requireNotNull(repository.findByInvocationId(start.invocationId).getOrThrow())

        assertEquals(start.invocationId, record.invocationId)
        assertEquals(start.mode, record.mode)
        assertEquals(start.symbol, record.symbol)
        assertEquals(start.triggerKind, record.triggerKind)
        assertEquals(finish.status, record.status)
        assertEquals(startedAt, record.startedAt)
        assertEquals(finishedAt, record.finishedAt)
        assertEquals(finish.errorMessage, record.errorMessage)
    }

    @Test
    fun obsidian_range_queries_filterOrderLimitAndReturnFieldValuesInPostgresPath() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val firstDecisionRepository = ExposedDecisionRepository(database, Clock.fixed(fixedInstant(), ZoneOffset.UTC))
        val firstDecisionResult = firstDecisionRepository
            .submitDecision(enterDecisionSubmission().copy(invocationId = "range-run-1"))
            .getOrThrow()
        firstDecisionRepository.submitFalsification(
            FalsificationSubmission(
                intentId = requireNotNull(firstDecisionResult.tradeIntent?.intentId),
                verdict = FalsificationVerdict.APPROVED,
                llmProvider = "codex",
                reasonJa = "反証観点でも entry を拒否する理由が不足しています。",
            ),
        ).getOrThrow()
        ExposedDecisionRepository(database, Clock.fixed(fixedInstant().plusSeconds(60), ZoneOffset.UTC))
            .submitDecision(noTradeDecisionSubmission().copy(invocationId = "range-run-2"))
            .getOrThrow()
        val llmRunRepository = ExposedLlmRunRepository(database)
        llmRunRepository.finish(
            LlmRunFinish(
                invocationId = "range-llm-1",
                mode = TradingMode.PAPER,
                symbol = TradingSymbol.BTC,
                triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
                status = LLM_RUN_STATUS_FAILED,
                startedAt = fixedInstant(),
                finishedAt = fixedInstant().plusSeconds(1),
                errorMessage = "failure 1",
            ),
        ).getOrThrow()
        llmRunRepository.finish(
            LlmRunFinish(
                invocationId = "range-llm-2",
                mode = TradingMode.PAPER,
                symbol = TradingSymbol.BTC,
                triggerKind = LlmDaemonTriggerKind.ECONOMIC_EVENT,
                status = "NO_TRADE",
                startedAt = fixedInstant().plusSeconds(60),
                finishedAt = fixedInstant().plusSeconds(61),
                errorMessage = null,
            ),
        ).getOrThrow()
        val firstPositionId = UUID.randomUUID()
        val secondPositionId = UUID.randomUUID()
        insertObsidianClosedPositionRows(
            database = database,
            positionId = firstPositionId,
            decisionRunId = "range-run-1",
            closedAt = fixedInstant().plusSeconds(10),
            realizedPnlJpy = BigDecimal("440"),
        )
        insertObsidianClosedPositionRows(
            database = database,
            positionId = secondPositionId,
            decisionRunId = "range-run-2",
            closedAt = fixedInstant().plusSeconds(70),
            realizedPnlJpy = BigDecimal("-120"),
        )

        val decisionRepository = ExposedDecisionRepository(database, fixedClock())
        val decisions = decisionRepository.findDecisionsCreatedBetween(
            from = fixedInstant().minusSeconds(1),
            toExclusive = fixedInstant().plusSeconds(120),
            limit = 2,
        ).getOrThrow()
        val limitedDecisions = decisionRepository.findDecisionsCreatedBetween(
            from = fixedInstant().minusSeconds(1),
            toExclusive = fixedInstant().plusSeconds(120),
            limit = 1,
        ).getOrThrow()
        val llmRuns = llmRunRepository.findRunsStartedBetween(
            from = fixedInstant().minusSeconds(1),
            toExclusive = fixedInstant().plusSeconds(120),
            limit = 2,
        ).getOrThrow()
        val closedPositions = ExposedPaperLedgerRepository(database).findClosedPositionsClosedBetween(
            from = fixedInstant(),
            toExclusive = fixedInstant().plusSeconds(120),
            limit = 2,
        ).getOrThrow()
        val limitedClosedPositions = ExposedPaperLedgerRepository(database).findClosedPositionsClosedBetween(
            from = fixedInstant(),
            toExclusive = fixedInstant().plusSeconds(120),
            limit = 1,
        ).getOrThrow()

        assertEquals(listOf("range-run-1", "range-run-2"), decisions.map { decision -> decision.decision.submission.invocationId })
        assertEquals(listOf("breakout", "trend-follow"), decisions.first().decision.submission.setupTags)
        assertEquals("ブレイク継続を狙います。", decisions.first().decision.submission.reasonJa)
        assertEquals(OrderSide.BUY, decisions.first().tradeIntent?.draft?.side)
        assertEquals(BigDecimal("10500000.00000000"), decisions.first().tradePlan?.draft?.targetPriceJpy)
        assertEquals(FalsificationVerdict.APPROVED, decisions.first().falsification?.verdict)
        assertEquals("反証観点でも entry を拒否する理由が不足しています。", decisions.first().falsification?.reasonJa)
        assertEquals(listOf("range-run-2"), limitedDecisions.map { decision -> decision.decision.submission.invocationId })
        assertEquals(listOf("range-llm-1", "range-llm-2"), llmRuns.map { run -> run.invocationId })
        assertEquals(
            listOf("range-llm-2"),
            llmRunRepository.findRunsStartedBetween(
                from = fixedInstant().minusSeconds(1),
                toExclusive = fixedInstant().plusSeconds(120),
                limit = 1,
            ).getOrThrow().map { run -> run.invocationId },
        )
        assertEquals(LLM_RUN_STATUS_FAILED, llmRuns.first().status)
        assertEquals(listOf(firstPositionId.toString(), secondPositionId.toString()), closedPositions.map { position -> position.position.positionId })
        assertEquals("range-run-1", closedPositions.first().decisionRunId)
        assertEquals("10100000.00000000", closedPositions.first().executions.last().priceJpy)
        assertEquals(listOf(secondPositionId.toString()), limitedClosedPositions.map { position -> position.position.positionId })
    }

    @Test
    fun verify_schema_fails_closed_when_new_primary_record_tables_are_missing() = runPostgresTest {
        val bootstrap = TradingPersistenceBootstrap(database, fixedClock())

        bootstrap.ensureSchema().getOrThrow()
        dropLlmRunsTable(database)

        assertTrue(bootstrap.verifySchema().isFailure)

        bootstrap.ensureSchema().getOrThrow()
        dropEquitySnapshotsTable(database)

        assertTrue(bootstrap.verifySchema().isFailure)
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
    fun bootstrap_backfills_open_position_lowest_watermark_idempotently() = runPostgresTest {
        val bootstrap = TradingPersistenceBootstrap(database, fixedClock())
        val openPositionId = UUID.randomUUID()
        val closedPositionId = UUID.randomUUID()

        bootstrap.ensureSchema().getOrThrow()
        exposedTransaction(database) {
            insertBackfillPosition(
                positionId = openPositionId,
                status = "OPEN",
                currentPriceJpy = BigDecimal("9900000"),
            )
            insertBackfillPosition(
                positionId = closedPositionId,
                status = "CLOSED",
                currentPriceJpy = BigDecimal("9800000"),
            )
        }

        bootstrap.ensureSchema().getOrThrow()
        exposedTransaction(database) {
            updateLowestWatermark(openPositionId, BigDecimal("9700000"))
        }
        bootstrap.ensureSchema().getOrThrow()

        val openWatermark = selectPositionWatermark(database, openPositionId)
        val closedWatermark = selectPositionWatermark(database, closedPositionId)

        assertEquals("9700000.00000000", openWatermark.lowestPriceSinceEntryJpy)
        assertNull(closedWatermark.lowestPriceSinceEntryJpy)
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
    fun llm_launch_reservation_allowsOnlyOneConcurrentRunningReservation() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val repository = ExposedLlmLaunchReservationRepository(database)
        val config = LlmRunnerConfig()

        coroutineScope {
            val firstReservation = async {
                repository.tryReserve(llmLaunchReservationRequest("daemon-run-1", config)).getOrThrow()
            }
            val secondReservation = async {
                repository.tryReserve(llmLaunchReservationRequest("daemon-run-2", config)).getOrThrow()
            }
            val outcomes = listOf(firstReservation.await(), secondReservation.await())
            val reservedCount = outcomes.filterIsInstance<LlmLaunchReservationOutcome.Reserved>().size
            val rejectedReasons = outcomes
                .filterIsInstance<LlmLaunchReservationOutcome.Rejected>()
                .map { outcome -> outcome.reason }

            assertEquals(1, reservedCount)
            assertEquals(listOf(LlmLaunchReservationRejectionReason.CONCURRENT_INVOCATION), rejectedReasons)
        }
    }

    @Test
    fun llm_launch_reservation_enforcesDailyCapInPostgresPath() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val repository = ExposedLlmLaunchReservationRepository(database)
        val config = LlmRunnerConfig(maxInvocationsPerDay = 1)

        repository.tryReserve(llmLaunchReservationRequest("daemon-run-1", config)).getOrThrow()
        repository.finish(
            LlmLaunchReservationFinish(
                invocationId = "daemon-run-1",
                status = LlmLaunchReservationStatus.FINISHED,
                reason = "NO_TRADE_DECISION",
                finishedAt = fixedInstant().plusSeconds(1),
            ),
        ).getOrThrow()
        val secondOutcome = repository.tryReserve(
            llmLaunchReservationRequest(
                invocationId = "daemon-run-2",
                config = config,
                reservedAt = fixedInstant().plus(Duration.ofHours(2)),
            ),
        ).getOrThrow()

        assertEquals(
            LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_DAY),
            secondOutcome,
        )
    }

    @Test
    fun llm_launch_reservation_reportsFreshRunningReservationInPostgresPath() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val repository = ExposedLlmLaunchReservationRepository(database)

        repository.tryReserve(llmLaunchReservationRequest("daemon-run-running", LlmRunnerConfig())).getOrThrow()
        val runningExists = repository.hasFreshRunningReservation(fixedInstant().minus(Duration.ofMinutes(30)))
            .getOrThrow()
        repository.finish(
            LlmLaunchReservationFinish(
                invocationId = "daemon-run-running",
                status = LlmLaunchReservationStatus.FINISHED,
                reason = "NO_TRADE_DECISION",
                finishedAt = fixedInstant().plusSeconds(1),
            ),
        ).getOrThrow()
        val runningExistsAfterFinish = repository.hasFreshRunningReservation(fixedInstant().minus(Duration.ofMinutes(30)))
            .getOrThrow()

        assertEquals(true, runningExists)
        assertEquals(false, runningExistsAfterFinish)
    }

    @Test
    fun llm_launch_reservation_treatsReflectionAsLowPriorityInPostgresPath() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val repository = ExposedLlmLaunchReservationRepository(database)
        val reflectionOutcome = repository.tryReserve(
            llmLaunchReservationRequest(
                invocationId = "reflection-run",
                config = LlmRunnerConfig(),
                triggerKind = LlmDaemonTriggerKind.REFLECTION,
            ),
        ).getOrThrow()
        val reflectionOnlyRunning = repository.hasFreshRunningReservation(fixedInstant().minus(Duration.ofMinutes(30)))
            .getOrThrow()
        val tradingOutcome = repository.tryReserve(
            llmLaunchReservationRequest(
                invocationId = "trading-run",
                config = LlmRunnerConfig(),
                triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
            ),
        ).getOrThrow()
        val reflectionBlockedOutcome = repository.tryReserve(
            llmLaunchReservationRequest(
                invocationId = "reflection-run-2",
                config = LlmRunnerConfig(),
                triggerKind = LlmDaemonTriggerKind.REFLECTION,
            ),
        ).getOrThrow()

        assertEquals(LlmLaunchReservationOutcome.Reserved("reflection-run"), reflectionOutcome)
        assertEquals(false, reflectionOnlyRunning)
        assertEquals(LlmLaunchReservationOutcome.Reserved("trading-run"), tradingOutcome)
        assertEquals(
            LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.CONCURRENT_INVOCATION),
            reflectionBlockedOutcome,
        )
    }

    @Test
    fun llm_launch_reservation_ignoresDaemonLaunchAuditForCapInPostgresPath() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        ExposedCommandEventLog(database).append(
            CommandEvent(
                decisionRunContext = DecisionRunContext(
                    decisionRunId = "daemon-reservation",
                    llmProvider = "claude",
                    promptHash = "hash",
                    systemPromptVersion = "system-prompt-v1",
                    marketSnapshotId = "snapshot",
                ),
                toolName = "llm-daemon-scheduler",
                toolCallId = null,
                clientRequestId = "flat-heartbeat",
                eventType = CommandEventType.DAEMON_TRIGGER_LAUNCHED,
                payload = "{}",
                occurredAt = fixedInstant(),
            ),
        ).getOrThrow()
        val repository = ExposedLlmLaunchReservationRepository(database)
        val config = LlmRunnerConfig(maxInvocationsPerHour = 1, maxInvocationsPerDay = 10)
        val outcome = repository.tryReserve(llmLaunchReservationRequest("daemon-run-1", config)).getOrThrow()

        assertEquals(LlmLaunchReservationOutcome.Reserved("daemon-run-1"), outcome)
    }

    @Test
    fun command_event_log_readsRawFeedWithLimitFilterAndDescendingOrderInPostgresPath() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val eventLog = ExposedCommandEventLog(database)
        eventLog.append(commandEvent("tool-old", CommandEventType.TOOL_CALL_COMPLETED, fixedInstant())).getOrThrow()
        eventLog.append(
            commandEvent(
                toolName = "daemon-new",
                eventType = CommandEventType.DAEMON_TRIGGER_LAUNCHED,
                occurredAt = fixedInstant().plusSeconds(1),
            ),
        ).getOrThrow()
        eventLog.append(
            commandEvent(
                toolName = "daemon-latest",
                eventType = CommandEventType.DAEMON_TRIGGER_LAUNCHED,
                occurredAt = fixedInstant().plusSeconds(2),
                runtimeConfigVersionId = "runtime-version-1",
                runtimeConfigHash = "runtime-hash-1",
            ),
        ).getOrThrow()

        val allEvents = eventLog.findEvents(limit = 2, eventType = null).getOrThrow()
        val filteredEvents = eventLog.findEvents(
            limit = 1,
            eventType = CommandEventType.DAEMON_TRIGGER_LAUNCHED,
        ).getOrThrow()
        val excludedEvents = eventLog.findEvents(
            limit = 10,
            eventType = null,
            excludeEventTypes = setOf(CommandEventType.DAEMON_TRIGGER_LAUNCHED),
        ).getOrThrow()

        assertEquals(listOf("daemon-latest", "daemon-new"), allEvents.map { event -> event.toolName })
        assertEquals(listOf("daemon-latest"), filteredEvents.map { event -> event.toolName })
        assertEquals(CommandEventType.DAEMON_TRIGGER_LAUNCHED, filteredEvents.single().eventType)
        assertEquals("runtime-version-1", filteredEvents.single().decisionRunContext.runtimeConfigVersionId)
        assertEquals("runtime-hash-1", filteredEvents.single().decisionRunContext.runtimeConfigHash)
        assertEquals(listOf("tool-old"), excludedEvents.map { event -> event.toolName })
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
    fun decision_repository_enforces_trade_plan_revision_lineage() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val repository = ExposedDecisionRepository(database, fixedClock())
        val initialPlan = requireNotNull(repository.submitDecision(enterDecisionSubmission()).getOrThrow().tradePlan)
        val revisionOne = requireNotNull(
            repository.submitDecision(
                tradePlanRevisionSubmission(
                    parentTradePlanId = initialPlan.tradePlanId,
                    revisionCount = 1,
                ),
            ).getOrThrow().tradePlan,
        )
        val repeatedZeroDecision = repository.submitDecision(
            tradePlanRevisionSubmission(
                parentTradePlanId = revisionOne.tradePlanId,
                revisionCount = 0,
            ),
        )
        val revisionTwo = requireNotNull(
            repository.submitDecision(
                tradePlanRevisionSubmission(
                    parentTradePlanId = revisionOne.tradePlanId,
                    revisionCount = 2,
                ),
            ).getOrThrow().tradePlan,
        )
        val overLimitDecision = repository.submitDecision(
            tradePlanRevisionSubmission(
                parentTradePlanId = revisionTwo.tradePlanId,
                revisionCount = 3,
            ),
        )
        val repeatedTwoDecision = repository.submitDecision(
            tradePlanRevisionSubmission(
                parentTradePlanId = revisionTwo.tradePlanId,
                revisionCount = 2,
            ),
        )
        val missingParentDecision = repository.submitDecision(
            tradePlanRevisionSubmission(
                parentTradePlanId = null,
                revisionCount = 1,
            ),
        )
        val unknownParentDecision = repository.submitDecision(
            tradePlanRevisionSubmission(
                parentTradePlanId = UUID.randomUUID(),
                revisionCount = 1,
            ),
        )
        val counts = selectDecisionProtocolCounts(database)

        assertTrue(repeatedZeroDecision.isFailure)
        assertTrue(overLimitDecision.isFailure)
        assertTrue(repeatedTwoDecision.isFailure)
        assertTrue(missingParentDecision.isFailure)
        assertTrue(unknownParentDecision.isFailure)
        assertEquals(DecisionProtocolCounts(3, 3, 1, 0, 0), counts)
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
    fun paper_ledger_repository_reads_recent_executions_with_limit_and_account_mode() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val oldestPaperPositionId = insertLedgerRows(
            database = database,
            mode = "PAPER",
            realizedPnlJpy = "10.00000000",
            executedAt = fixedInstant().plusSeconds(60),
        )
        val newestLivePositionId = insertLedgerRows(
            database = database,
            mode = "LIVE",
            realizedPnlJpy = "99.00000000",
            executedAt = fixedInstant().plusSeconds(240),
        )
        val middlePaperPositionId = insertLedgerRows(
            database = database,
            mode = "PAPER",
            realizedPnlJpy = "20.00000000",
            executedAt = fixedInstant().plusSeconds(120),
        )
        val newestPaperPositionId = insertLedgerRows(
            database = database,
            mode = "PAPER",
            realizedPnlJpy = "30.00000000",
            executedAt = fixedInstant().plusSeconds(180),
        )

        val repository = ExposedPaperLedgerRepository(database)
        val executions = repository.getRecentExecutions(2).getOrThrow()

        assertEquals(2, executions.size)
        assertEquals(
            listOf(newestPaperPositionId.toString(), middlePaperPositionId.toString()),
            executions.map { execution -> execution.positionId },
        )
        assertEquals(
            listOf(TradingMode.PAPER, TradingMode.PAPER),
            executions.map { execution -> execution.mode },
        )
        assertEquals(
            listOf("30.00000000", "20.00000000"),
            executions.map { execution -> execution.realizedPnlJpy },
        )
        assertEquals(
            listOf(
                fixedInstant().plusSeconds(180).toString(),
                fixedInstant().plusSeconds(120).toString(),
            ),
            executions.map { execution -> execution.executedAt },
        )
        assertTrue(
            executions.none { execution -> execution.positionId == oldestPaperPositionId.toString() },
        )
        assertTrue(
            executions.none { execution -> execution.positionId == newestLivePositionId.toString() },
        )
    }

    @Test
    fun paper_watermark_scenario_matches_in_memory_and_postgres_repositories() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val postgresDecisionRepository = ExposedDecisionRepository(database, fixedClock())
        val postgresBroker = PaperBroker(
            ledgerRepository = ExposedPaperLedgerRepository(database),
            riskStateRepository = ExposedRiskStateRepository(database),
            decisionRepository = postgresDecisionRepository,
            marketDataSource = PostgresFakeMarketDataSource,
            clock = fixedClock(),
        )
        val inMemoryDecisionRepository = InMemoryDecisionRepository(fixedClock())
        val inMemoryBroker = PaperBroker(
            ledgerRepository = InMemoryPaperLedgerRepository(),
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = inMemoryDecisionRepository,
            marketDataSource = PostgresFakeMarketDataSource,
            clock = fixedClock(),
        )

        val postgresSnapshot = runWatermarkScenario(postgresBroker, postgresDecisionRepository)
        val inMemorySnapshot = runWatermarkScenario(inMemoryBroker, inMemoryDecisionRepository)
        val expectedSnapshot = WatermarkScenarioSnapshot(
            currentPriceJpy = "10050000.00000000",
            unrealizedPnlJpy = "225.00000000",
            highestPriceSinceEntryJpy = "10100000.00000000",
            lowestPriceSinceEntryJpy = "9900000.00000000",
        )

        assertEquals(expectedSnapshot, postgresSnapshot)
        assertEquals(expectedSnapshot, inMemorySnapshot)
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
        val positionId = UUID.fromString(broker.getPositions().getOrThrow().single().positionId)

        repository.reconcile(stopTickSnapshot(), me.matsumo.fukurou.trading.broker.FillSimulator()).getOrThrow()

        val balance = broker.getBalance().getOrThrow()
        val positions = broker.getPositions().getOrThrow()
        val openOrders = broker.getOpenOrders().getOrThrow()
        val executions = repository.getExecutions().getOrThrow()
        val watermark = selectPositionWatermark(database, positionId)

        assertEquals(0, balance.btcQuantity.toBigDecimal().compareTo(BigDecimal.ZERO))
        assertEquals(0, positions.size)
        assertEquals(0, openOrders.size)
        assertEquals(2, executions.size)
        assertEquals(listOf(OrderSide.BUY, OrderSide.SELL), executions.map { execution -> execution.side })
        assertEquals("10005000.00000000", watermark.highestPriceSinceEntryJpy)
        assertEquals("9685155.00000000", watermark.lowestPriceSinceEntryJpy)
    }

    @Test
    fun paper_ledger_repository_links_executionActivityContextInPostgresPath() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val repository = ExposedPaperLedgerRepository(database)
        val command = postgresEntryCommand(
            orderType = OrderType.LIMIT,
            priceJpy = BigDecimal("9900000"),
            takeProfitPriceJpy = BigDecimal("10500000"),
        )
        val expectedDecisionRunId = "run-entry-${command.commandId}"
        val positionId = UUID.randomUUID()
        val tradeGroupId = UUID.randomUUID()
        val entryOrderId = UUID.randomUUID()
        val stopOrderId = UUID.randomUUID()
        ExposedDecisionRepository(database, fixedClock())
            .submitDecision(entryDecisionSubmission(command))
            .getOrThrow()

        insertActivityContextRows(
            database = database,
            positionId = positionId,
            tradeGroupId = tradeGroupId,
            entryOrderId = entryOrderId,
            stopOrderId = stopOrderId,
            decisionRunId = expectedDecisionRunId,
        )

        val activities = repository.findExecutionActivitiesForStableFeed(
            cursor = StableFeedCursor(
                occurredAt = fixedInstant().plusSeconds(1),
                includesSameTimestamp = false,
                afterId = null,
            ),
            limit = 10,
        ).getOrThrow()
        val stopActivity = activities.single { activity -> activity.execution.side == OrderSide.SELL }
        val order = requireNotNull(stopActivity.order)
        val position = requireNotNull(stopActivity.position)
        val entryDecision = requireNotNull(stopActivity.entryDecision)

        assertEquals(OrderType.STOP, order.orderType)
        assertEquals("9800000.00000000", order.triggerPriceJpy)
        assertEquals("activity stop trigger", order.reasonJa)
        assertEquals(stopActivity.execution.positionId, position.positionId)
        assertEquals(tradeGroupId.toString(), position.tradeGroupId)
        assertEquals(expectedDecisionRunId, entryDecision.decisionRunId)
        assertEquals(DecisionAction.ENTER, entryDecision.action)
        assertEquals("integration entry", entryDecision.reasonJa)
        assertTrue(entryDecision.decisionId != null)
    }

    @Test
    fun paper_execution_linksRestingLimitEntryOrderToClosedTradeEvaluation() = runPostgresTest {
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
            ),
        )

        val placeResult = broker.placeOrder(command).getOrThrow()
        val entryOrderId = UUID.fromString(placeResult.orderIds.single())

        assertEquals(0, broker.getPositions().getOrThrow().size)
        assertNull(selectOrderPositionId(database, entryOrderId))

        repository.reconcile(
            tickSnapshot = watermarkTickSnapshot("9900000"),
            simulator = FillSimulator(clock = fixedClock()),
        ).getOrThrow()

        val position = broker.getPositions().getOrThrow().single()
        val positionId = UUID.fromString(position.positionId)
        val openWatermark = selectPositionWatermark(database, positionId)

        assertEquals(positionId, selectOrderPositionId(database, entryOrderId))
        assertEquals("9900000.00000000", openWatermark.highestPriceSinceEntryJpy)
        assertEquals("9900000.00000000", openWatermark.lowestPriceSinceEntryJpy)

        repository.reconcile(
            tickSnapshot = stopTickSnapshot(),
            simulator = FillSimulator(clock = fixedClock()),
        ).getOrThrow()

        val closedWatermark = selectPositionWatermark(database, positionId)
        val tradeResult = ExposedEvaluationRepository(database).fetchClosedTrades(
            EvaluationPeriod(
                from = fixedInstant().minusSeconds(1),
                toExclusive = fixedInstant().plusSeconds(1),
            ),
        ).getOrThrow()
        val trade = tradeResult.trades.single()
        val evaluatedTrade = EvaluationMath.evaluateTrade(trade)

        assertEquals(false, tradeResult.truncated)
        assertEquals("9700000.00000000", trade.initialProtectiveStopPriceJpy?.toPlainString())
        assertEquals("9900000.00000000", trade.highestPriceSinceEntryJpy.toPlainString())
        assertEquals("9685155.00000000", trade.lowestPriceSinceEntryJpy?.toPlainString())
        assertEquals("9900000.00000000", closedWatermark.highestPriceSinceEntryJpy)
        assertEquals("9685155.00000000", closedWatermark.lowestPriceSinceEntryJpy)
        assertEquals("200000.00000000", evaluatedTrade.initialRiskPriceWidthJpy?.toPlainString())
        assertEquals("-1.0934878875", evaluatedTrade.realizedR?.toPlainString())
        assertEquals("1.0742250000", evaluatedTrade.maeR?.toPlainString())
        assertEquals("0.0000000000", evaluatedTrade.mfeR?.toPlainString())
    }

    @Test
    fun paper_execution_appendsFillEquitySnapshotsAndSkipsMarkOnlyUpdates() = runPostgresTest {
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
        val fillCountAfterBuy = selectEquitySnapshotCountByReason(database, EquitySnapshotReason.FILL)
        val accountAfterBuy = repository.getAccountSnapshot().getOrThrow()
        val fillSnapshotAfterBuy = ExposedEquitySnapshotRepository(database).findAll().getOrThrow()
            .single { snapshot -> snapshot.reason == EquitySnapshotReason.FILL }
        repository.reconcile(
            tickSnapshot = trailingTickSnapshot(),
            simulator = FillSimulator(clock = fixedClock()),
        ).getOrThrow()
        val fillCountAfterMarkOnly = selectEquitySnapshotCountByReason(database, EquitySnapshotReason.FILL)
        repository.reconcile(
            tickSnapshot = stopTickSnapshot(),
            simulator = FillSimulator(clock = fixedClock()),
        ).getOrThrow()
        val fillCountAfterSell = selectEquitySnapshotCountByReason(database, EquitySnapshotReason.FILL)

        assertEquals(1, fillCountAfterBuy)
        assertEquals(1, fillCountAfterMarkOnly)
        assertEquals(2, fillCountAfterSell)
        assertEquitySnapshotMatchesAccount(fillSnapshotAfterBuy, accountAfterBuy)
    }

    @Test
    fun equity_snapshot_repository_ignoresDuplicateDailySnapshotsInPostgresPath() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val account = ExposedPaperLedgerRepository(database).getAccountSnapshot().getOrThrow()
        val repository = ExposedEquitySnapshotRepository(database)
        val tradingDate = LocalDate.of(2026, 7, 2)
        val firstSnapshot = account.toEquitySnapshotRecord(
            id = UUID.randomUUID(),
            reason = EquitySnapshotReason.DAILY,
            tradingDate = tradingDate,
            capturedAt = fixedInstant(),
        )
        val secondSnapshot = firstSnapshot.copy(
            id = UUID.randomUUID(),
            capturedAt = fixedInstant().plusSeconds(60),
        )

        repository.appendDailyIfAbsent(firstSnapshot).getOrThrow()
        repository.appendDailyIfAbsent(secondSnapshot).getOrThrow()

        val dailySnapshots = repository.findAll().getOrThrow()
            .filter { snapshot -> snapshot.reason == EquitySnapshotReason.DAILY }

        assertEquals(1, dailySnapshots.size)
        assertEquals(tradingDate, dailySnapshots.single().tradingDate)
    }

    @Test
    fun evaluation_repository_readsClosedTradeFactAndKillStatsFromPostgresLedger() = runPostgresTest {
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
        repository.reconcile(
            tickSnapshot = takeProfitTickSnapshot(),
            simulator = FillSimulator(clock = fixedClock()),
        ).getOrThrow()

        val evaluationRepository = ExposedEvaluationRepository(database)
        val tradeResult = evaluationRepository.fetchClosedTrades(
            EvaluationPeriod(
                from = fixedInstant().minusSeconds(1),
                toExclusive = fixedInstant().plusSeconds(1),
            ),
        ).getOrThrow()
        val trade = tradeResult.trades.single()
        val executions = repository.getExecutions().getOrThrow()
        val expectedPnl = executions
            .filter { execution -> execution.side == OrderSide.SELL }
            .sumOf { execution -> execution.realizedPnlJpy.toBigDecimal() }
            .subtract(
                executions
                    .filter { execution -> execution.side == OrderSide.BUY }
                    .sumOf { execution -> execution.feeJpy.toBigDecimal() },
            )
        val killStats = evaluationRepository.fetchKillCriterionStats().getOrThrow()

        assertEquals(false, tradeResult.truncated)
        assertEquals(listOf("integration-entry"), trade.setupTags)
        assertEquals("9700000.00000000", trade.initialProtectiveStopPriceJpy?.toPlainString())
        assertEquals("10100000.00000000", trade.highestPriceSinceEntryJpy.toPlainString())
        assertEquals("10005000.00000000", trade.lowestPriceSinceEntryJpy?.toPlainString())
        assertEquals(expectedPnl.toPlainString(), trade.tradePnlJpy.toPlainString())
        assertEquals(1, killStats.closedTrades)
        assertNull(killStats.profitFactor)
    }

    @Test
    fun evaluation_repository_readsLlmUsageFromInvocationPhasesWithFallbackAndLimit() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val eventLog = ExposedCommandEventLog(database)
        eventLog.append(runnerPhaseEvent(phase = "preflight", provider = null, occurredAt = fixedInstant())).getOrThrow()
        eventLog.append(
            runnerPhaseEvent(
                phase = "proposer",
                provider = "claude",
                occurredAt = fixedInstant().plusMillis(1),
                stdout = """{"total_cost_usd":0.20,"modelUsage":{"claude-sonnet":{"input_tokens":10,"output_tokens":4}}}""",
            ),
        ).getOrThrow()
        eventLog.append(
            runnerPhaseEvent(
                phase = "falsifier",
                provider = "codex",
                occurredAt = fixedInstant().plusMillis(2),
                stdout = "codex text output",
            ),
        ).getOrThrow()
        eventLog.append(
            runnerPhaseEvent(
                phase = "proposer",
                provider = "claude",
                occurredAt = fixedInstant().plusMillis(4),
                stdout = """{"total_cost_usd":0.30}""",
            ),
        ).getOrThrow()
        eventLog.append(
            runnerPhaseEvent(
                phase = "reflection",
                provider = "claude",
                occurredAt = fixedInstant().plusMillis(3),
                stdout = """{"total_cost_usd":0.10}""",
            ),
        ).getOrThrow()

        val evaluationRepository = ExposedEvaluationRepository(database)
        val usageResult = evaluationRepository.fetchLlmPhaseUsages(
            period = EvaluationPeriod(
                from = fixedInstant().minusSeconds(1),
                toExclusive = fixedInstant().plusSeconds(1),
            ),
            limit = 3,
        ).getOrThrow()

        assertTrue(usageResult.truncated)
        assertEquals(listOf("proposer", "falsifier", "reflection"), usageResult.facts.map { fact -> fact.phase })
        assertEquals("0.20", usageResult.facts.first().usage?.totalCostUsd?.toPlainString())
        assertEquals(null, usageResult.facts[1].usage)
        assertEquals("0.10", usageResult.facts[2].usage?.totalCostUsd?.toPlainString())
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
    fun paper_execution_closeAllDoesNotReuseClientRequestIdAcrossCloseOrders() = runPostgresTest {
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
        val firstEntry = approvedPostgresEntryCommand(
            repository = decisionRepository,
            command = postgresEntryCommand(
                sizeBtc = BigDecimal("0.0030"),
                takeProfitPriceJpy = BigDecimal("10500000"),
                clientRequestId = "entry-close-all-1",
            ),
        )
        val secondEntry = approvedPostgresEntryCommand(
            repository = decisionRepository,
            command = postgresEntryCommand(
                sizeBtc = BigDecimal("0.0030"),
                takeProfitPriceJpy = BigDecimal("10600000"),
                clientRequestId = "entry-close-all-2",
            ),
        )

        broker.placeOrder(firstEntry).getOrThrow()
        broker.placeOrder(secondEntry).getOrThrow()
        val closeResult = broker.closePosition(
            ClosePositionCommand(
                commandId = UUID.randomUUID(),
                positionId = null,
                closeAll = true,
                reasonJa = "close all integration",
                auditContext = PaperTradeAuditContext.EMPTY.copy(clientRequestId = "close-all-request"),
            ),
        ).getOrThrow()
        val positions = repository.getOpenPositions().getOrThrow()
        val closeClientRequestIds = selectClientRequestIdsByOrderIds(database, closeResult.orderIds)
        val expectedCloseClientRequestIds = closeResult.positionIds
            .map { positionId -> "close-all-request:$positionId" }
            .sorted()
        val watermarks = closeResult.positionIds
            .map { positionId ->
                selectPositionWatermark(
                    database = database,
                    positionId = UUID.fromString(positionId),
                )
            }

        assertTrue(closeResult.accepted)
        assertEquals(2, closeResult.orderIds.size)
        assertEquals(expectedCloseClientRequestIds, closeClientRequestIds.filterNotNull().sorted())
        assertEquals(0, positions.size)
        assertEquals(
            listOf("10005000.00000000", "10005000.00000000"),
            watermarks.map { watermark -> watermark.highestPriceSinceEntryJpy },
        )
        assertEquals(
            listOf("9985005.00000000", "9985005.00000000"),
            watermarks.map { watermark -> watermark.lowestPriceSinceEntryJpy },
        )
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
        val positionId = UUID.fromString(broker.getPositions().getOrThrow().single().positionId)

        repository.reconcile(takeProfitTickSnapshot(), me.matsumo.fukurou.trading.broker.FillSimulator()).getOrThrow()

        val positions = broker.getPositions().getOrThrow()
        val openOrders = broker.getOpenOrders().getOrThrow()
        val executions = repository.getExecutions().getOrThrow()
        val watermark = selectPositionWatermark(database, positionId)

        assertEquals(0, positions.size)
        assertEquals(0, openOrders.size)
        assertEquals(2, executions.size)
        assertEquals("10100000.00000000", watermark.highestPriceSinceEntryJpy)
        assertEquals("10005000.00000000", watermark.lowestPriceSinceEntryJpy)
    }

    @Test
    fun paper_execution_hard_halt_sweep_folds_close_fill_into_watermark() = runPostgresTest {
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
        val positionId = UUID.fromString(broker.getPositions().getOrThrow().single().positionId)

        broker.sweepHardHalt(
            reasonJa = "integration hard halt",
            tickSnapshot = hardHaltSweepTickSnapshot(),
        ).getOrThrow()

        val positions = broker.getPositions().getOrThrow()
        val openOrders = broker.getOpenOrders().getOrThrow()
        val watermark = selectPositionWatermark(database, positionId)

        assertEquals(0, positions.size)
        assertEquals(0, openOrders.size)
        assertEquals("10005000.00000000", watermark.highestPriceSinceEntryJpy)
        assertEquals("9885055.00000000", watermark.lowestPriceSinceEntryJpy)
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
        assertEquals(RiskHaltState.HARD_HALT, riskState.state)
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
 * position watermark の確認行。
 *
 * @param highestPriceSinceEntryJpy entry 以降の最高値
 * @param lowestPriceSinceEntryJpy entry 以降の最安値。null は記録開始前。
 */
private data class PositionWatermarkRow(
    val highestPriceSinceEntryJpy: String,
    val lowestPriceSinceEntryJpy: String?,
)

/**
 * risk_state の rollback 互換列確認行。
 *
 * @param state state column の値
 * @param hardHalt legacy hard_halt column の値
 */
private data class RiskStateColumns(
    val state: RiskHaltState,
    val hardHalt: Boolean,
)

/**
 * repository 実装間で比較する watermark scenario の結果。
 *
 * @param currentPriceJpy 現在価格
 * @param unrealizedPnlJpy 未実現損益
 * @param highestPriceSinceEntryJpy entry 以降の最高値
 * @param lowestPriceSinceEntryJpy entry 以降の最安値
 */
private data class WatermarkScenarioSnapshot(
    val currentPriceJpy: String,
    val unrealizedPnlJpy: String,
    val highestPriceSinceEntryJpy: String,
    val lowestPriceSinceEntryJpy: String?,
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

/**
 * repository test 用 TradePlan revision decision を作る。
 */
private fun tradePlanRevisionSubmission(parentTradePlanId: UUID?, revisionCount: Int): DecisionSubmission {
    return DecisionSubmission(
        invocationId = "run-revision",
        llmProvider = "claude",
        promptHash = "prompt-hash",
        systemPromptVersion = "system-prompt-v1",
        marketSnapshotId = "snapshot-revision",
        action = DecisionAction.ADJUST_PROTECTION,
        setupTags = listOf("breakout", "trend-follow"),
        estimatedWinProbability = BigDecimal("0.64"),
        expectedRMultiple = BigDecimal("1.20"),
        roundTripCostR = BigDecimal("0.05"),
        toolEvidenceIds = listOf("tool-1", "tool-2"),
        factCheckJson = """{"ticker":true}""",
        selfReviewJson = """{"reasonsNotToTrade":[]}""",
        reasonJa = "否定条件は未成立ですが、保護計画を正式修正します。",
        missingDataJa = emptyList(),
        noTradeConditionsJa = emptyList(),
        entryIntent = null,
        tradePlan = TradePlanDraft(
            parentTradePlanId = parentTradePlanId,
            revisionCount = revisionCount,
            symbol = TradingSymbol.BTC,
            thesisJa = "上昇継続の仮説は維持します。",
            invalidationConditionsJa = listOf("直近安値割れ"),
            targetPriceJpy = BigDecimal("10400000"),
            timeStopAt = fixedInstant().plusSeconds(7200),
            setupTags = listOf("breakout", "trend-follow"),
        ),
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
        invocationId = "run-entry-${command.commandId}",
        llmProvider = "claude",
        promptHash = "prompt-hash",
        systemPromptVersion = "system-prompt-v1",
        marketSnapshotId = "snapshot-entry-${command.commandId}",
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
    sizeBtc: BigDecimal = BigDecimal("0.0050"),
    takeProfitPriceJpy: BigDecimal,
    estimatedWinProbability: BigDecimal = BigDecimal("0.95"),
    clientRequestId: String? = null,
): PlaceOrderCommand {
    return PlaceOrderCommand(
        commandId = UUID.randomUUID(),
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = orderType,
        sizeBtc = sizeBtc,
        priceJpy = priceJpy,
        tradeGroupId = null,
        protectiveStopPriceJpy = BigDecimal("9700000"),
        takeProfitPriceJpy = takeProfitPriceJpy,
        estimatedWinProbability = estimatedWinProbability,
        reasonJa = "integration entry",
        auditContext = PaperTradeAuditContext.EMPTY.copy(clientRequestId = clientRequestId),
    )
}

/**
 * InMemory / Postgres の watermark 意味論を同じ操作列で検証する。
 */
private suspend fun runWatermarkScenario(
    broker: PaperBroker,
    decisionRepository: DecisionRepository,
): WatermarkScenarioSnapshot {
    val command = approvedPostgresEntryCommand(
        repository = decisionRepository,
        command = postgresEntryCommand(takeProfitPriceJpy = BigDecimal("10500000")),
    )

    broker.placeOrder(command).getOrThrow()
    broker.reconcile(watermarkTickSnapshot("9900000")).getOrThrow()
    broker.reconcile(watermarkTickSnapshot("10100000")).getOrThrow()
    broker.reconcile(watermarkTickSnapshot("10050000")).getOrThrow()

    val position = broker.getPositions().getOrThrow().single()

    return WatermarkScenarioSnapshot(
        currentPriceJpy = position.currentPriceJpy,
        unrealizedPnlJpy = position.unrealizedPnlJpy,
        highestPriceSinceEntryJpy = position.highestPriceSinceEntryJpy,
        lowestPriceSinceEntryJpy = position.lowestPriceSinceEntryJpy,
    )
}

private fun llmLaunchReservationRequest(
    invocationId: String,
    config: LlmRunnerConfig,
    reservedAt: Instant = fixedInstant(),
    triggerKind: LlmDaemonTriggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
): LlmLaunchReservationRequest {
    return LlmLaunchReservationRequest(
        invocationId = invocationId,
        triggerKind = triggerKind,
        triggerKey = "test:${triggerKind.name.lowercase()}:$invocationId",
        reservedAt = reservedAt,
        runnerConfig = config,
        hourlyWindow = Duration.ofHours(1),
        dailyWindow = Duration.ofHours(24),
        activeReservationStaleAfter = Duration.ofMinutes(30),
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

/**
 * HARD_HALT sweep で close fill を watermark に fold するための tick。
 */
private fun hardHaltSweepTickSnapshot(): TickSnapshot {
    return TickSnapshot(
        symbol = "BTC",
        observedAt = fixedInstant(),
        lastPrice = "9900000",
        bidPrice = "9890000",
        askPrice = "9900000",
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

/**
 * watermark の単調更新検証に使う tick。
 */
private fun watermarkTickSnapshot(
    lastPrice: String,
    bidPrice: String = lastPrice,
    askPrice: String = lastPrice,
): TickSnapshot {
    return TickSnapshot(
        symbol = "BTC",
        observedAt = fixedInstant(),
        lastPrice = lastPrice,
        bidPrice = bidPrice,
        askPrice = askPrice,
        symbolRules = postgresSymbolRules(),
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
private fun runPostgresTest(block: suspend PostgresTestContext.() -> Unit) = runBlocking {
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
 * risk_state state column を削除する。
 */
private fun dropRiskStateStateColumn(database: ExposedDatabase) {
    exposedTransaction(database) {
        jdbcConnection().prepareStatement(DROP_RISK_STATE_STATE_COLUMN_SQL).use { statement ->
            statement.executeUpdate()
        }
    }
}

/**
 * risk_state の state / hard_halt を意図的に設定する。
 */
private fun forceRiskStateColumns(
    database: ExposedDatabase,
    state: RiskHaltState,
    hardHalt: Boolean,
) {
    exposedTransaction(database) {
        jdbcConnection().prepareStatement(FORCE_RISK_STATE_COLUMNS_SQL).use { statement ->
            statement.setString(1, state.name)
            statement.setBoolean(2, hardHalt)
            statement.setInt(3, RISK_STATE_SINGLE_ROW_ID)
            statement.executeUpdate()
        }
    }
}

/**
 * risk_state の state / hard_halt を読む。
 */
private fun selectRiskStateColumns(database: ExposedDatabase): RiskStateColumns {
    return exposedTransaction(database) {
        jdbcConnection().prepareStatement(SELECT_RISK_STATE_COLUMNS_SQL).use { statement ->
            statement.setInt(1, RISK_STATE_SINGLE_ROW_ID)
            statement.executeQuery().use { resultSet ->
                require(resultSet.next()) { "risk_state columns did not return a row." }

                RiskStateColumns(
                    state = RiskHaltState.valueOf(resultSet.getString("state")),
                    hardHalt = resultSet.getBoolean("hard_halt"),
                )
            }
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
 * llm_runs table を削除する。
 */
private fun dropLlmRunsTable(database: ExposedDatabase) {
    exposedTransaction(database) {
        jdbcConnection().prepareStatement(DROP_LLM_RUNS_TABLE_SQL).use { statement ->
            statement.executeUpdate()
        }
    }
}

/**
 * active runtime config version をなくす。
 */
private fun deactivateRuntimeConfigVersions(database: ExposedDatabase) {
    exposedTransaction(database) {
        jdbcConnection().prepareStatement(DEACTIVATE_RUNTIME_CONFIG_VERSIONS_SQL).use { statement ->
            statement.executeUpdate()
        }
    }
}

/**
 * runtime config values をすべて削除する。
 */
private fun deleteRuntimeConfigValues(database: ExposedDatabase) {
    exposedTransaction(database) {
        jdbcConnection().prepareStatement(DELETE_RUNTIME_CONFIG_VALUES_SQL).use { statement ->
            statement.executeUpdate()
        }
    }
}

/**
 * runtime config value を key 単位で削除する。
 */
private fun deleteRuntimeConfigValue(database: ExposedDatabase, configKey: String) {
    exposedTransaction(database) {
        jdbcConnection().prepareStatement(DELETE_RUNTIME_CONFIG_VALUE_SQL).use { statement ->
            statement.setString(1, configKey)
            statement.executeUpdate()
        }
    }
}

/**
 * equity_snapshots table を削除する。
 */
private fun dropEquitySnapshotsTable(database: ExposedDatabase) {
    exposedTransaction(database) {
        jdbcConnection().prepareStatement(DROP_EQUITY_SNAPSHOTS_TABLE_SQL).use { statement ->
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
 * command_event_log 集計 index 件数を読む。
 */
private fun selectCommandEventLogIndexCount(database: ExposedDatabase): Int {
    return exposedTransaction(database) {
        jdbcConnection().prepareStatement(SELECT_COMMAND_EVENT_LOG_INDEX_COUNT_SQL).use { statement ->
            statement.executeQuery().use { resultSet ->
                require(resultSet.next()) { "command_event_log index count did not return a row." }

                resultSet.getInt(1)
            }
        }
    }
}

/**
 * orders.client_request_id unique index 件数を読む。
 */
private fun selectOrdersClientRequestIdIndexCount(database: ExposedDatabase): Int {
    return exposedTransaction(database) {
        jdbcConnection().prepareStatement(SELECT_ORDERS_CLIENT_REQUEST_ID_INDEX_COUNT_SQL).use { statement ->
            statement.executeQuery().use { resultSet ->
                require(resultSet.next()) { "orders client_request_id index count did not return a row." }

                resultSet.getInt(1)
            }
        }
    }
}

/**
 * 指定 order IDs の client_request_id を読む。
 */
private fun selectClientRequestIdsByOrderIds(database: ExposedDatabase, orderIds: List<String>): List<String?> {
    return exposedTransaction(database) {
        val placeholders = orderIds.joinToString(", ") { "?" }
        val sql = "SELECT client_request_id FROM orders WHERE id IN ($placeholders)"

        jdbcConnection().prepareStatement(sql).use { statement ->
            orderIds.forEachIndexed { orderIndex, orderId ->
                statement.setObject(orderIndex + 1, UUID.fromString(orderId))
            }
            statement.executeQuery().use { resultSet ->
                val clientRequestIds = mutableListOf<String?>()

                while (resultSet.next()) {
                    clientRequestIds += resultSet.getString("client_request_id")
                }

                clientRequestIds
            }
        }
    }
}

/**
 * LLM 起動予約 index 件数を読む。
 */
private fun selectLlmLaunchReservationIndexCount(database: ExposedDatabase): Int {
    return exposedTransaction(database) {
        jdbcConnection().prepareStatement(SELECT_LLM_LAUNCH_RESERVATION_INDEX_COUNT_SQL).use { statement ->
            statement.executeQuery().use { resultSet ->
                require(resultSet.next()) { "llm launch reservation index count did not return a row." }

                resultSet.getInt(1)
            }
        }
    }
}

/**
 * llm_runs index 件数を読む。
 */
private fun selectLlmRunIndexCount(database: ExposedDatabase): Int {
    return exposedTransaction(database) {
        jdbcConnection().prepareStatement(SELECT_LLM_RUN_INDEX_COUNT_SQL).use { statement ->
            statement.executeQuery().use { resultSet ->
                require(resultSet.next()) { "llm_runs index count did not return a row." }

                resultSet.getInt(1)
            }
        }
    }
}

/**
 * equity_snapshots index 件数を読む。
 */
private fun selectEquitySnapshotIndexCount(database: ExposedDatabase): Int {
    return exposedTransaction(database) {
        jdbcConnection().prepareStatement(SELECT_EQUITY_SNAPSHOT_INDEX_COUNT_SQL).use { statement ->
            statement.executeQuery().use { resultSet ->
                require(resultSet.next()) { "equity_snapshots index count did not return a row." }

                resultSet.getInt(1)
            }
        }
    }
}

private fun assertEquitySnapshotMatchesAccount(snapshot: EquitySnapshotRecord, account: AccountSnapshot) {
    assertDecimalStringEquals("cash_jpy", account.cashJpy, snapshot.cashJpy)
    assertDecimalStringEquals("btc_quantity", account.btcQuantity, snapshot.btcQuantity)
    assertDecimalStringEquals("btc_mark_price_jpy", account.btcMarkPriceJpy, snapshot.btcMarkPriceJpy)
    assertDecimalStringEquals("total_equity_jpy", account.totalEquityJpy, snapshot.totalEquityJpy)
    assertDecimalStringEquals("equity_peak_jpy", account.equityPeakJpy, snapshot.equityPeakJpy)
    assertDecimalStringEquals("drawdown_ratio", account.drawdownRatio, snapshot.drawdownRatio)
}

private fun assertDecimalStringEquals(
    fieldName: String,
    expected: String,
    actual: BigDecimal,
) {
    assertEquals(0, actual.compareTo(expected.toBigDecimal()), "$fieldName mismatch")
}

/**
 * reason 別 equity_snapshots 件数を読む。
 */
private fun selectEquitySnapshotCountByReason(database: ExposedDatabase, reason: EquitySnapshotReason): Int {
    return exposedTransaction(database) {
        jdbcConnection().prepareStatement(SELECT_EQUITY_SNAPSHOT_COUNT_BY_REASON_SQL).use { statement ->
            statement.setString(1, reason.name)
            statement.executeQuery().use { resultSet ->
                require(resultSet.next()) { "equity_snapshots count did not return a row." }

                resultSet.getInt(1)
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
 * position watermark を読む。
 */
private fun selectPositionWatermark(database: ExposedDatabase, positionId: UUID): PositionWatermarkRow {
    return exposedTransaction(database) {
        jdbcConnection().prepareStatement(SELECT_POSITION_WATERMARK_SQL).use { statement ->
            statement.setObject(1, positionId)
            statement.executeQuery().use { resultSet ->
                require(resultSet.next()) { "position watermark did not return a row." }

                PositionWatermarkRow(
                    highestPriceSinceEntryJpy = resultSet
                        .getBigDecimal("highest_price_since_entry_jpy")
                        .toPlainString(),
                    lowestPriceSinceEntryJpy = resultSet
                        .getBigDecimal("lowest_price_since_entry_jpy")
                        ?.toPlainString(),
                )
            }
        }
    }
}

/**
 * order に紐付いた position ID を読む。
 */
private fun selectOrderPositionId(database: ExposedDatabase, orderId: UUID): UUID? {
    return exposedTransaction(database) {
        jdbcConnection().prepareStatement(SELECT_ORDER_POSITION_ID_SQL).use { statement ->
            statement.setObject(1, orderId)
            statement.executeQuery().use { resultSet ->
                require(resultSet.next()) {
                    "order position link did not return a row."
                }

                resultSet.getNullableUuid("position_id")
            }
        }
    }
}

/**
 * backfill 検証用 position 行を lowest なしで追加する。
 */
private fun JdbcTransaction.insertBackfillPosition(
    positionId: UUID,
    status: String,
    currentPriceJpy: BigDecimal,
) {
    jdbcConnection().prepareStatement(INSERT_BACKFILL_POSITION_SQL).use { statement ->
        statement.setObject(1, positionId)
        statement.setObject(2, UUID.randomUUID())
        statement.setString(3, status)
        statement.setLong(4, fixedInstant().toEpochMilli())
        statement.setBigDecimal(5, currentPriceJpy)
        statement.executeUpdate()
    }
}

/**
 * backfill 済み lowest watermark を実測値相当に更新する。
 */
private fun JdbcTransaction.updateLowestWatermark(positionId: UUID, lowestPriceJpy: BigDecimal) {
    jdbcConnection().prepareStatement(UPDATE_LOWEST_WATERMARK_SQL).use { statement ->
        statement.setBigDecimal(1, lowestPriceJpy)
        statement.setObject(2, positionId)
        statement.executeUpdate()
    }
}

/**
 * execution を含む ledger fixture 行を追加する。
 */
private fun insertLedgerRows(
    database: ExposedDatabase,
    mode: String,
    realizedPnlJpy: String,
    executedAt: Instant = fixedInstant(),
): UUID {
    val positionId = UUID.randomUUID()
    val tradeGroupId = UUID.randomUUID()

    exposedTransaction(database) {
        insertTestPosition(positionId, tradeGroupId, mode)
        insertTestOrder(positionId, tradeGroupId, mode)
        insertTestExecution(
            positionId = positionId,
            mode = mode,
            realizedPnlJpy = realizedPnlJpy,
            executedAt = executedAt,
        )
    }

    return positionId
}

/**
 * execution activity context join 検証用 ledger fixture を追加する。
 */
private fun insertActivityContextRows(
    database: ExposedDatabase,
    positionId: UUID,
    tradeGroupId: UUID,
    entryOrderId: UUID,
    stopOrderId: UUID,
    decisionRunId: String,
) {
    exposedTransaction(database) {
        insertTestPosition(positionId, tradeGroupId, TradingMode.PAPER.name)
        insertActivityContextOrder(
            orderId = entryOrderId,
            positionId = positionId,
            tradeGroupId = tradeGroupId,
            side = OrderSide.BUY,
            orderType = OrderType.LIMIT,
            limitPriceJpy = BigDecimal("9900000"),
            triggerPriceJpy = null,
            takeProfitPriceJpy = BigDecimal("10500000"),
            reasonJa = "integration entry",
            decisionRunId = decisionRunId,
        )
        insertActivityContextOrder(
            orderId = stopOrderId,
            positionId = positionId,
            tradeGroupId = tradeGroupId,
            side = OrderSide.SELL,
            orderType = OrderType.STOP,
            limitPriceJpy = null,
            triggerPriceJpy = BigDecimal("9800000"),
            takeProfitPriceJpy = null,
            reasonJa = "activity stop trigger",
            decisionRunId = null,
        )
        insertActivityContextExecution(
            orderId = stopOrderId,
            positionId = positionId,
            side = OrderSide.SELL,
            priceJpy = BigDecimal("9800000"),
            realizedPnlJpy = BigDecimal("1200"),
        )
    }
}

/**
 * Obsidian range query 検証用 closed position と約定を追加する。
 */
private fun insertObsidianClosedPositionRows(
    database: ExposedDatabase,
    positionId: UUID,
    decisionRunId: String,
    closedAt: Instant,
    realizedPnlJpy: BigDecimal,
) {
    exposedTransaction(database) {
        val tradeGroupId = UUID.randomUUID()

        insertObsidianClosedPosition(
            positionId = positionId,
            tradeGroupId = tradeGroupId,
            decisionRunId = decisionRunId,
            closedAt = closedAt,
        )
        insertObsidianExecution(
            positionId = positionId,
            side = OrderSide.BUY,
            priceJpy = BigDecimal("10000000"),
            feeJpy = BigDecimal("50"),
            realizedPnlJpy = BigDecimal.ZERO,
            executedAt = fixedInstant(),
        )
        insertObsidianExecution(
            positionId = positionId,
            side = OrderSide.SELL,
            priceJpy = BigDecimal("10100000"),
            feeJpy = BigDecimal("60"),
            realizedPnlJpy = realizedPnlJpy,
            executedAt = closedAt,
        )
    }
}

/**
 * Obsidian range query 検証用 closed position 行を追加する。
 */
private fun JdbcTransaction.insertObsidianClosedPosition(
    positionId: UUID,
    tradeGroupId: UUID,
    decisionRunId: String,
    closedAt: Instant,
) {
    jdbcConnection().prepareStatement(INSERT_OBSIDIAN_CLOSED_POSITION_SQL).use { statement ->
        statement.setObject(1, positionId)
        statement.setObject(2, tradeGroupId)
        statement.setLong(3, fixedInstant().toEpochMilli())
        statement.setLong(4, closedAt.toEpochMilli())
        statement.setString(5, decisionRunId)
        statement.executeUpdate()
    }
}

/**
 * Obsidian range query 検証用 execution 行を追加する。
 */
private fun JdbcTransaction.insertObsidianExecution(
    positionId: UUID,
    side: OrderSide,
    priceJpy: BigDecimal,
    feeJpy: BigDecimal,
    realizedPnlJpy: BigDecimal,
    executedAt: Instant,
) {
    jdbcConnection().prepareStatement(INSERT_OBSIDIAN_EXECUTION_SQL).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, UUID.randomUUID())
        statement.setObject(3, positionId)
        statement.setString(4, side.name)
        statement.setBigDecimal(5, priceJpy)
        statement.setBigDecimal(6, feeJpy)
        statement.setBigDecimal(7, realizedPnlJpy)
        statement.setLong(8, executedAt.toEpochMilli())
        statement.executeUpdate()
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
    executedAt: Instant = fixedInstant(),
) {
    jdbcConnection().prepareStatement(INSERT_TEST_EXECUTION_SQL).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, positionId)
        statement.setString(3, mode)
        statement.setBigDecimal(4, realizedPnlJpy.toBigDecimal())
        statement.setLong(5, executedAt.toEpochMilli())
        statement.executeUpdate()
    }
}

/**
 * Activity context join 検証用 order 行を追加する。
 */
private fun JdbcTransaction.insertActivityContextOrder(
    orderId: UUID,
    positionId: UUID,
    tradeGroupId: UUID,
    side: OrderSide,
    orderType: OrderType,
    limitPriceJpy: BigDecimal?,
    triggerPriceJpy: BigDecimal?,
    takeProfitPriceJpy: BigDecimal?,
    reasonJa: String,
    decisionRunId: String?,
) {
    jdbcConnection().prepareStatement(INSERT_ACTIVITY_CONTEXT_ORDER_SQL).use { statement ->
        statement.setObject(1, orderId)
        statement.setObject(2, positionId)
        statement.setObject(3, tradeGroupId)
        statement.setString(4, side.name)
        statement.setString(5, orderType.name)
        statement.setNullableBigDecimal(6, limitPriceJpy)
        statement.setNullableBigDecimal(7, triggerPriceJpy)
        statement.setNullableBigDecimal(8, takeProfitPriceJpy)
        statement.setString(9, reasonJa)
        statement.setString(10, decisionRunId)
        statement.setLong(11, fixedInstant().toEpochMilli())
        statement.setLong(12, fixedInstant().toEpochMilli())
        statement.executeUpdate()
    }
}

/**
 * Activity context join 検証用 execution 行を追加する。
 */
private fun JdbcTransaction.insertActivityContextExecution(
    orderId: UUID,
    positionId: UUID,
    side: OrderSide,
    priceJpy: BigDecimal,
    realizedPnlJpy: BigDecimal,
) {
    jdbcConnection().prepareStatement(INSERT_ACTIVITY_CONTEXT_EXECUTION_SQL).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, orderId)
        statement.setObject(3, positionId)
        statement.setString(4, side.name)
        statement.setBigDecimal(5, priceJpy)
        statement.setBigDecimal(6, realizedPnlJpy)
        statement.setLong(7, fixedInstant().toEpochMilli())
        statement.executeUpdate()
    }
}

private fun java.sql.PreparedStatement.setNullableBigDecimal(index: Int, value: BigDecimal?) {
    if (value == null) {
        setNull(index, Types.NUMERIC)

        return
    }

    setBigDecimal(index, value)
}

private fun commandEvent(
    toolName: String,
    eventType: CommandEventType,
    occurredAt: Instant,
    runtimeConfigVersionId: String? = null,
    runtimeConfigHash: String? = null,
): CommandEvent {
    return CommandEvent(
        decisionRunContext = DecisionRunContext(
            decisionRunId = null,
            llmProvider = null,
            promptHash = null,
            systemPromptVersion = null,
            marketSnapshotId = null,
            runtimeConfigVersionId = runtimeConfigVersionId,
            runtimeConfigHash = runtimeConfigHash,
        ),
        toolName = toolName,
        toolCallId = null,
        clientRequestId = null,
        eventType = eventType,
        payload = """{"toolName":"$toolName"}""",
        occurredAt = occurredAt,
    )
}

private fun runnerPhaseEvent(
    phase: String,
    provider: String?,
    occurredAt: Instant,
    stdout: String? = null,
): CommandEvent {
    val escapedStdout = stdout?.replace("\"", "\\\"")
    val details = if (escapedStdout == null) {
        "{}"
    } else {
        """{"stdout":"$escapedStdout"}"""
    }

    return CommandEvent(
        decisionRunContext = DecisionRunContext(
            decisionRunId = "run-$phase-${occurredAt.toEpochMilli()}",
            llmProvider = provider,
            promptHash = null,
            systemPromptVersion = null,
            marketSnapshotId = null,
        ),
        toolName = "one_shot_runner",
        toolCallId = null,
        clientRequestId = null,
        eventType = CommandEventType.RUNNER_PHASE_COMPLETED,
        payload = """{"phase":"$phase","details":$details}""",
        occurredAt = occurredAt,
    )
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
