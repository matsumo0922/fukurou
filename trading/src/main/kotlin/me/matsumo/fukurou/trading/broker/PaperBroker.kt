package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.config.DecisionProtocolConfig
import me.matsumo.fukurou.trading.decision.AtomicIntentConsumptionRepository
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.AccountStatus
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.ProtectionStatus
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.domain.requiredCashFor
import me.matsumo.fukurou.trading.logging.RateLimitedWarnLogger
import me.matsumo.fukurou.trading.market.IndicatorCalculator
import me.matsumo.fukurou.trading.market.IndicatorParams
import me.matsumo.fukurou.trading.market.IndicatorType
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.reconciler.NoReconcilerStatusProvider
import me.matsumo.fukurou.trading.reconciler.ReconcilerStatus
import me.matsumo.fukurou.trading.reconciler.ReconcilerStatusProvider
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import me.matsumo.fukurou.trading.reconciler.requireTicker
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.risk.RiskStateCommandService
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import me.matsumo.fukurou.trading.safety.InMemorySafetyViolationRepository
import me.matsumo.fukurou.trading.safety.SafetyFloor
import me.matsumo.fukurou.trading.safety.SafetyFloorContext
import me.matsumo.fukurou.trading.safety.SafetyFloorDefaults
import me.matsumo.fukurou.trading.safety.SafetyFloorVerdict
import me.matsumo.fukurou.trading.safety.SafetyViolation
import me.matsumo.fukurou.trading.safety.SafetyViolationRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.logging.Logger

/**
 * paper ledger を読み取る Broker 実装。
 *
 * @param ledgerRepository paper ledger repository
 * @param riskStateRepository risk_state repository
 * @param riskStateCommandService risk_state 更新と audit をまとめる service
 * @param decisionRepository decision / intent / falsification repository
 * @param falsificationFreshnessWindow fresh APPROVED falsification とみなす時間窓
 * @param safetyViolationRepository SafetyFloor violation repository
 * @param safetyFloor Broker 副作用前に実行する SafetyFloor
 * @param marketDataSource paper 約定に使う市場データ source
 * @param paperExecutionConfig paper 約定近似設定
 * @param fillSimulator paper 約定 simulator
 * @param reconcilerStatusProvider ProtectionReconciler 状態 provider
 * @param clock 当日実現損益の対象日算出に使う clock
 * @param tradingDateZone 当日判定に使う timezone
 */
class PaperBroker private constructor(
    private val runtime: PaperBrokerRuntime,
) : BrokerReadBoundary by PaperBrokerReadDelegate(runtime),
    BrokerTradeBoundary by PaperBrokerTradeDelegate(runtime),
    BrokerReconcileBoundary by PaperBrokerReconcileDelegate(runtime),
    Broker {

    internal val ledgerRepository = runtime.stores.ledgerRepository
    internal val marketDataSource = runtime.market.marketDataSource
    internal val fillSimulator = runtime.market.fillSimulator

    init {
        val hasAtomicLedgerRepository = ledgerRepository is IntentConsumingPaperLedgerRepository
        val hasAtomicDecisionRepository = runtime.stores.decisionRepository is AtomicIntentConsumptionRepository
        val hasAtomicIntentConsumption = hasAtomicLedgerRepository || hasAtomicDecisionRepository

        require(hasAtomicIntentConsumption) {
            "PaperBroker requires atomic intent consumption support."
        }
    }

    constructor(
        ledgerRepository: PaperLedgerRepository,
        riskStateRepository: RiskStateRepository,
        riskStateCommandService: RiskStateCommandService? = null,
        decisionRepository: DecisionRepository = InMemoryDecisionRepository(),
        falsificationFreshnessWindow: Duration = DecisionProtocolConfig().falsificationFreshnessWindow,
        safetyViolationRepository: SafetyViolationRepository = InMemorySafetyViolationRepository(),
        safetyFloor: SafetyFloor? = null,
        marketDataSource: MarketDataSource? = null,
        paperExecutionConfig: PaperExecutionConfig = PaperExecutionConfig(),
        fillSimulator: PaperExecutionSimulator? = null,
        reconcilerStatusProvider: ReconcilerStatusProvider = NoReconcilerStatusProvider,
        clock: Clock = Clock.systemUTC(),
        tradingDateZone: ZoneId = TRADING_DATE_ZONE,
        warnLogger: RateLimitedWarnLogger = RateLimitedWarnLogger(
            logger = paperBrokerLogger,
            clock = clock,
        ),
    ) : this(
        runtime = PaperBrokerRuntime(
            stores = PaperBrokerStores(
                ledgerRepository = ledgerRepository,
                riskStateRepository = riskStateRepository,
                decisionRepository = decisionRepository,
            ),
            safety = PaperBrokerSafetyServices(
                riskStateCommandService = riskStateCommandService,
                safetyViolationRepository = safetyViolationRepository,
                safetyFloor = safetyFloor ?: SafetyFloor(
                    clock = clock,
                    paperExecutionConfig = paperExecutionConfig,
                ),
            ),
            market = PaperBrokerMarketServices(
                marketDataSource = marketDataSource,
                paperExecutionConfig = paperExecutionConfig,
                fillSimulator = fillSimulator ?: DefaultPaperExecutionSimulator(
                    config = paperExecutionConfig,
                    clock = clock,
                ),
                warnLogger = warnLogger,
            ),
            time = PaperBrokerTimeConfig(
                clock = clock,
                tradingDateZone = tradingDateZone,
                falsificationFreshnessWindow = falsificationFreshnessWindow,
            ),
            reconcilerStatusProvider = reconcilerStatusProvider,
        ),
    )
}

private val paperBrokerLogger: Logger = Logger.getLogger(PaperBroker::class.java.name)

/**
 * PaperBroker delegate 群で共有する repository。
 *
 * @param ledgerRepository paper ledger repository
 * @param riskStateRepository risk_state repository
 * @param decisionRepository decision / intent / falsification repository
 */
private data class PaperBrokerStores(
    val ledgerRepository: PaperLedgerRepository,
    val riskStateRepository: RiskStateRepository,
    val decisionRepository: DecisionRepository,
)

/**
 * PaperBroker delegate 群で共有する SafetyFloor 関連 service。
 *
 * @param riskStateCommandService risk_state 更新と audit をまとめる service
 * @param safetyViolationRepository SafetyFloor violation repository
 * @param safetyFloor Broker 副作用前に実行する SafetyFloor
 */
private data class PaperBrokerSafetyServices(
    val riskStateCommandService: RiskStateCommandService?,
    val safetyViolationRepository: SafetyViolationRepository,
    val safetyFloor: SafetyFloor,
)

/**
 * PaperBroker delegate 群で共有する market execution service。
 *
 * @param marketDataSource paper 約定に使う市場データ source
 * @param paperExecutionConfig paper 約定近似設定
 * @param fillSimulator paper 約定 simulator
 * @param warnLogger rate-limited warn logger
 */
private data class PaperBrokerMarketServices(
    val marketDataSource: MarketDataSource?,
    val paperExecutionConfig: PaperExecutionConfig,
    val fillSimulator: PaperExecutionSimulator,
    val warnLogger: RateLimitedWarnLogger,
)

/**
 * PaperBroker delegate 群で共有する時刻設定。
 *
 * @param clock audit / 約定時刻用 clock
 * @param tradingDateZone 当日判定に使う timezone
 * @param falsificationFreshnessWindow fresh APPROVED falsification とみなす時間窓
 */
