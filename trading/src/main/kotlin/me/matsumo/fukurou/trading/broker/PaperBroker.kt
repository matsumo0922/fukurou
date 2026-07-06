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
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.ProtectionStatus
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol
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
 * @param fillSimulator paper 約定 simulator
 * @param reconcilerStatusProvider ProtectionReconciler 状態 provider
 * @param clock 当日実現損益の対象日算出に使う clock
 * @param tradingDateZone 当日判定に使う timezone
 */
class PaperBroker(
    internal val ledgerRepository: PaperLedgerRepository,
    private val riskStateRepository: RiskStateRepository,
    private val riskStateCommandService: RiskStateCommandService? = null,
    private val decisionRepository: DecisionRepository = InMemoryDecisionRepository(),
    private val falsificationFreshnessWindow: Duration = DecisionProtocolConfig().falsificationFreshnessWindow,
    private val safetyViolationRepository: SafetyViolationRepository = InMemorySafetyViolationRepository(),
    safetyFloor: SafetyFloor? = null,
    internal val marketDataSource: MarketDataSource? = null,
    fillSimulator: FillSimulator? = null,
    private val reconcilerStatusProvider: ReconcilerStatusProvider = NoReconcilerStatusProvider,
    private val clock: Clock = Clock.systemUTC(),
    private val tradingDateZone: ZoneId = TRADING_DATE_ZONE,
    private val warnLogger: RateLimitedWarnLogger = RateLimitedWarnLogger(
        logger = paperBrokerLogger,
        clock = clock,
    ),
) : Broker {

    private val safetyFloor = safetyFloor ?: SafetyFloor(clock = clock)
    internal val fillSimulator = fillSimulator ?: FillSimulator(clock = clock)

    init {
        val hasAtomicLedgerRepository = ledgerRepository is IntentConsumingPaperLedgerRepository
        val hasAtomicDecisionRepository = decisionRepository is AtomicIntentConsumptionRepository
        val hasAtomicIntentConsumption = hasAtomicLedgerRepository || hasAtomicDecisionRepository

        require(hasAtomicIntentConsumption) {
            "PaperBroker requires atomic intent consumption support."
        }
    }

    override suspend fun getBalance(): Result<AccountSnapshot> {
        return ledgerRepository.getAccountSnapshot()
    }

    override suspend fun getBalanceWithUpdatedAt(): Result<AccountSnapshotWithUpdatedAt> {
        return ledgerRepository.getAccountSnapshotWithUpdatedAt()
    }

    override suspend fun getPositions(): Result<List<Position>> {
        return ledgerRepository.getOpenPositions()
    }

    override suspend fun getPositionsWithUpdatedAt(): Result<PositionsWithUpdatedAt> {
        return ledgerRepository.getOpenPositionsWithUpdatedAt()
    }

    override suspend fun getOpenOrders(): Result<List<Order>> {
        return ledgerRepository.getOpenOrders()
    }

    override suspend fun getOpenOrdersWithUpdatedAt(): Result<OpenOrdersWithUpdatedAt> {
        return ledgerRepository.getOpenOrdersWithUpdatedAt()
    }

    override suspend fun getAccountStatus(): Result<AccountStatus> {
        return getAccountStatusWithUpdatedAt().map { statusWithUpdatedAt -> statusWithUpdatedAt.accountStatus }
    }

    override suspend fun getAccountStatusWithUpdatedAt(): Result<AccountStatusWithUpdatedAt> {
        return runCatching {
            val accountSnapshotWithUpdatedAt = ledgerRepository.getAccountSnapshotWithUpdatedAt().getOrThrow()
            val accountSnapshot = accountSnapshotWithUpdatedAt.accountSnapshot
            val riskState = riskStateRepository.current().getOrThrow()
            val positions = ledgerRepository.getOpenPositions().getOrThrow()
            val openOrders = ledgerRepository.getOpenOrders().getOrThrow()
            val reconcilerStatus = reconcilerStatusProvider.snapshot()
            val today = LocalDate.now(clock.withZone(tradingDateZone))
            val todayRealizedPnlJpy = ledgerRepository.getRealizedPnlForDate(today).getOrThrow()

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

    override suspend fun placeOrder(command: PlaceOrderCommand): Result<PaperTradeResult> {
        return runCatching {
            validatePlaceOrderCommand(command)

            findExistingPlaceOrderResult(command)?.let { existingResult ->
                return@runCatching existingResult
            }

            val ticker = tickerFor(command.symbol).getOrThrow()
            val symbolRules = symbolRulesFor(command.symbol).getOrThrow()
            val context = safetyContext(
                ticker = ticker,
                symbolRules = symbolRules,
                intentId = command.intentId,
            )
            val resolvedTradeGroupId = resolveTradeGroupId(command, context.positions)
            val resolvedCommand = command.copy(tradeGroupId = resolvedTradeGroupId)

            enforceSafetyFloor(
                verdict = safetyFloor.evaluatePlaceOrder(resolvedCommand, context),
                command = resolvedCommand,
                ticker = ticker,
                symbolRules = symbolRules,
            )?.let { rejectedResult -> return@runCatching rejectedResult }

            validateSymbolRules(resolvedCommand, symbolRules)
            validateEntryPriceContract(resolvedCommand, ticker)
            validateCashAvailability(resolvedCommand, ticker, symbolRules)

            if (resolvedCommand.orderType == OrderType.MARKET) {
                val fill = fillSimulator.marketFill(resolvedCommand.side, resolvedCommand.sizeBtc, ticker, symbolRules)
                val entryOrderId = resolvedCommand.commandId

                return@runCatching fillMarketEntryAndConsumeIntent(
                    command = resolvedCommand,
                    fill = fill,
                    positionId = UUID.randomUUID(),
                    tradeGroupId = resolvedTradeGroupId,
                    entryOrderId = entryOrderId,
                    stopOrderId = UUID.randomUUID(),
                )
            }

            val orderId = UUID.randomUUID()

            createRestingEntryOrderAndConsumeIntent(
                command = resolvedCommand,
                orderId = orderId,
                tradeGroupId = resolvedTradeGroupId,
            )
        }
    }

    override suspend fun previewOrder(command: PlaceOrderCommand): Result<PreviewOrderResult> {
        return runCatching {
            validatePlaceOrderCommand(command)

            val ticker = tickerFor(command.symbol).getOrThrow()
            val symbolRules = symbolRulesFor(command.symbol).getOrThrow()
            val context = safetyContext(
                ticker = ticker,
                symbolRules = symbolRules,
                intentId = command.intentId,
            )
            val resolvedTradeGroupId = resolveTradeGroupId(command, context.positions)
            val resolvedCommand = command.copy(tradeGroupId = resolvedTradeGroupId)
            val riskDetails = safetyFloor.placeOrderRiskDetails(resolvedCommand, context)
            val normalizedOrderContent = command.toPreviewOrderNormalizedContent()
            val previewHash = normalizedOrderContent.calculatePreviewHash()
            val verdict = safetyFloor.evaluatePlaceOrder(resolvedCommand, context)

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

            validateSymbolRules(resolvedCommand, symbolRules)
            validateEntryPriceContract(resolvedCommand, ticker)
            validateCashAvailability(resolvedCommand, ticker, symbolRules)

            PreviewOrderResult(
                accepted = true,
                previewHash = previewHash,
                normalizedOrderContent = normalizedOrderContent,
                riskDetails = riskDetails,
                messageJa = "paper entry 注文 preview は SafetyFloor と broker 事前検証を通過しました。",
            )
        }
    }

    override suspend fun recoverRejectedPreviewHardHalt(
        command: PlaceOrderCommand,
        violation: SafetyViolation,
    ): Result<PaperTradeResult> {
        return runCatching {
            validatePlaceOrderCommand(command)
            require(violation.hardHaltRequired) {
                "SafetyViolation must require HARD_HALT side effects."
            }

            val ticker = tickerFor(command.symbol).getOrThrow()
            val symbolRules = symbolRulesFor(command.symbol).getOrThrow()

            safetyViolationRepository.append(violation).getOrThrow()
            val sweepResult = activateHardHaltAndSweep(violation, command, ticker, symbolRules)

            rejectedTradeResult(violation, sweepResult)
        }
    }

    override suspend fun closePosition(command: ClosePositionCommand): Result<PaperTradeResult> {
        return runCatching {
            validateReason(command.reasonJa)

            val ticker = tickerFor(TradingSymbol.BTC).getOrThrow()
            val symbolRules = symbolRulesFor(TradingSymbol.BTC).getOrThrow()
            val context = safetyContext(ticker, symbolRules)

            enforceSafetyFloor(
                verdict = safetyFloor.evaluateClosePosition(command, context),
                command = command,
                ticker = ticker,
                symbolRules = symbolRules,
            )?.let { rejectedResult -> return@runCatching rejectedResult }

            val openPositions = ledgerRepository.getOpenPositions().getOrThrow()
            val targetPositions = resolveCloseTargets(command, openPositions)
            val results = targetPositions.map { position ->
                val closeCommand = command.forCloseTarget(position, targetPositions.size)
                val fill = fillSimulator.marketFill(
                    side = OrderSide.SELL,
                    sizeBtc = position.sizeBtc.toBigDecimal(),
                    ticker = ticker,
                    rules = symbolRules,
                )

                ledgerRepository.closePosition(
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

            val ticker = tickerFor(TradingSymbol.BTC).getOrThrow()
            val symbolRules = symbolRulesFor(TradingSymbol.BTC).getOrThrow()
            val context = safetyContext(
                ticker = ticker,
                symbolRules = symbolRules,
                includeAtr = true,
            )

            enforceSafetyFloor(
                verdict = safetyFloor.evaluateUpdateProtection(command, context),
                command = command,
                ticker = ticker,
                symbolRules = symbolRules,
            )?.let { rejectedResult -> return@runCatching rejectedResult }

            validateStopPriceIfPresent(command, ticker, symbolRules)

            ledgerRepository.updateProtection(command).getOrThrow()
        }
    }

    override suspend fun cancelOrder(command: CancelOrderCommand): Result<PaperTradeResult> {
        return runCatching {
            validateReason(command.reasonJa)

            val ticker = tickerFor(TradingSymbol.BTC).getOrThrow()
            val symbolRules = symbolRulesFor(TradingSymbol.BTC).getOrThrow()
            val context = safetyContext(ticker, symbolRules)

            enforceSafetyFloor(
                verdict = safetyFloor.evaluateCancelOrder(command, context),
                command = command,
                ticker = ticker,
                symbolRules = symbolRules,
            )?.let { rejectedResult -> return@runCatching rejectedResult }

            ledgerRepository.cancelOrder(command).getOrThrow()
        }
    }

    override suspend fun reconcile(tickSnapshot: TickSnapshot): Result<PaperReconcileResult> {
        return runCatching {
            val result = ledgerRepository.reconcile(tickSnapshot, fillSimulator).getOrThrow()

            activateHardHaltIfAccountDrawdownReached()

            result
        }
    }

    override suspend fun sweepHardHalt(reasonJa: String, tickSnapshot: TickSnapshot): Result<PaperTradeResult> {
        return runCatching {
            val ticker = tickSnapshot.requireTicker()
            val symbolRules = tickSnapshot.symbolRules ?: symbolRulesFor(TradingSymbol.BTC).getOrThrow()

            sweepOpenRisk(
                reason = reasonJa,
                auditContext = PaperTradeAuditContext.EMPTY,
                ticker = ticker,
                symbolRules = symbolRules,
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

    private suspend fun safetyContext(
        ticker: Ticker,
        symbolRules: SymbolRules,
        includeAtr: Boolean = false,
        intentId: UUID? = null,
    ): SafetyFloorContext {
        val observedAt = Instant.now(clock)
        val entryIntent = intentId?.let { requestedIntentId ->
            decisionRepository.entryIntentSafetySnapshot(
                intentId = requestedIntentId,
                observedAt = observedAt,
                freshnessWindow = falsificationFreshnessWindow,
            ).getOrThrow()
        }

        return SafetyFloorContext(
            account = ledgerRepository.getAccountSnapshot().getOrThrow(),
            riskState = riskStateRepository.current().getOrThrow(),
            positions = ledgerRepository.getOpenPositions().getOrThrow(),
            openOrders = ledgerRepository.getOpenOrders().getOrThrow(),
            ticker = ticker,
            symbolRules = symbolRules,
            entryIntent = entryIntent,
            atr14Jpy = if (includeAtr) {
                atr14JpyFor(TradingSymbol.BTC)
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
            warnLogger.warn(
                key = "paper-broker-ticker-timestamp-parse-failure",
                message = "PaperBroker could not parse ticker timestamp. Data quality probability cap will fail closed.",
                throwable = exception,
            )
            null
        }
    }

    private fun requireEntryIntentId(command: PlaceOrderCommand): UUID {
        return requireNotNull(command.intentId) {
            "intentId is required for entry order."
        }
    }

    private suspend fun fillMarketEntryAndConsumeIntent(
        command: PlaceOrderCommand,
        fill: SimulatedFill,
        positionId: UUID,
        tradeGroupId: UUID,
        entryOrderId: UUID,
        stopOrderId: UUID,
    ): PaperTradeResult {
        val intentId = requireEntryIntentId(command)
        val consumedAt = Instant.now(clock)
        val atomicRepository = ledgerRepository as? IntentConsumingPaperLedgerRepository

        if (atomicRepository != null) {
            return atomicRepository.fillMarketEntryAndConsumeIntent(
                command = command,
                fill = fill,
                positionId = positionId,
                tradeGroupId = tradeGroupId,
                stopOrderId = stopOrderId,
                intentId = intentId,
                consumedAt = consumedAt,
            ).getOrThrow()
        }

        return atomicDecisionRepository().consumeIntentAfterLedgerWrite(
            intentId = intentId,
            orderId = entryOrderId,
            consumedAt = consumedAt,
        ) {
            ledgerRepository.fillMarketEntry(
                command = command,
                fill = fill,
                positionId = positionId,
                tradeGroupId = tradeGroupId,
                stopOrderId = stopOrderId,
            ).getOrThrow()
        }.getOrThrow()
    }

    private suspend fun createRestingEntryOrderAndConsumeIntent(
        command: PlaceOrderCommand,
        orderId: UUID,
        tradeGroupId: UUID,
    ): PaperTradeResult {
        val intentId = requireEntryIntentId(command)
        val consumedAt = Instant.now(clock)
        val atomicRepository = ledgerRepository as? IntentConsumingPaperLedgerRepository

        if (atomicRepository != null) {
            return atomicRepository.createRestingEntryOrderAndConsumeIntent(
                command = command,
                orderId = orderId,
                tradeGroupId = tradeGroupId,
                intentId = intentId,
                consumedAt = consumedAt,
            ).getOrThrow()
        }

        return atomicDecisionRepository().consumeIntentAfterLedgerWrite(
            intentId = intentId,
            orderId = orderId,
            consumedAt = consumedAt,
        ) {
            ledgerRepository.createRestingEntryOrder(command, orderId, tradeGroupId).getOrThrow()
        }.getOrThrow()
    }

    private fun atomicDecisionRepository(): AtomicIntentConsumptionRepository {
        return requireNotNull(decisionRepository as? AtomicIntentConsumptionRepository) {
            "PaperBroker requires AtomicIntentConsumptionRepository for non-transactional ledger repositories."
        }
    }

    private suspend fun atr14JpyFor(symbol: TradingSymbol): BigDecimal? {
        val marketData = marketDataSource ?: return null
        val candles = marketData.getCandles(
            symbol = symbol,
            interval = CandleInterval.FIVE_MINUTES,
            limit = ATR_CANDLE_LIMIT,
        )
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

    private suspend fun enforceSafetyFloor(
        verdict: SafetyFloorVerdict,
        command: Any,
        ticker: Ticker,
        symbolRules: SymbolRules,
    ): PaperTradeResult? {
        if (verdict is SafetyFloorVerdict.Accepted) {
            return null
        }

        val violation = (verdict as SafetyFloorVerdict.Rejected).violation

        safetyViolationRepository.append(violation).getOrThrow()

        val sweepResult = if (violation.hardHaltRequired) {
            activateHardHaltAndSweep(violation, command, ticker, symbolRules)
        } else {
            null
        }

        return rejectedTradeResult(violation, sweepResult)
    }

    private suspend fun activateHardHaltAndSweep(
        violation: SafetyViolation,
        command: Any,
        ticker: Ticker,
        symbolRules: SymbolRules,
    ): PaperTradeResult {
        val reason = "SafetyFloor HARD_HALT: ${violation.rule.name}"
        val decisionRunContext = command.auditContext().decisionRunContext

        if (riskStateCommandService != null) {
            riskStateCommandService.setHardHalt(reason, decisionRunContext).getOrThrow()
        } else {
            riskStateRepository.setHardHalt(reason, Instant.now(clock)).getOrThrow()
        }

        return sweepOpenRisk(reason, command.auditContext(), ticker, symbolRules)
    }

    private suspend fun sweepOpenRisk(
        reason: String,
        auditContext: PaperTradeAuditContext,
        ticker: Ticker,
        symbolRules: SymbolRules,
    ): PaperTradeResult {
        val cancelResults = ledgerRepository.getOpenOrders()
            .getOrThrow()
            .filterNot { order -> order.isLinkedProtectiveStop() }
            .map { order ->
                ledgerRepository.cancelOrder(
                    CancelOrderCommand(
                        commandId = UUID.randomUUID(),
                        orderId = UUID.fromString(order.orderId),
                        reasonJa = reason,
                        auditContext = auditContext,
                    ),
                ).getOrThrow()
            }
        val closeResults = ledgerRepository.getOpenPositions()
            .getOrThrow()
            .map { position ->
                val fill = fillSimulator.marketFill(
                    side = OrderSide.SELL,
                    sizeBtc = position.sizeBtc.toBigDecimal(),
                    ticker = ticker,
                    rules = symbolRules,
                )

                ledgerRepository.closePosition(
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

        return mergeTradeResults(cancelResults + closeResults, "HARD_HALT 掃引で open order を取消し、open position を close しました。")
    }

    private suspend fun activateHardHaltIfAccountDrawdownReached() {
        val accountSnapshot = ledgerRepository.getAccountSnapshot().getOrThrow()
        val drawdownRatio = accountSnapshot.drawdownRatio.toBigDecimal()

        if (drawdownRatio > SafetyFloorDefaults.maxDrawdownRatio) {
            return
        }

        val riskState = riskStateRepository.current().getOrThrow()

        if (riskState.state == RiskHaltState.HARD_HALT) {
            return
        }

        val reason = "Paper account drawdown reached HARD_HALT threshold."

        if (riskStateCommandService != null) {
            riskStateCommandService.setHardHalt(reason, PaperTradeAuditContext.EMPTY.decisionRunContext).getOrThrow()
        } else {
            riskStateRepository.setHardHalt(reason, Instant.now(clock)).getOrThrow()
        }
    }
}

private val paperBrokerLogger: Logger = Logger.getLogger(PaperBroker::class.java.name)

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

private suspend fun PaperBroker.findExistingPlaceOrderResult(command: PlaceOrderCommand): PaperTradeResult? {
    val clientRequestId = command.auditContext.clientRequestId?.takeIf { requestId -> requestId.isNotBlank() }
        ?: return null

    return ledgerRepository.findPlaceOrderResultByClientRequestId(clientRequestId).getOrThrow()
}

private suspend fun PaperBroker.tickerFor(symbol: TradingSymbol): Result<Ticker> {
    val marketData = requireNotNull(marketDataSource) {
        "MarketDataSource is required for paper execution."
    }

    return marketData.getTicker(symbol)
}

private suspend fun PaperBroker.symbolRulesFor(symbol: TradingSymbol): Result<SymbolRules> {
    val marketData = requireNotNull(marketDataSource) {
        "MarketDataSource is required for paper execution."
    }

    return marketData.getSymbolRules(symbol)
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

private suspend fun PaperBroker.validateCashAvailability(
    command: PlaceOrderCommand,
    ticker: Ticker,
    rules: SymbolRules,
) {
    val balance = ledgerRepository.getAccountSnapshot().getOrThrow()
    val openOrders = ledgerRepository.getOpenOrders().getOrThrow()
    val cashJpy = balance.cashJpy.toBigDecimal()
    val reservedCashJpy = openOrders
        .filter { order -> order.side == OrderSide.BUY && order.status == OrderStatus.OPEN }
        .sumOf { order -> order.estimatedBuyReservationJpy(rules) }
    val requiredCash = command.estimatedRequiredCash(ticker, rules, fillSimulator)
    val availableCash = cashJpy.subtract(reservedCashJpy).moneyScale()

    require(requiredCash <= availableCash) {
        "Insufficient JPY cash for paper order. required=$requiredCash available=$availableCash."
    }
}

private fun PlaceOrderCommand.estimatedRequiredCash(
    ticker: Ticker,
    rules: SymbolRules,
    fillSimulator: FillSimulator,
): BigDecimal {
    if (orderType == OrderType.MARKET) {
        val fill = fillSimulator.marketFill(side, sizeBtc, ticker, rules)

        return fill.priceJpy.multiply(sizeBtc).add(fill.feeJpy).moneyScale()
    }

    val estimatedPrice = requireNotNull(priceJpy) {
        "$orderType order requires priceJpy."
    }
    val estimatedNotional = estimatedPrice.multiply(sizeBtc)
    val estimatedFee = estimatedNotional.multiply(rules.takerFee.toBigDecimal())

    return estimatedNotional.add(estimatedFee).moneyScale()
}

private fun Order.estimatedBuyReservationJpy(rules: SymbolRules): BigDecimal {
    val price = limitPriceJpy?.toBigDecimal()
        ?: triggerPriceJpy?.toBigDecimal()
        ?: BigDecimal.ZERO
    val notional = price.multiply(sizeBtc.toBigDecimal())
    val fee = notional.multiply(rules.takerFee.toBigDecimal())

    return notional.add(fee).moneyScale()
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

private fun mergeTradeResults(results: List<PaperTradeResult>, messageJa: String): PaperTradeResult {
    return PaperTradeResult(
        accepted = true,
        status = OrderStatus.FILLED,
        orderIds = results.flatMap { result -> result.orderIds },
        positionIds = results.flatMap { result -> result.positionIds },
        executionIds = results.flatMap { result -> result.executionIds },
        messageJa = messageJa,
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
 * orders.client_request_id の DB 最大長。
 */
private const val CLIENT_REQUEST_ID_MAX_LENGTH = 128

/**
 * 派生 client_request_id の separator。
 */
private const val CLIENT_REQUEST_ID_DERIVATION_SEPARATOR = ":"
