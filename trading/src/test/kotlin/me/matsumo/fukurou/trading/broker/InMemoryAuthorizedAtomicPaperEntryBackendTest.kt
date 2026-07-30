package me.matsumo.fukurou.trading.broker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.decision.TradePlanDraft
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationPredicate
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationType
import me.matsumo.fukurou.trading.domain.ExecutionLiquidity
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderExpirySource
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotRecord
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotRecorder
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotRepository
import me.matsumo.fukurou.trading.evaluation.InMemoryEquitySnapshotRepository
import me.matsumo.fukurou.trading.market.PaperMarketTradeEvent
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InMemoryAuthorizedAtomicPaperEntryBackendTest {
    @Test
    fun `market create is exact before consumed and non flat checks`() = runBlocking {
        val decisions = InMemoryDecisionRepository()
        val intentId = seedIntent(decisions)
        val ledger = InMemoryPaperLedgerRepository()
        val backend = InMemoryAuthorizedAtomicPaperEntryBackend(decisions, ledger)
        val clientRequestId = "runner-place-v2-retry"
        val initial = request(intentId, clientRequestId)

        val created = backend.commit(initial).getOrThrow()
        val replay = backend.commit(request(intentId, clientRequestId)).getOrThrow()

        assertIs<AuthorizedAtomicEntryResult.Created>(created)
        val exact = assertIs<AuthorizedAtomicEntryResult.Exact>(replay)
        assertEquals(created.result.orderIds, exact.result.orderIds)
        assertEquals(1, decisions.snapshots.intentConsumptions().size)
    }

    @Test
    fun `exact ignores invalid fresh proposal while missing rejects it`(): Unit = runBlocking {
        val decisions = InMemoryDecisionRepository()
        val intentId = seedIntent(decisions)
        val backend = InMemoryAuthorizedAtomicPaperEntryBackend(
            decisions = decisions,
            ledger = InMemoryPaperLedgerRepository(),
        )
        val initial = request(intentId, "runner-place-v2-invalid-proposal", UUID.randomUUID())
        backend.commit(initial).getOrThrow()
        val freshResting = restingRequest(intentId).proposal as AuthorizedAtomicEntryCreationProposal.Resting
        val invalidExact = initial.copy(proposal = freshResting)

        assertIs<AuthorizedAtomicEntryResult.Exact>(backend.commit(invalidExact).getOrThrow())

        val missingDecisions = InMemoryDecisionRepository()
        val missingIntentId = seedIntent(missingDecisions)
        val invalidMissing = request(missingIntentId, tradeGroupId = UUID.randomUUID()).withInvalidMarketIds()
        val failure = InMemoryAuthorizedAtomicPaperEntryBackend(missingDecisions, InMemoryPaperLedgerRepository())
            .commit(invalidMissing)
            .exceptionOrNull()
        assertIs<IllegalArgumentException>(failure)

        val invalidGroup = request(missingIntentId, tradeGroupId = UUID.randomUUID()).withInvalidMarketGroup()
        val groupFailure = InMemoryAuthorizedAtomicPaperEntryBackend(missingDecisions, InMemoryPaperLedgerRepository())
            .commit(invalidGroup)
            .exceptionOrNull()
        assertIs<IllegalArgumentException>(groupFailure)

        val ambiguousRequest = request(UUID.randomUUID()).withInvalidMarketIds()
        val ambiguousFailure = InMemoryAuthorizedAtomicPaperEntryBackend(
            decisions = InMemoryDecisionRepository(),
            ledger = InMemoryPaperLedgerRepository(openOrders = listOf(ambiguousFilledEntry(ambiguousRequest))),
        ).commit(ambiguousRequest).exceptionOrNull()
        assertIs<AuthorizedAtomicEntryReplayIndeterminateException>(ambiguousFailure)
    }

    @Test
    fun `missing rejects identities already used by canceled artifacts`() = runBlocking {
        val decisions = InMemoryDecisionRepository()
        val intentId = seedIntent(decisions)
        listOf(request(intentId), restingRequest(intentId)).forEach { candidate ->
            val groupId = requireNotNull(candidate.proposal.command.tradeGroupId)
            val collisions = candidate.proposal.freshEntityIds().map { entityId ->
                protectiveSell().copy(orderId = entityId.toString(), status = OrderStatus.CANCELED)
            } + protectiveSell().copy(tradeGroupId = groupId.toString(), status = OrderStatus.CANCELED)

            collisions.forEach { artifact ->
                val failure = InMemoryAuthorizedAtomicPaperEntryBackend(
                    decisions = decisions,
                    ledger = InMemoryPaperLedgerRepository(openOrders = listOf(artifact)),
                ).commit(candidate).exceptionOrNull()

                assertIs<IllegalArgumentException>(failure)
            }
        }
    }

    @Test
    fun `market proposal rejects stop entry`(): Unit = runBlocking {
        val decisions = InMemoryDecisionRepository()
        val intentId = seedIntent(decisions)
        val stop = request(intentId, type = OrderType.STOP, price = BigDecimal("101"))

        val failure = InMemoryAuthorizedAtomicPaperEntryBackend(
            decisions = decisions,
            ledger = InMemoryPaperLedgerRepository(),
        ).commit(stop).exceptionOrNull()

        assertIs<IllegalArgumentException>(failure)
    }

    @Test
    fun `resting create excludes protective sell from flat predicate`() = runBlocking {
        val decisions = InMemoryDecisionRepository()
        val intentId = seedIntent(decisions)
        val protectiveSell = protectiveSell()
        val ledger = InMemoryPaperLedgerRepository(openOrders = listOf(protectiveSell))
        val backend = InMemoryAuthorizedAtomicPaperEntryBackend(decisions, ledger)

        val result = backend.commit(restingRequest(intentId)).getOrThrow()

        assertIs<AuthorizedAtomicEntryResult.Created>(result)
        assertEquals(OrderStatus.OPEN, result.result.status)
    }

    @Test
    fun `missing consumed and non flat return distinct typed failures`(): Unit = runBlocking {
        val missing = InMemoryAuthorizedAtomicPaperEntryBackend(
            decisions = InMemoryDecisionRepository(),
            ledger = InMemoryPaperLedgerRepository(),
        )
            .commit(request(UUID.randomUUID()))
            .exceptionOrNull()
        assertIs<AuthorizedAtomicEntryIntentMissingException>(missing)

        val decisions = InMemoryDecisionRepository()
        val consumedIntentId = seedIntent(decisions)
        decisions.appendIntentConsumption(consumedIntentId, UUID.randomUUID(), NOW).getOrThrow()
        val consumed = InMemoryAuthorizedAtomicPaperEntryBackend(
            decisions = decisions,
            ledger = InMemoryPaperLedgerRepository(),
        )
            .commit(request(consumedIntentId))
            .exceptionOrNull()
        assertIs<AuthorizedAtomicEntryIntentConsumedException>(consumed)

        val flatDecisions = InMemoryDecisionRepository()
        val flatIntentId = seedIntent(flatDecisions)
        val nonFlat = InMemoryAuthorizedAtomicPaperEntryBackend(
            decisions = flatDecisions,
            ledger = InMemoryPaperLedgerRepository(openOrders = listOf(openBuy())),
        ).commit(request(flatIntentId)).exceptionOrNull()
        assertIs<AuthorizedAtomicEntryNotFlatException>(nonFlat)
    }

    @Test
    fun `bounded concurrent requests converge to one mutation and typed losers`() = runBlocking {
        val decisions = InMemoryDecisionRepository()
        val sharedIntentId = seedIntent(decisions)
        val ledger = InMemoryPaperLedgerRepository()
        val backend = InMemoryAuthorizedAtomicPaperEntryBackend(decisions, ledger)
        val clientRequestId = "runner-place-v2-concurrent"
        val sameRequestReady = CountDownLatch(8)
        val releaseSameRequest = CompletableDeferred<Unit>()

        val results = coroutineScope {
            List(8) {
                async(Dispatchers.Default) {
                    sameRequestReady.countDown()
                    releaseSameRequest.await()
                    backend.commit(request(sharedIntentId, clientRequestId))
                }
            }.also {
                assertTrue(sameRequestReady.await(1, TimeUnit.SECONDS))
                releaseSameRequest.complete(Unit)
            }
        }.awaitAll()

        assertEquals(1, results.count { it.getOrNull() is AuthorizedAtomicEntryResult.Created })
        assertEquals(7, results.count { it.getOrNull() is AuthorizedAtomicEntryResult.Exact })
        assertEquals(1, ledger.getOpenPositions().getOrThrow().size)
        assertEquals(1, decisions.snapshots.intentConsumptions().size)

        assertConcurrentSameIntentDifferentRequests()
        assertConcurrentDifferentRequests(requestType = RequestType.MARKET)
        assertConcurrentDifferentRequests(requestType = RequestType.RESTING)
    }

    @Test
    fun `post ledger fault restores ledger equity and consumption`() = runBlocking {
        val decisions = InMemoryDecisionRepository()
        val intentId = seedIntent(decisions)
        val ledger = InMemoryPaperLedgerRepository()
        val backend = InMemoryAuthorizedAtomicPaperEntryBackend(
            decisions = decisions,
            ledger = ledger,
            faultAfterLedgerPublishBeforeConsumption = { error("fault") },
        )

        val beforeLedger = ledger.snapshotAuthorizedAtomicState()
        val beforeEquity = ledger.equitySnapshotRepository.findAll().getOrThrow()
        val beforeConsumption = decisions.snapshots.intentConsumptions()

        val failure = backend.commit(request(intentId).withMarketEvidence()).exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(ledger.getOpenOrders().getOrThrow().isEmpty())
        assertTrue(ledger.getOpenPositions().getOrThrow().isEmpty())
        assertTrue(ledger.getExecutions().getOrThrow().isEmpty())
        assertTrue(ledger.equitySnapshotRepository.findAll().getOrThrow().isEmpty())
        assertTrue(decisions.snapshots.intentConsumptions().isEmpty())
        assertEquals(beforeLedger, ledger.snapshotAuthorizedAtomicState())
        assertEquals(beforeEquity, ledger.equitySnapshotRepository.findAll().getOrThrow())
        assertEquals(beforeConsumption, decisions.snapshots.intentConsumptions())
    }

    @Test
    fun `resting fault restores eligibility queue lineage and consumption`() = runBlocking {
        val decisions = InMemoryDecisionRepository()
        val intentId = seedIntent(decisions)
        val ledger = InMemoryPaperLedgerRepository()
        val backend = InMemoryAuthorizedAtomicPaperEntryBackend(
            decisions = decisions,
            ledger = ledger,
            faultAfterLedgerPublishBeforeConsumption = { error("fault") },
        )
        val eligibility = RestingOrderMarketEligibility(
            sessionId = UUID.randomUUID(),
            eligibleAfterSequence = 0,
            eligibleFrom = NOW,
            queueAheadBtc = BigDecimal.ONE,
            queueSnapshotAt = NOW,
        )
        val beforeLedger = ledger.snapshotAuthorizedAtomicState()
        val beforeConsumption = decisions.snapshots.intentConsumptions()

        val failure = backend.commit(restingRequest(intentId, eligibility)).exceptionOrNull()

        assertIs<IllegalStateException>(failure)
        assertEquals(beforeLedger, ledger.snapshotAuthorizedAtomicState())
        assertEquals(beforeConsumption, decisions.snapshots.intentConsumptions())
    }

    @Test
    fun `daily append waits for fault restore and recorder reads account before equity append`(): Unit = runBlocking {
        val decisions = InMemoryDecisionRepository()
        val intentId = seedIntent(decisions)
        val equity = InMemoryEquitySnapshotRepository()
        val ledger = InMemoryPaperLedgerRepository(equitySnapshotRepository = equity)
        val faultEntered = CountDownLatch(1)
        val releaseFault = CountDownLatch(1)
        val backend = InMemoryAuthorizedAtomicPaperEntryBackend(
            decisions = decisions,
            ledger = ledger,
            faultAfterLedgerPublishBeforeConsumption = {
                faultEntered.countDown()
                check(releaseFault.await(2, TimeUnit.SECONDS)) { "fault was not released" }
                error("fault")
            },
        )
        val dailyRepository = BlockingDailyRepository(equity)
        val recorder = EquitySnapshotRecorder(
            accountSource = {
                dailyRepository.accountSourceCompleted = true
                Result.success(ledger.getAccountSnapshot().getOrThrow())
            },
            repository = dailyRepository,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )

        val daily = async(Dispatchers.Default) { recorder.recordDailyIfNeeded() }
        dailyRepository.beforeAppend.await()
        val failedEntry = async(Dispatchers.Default) { backend.commit(request(intentId)) }
        assertTrue(faultEntered.await(1, TimeUnit.SECONDS))

        dailyRepository.releaseAppend.complete(Unit)
        withTimeout(1_000) {
            while (dailyRepository.appendThread.get()?.state != Thread.State.BLOCKED) delay(1)
        }
        assertTrue(!daily.isCompleted)
        releaseFault.countDown()

        withTimeout(2_000) {
            assertTrue(failedEntry.await().isFailure)
            daily.await().getOrThrow()
        }
        assertTrue(dailyRepository.accountReadBeforeAppend)
        assertEquals(1, equity.findAll().getOrThrow().count { it.reason.name == "DAILY" })
        assertTrue(ledger.getOpenOrders().getOrThrow().isEmpty())
        assertTrue(decisions.snapshots.intentConsumptions().isEmpty())
    }

    private suspend fun seedIntent(decisions: InMemoryDecisionRepository): UUID {
        val command = command(UUID.randomUUID())
        return requireNotNull(decisions.submitDecision(submission(command)).getOrThrow().tradeIntent).intentId
    }

    private fun request(
        intentId: UUID,
        clientRequestId: String = "runner-place-v2-${UUID.randomUUID()}",
        tradeGroupId: UUID? = null,
        type: OrderType = OrderType.MARKET,
        price: BigDecimal? = null,
    ): AuthorizedAtomicPaperEntryRequest {
        val stableCommand = command(
            intentId = intentId,
            type = type,
            price = price,
            clientRequestId = clientRequestId,
            tradeGroupId = tradeGroupId,
        )
        val resolvedTradeGroupId = tradeGroupId ?: UUID.randomUUID()
        val preparedCommand = stableCommand.copy(tradeGroupId = resolvedTradeGroupId)
        val identity = AuthorizedAtomicEntryIdentity.from(stableCommand, TradingMode.PAPER)
        return AuthorizedAtomicPaperEntryRequest(
            identity = identity,
            proposal = AuthorizedAtomicEntryCreationProposal.Market(
                IntentConsumingMarketEntryFillRequest(
                    entry = MarketEntryFillRequest(
                        command = preparedCommand,
                        fill = SimulatedFill(
                            executionId = UUID.randomUUID(),
                            priceJpy = BigDecimal("101"),
                            sizeBtc = BigDecimal("0.01"),
                            feeJpy = BigDecimal.ZERO,
                            realizedPnlJpy = BigDecimal.ZERO,
                            liquidity = ExecutionLiquidity.TAKER,
                            executedAt = NOW,
                        ),
                        positionId = UUID.randomUUID(),
                        tradeGroupId = resolvedTradeGroupId,
                        stopOrderId = UUID.randomUUID(),
                    ),
                    consumption = TradeIntentConsumptionRequest(
                        intentId = intentId,
                        consumedAt = NOW,
                    ),
                ),
            ),
        )
    }

    private fun restingRequest(
        intentId: UUID,
        marketEligibility: RestingOrderMarketEligibility? = null,
    ): AuthorizedAtomicPaperEntryRequest {
        val stableCommand = command(
            intentId = intentId,
            type = OrderType.LIMIT,
            price = BigDecimal("101"),
        )
        val resolvedTradeGroupId = UUID.randomUUID()
        val preparedCommand = stableCommand.copy(tradeGroupId = resolvedTradeGroupId)
        return AuthorizedAtomicPaperEntryRequest(
            identity = AuthorizedAtomicEntryIdentity.from(stableCommand, TradingMode.PAPER),
            proposal = AuthorizedAtomicEntryCreationProposal.Resting(
                IntentConsumingRestingEntryOrderRequest(
                    order = RestingEntryOrderRequest(
                        command = preparedCommand,
                        orderId = UUID.randomUUID(),
                        tradeGroupId = resolvedTradeGroupId,
                        createdAt = NOW,
                        expiresAt = NOW.plusSeconds(60),
                        expirySource = OrderExpirySource.SYSTEM_TTL,
                        effectiveTtlSeconds = 60,
                        marketEligibility = marketEligibility,
                    ),
                    consumption = TradeIntentConsumptionRequest(
                        intentId = intentId,
                        consumedAt = NOW,
                    ),
                ),
            ),
        )
    }

    private fun command(
        intentId: UUID,
        type: OrderType = OrderType.MARKET,
        price: BigDecimal? = null,
        clientRequestId: String = "runner-place-v2-${UUID.randomUUID()}",
        tradeGroupId: UUID? = null,
    ): PlaceOrderCommand = PlaceOrderCommand(
        commandId = UUID.randomUUID(),
        intentId = intentId,
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = type,
        sizeBtc = BigDecimal("0.01"),
        priceJpy = price,
        tradeGroupId = tradeGroupId,
        protectiveStopPriceJpy = BigDecimal("100"),
        takeProfitPriceJpy = BigDecimal("102"),
        estimatedWinProbability = BigDecimal("0.5"),
        reasonJa = "test",
        auditContext = PaperTradeAuditContext.EMPTY.copy(clientRequestId = clientRequestId),
    )

    private fun protectiveSell() = Order(
        orderId = UUID.randomUUID().toString(),
        intentId = null,
        positionId = UUID.randomUUID().toString(),
        tradeGroupId = UUID.randomUUID().toString(),
        symbol = "BTC",
        mode = TradingMode.PAPER,
        side = OrderSide.SELL,
        orderType = OrderType.STOP,
        status = OrderStatus.OPEN,
        sizeBtc = "0.01",
        limitPriceJpy = null,
        triggerPriceJpy = "100",
        protectiveStopPriceJpy = null,
        takeProfitPriceJpy = null,
        estimatedWinProbability = null,
        reasonJa = "test",
        clientRequestId = null,
        createdAt = NOW.toString(),
        updatedAt = NOW.toString(),
    )

    private fun openBuy() = protectiveSell().copy(
        side = OrderSide.BUY,
        orderType = OrderType.LIMIT,
        clientRequestId = "other",
        limitPriceJpy = "101",
        triggerPriceJpy = null,
    )

    private fun AuthorizedAtomicPaperEntryRequest.withInvalidMarketIds(): AuthorizedAtomicPaperEntryRequest {
        val market = proposal as AuthorizedAtomicEntryCreationProposal.Market
        return copy(
            proposal = AuthorizedAtomicEntryCreationProposal.Market(
                market.request.copy(entry = market.request.entry.copy(positionId = market.request.entry.command.commandId)),
            ),
        )
    }

    private fun AuthorizedAtomicPaperEntryRequest.withInvalidMarketGroup(): AuthorizedAtomicPaperEntryRequest {
        val market = proposal as AuthorizedAtomicEntryCreationProposal.Market
        return copy(
            proposal = AuthorizedAtomicEntryCreationProposal.Market(
                market.request.copy(entry = market.request.entry.copy(tradeGroupId = UUID.randomUUID())),
            ),
        )
    }

    private fun AuthorizedAtomicPaperEntryRequest.withMarketEvidence(): AuthorizedAtomicPaperEntryRequest {
        val market = proposal as AuthorizedAtomicEntryCreationProposal.Market
        val sessionId = UUID.randomUUID()
        val source = PaperMarketTradeEvent(
            symbol = TradingSymbol.BTC,
            side = OrderSide.SELL,
            priceJpy = BigDecimal("101"),
            sizeBtc = BigDecimal("0.01"),
            exchangeAt = NOW,
            receivedAt = NOW,
            connectionSessionId = sessionId,
            sequence = 1,
        )
        return copy(
            proposal = AuthorizedAtomicEntryCreationProposal.Market(
                market.request.copy(
                    entry = market.request.entry.copy(
                        source = source,
                        positionMarketEligibility = PositionMarketEligibility(
                            sessionId = sessionId,
                            eligibleAfterSequence = 0,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun ambiguousFilledEntry(request: AuthorizedAtomicPaperEntryRequest): Order {
        val identity = request.identity
        return openBuy().copy(
            intentId = identity.intentId.toString(),
            tradeGroupId = identity.tradeGroupId?.toString() ?: UUID.randomUUID().toString(),
            orderType = identity.orderType,
            status = OrderStatus.FILLED,
            sizeBtc = identity.sizeBtc.toPlainString(),
            limitPriceJpy = identity.priceJpy?.toPlainString(),
            protectiveStopPriceJpy = identity.protectiveStopPriceJpy.toPlainString(),
            takeProfitPriceJpy = identity.takeProfitPriceJpy?.toPlainString(),
            estimatedWinProbability = identity.estimatedWinProbability.toPlainString(),
            clientRequestId = identity.clientRequestId,
        )
    }

    private suspend fun assertConcurrentDifferentRequests(requestType: RequestType) = coroutineScope {
        val decisions = InMemoryDecisionRepository()
        val firstIntentId = seedIntent(decisions)
        val secondIntentId = seedIntent(decisions)
        val backend = InMemoryAuthorizedAtomicPaperEntryBackend(
            decisions = decisions,
            ledger = InMemoryPaperLedgerRepository(),
        )
        val ready = CountDownLatch(2)
        val release = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            ready.countDown()
            release.await()
            when (requestType) {
                RequestType.MARKET -> backend.commit(request(firstIntentId))
                RequestType.RESTING -> backend.commit(restingRequest(firstIntentId))
            }
        }
        val second = async(Dispatchers.Default) {
            ready.countDown()
            release.await()
            when (requestType) {
                RequestType.MARKET -> backend.commit(restingRequest(secondIntentId))
                RequestType.RESTING -> backend.commit(request(secondIntentId))
            }
        }
        assertTrue(ready.await(1, TimeUnit.SECONDS))
        release.complete(Unit)
        val results = listOf(first.await(), second.await())

        assertEquals(1, results.count { it.getOrNull() is AuthorizedAtomicEntryResult.Created })
        assertEquals(1, results.count { it.exceptionOrNull() is AuthorizedAtomicEntryNotFlatException })
    }

    private suspend fun assertConcurrentSameIntentDifferentRequests() = coroutineScope {
        val decisions = InMemoryDecisionRepository()
        val intentId = seedIntent(decisions)
        val backend = InMemoryAuthorizedAtomicPaperEntryBackend(
            decisions = decisions,
            ledger = InMemoryPaperLedgerRepository(),
        )
        val ready = CountDownLatch(2)
        val release = CompletableDeferred<Unit>()
        val results = listOf(
            async(Dispatchers.Default) {
                ready.countDown()
                release.await()
                backend.commit(request(intentId))
            },
            async(Dispatchers.Default) {
                ready.countDown()
                release.await()
                backend.commit(request(intentId))
            },
        )
        assertTrue(ready.await(1, TimeUnit.SECONDS))
        release.complete(Unit)
        val completed = results.awaitAll()

        assertEquals(1, completed.count { it.getOrNull() is AuthorizedAtomicEntryResult.Created })
        assertEquals(1, completed.count { it.exceptionOrNull() is AuthorizedAtomicEntryIntentConsumedException })
    }

    private fun submission(command: PlaceOrderCommand) = DecisionSubmission(
        invocationId = "test",
        llmProvider = "test",
        promptHash = "test",
        systemPromptVersion = "test",
        marketSnapshotId = "test",
        action = DecisionAction.ENTER,
        setupTags = listOf("test"),
        estimatedWinProbability = command.estimatedWinProbability,
        expectedRMultiple = BigDecimal("2"),
        roundTripCostR = BigDecimal("0.1"),
        toolEvidenceIds = emptyList(),
        factCheckJson = "{}",
        selfReviewJson = "{}",
        reasonJa = "test",
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
            thesisJa = "test",
            invalidationConditionsJa = listOf("test"),
            targetPriceJpy = command.takeProfitPriceJpy,
            timeStopAt = null,
            setupTags = listOf("test"),
            invalidationPredicates = listOf(
                TradePlanInvalidationPredicate(
                    type = TradePlanInvalidationType.LAST_PRICE_AT_OR_BELOW,
                    decimalThresholdJpy = command.protectiveStopPriceJpy,
                ),
            ),
        ),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-31T00:00:00Z")
    }

    private enum class RequestType {
        MARKET,
        RESTING,
    }
}

private class BlockingDailyRepository(
    private val delegate: InMemoryEquitySnapshotRepository,
) : EquitySnapshotRepository {
    val beforeAppend = CompletableDeferred<Unit>()
    val releaseAppend = CompletableDeferred<Unit>()
    val appendThread = AtomicReference<Thread>()
    var accountSourceCompleted = false
    var accountReadBeforeAppend = false

    override suspend fun append(snapshot: EquitySnapshotRecord): Result<Unit> = delegate.append(snapshot)

    override suspend fun appendDailyIfAbsent(snapshot: EquitySnapshotRecord): Result<Unit> {
        accountReadBeforeAppend = accountSourceCompleted
        beforeAppend.complete(Unit)
        releaseAppend.await()
        appendThread.set(Thread.currentThread())

        return delegate.appendDailyIfAbsent(snapshot)
    }

    override suspend fun findAll(): Result<List<EquitySnapshotRecord>> = delegate.findAll()
}