private data class PaperBrokerTimeConfig(
    val clock: Clock,
    val tradingDateZone: ZoneId,
    val falsificationFreshnessWindow: Duration,
)

/**
 * PaperBroker delegate 群で共有する runtime context。
 *
 * @param stores repository 群
 * @param safety SafetyFloor 関連 service
 * @param market market execution service
 * @param time 時刻設定
 * @param reconcilerStatusProvider ProtectionReconciler 状態 provider
 */
private data class PaperBrokerRuntime(
    val stores: PaperBrokerStores,
    val safety: PaperBrokerSafetyServices,
    val market: PaperBrokerMarketServices,
    val time: PaperBrokerTimeConfig,
    val reconcilerStatusProvider: ReconcilerStatusProvider,
)

/**
 * close_position で実際に決済する数量。
 *
 * @param sizeBtc 決済数量
 */
private data class CloseExecutionPlan(
    val sizeBtc: BigDecimal,
)

/**
 * 即時 entry 約定と paper/live 乖離 memo。
 *
 * @param fill paper 約定
 * @param divergenceMemo paper/live 乖離を audit に渡す structured memo
 */
private data class ImmediateEntryUpdate(
    val fill: SimulatedFill,
    val divergenceMemo: PaperExecutionDivergenceMemo? = null,
)

/**
 * PaperBroker の read boundary 実装。
 *
 * @param runtime PaperBroker runtime context
 */
private class PaperBrokerReadDelegate(
    private val runtime: PaperBrokerRuntime,
) : BrokerReadBoundary {
    override suspend fun getBalance(): Result<AccountSnapshot> {
        return runtime.stores.ledgerRepository.getAccountSnapshot()
    }

    override suspend fun getBalanceWithUpdatedAt(): Result<AccountSnapshotWithUpdatedAt> {
        return runtime.stores.ledgerRepository.getAccountSnapshotWithUpdatedAt()
    }

    override suspend fun getPositions(): Result<List<Position>> {
        return runtime.stores.ledgerRepository.getOpenPositions()
    }

    override suspend fun getPositionsWithUpdatedAt(): Result<PositionsWithUpdatedAt> {
        return runtime.stores.ledgerRepository.getOpenPositionsWithUpdatedAt()
    }

    override suspend fun getOpenOrders(): Result<List<Order>> {
        return runtime.stores.ledgerRepository.getOpenOrders()
    }

    override suspend fun getOpenOrdersWithUpdatedAt(): Result<OpenOrdersWithUpdatedAt> {
        return runtime.stores.ledgerRepository.getOpenOrdersWithUpdatedAt()
    }

    override suspend fun getAccountStatus(): Result<AccountStatus> {
        return getAccountStatusWithUpdatedAt().map { statusWithUpdatedAt -> statusWithUpdatedAt.accountStatus }
    }

    override suspend fun getAccountStatusWithUpdatedAt(): Result<AccountStatusWithUpdatedAt> {
        return runCatching {
            val accountSnapshotWithUpdatedAt = runtime.stores.ledgerRepository
                .getAccountSnapshotWithUpdatedAt()
                .getOrThrow()
            val accountSnapshot = accountSnapshotWithUpdatedAt.accountSnapshot
            val riskState = runtime.stores.riskStateRepository.current().getOrThrow()
            val positions = runtime.stores.ledgerRepository.getOpenPositions().getOrThrow()
            val openOrders = runtime.stores.ledgerRepository.getOpenOrders().getOrThrow()
            val reconcilerStatus = runtime.reconcilerStatusProvider.snapshot()
            val today = LocalDate.now(runtime.time.clock.withZone(runtime.time.tradingDateZone))
            val todayRealizedPnlJpy = runtime.stores.ledgerRepository.getRealizedPnlForDate(today).getOrThrow()

            AccountStatusWithUpdatedAt(
                accountStatus = AccountStatus(
                    mode = accountSnapshot.mode,
                    riskState = riskState.state.name,
                    drawdownRatio = riskState.drawdownRatio.toPlainString(),
                    hardHalt = riskState.state == RiskHaltState.HARD_HALT,
                    currentEquityJpy = accountSnapshot.totalEquityJpy,
                    todayRealizedPnlJpy = todayRealizedPnlJpy.toPlainString(),
                    protectionStatus = protectionStatus(positions, openOrders, reconcilerStatus),
                ),
                updatedAt = accountSnapshotWithUpdatedAt.updatedAt,
            )
        }
    }

    private fun protectionStatus(
        positions: List<Position>,
        openOrders: List<Order>,
        reconcilerStatus: ReconcilerStatus,
    ): ProtectionStatus {
        val openPositions = positions.filter { position -> position.status == PositionStatus.OPEN }
        val activeStopOrderPositionIds = openOrders
            .filter { order -> order.isActiveProtectionStop() }
            .mapNotNull { order -> order.positionId }
            .toSet()
        val protectedPositionCount = openPositions.count { position ->
            position.currentStopLossJpy != null && position.positionId in activeStopOrderPositionIds
        }
        val unprotectedPositionCount = openPositions.size - protectedPositionCount
        val orphanStopCount = openOrders.count { order -> order.orderType == OrderType.STOP && order.positionId == null }
        val orphanTakeProfitCount = openOrders.count { order -> order.isTakeProfitCandidate() && order.positionId == null }
        val pendingCancelCount = openOrders.count { order -> order.status == OrderStatus.PENDING_CANCEL }

        return ProtectionStatus(
            protectedPositionCount = protectedPositionCount,
            unprotectedPositionCount = unprotectedPositionCount,
            orphanStopCount = orphanStopCount,
            orphanTakeProfitCount = orphanTakeProfitCount,
            pendingCancelCount = pendingCancelCount,
            lastReconciledAt = reconcilerStatus.lastReconciledAt?.toString(),
            lastMarketDataAt = reconcilerStatus.lastMarketDataAt?.toString(),
            tradingLockOwner = null,
        )
    }
}

/**
 * PaperBroker の trade boundary 実装。
 *
 * @param runtime PaperBroker runtime context
 */
private class PaperBrokerTradeDelegate(
    private val runtime: PaperBrokerRuntime,
) : BrokerTradeBoundary {
    private val marketContextFactory = PaperBrokerMarketContextFactory(runtime)
    private val intentConsumer = PaperBrokerEntryIntentConsumer(runtime)
    private val safetyGate = PaperBrokerSafetyGate(runtime)

    override suspend fun placeOrder(command: PlaceOrderCommand): Result<PaperTradeResult> {
        return runCatching {
            validatePlaceOrderCommand(command)

            runtime.findExistingPlaceOrderResult(command)?.let { existingResult ->
                return@runCatching existingResult
            }

            val preparedOrder = preparePlaceOrder(command)

            safetyGate.enforceSafetyFloor(
                verdict = runtime.safety.safetyFloor.evaluatePlaceOrder(
                    preparedOrder.command,
                    preparedOrder.safetyContext,
                ),
                command = preparedOrder.command,
                ticker = preparedOrder.ticker,
                symbolRules = preparedOrder.symbolRules,
            )?.let { rejectedResult -> return@runCatching rejectedResult }

            validateSymbolRules(preparedOrder.command, preparedOrder.symbolRules)
            validateEntryPriceContract(preparedOrder.command, preparedOrder.ticker)
            val immediateUpdate = immediateEntryUpdateOrNull(
                preparedOrder.command,
                preparedOrder.ticker,
                preparedOrder.symbolRules,
            )

            if (immediateUpdate != null) {
                return@runCatching intentConsumer.fillMarketEntryAndConsumeIntent(
                    MarketEntryFillRequest(
                        command = preparedOrder.command,
                        fill = immediateUpdate.fill,
                        positionId = UUID.randomUUID(),
                        tradeGroupId = preparedOrder.tradeGroupId,
                        stopOrderId = UUID.randomUUID(),
                        divergenceMemo = immediateUpdate.divergenceMemo,
                    ),
                )
            }

            runtime.validateCashAvailability(
                command = preparedOrder.command,
                ticker = preparedOrder.ticker,
                rules = preparedOrder.symbolRules,
            )

            intentConsumer.createRestingEntryOrderAndConsumeIntent(
                RestingEntryOrderRequest(
                    command = preparedOrder.command,
                    orderId = UUID.randomUUID(),
                    tradeGroupId = preparedOrder.tradeGroupId,
                ),
            )
        }
    }

    private suspend fun immediateEntryUpdateOrNull(
        command: PlaceOrderCommand,
        ticker: Ticker,
        symbolRules: SymbolRules,
    ): ImmediateEntryUpdate? {
        return when (command.orderType) {
            OrderType.MARKET -> ImmediateEntryUpdate(marketEntryFill(command, ticker, symbolRules))
            OrderType.LIMIT -> crossingLimitEntryUpdateOrNull(command, ticker, symbolRules)
            OrderType.STOP -> null
        }
    }

    private suspend fun marketEntryFill(
        command: PlaceOrderCommand,
        ticker: Ticker,
        symbolRules: SymbolRules,
    ): SimulatedFill {
        val executionContext = runtime.paperSimulationContext(
            symbol = command.symbol,
            ticker = ticker,
            rules = symbolRules,
            includeOrderbook = true,
            includeVolatilitySlippage = true,
        )

        runtime.validateCashAvailability(
            command = command,
            ticker = ticker,
            rules = symbolRules,
            executionContext = executionContext,
        )

        return runtime.market.fillSimulator.marketFill(
            side = command.side,
            sizeBtc = command.sizeBtc,
            context = executionContext,
        )
    }

    private suspend fun crossingLimitEntryUpdateOrNull(
        command: PlaceOrderCommand,
        ticker: Ticker,
        symbolRules: SymbolRules,
    ): ImmediateEntryUpdate? {
        val limitPriceJpy = requireNotNull(command.priceJpy) {
            "LIMIT order requires priceJpy."
        }
        val executionContext = runtime.paperSimulationContext(
            symbol = command.symbol,
            ticker = ticker,
            rules = symbolRules,
            includeOrderbook = true,
            includeVolatilitySlippage = false,
        )

        if (!limitOrderCrossesBook(command.side, limitPriceJpy, executionContext)) {
            return null
        }

        val fill = runtime.market.fillSimulator.limitTakerFill(
            side = command.side,
            sizeBtc = command.sizeBtc,
            limitPriceJpy = limitPriceJpy,
            context = executionContext,
        )
        val divergenceMemo = limitFillDivergenceMemo(
            request = PendingLimitExecutionRequest(
                side = command.side,
                sizeBtc = command.sizeBtc,
                limitPriceJpy = limitPriceJpy,
            ),
            context = executionContext,
            warnLogger = runtime.market.warnLogger,
        )

        runtime.validateCashAvailability(
            command = command,
            ticker = ticker,
            rules = symbolRules,
            immediateFill = fill,
        )

        return ImmediateEntryUpdate(
            fill = fill,
            divergenceMemo = divergenceMemo,
        )
    }

    override suspend fun previewOrder(command: PlaceOrderCommand): Result<PreviewOrderResult> {
        return runCatching {
            validatePlaceOrderCommand(command)

            val preparedOrder = preparePlaceOrder(command)
            val riskDetails = runtime.safety.safetyFloor.placeOrderRiskDetails(
                preparedOrder.command,
                preparedOrder.safetyContext,
            )
            val normalizedOrderContent = command.toPreviewOrderNormalizedContent()
            val previewHash = normalizedOrderContent.calculatePreviewHash()
            val verdict = runtime.safety.safetyFloor.evaluatePlaceOrder(
                preparedOrder.command,
                preparedOrder.safetyContext,
            )

            if (verdict is SafetyFloorVerdict.Rejected) {
                return@runCatching PreviewOrderResult(
                    accepted = false,
                    previewHash = previewHash,
                    normalizedOrderContent = normalizedOrderContent,
                    riskDetails = riskDetails,
                    messageJa = verdict.violation.messageJa,
                    safetyViolation = verdict.violation,
                )
            }

            validateSymbolRules(preparedOrder.command, preparedOrder.symbolRules)
            validateEntryPriceContract(preparedOrder.command, preparedOrder.ticker)
            runtime.validateCashAvailability(
                command = preparedOrder.command,
                ticker = preparedOrder.ticker,
                rules = preparedOrder.symbolRules,
            )

            PreviewOrderResult(
                accepted = true,
                previewHash = previewHash,
                normalizedOrderContent = normalizedOrderContent,
                riskDetails = riskDetails,
                messageJa = "paper entry 注文 preview は SafetyFloor と broker 事前検証を通過しました。",
            )
        }
    }

    private suspend fun preparePlaceOrder(command: PlaceOrderCommand): PreparedPlaceOrder {
        val ticker = runtime.tickerFor(command.symbol).getOrThrow()
        val symbolRules = runtime.symbolRulesFor(command.symbol).getOrThrow()
        val context = marketContextFactory.safetyContext(
            ticker = ticker,
            symbolRules = symbolRules,
            includeAtr = true,
            intentId = command.intentId,
        )
        val tradeGroupId = resolveTradeGroupId(command, context.positions)
        val resolvedCommand = command.copy(tradeGroupId = tradeGroupId)
        val safetyContext = marketContextFactory.safetyContext(
            ticker = ticker,
            symbolRules = symbolRules,
            intentId = command.intentId,
            tradeGroupId = tradeGroupId,
        )

        return PreparedPlaceOrder(
            command = resolvedCommand,
            ticker = ticker,
            symbolRules = symbolRules,
            safetyContext = safetyContext,
            tradeGroupId = tradeGroupId,
        )
    }

    override suspend fun closePosition(command: ClosePositionCommand): Result<PaperTradeResult> {
        return runCatching {
            validateClosePositionCommand(command)

            val ticker = runtime.tickerFor(TradingSymbol.BTC).getOrThrow()
            val symbolRules = runtime.symbolRulesFor(TradingSymbol.BTC).getOrThrow()
            val context = marketContextFactory.safetyContext(ticker, symbolRules)

            safetyGate.enforceSafetyFloor(
                verdict = runtime.safety.safetyFloor.evaluateClosePosition(command, context),
                command = command,
                ticker = ticker,
                symbolRules = symbolRules,
            )?.let { rejectedResult -> return@runCatching rejectedResult }

            val openPositions = runtime.stores.ledgerRepository.getOpenPositions().getOrThrow()
            val targetPositions = resolveCloseTargets(command, openPositions)
            val executionContext = runtime.paperSimulationContext(
                symbol = TradingSymbol.BTC,
                ticker = ticker,
                rules = symbolRules,
                includeOrderbook = true,
                includeVolatilitySlippage = true,
            )
            val results = targetPositions.map { position ->
                val closeCommand = command.forCloseTarget(position, targetPositions.size)
                val closePlan = resolveCloseExecutionPlan(closeCommand, position, symbolRules)
                val fill = runtime.market.fillSimulator.marketFill(
                    side = OrderSide.SELL,
                    sizeBtc = closePlan.sizeBtc,
                    context = executionContext,
                )

                runtime.stores.ledgerRepository.closePosition(
                    command = closeCommand,
                    positionId = UUID.fromString(position.positionId),
                    orderId = UUID.randomUUID(),
                    fill = fill,
                ).getOrThrow()
            }

            mergeTradeResults(results, "position を close しました。")
        }
    }

    override suspend fun updateProtection(command: UpdateProtectionCommand): Result<PaperTradeResult> {
        return runCatching {
            validateReason(command.reasonJa)
            validateProtectionUpdateHasChange(command)

            val ticker = runtime.tickerFor(TradingSymbol.BTC).getOrThrow()
            val symbolRules = runtime.symbolRulesFor(TradingSymbol.BTC).getOrThrow()
            val context = marketContextFactory.safetyContext(
                ticker = ticker,
                symbolRules = symbolRules,
                includeAtr = true,
            )

            safetyGate.enforceSafetyFloor(
                verdict = runtime.safety.safetyFloor.evaluateUpdateProtection(command, context),
                command = command,
                ticker = ticker,
                symbolRules = symbolRules,
            )?.let { rejectedResult -> return@runCatching rejectedResult }

            validateStopPriceIfPresent(command, ticker, symbolRules)

            runtime.stores.ledgerRepository.updateProtection(command).getOrThrow()
        }
    }

    override suspend fun cancelOrder(command: CancelOrderCommand): Result<PaperTradeResult> {
        return runCatching {
            validateReason(command.reasonJa)

            val ticker = runtime.tickerFor(TradingSymbol.BTC).getOrThrow()
            val symbolRules = runtime.symbolRulesFor(TradingSymbol.BTC).getOrThrow()
            val context = marketContextFactory.safetyContext(ticker, symbolRules)

            safetyGate.enforceSafetyFloor(
                verdict = runtime.safety.safetyFloor.evaluateCancelOrder(command, context),
                command = command,
                ticker = ticker,
                symbolRules = symbolRules,
            )?.let { rejectedResult -> return@runCatching rejectedResult }

            runtime.stores.ledgerRepository.cancelOrder(command).getOrThrow()
        }
    }
}

/**
 * PaperBroker の reconcile boundary 実装。
 *
 * @param runtime PaperBroker runtime context
 */
private class PaperBrokerReconcileDelegate(
    private val runtime: PaperBrokerRuntime,
) : BrokerReconcileBoundary {
    private val safetyGate = PaperBrokerSafetyGate(runtime)

    override suspend fun reconcile(tickSnapshot: TickSnapshot): Result<PaperReconcileResult> {
        return runCatching {
            val simulationContext = runtime.reconcileSimulationContext(tickSnapshot)
            val result = runtime.stores.ledgerRepository
                .reconcile(
                    tickSnapshot = tickSnapshot,
                    simulator = runtime.market.fillSimulator,
                    simulationContext = simulationContext,
                )
                .getOrThrow()

            safetyGate.activateHardHaltIfAccountDrawdownReached()

            result
        }
    }

    override suspend fun sweepHardHalt(reasonJa: String, tickSnapshot: TickSnapshot): Result<PaperTradeResult> {
        return runCatching {
            val ticker = tickSnapshot.requireTicker()
            val symbolRules = tickSnapshot.symbolRules ?: runtime.symbolRulesFor(TradingSymbol.BTC).getOrThrow()
            val simulationContext = runtime.paperSimulationContext(
                symbol = TradingSymbol.BTC,
                ticker = ticker,
                rules = symbolRules,
                atr14Jpy = tickSnapshot.atr14Jpy?.toBigDecimalOrNull(),
                includeOrderbook = true,
                includeVolatilitySlippage = true,
            )

            safetyGate.sweepOpenRisk(
                reason = reasonJa,
                auditContext = PaperTradeAuditContext.EMPTY,
                simulationContext = simulationContext,
            )
        }
    }
}

private data class PreparedPlaceOrder(
    val command: PlaceOrderCommand,
    val ticker: Ticker,
    val symbolRules: SymbolRules,
    val safetyContext: SafetyFloorContext,
    val tradeGroupId: UUID,
)

/**
 * SafetyFloor 評価に渡す market context を組み立てる helper。
 *
 * @param runtime PaperBroker runtime context
 */
private class PaperBrokerMarketContextFactory(
    private val runtime: PaperBrokerRuntime,
) {
    suspend fun safetyContext(
        ticker: Ticker,
        symbolRules: SymbolRules,
        includeAtr: Boolean = false,
        intentId: UUID? = null,
        tradeGroupId: UUID? = null,
    ): SafetyFloorContext {
        val observedAt = Instant.now(runtime.time.clock)
        val entryIntent = intentId?.let { requestedIntentId ->
            runtime.stores.decisionRepository.entryIntentSafetySnapshot(
                intentId = requestedIntentId,
                observedAt = observedAt,
                freshnessWindow = runtime.time.falsificationFreshnessWindow,
            ).getOrThrow()
        }
        val tradeGroupOrders = tradeGroupId
            ?.let { requestedTradeGroupId ->
                runtime.stores.ledgerRepository.findOrdersByTradeGroupId(requestedTradeGroupId).getOrThrow()
            }
            .orEmpty()
        val tradeGroupOrderIds = tradeGroupOrders.map { order -> order.orderId }.toSet()
        val tradeGroupExecutions = if (tradeGroupOrderIds.isEmpty()) {
            emptyList()
        } else {
            runtime.stores.ledgerRepository.getExecutions().getOrThrow()
                .filter { execution -> execution.orderId in tradeGroupOrderIds }
        }

        return SafetyFloorContext(
            account = runtime.stores.ledgerRepository.getAccountSnapshot().getOrThrow(),
            riskState = runtime.stores.riskStateRepository.current().getOrThrow(),
            positions = runtime.stores.ledgerRepository.getOpenPositions().getOrThrow(),
            openOrders = runtime.stores.ledgerRepository.getOpenOrders().getOrThrow(),
            tradeGroupOrders = tradeGroupOrders,
            tradeGroupExecutions = tradeGroupExecutions,
            ticker = ticker,
            symbolRules = symbolRules,
            entryIntent = entryIntent,
            atr14Jpy = if (includeAtr) {
                runtime.atr14JpyFor(
                    symbol = TradingSymbol.BTC,
                    warnOnFailure = false,
                )
            } else {
                null
            },
            marketDataObservedAt = ticker.marketDataObservedAtOrNull(),
        )
    }

    private fun Ticker.marketDataObservedAtOrNull(): Instant? {
        return try {
            Instant.parse(timestamp)
        } catch (exception: DateTimeParseException) {
            runtime.market.warnLogger.warn(
                key = "paper-broker-ticker-timestamp-parse-failure",
                message = "PaperBroker could not parse ticker timestamp. Data quality probability cap will fail closed.",
                throwable = exception,
            )
            null
        }
    }
}

/**
 * entry intent の atomic consumption と ledger write をまとめる helper。
 *
 * @param runtime PaperBroker runtime context
 */
private class PaperBrokerEntryIntentConsumer(
    private val runtime: PaperBrokerRuntime,
) {
    suspend fun fillMarketEntryAndConsumeIntent(request: MarketEntryFillRequest): PaperTradeResult {
        val intentId = requireEntryIntentId(request.command)
        val consumedAt = Instant.now(runtime.time.clock)
        val atomicRepository = runtime.stores.ledgerRepository as? IntentConsumingPaperLedgerRepository
        val consumption = TradeIntentConsumptionRequest(
            intentId = intentId,
            consumedAt = consumedAt,
        )

        if (atomicRepository != null) {
            return atomicRepository.fillMarketEntryAndConsumeIntent(
                IntentConsumingMarketEntryFillRequest(
                    entry = request,
                    consumption = consumption,
                ),
            ).getOrThrow()
        }

        return atomicDecisionRepository().consumeIntentAfterLedgerWrite(
            intentId = intentId,
            orderId = request.command.commandId,
            consumedAt = consumedAt,
        ) {
            runtime.stores.ledgerRepository.fillMarketEntry(request).getOrThrow()
        }.getOrThrow()
    }

    suspend fun createRestingEntryOrderAndConsumeIntent(request: RestingEntryOrderRequest): PaperTradeResult {
        val intentId = requireEntryIntentId(request.command)
        val consumedAt = Instant.now(runtime.time.clock)
        val atomicRepository = runtime.stores.ledgerRepository as? IntentConsumingPaperLedgerRepository
        val consumption = TradeIntentConsumptionRequest(
            intentId = intentId,
            consumedAt = consumedAt,
        )

        if (atomicRepository != null) {
            return atomicRepository.createRestingEntryOrderAndConsumeIntent(
                IntentConsumingRestingEntryOrderRequest(
                    order = request,
                    consumption = consumption,
                ),
            ).getOrThrow()
        }

        return atomicDecisionRepository().consumeIntentAfterLedgerWrite(
            intentId = intentId,
            orderId = request.orderId,
            consumedAt = consumedAt,
        ) {
            runtime.stores.ledgerRepository.createRestingEntryOrder(request).getOrThrow()
        }.getOrThrow()
    }

    private fun requireEntryIntentId(command: PlaceOrderCommand): UUID {
        return requireNotNull(command.intentId) {
            "intentId is required for entry order."
        }
    }

    private fun atomicDecisionRepository(): AtomicIntentConsumptionRepository {
        return requireNotNull(runtime.stores.decisionRepository as? AtomicIntentConsumptionRepository) {
            "PaperBroker requires AtomicIntentConsumptionRepository for non-transactional ledger repositories."
        }
    }
}

/**
 * SafetyFloor violation と HARD_HALT 副作用を処理する helper。
 *
 * @param runtime PaperBroker runtime context
 */
private class PaperBrokerSafetyGate(
    private val runtime: PaperBrokerRuntime,
) {
    suspend fun enforceSafetyFloor(
        verdict: SafetyFloorVerdict,
        command: Any,
        ticker: Ticker,
        symbolRules: SymbolRules,
    ): PaperTradeResult? {
        if (verdict is SafetyFloorVerdict.Accepted) {
            return null
        }

        val violation = (verdict as SafetyFloorVerdict.Rejected).violation

        runtime.safety.safetyViolationRepository.append(violation).getOrThrow()

        val sweepResult = if (violation.hardHaltRequired) {
            activateHardHaltAndSweep(violation, command, ticker, symbolRules)
        } else {
            null
        }

        return rejectedTradeResult(violation, sweepResult)
    }

    suspend fun sweepOpenRisk(
        reason: String,
        auditContext: PaperTradeAuditContext,
        simulationContext: PaperSimulationContext,
    ): PaperTradeResult {
        val cancelResults = runtime.stores.ledgerRepository.getOpenOrders()
            .getOrThrow()
            .filterNot { order -> order.isLinkedProtectiveStop() }
            .map { order ->
                runtime.stores.ledgerRepository.cancelOrder(
                    CancelOrderCommand(
                        commandId = UUID.randomUUID(),
                        orderId = UUID.fromString(order.orderId),
                        reasonJa = reason,
                        auditContext = auditContext,
                    ),
                ).getOrThrow()
            }
        val closeResults = runtime.stores.ledgerRepository.getOpenPositions()
            .getOrThrow()
            .map { position ->
                val fill = runtime.market.fillSimulator.marketFill(
                    side = OrderSide.SELL,
                    sizeBtc = position.sizeBtc.toBigDecimal(),
                    context = simulationContext,
                )

                runtime.stores.ledgerRepository.closePosition(
                    command = ClosePositionCommand(
                        commandId = UUID.randomUUID(),
                        positionId = UUID.fromString(position.positionId),
                        closeAll = false,
                        reasonJa = reason,
                        auditContext = auditContext,
                    ),
                    positionId = UUID.fromString(position.positionId),
                    orderId = UUID.randomUUID(),
                    fill = fill,
                ).getOrThrow()
            }

        return mergeTradeResults(
            results = cancelResults + closeResults,
            messageJa = "HARD_HALT 掃引で open order を取消し、open position を close しました。",
        )
    }

    suspend fun activateHardHaltIfAccountDrawdownReached() {
        val accountSnapshot = runtime.stores.ledgerRepository.getAccountSnapshot().getOrThrow()
        val drawdownRatio = accountSnapshot.drawdownRatio.toBigDecimal()

        if (drawdownRatio > SafetyFloorDefaults.maxDrawdownRatio) {
            return
        }

        val riskState = runtime.stores.riskStateRepository.current().getOrThrow()

        if (riskState.state == RiskHaltState.HARD_HALT) {
            return
        }

        val reason = "Paper account drawdown reached HARD_HALT threshold."

        if (runtime.safety.riskStateCommandService != null) {
            runtime.safety.riskStateCommandService
                .setHardHalt(reason, PaperTradeAuditContext.EMPTY.decisionRunContext)
                .getOrThrow()
        } else {
            runtime.stores.riskStateRepository.setHardHalt(reason, Instant.now(runtime.time.clock)).getOrThrow()
        }
    }

    private suspend fun activateHardHaltAndSweep(
        violation: SafetyViolation,
        command: Any,
        ticker: Ticker,
        symbolRules: SymbolRules,
    ): PaperTradeResult {
        val reason = "SafetyFloor HARD_HALT: ${violation.rule.name}"
        val decisionRunContext = command.auditContext().decisionRunContext

        if (runtime.safety.riskStateCommandService != null) {
            runtime.safety.riskStateCommandService.setHardHalt(reason, decisionRunContext).getOrThrow()
        } else {
            runtime.stores.riskStateRepository.setHardHalt(reason, Instant.now(runtime.time.clock)).getOrThrow()
        }

        val simulationContext = runtime.paperSimulationContext(
            symbol = TradingSymbol.BTC,
            ticker = ticker,
            rules = symbolRules,
            includeOrderbook = true,
            includeVolatilitySlippage = true,
        )

        return sweepOpenRisk(reason, command.auditContext(), simulationContext)
    }
}

private fun ClosePositionCommand.forCloseTarget(position: Position, targetCount: Int): ClosePositionCommand {
    if (targetCount <= 1) {
        return this
    }

    val baseClientRequestId = auditContext.clientRequestId?.takeIf { requestId -> requestId.isNotBlank() }
        ?: return copy(auditContext = auditContext.copy(clientRequestId = null))
    val derivedClientRequestId = baseClientRequestId.deriveCloseAllClientRequestId(position.positionId)

    return copy(auditContext = auditContext.copy(clientRequestId = derivedClientRequestId))
}

private fun String.deriveCloseAllClientRequestId(positionId: String): String {
    val suffix = "$CLIENT_REQUEST_ID_DERIVATION_SEPARATOR$positionId"
    val maxBaseLength = (CLIENT_REQUEST_ID_MAX_LENGTH - suffix.length).coerceAtLeast(0)
    val boundedBase = take(maxBaseLength)

    return boundedBase + suffix
}

private suspend fun PaperBrokerRuntime.findExistingPlaceOrderResult(command: PlaceOrderCommand): PaperTradeResult? {
    val clientRequestId = command.auditContext.clientRequestId?.takeIf { requestId -> requestId.isNotBlank() }
        ?: return null

    return stores.ledgerRepository.findPlaceOrderResultByClientRequestId(clientRequestId).getOrThrow()
}

private suspend fun PaperBrokerRuntime.tickerFor(symbol: TradingSymbol): Result<Ticker> {
    val marketData = requireNotNull(market.marketDataSource) {
        "MarketDataSource is required for paper execution."
    }

    return marketData.getTicker(symbol)
}

private suspend fun PaperBrokerRuntime.symbolRulesFor(symbol: TradingSymbol): Result<SymbolRules> {
    val marketData = requireNotNull(market.marketDataSource) {
        "MarketDataSource is required for paper execution."
    }

    return marketData.getSymbolRules(symbol)
}

private suspend fun PaperBrokerRuntime.paperSimulationContext(
    symbol: TradingSymbol,
    ticker: Ticker,
    rules: SymbolRules,
    atr14Jpy: BigDecimal? = null,
    includeOrderbook: Boolean,
    includeVolatilitySlippage: Boolean,
): PaperSimulationContext {
    val orderbook = if (includeOrderbook) {
        orderbookFor(symbol)
    } else {
        null
    }
    val volatilitySlippageJpy = if (includeVolatilitySlippage) {
        volatilitySlippageJpyFor(symbol, atr14Jpy)
    } else {
        BigDecimal.ZERO
    }

    return PaperSimulationContext(
        ticker = ticker,
        rules = rules,
        orderbook = orderbook,
        orderbookLookupAttempted = includeOrderbook,
        volatilitySlippageJpy = volatilitySlippageJpy,
    )
}

private suspend fun PaperBrokerRuntime.reconcileSimulationContext(tickSnapshot: TickSnapshot): PaperSimulationContext {
    val ticker = tickSnapshot.requireTicker()
    val rules = tickSnapshot.symbolRules ?: symbolRulesFor(TradingSymbol.BTC).getOrThrow()
    val lastPrice = tickSnapshot.lastPrice?.toBigDecimal() ?: ticker.last.toBigDecimal()
    val immediateFillTriggered = immediateReconcileFillTriggered(lastPrice)
    val orderbookNeeded = immediateFillTriggered || openLimitEntryOrderExists()

    return paperSimulationContext(
        symbol = TradingSymbol.BTC,
        ticker = ticker,
        rules = rules,
        atr14Jpy = tickSnapshot.atr14Jpy?.toBigDecimalOrNull(),
        includeOrderbook = orderbookNeeded,
        includeVolatilitySlippage = immediateFillTriggered,
    )
}

private suspend fun PaperBrokerRuntime.immediateReconcileFillTriggered(lastPrice: BigDecimal): Boolean {
    val openOrders = stores.ledgerRepository.getOpenOrders().getOrThrow()
    val buyStopTriggered = openOrders.any { order -> order.isTriggeredBuyStop(lastPrice) }

    if (buyStopTriggered) {
        return true
    }

    return stores.ledgerRepository.getOpenPositions()
        .getOrThrow()
        .any { position -> position.immediateProtectionTriggered(lastPrice) }
}

private suspend fun PaperBrokerRuntime.openLimitEntryOrderExists(): Boolean {
    return stores.ledgerRepository.getOpenOrders()
        .getOrThrow()
        .any { order ->
            order.status == OrderStatus.OPEN &&
                order.side == OrderSide.BUY &&
                order.orderType == OrderType.LIMIT
        }
}

private fun Order.isTriggeredBuyStop(lastPrice: BigDecimal): Boolean {
    val triggerPrice = triggerPriceJpy?.toBigDecimalOrNull() ?: return false
    val isBuyStop = side == OrderSide.BUY && orderType == OrderType.STOP
    val isOpenBuyStop = status == OrderStatus.OPEN && isBuyStop
    val hasReachedTrigger = lastPrice >= triggerPrice

    return isOpenBuyStop && hasReachedTrigger
}

private fun Position.immediateProtectionTriggered(lastPrice: BigDecimal): Boolean {
    val stopPrice = currentStopLossJpy?.toBigDecimalOrNull()
    val takeProfitPrice = currentTakeProfitJpy?.toBigDecimalOrNull()
    val stopTriggered = stopPrice != null && lastPrice <= stopPrice
    val takeProfitTriggered = takeProfitPrice != null && lastPrice >= takeProfitPrice
    val hasTriggeredProtection = stopTriggered || takeProfitTriggered

    return status == PositionStatus.OPEN && hasTriggeredProtection
}

private suspend fun PaperBrokerRuntime.orderbookFor(symbol: TradingSymbol): Orderbook? {
    return market.marketDataSource
        ?.getOrderbook(symbol, PAPER_EXECUTION_ORDERBOOK_DEPTH)
        ?.onFailure { throwable ->
            market.warnLogger.warn(
                key = PAPER_EXECUTION_ORDERBOOK_FETCH_LOG_KEY,
                message = "PaperBroker could not fetch orderbook for paper execution; ticker fallback will be used.",
                throwable = throwable,
            )
        }
        ?.getOrNull()
}

private suspend fun PaperBrokerRuntime.volatilitySlippageJpyFor(
    symbol: TradingSymbol,
    atr14Jpy: BigDecimal?,
): BigDecimal {
    if (market.paperExecutionConfig.volatilitySlippageMultiplier.signum() == 0) {
        return BigDecimal.ZERO
    }

    val resolvedAtr14Jpy = atr14Jpy ?: atr14JpyFor(
        symbol = symbol,
        warnOnFailure = true,
    )

    if (resolvedAtr14Jpy == null) {
        market.warnLogger.warn(
            key = PAPER_EXECUTION_ATR_FALLBACK_LOG_KEY,
            message = "PaperBroker could not resolve ATR for paper volatility slippage; fixed bps slippage will be used.",
        )

        return BigDecimal.ZERO
    }

    return resolvedAtr14Jpy
        .multiply(market.paperExecutionConfig.volatilitySlippageMultiplier)
        .moneyScale()
}

private suspend fun PaperBrokerRuntime.atr14JpyFor(symbol: TradingSymbol, warnOnFailure: Boolean): BigDecimal? {
    val marketData = market.marketDataSource ?: return null
    val candles = marketData.getCandles(
        symbol = symbol,
        interval = CandleInterval.FIVE_MINUTES,
        limit = ATR_CANDLE_LIMIT,
    )
        .onFailure { throwable ->
            if (warnOnFailure) {
                market.warnLogger.warn(
                    key = PAPER_EXECUTION_ATR_FETCH_LOG_KEY,
                    message = "PaperBroker could not fetch candles for paper ATR slippage.",
                    throwable = throwable,
                )
            }
        }
        .getOrNull()
        ?: return null
    val atr = IndicatorCalculator.calculate(
        candles = candles,
        indicator = IndicatorType.ATR,
        params = IndicatorParams(period = ATR_PERIOD),
    )
        .getOrNull()
        ?: return null
    val latestAtr = atr.values
        .lastOrNull { value -> value.value != null }
        ?.value
        ?: return null

    return BigDecimal.valueOf(latestAtr)
        .setScale(ATR_SCALE, RoundingMode.HALF_UP)
}

private fun validatePlaceOrderCommand(command: PlaceOrderCommand) {
    validateReason(command.reasonJa)

    require(command.symbol == TradingSymbol.BTC) {
        "BTC spot is the only supported symbol."
    }
    require(command.side == OrderSide.BUY) {
        "place_order supports BUY entry only. Use close_position for SELL."
    }
    require(command.sizeBtc > BigDecimal.ZERO) {
        "sizeBtc must be greater than zero."
    }
}

private fun validateClosePositionCommand(command: ClosePositionCommand) {
    validateReason(command.reasonJa)

    require(command.closeRatio > BigDecimal.ZERO && command.closeRatio <= BigDecimal.ONE) {
        "closeRatio must be greater than zero and less than or equal to 1."
    }
    require(!(command.closeAll && command.closeRatio.compareTo(BigDecimal.ONE) != 0)) {
        "closeAll=true cannot be combined with a partial closeRatio."
    }
}

private fun validateSymbolRules(command: PlaceOrderCommand, rules: SymbolRules) {
    val minOrderSize = rules.minOrderSize.toBigDecimal()
    val sizeStep = rules.sizeStep.toBigDecimal()
    val tickSize = rules.tickSize.toBigDecimal()

    require(command.sizeBtc >= minOrderSize) {
        "sizeBtc must be at least GMO minOrderSize $minOrderSize."
    }
    require(command.sizeBtc.isMultipleOf(sizeStep)) {
        "sizeBtc must be aligned to GMO sizeStep $sizeStep."
    }

    command.priceJpy?.let { price ->
        require(price.isMultipleOf(tickSize)) {
            "priceJpy must be aligned to GMO tickSize $tickSize."
        }
    }
    require(command.protectiveStopPriceJpy.isMultipleOf(tickSize)) {
        "protectiveStopPriceJpy must be aligned to GMO tickSize $tickSize."
    }
    command.takeProfitPriceJpy?.let { takeProfitPrice ->
        require(takeProfitPrice.isMultipleOf(tickSize)) {
            "takeProfitPriceJpy must be aligned to GMO tickSize $tickSize."
        }
    }
}

private fun validateEntryPriceContract(command: PlaceOrderCommand, ticker: Ticker) {
    val price = command.priceJpy
    val ask = ticker.ask.toBigDecimal()

    when (command.orderType) {
        OrderType.MARKET -> require(price == null) {
            "MARKET order must not include priceJpy."
        }
        OrderType.LIMIT -> requireNotNull(price) {
            "LIMIT order requires priceJpy."
        }
        OrderType.STOP -> validateStopEntryPrice(price, ask)
    }

    val referenceEntryPrice = price ?: ask

    require(command.protectiveStopPriceJpy < referenceEntryPrice) {
        "protectiveStopPriceJpy must be below entry price for long BTC spot."
    }
    command.takeProfitPriceJpy?.let { takeProfitPrice ->
        require(takeProfitPrice > referenceEntryPrice) {
            "takeProfitPriceJpy must be above entry price for long BTC spot."
        }
    }
}

private fun validateStopEntryPrice(price: BigDecimal?, ask: BigDecimal) {
    val stopPrice = requireNotNull(price) {
        "STOP order requires priceJpy."
    }

    require(stopPrice > ask) {
        "BUY STOP price would trigger immediately."
    }
}

private suspend fun PaperBrokerRuntime.validateCashAvailability(
    command: PlaceOrderCommand,
    ticker: Ticker,
    rules: SymbolRules,
    immediateFill: SimulatedFill? = null,
    executionContext: PaperSimulationContext? = null,
) {
    val balance = stores.ledgerRepository.getAccountSnapshot().getOrThrow()
    val openOrders = stores.ledgerRepository.getOpenOrders().getOrThrow()
    val cashJpy = balance.cashJpy.toBigDecimal()
    val reservedCashJpy = openOrders
        .filter { order -> order.side == OrderSide.BUY && order.status == OrderStatus.OPEN }
        .sumOf { order -> order.estimatedBuyReservationJpy(ticker, rules, market.fillSimulator) }
    val requiredCash = immediateFill?.requiredCashForBuy()
        ?: command.estimatedRequiredCash(ticker, rules, market.fillSimulator, executionContext)
    val availableCash = cashJpy.subtract(reservedCashJpy).moneyScale()

    require(requiredCash <= availableCash) {
        "Insufficient JPY cash for paper order. required=$requiredCash available=$availableCash."
    }
}

private fun SimulatedFill.requiredCashForBuy(): BigDecimal {
    val notional = priceJpy.multiply(sizeBtc)
    val cashFee = feeJpy.max(BigDecimal.ZERO)

    return notional.add(cashFee).moneyScale()
}

private fun PlaceOrderCommand.estimatedRequiredCash(
    ticker: Ticker,
    rules: SymbolRules,
    fillSimulator: PaperExecutionSimulator,
    executionContext: PaperSimulationContext?,
): BigDecimal {
    if (orderType == OrderType.MARKET) {
        val fill = if (executionContext == null) {
            fillSimulator.marketFill(side, sizeBtc, ticker, rules)
        } else {
            fillSimulator.marketFill(
                side = side,
                sizeBtc = sizeBtc,
                context = executionContext,
            )
        }
        val notional = fill.priceJpy.multiply(sizeBtc)

        return requiredCashFor(
            notional = notional,
            orderType = orderType,
            symbolRules = rules,
        ).moneyScale()
    }

    val estimatedPrice = requireNotNull(priceJpy) {
        "$orderType order requires priceJpy."
    }
    val estimatedNotional = estimatedPrice.multiply(sizeBtc)
    val requiredCash = requiredCashFor(
        notional = estimatedNotional,
        orderType = orderType,
        symbolRules = rules,
    )

    return requiredCash.moneyScale()
}

internal fun Order.estimatedBuyReservationJpy(
    ticker: Ticker,
    rules: SymbolRules,
    fillSimulator: PaperExecutionSimulator,
): BigDecimal {
    val price = when (orderType) {
        OrderType.MARKET -> fillSimulator.marketFill(side, sizeBtc.toBigDecimal(), ticker, rules).priceJpy
        OrderType.LIMIT -> requireNotNull(limitPriceJpy) {
            "LIMIT buy reservation requires limitPriceJpy."
        }.toBigDecimal()
        OrderType.STOP -> fillSimulator.stopFill(
            side = side,
            sizeBtc = sizeBtc.toBigDecimal(),
            triggerPriceJpy = requireNotNull(triggerPriceJpy) {
                "STOP buy reservation requires triggerPriceJpy."
            }.toBigDecimal(),
            ticker = ticker,
            rules = rules,
        ).priceJpy
    }
    val notional = price.multiply(sizeBtc.toBigDecimal())
    val requiredCash = requiredCashFor(
        notional = notional,
        orderType = orderType,
        symbolRules = rules,
    )

    return requiredCash.moneyScale()
}

private fun validateProtectionUpdateHasChange(command: UpdateProtectionCommand) {
    val hasStopChange = command.newStopPriceJpy != null
    val hasTakeProfitChange = command.takeProfitPriceSpecified

    require(hasStopChange || hasTakeProfitChange) {
        "update_protection requires newStopPriceJpy or newTakeProfitPriceJpy."
    }
}

private fun validateStopPriceIfPresent(
    command: UpdateProtectionCommand,
    ticker: Ticker,
    symbolRules: SymbolRules,
) {
    val newStopPrice = command.newStopPriceJpy ?: return
    val bid = ticker.bid.toBigDecimal()
    val tickSize = symbolRules.tickSize.toBigDecimal()

    require(newStopPrice.isMultipleOf(tickSize)) {
        "newStopPriceJpy must be aligned to GMO tickSize $tickSize."
    }
    require(newStopPrice < bid) {
        "SELL STOP price would trigger immediately."
    }
}

private fun resolveCloseTargets(command: ClosePositionCommand, openPositions: List<Position>): List<Position> {
    val targetPositions = if (command.closeAll) {
        openPositions
    } else {
        val positionId = requireNotNull(command.positionId) {
            "positionId is required unless closeAll is true."
        }

        openPositions.filter { position -> position.positionId == positionId.toString() }
    }

    require(targetPositions.isNotEmpty()) {
        "No open position matched close_position command."
    }

    return targetPositions
}

private fun resolveCloseExecutionPlan(
    command: ClosePositionCommand,
    position: Position,
    symbolRules: SymbolRules,
): CloseExecutionPlan {
    val positionSize = position.sizeBtc.toBigDecimal()
    val sizeStep = symbolRules.sizeStep.toBigDecimal()
    val requestedSize = if (command.closeAll || command.closeRatio.compareTo(BigDecimal.ONE) == 0) {
        positionSize
    } else {
        positionSize.multiply(command.closeRatio).floorToStep(sizeStep)
    }

    require(requestedSize > BigDecimal.ZERO) {
        "closeRatio is too small for GMO sizeStep $sizeStep."
    }

    val remainingSize = positionSize.subtract(requestedSize)
    val shouldPromoteToFullClose = remainingSize > BigDecimal.ZERO && remainingSize < sizeStep
    val closeSize = if (shouldPromoteToFullClose) positionSize else requestedSize

    return CloseExecutionPlan(sizeBtc = closeSize.btcScale())
}

private fun mergeTradeResults(results: List<PaperTradeResult>, messageJa: String): PaperTradeResult {
    return PaperTradeResult(
        accepted = true,
        status = OrderStatus.FILLED,
        orderIds = results.flatMap { result -> result.orderIds },
        positionIds = results.flatMap { result -> result.positionIds },
        executionIds = results.flatMap { result -> result.executionIds },
        messageJa = messageJa,
        divergenceMemos = results.flatMap { result -> result.divergenceMemos },
    )
}

private fun resolveTradeGroupId(command: PlaceOrderCommand, positions: List<Position>): UUID {
    command.tradeGroupId?.let { tradeGroupId -> return tradeGroupId }

    val openTradeGroupIds = positions
        .filter { position -> position.status == PositionStatus.OPEN }
        .map { position -> position.tradeGroupId }
        .distinct()

    if (openTradeGroupIds.isEmpty()) {
        return UUID.randomUUID()
    }

    require(openTradeGroupIds.size == 1) {
        "trade_group_id is required when multiple BTC trade groups are open."
    }

    return UUID.fromString(openTradeGroupIds.single())
}

private fun rejectedTradeResult(violation: SafetyViolation, sweepResult: PaperTradeResult?): PaperTradeResult {
    return PaperTradeResult(
        accepted = false,
        status = OrderStatus.REJECTED,
        orderIds = sweepResult?.orderIds.orEmpty(),
        positionIds = sweepResult?.positionIds.orEmpty(),
        executionIds = sweepResult?.executionIds.orEmpty(),
        messageJa = violation.messageJa,
        safetyViolation = violation,
        divergenceMemos = sweepResult?.divergenceMemos.orEmpty(),
    )
}

private fun Any.auditContext(): PaperTradeAuditContext {
    return when (this) {
        is PlaceOrderCommand -> auditContext
        is UpdateProtectionCommand -> auditContext
        is ClosePositionCommand -> auditContext
        is CancelOrderCommand -> auditContext
        else -> PaperTradeAuditContext.EMPTY
    }
}

private fun validateReason(reasonJa: String) {
    require(reasonJa.isNotBlank()) {
        "reason is required."
    }
}

private fun BigDecimal.isMultipleOf(step: BigDecimal): Boolean {
    if (step.compareTo(BigDecimal.ZERO) == 0) {
        return true
    }

    return remainder(step).abs().compareTo(BigDecimal.ZERO) == 0
}

private fun Order.isActiveProtectionStop(): Boolean {
    return side == OrderSide.SELL && orderType == OrderType.STOP && status == OrderStatus.OPEN
}

private fun Order.isTakeProfitCandidate(): Boolean {
    return orderType == OrderType.LIMIT && side == OrderSide.SELL
}

private fun Order.isLinkedProtectiveStop(): Boolean {
    return side == OrderSide.SELL && orderType == OrderType.STOP && positionId != null
}

/**
 * 取引日判定に使う timezone。
 */
private val TRADING_DATE_ZONE = ZoneId.of("Asia/Tokyo")

/**
 * ATR 算出に取得する 5分足本数。
 */
private const val ATR_CANDLE_LIMIT = 64

/**
 * ATR の期間。
 */
private const val ATR_PERIOD = 14

/**
 * ATR の返却 scale。
 */
private const val ATR_SCALE = 8

/**
 * paper 約定時に取得する orderbook depth。
 */
private const val PAPER_EXECUTION_ORDERBOOK_DEPTH = 50

/**
 * paper orderbook fetch fallback log の key。
 */
private const val PAPER_EXECUTION_ORDERBOOK_FETCH_LOG_KEY = "paper-execution-orderbook-fetch-fallback"

/**
 * paper ATR fetch fallback log の key。
 */
private const val PAPER_EXECUTION_ATR_FETCH_LOG_KEY = "paper-execution-atr-fetch-fallback"

/**
 * paper ATR unavailable fallback log の key。
 */
private const val PAPER_EXECUTION_ATR_FALLBACK_LOG_KEY = "paper-execution-atr-fallback"

/**
 * orders.client_request_id の DB 最大長。
 */
private const val CLIENT_REQUEST_ID_MAX_LENGTH = 128

/**
 * 派生 client_request_id の separator。
 */
private const val CLIENT_REQUEST_ID_DERIVATION_SEPARATOR = ":"
