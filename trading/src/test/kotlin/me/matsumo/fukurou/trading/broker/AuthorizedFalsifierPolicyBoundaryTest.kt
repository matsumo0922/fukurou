package me.matsumo.fukurou.trading.broker

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.config.FalsifierPolicy
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.FalsifierPolicyDecision
import me.matsumo.fukurou.trading.decision.FalsifierPolicyDecisionAttributes
import me.matsumo.fukurou.trading.decision.FalsifierPolicyDecisionRepository
import me.matsumo.fukurou.trading.decision.FalsifierPolicyDecisionRequest
import me.matsumo.fukurou.trading.decision.FalsifierPolicyReasonCode
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.decision.InMemoryFalsifierPolicyDecisionRepository
import me.matsumo.fukurou.trading.decision.TradePlanDraft
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationPredicate
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationType
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.runner.FalsifierPolicyPermit
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthorizedFalsifierPolicyBoundaryTest {
    @Test
    fun authorized_missing_replay_fails_closed_without_mutation() = runBlocking {
        val policyRepository = InMemoryFalsifierPolicyDecisionRepository()
        val decision = offEnterDecision()
        policyRepository.recordFalsifierPolicyDecision(FalsifierPolicyDecisionRequest(decision)).getOrThrow()
        val permit = requireNotNull(FalsifierPolicyPermit.from(decision))
        val broker = PaperBroker(
            ledgerRepository = InMemoryPaperLedgerRepository(),
            riskStateRepository = InMemoryRiskStateRepository(),
            falsifierPolicyDecisionRepository = policyRepository,
        )
        val command = command(decision.intentId).let { original ->
            original.copy(auditContext = original.auditContext.copy(clientRequestId = original.authorizedFingerprint(permit)))
        }

        val failure = broker.placeAuthorizedOrder(AuthorizedPlaceOrder(command, permit)).exceptionOrNull()

        assertTrue(failure is AuthorizedNewMutationUnsupportedException)
        assertTrue(broker.ledgerRepository.getOpenOrders().getOrThrow().isEmpty())
    }

    @Test
    fun public_v2_place_is_rejected_before_lookup() = runBlocking {
        val command = command(UUID.randomUUID(), "runner-place-v2-spoof")
        val broker = PaperBroker(
            ledgerRepository = InMemoryPaperLedgerRepository(openOrders = orderRows(command)),
            riskStateRepository = InMemoryRiskStateRepository(),
        )

        val failure = broker.placeOrder(command).exceptionOrNull()

        assertTrue(failure is ReservedAuthorizedClientRequestIdException)
    }

    @Test
    fun canonical_fingerprint_distinguishes_null_and_string_null() {
        val permit = requireNotNull(FalsifierPolicyPermit.from(offEnterDecision()))
        val nullThesis = command(permit.intentId)
        val stringNullThesis = nullThesis.copy(canonicalThesisId = "null")

        assertTrue(nullThesis.authorizedFingerprint(permit) != stringNullThesis.authorizedFingerprint(permit))
    }

    @Test
    fun fingerprint_binds_business_and_authority_fields() {
        val decision = offEnterDecision()
        val permit = requireNotNull(FalsifierPolicyPermit.from(decision))
        val original = command(decision.intentId)
        val changedCommands = listOf(
            original.copy(sizeBtc = BigDecimal("0.02")),
            original.copy(intentId = UUID.randomUUID()),
            original.copy(side = OrderSide.SELL),
            original.copy(orderType = OrderType.LIMIT),
            original.copy(priceJpy = BigDecimal.ONE),
            original.copy(tradeGroupId = UUID.randomUUID()),
            original.copy(protectiveStopPriceJpy = BigDecimal("99")),
            original.copy(takeProfitPriceJpy = BigDecimal("101")),
            original.copy(estimatedWinProbability = BigDecimal("0.6")),
            original.copy(timeStopAt = Instant.parse("2026-08-01T00:00:00Z")),
            original.copy(canonicalThesisId = "line\n\"\\{},:"),
        )
        val changedPermits = listOf(
            permit.copy(decisionId = UUID.randomUUID()),
            permit.copy(intentId = UUID.randomUUID()),
            permit.copy(attributes = permit.attributes.copy(action = DecisionAction.ADD_LONG)),
            permit.copy(attributes = permit.attributes.copy(policy = FalsifierPolicy.ALWAYS_ON_V1)),
            permit.copy(attributes = permit.attributes.copy(required = true)),
            permit.copy(attributes = permit.attributes.copy(reasonCodes = emptySet())),
            permit.copy(attributes = permit.attributes.copy(runtimeConfigVersionId = "other")),
            permit.copy(attributes = permit.attributes.copy(runtimeConfigHash = "other")),
        )
        val originalFingerprint = original.authorizedFingerprint(permit)

        assertTrue(changedCommands.all { it.authorizedFingerprint(permit) != originalFingerprint })
        assertTrue(changedPermits.all { original.authorizedFingerprint(it) != originalFingerprint })
    }

    @Test
    fun fingerprint_uses_the_v1_canonical_json_contract() {
        val intentId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val permit = FalsifierPolicyPermit(
            decisionId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            intentId = intentId,
            attributes = FalsifierPolicyDecisionAttributes(
                action = DecisionAction.ENTER,
                policy = FalsifierPolicy.OFF_V1,
                required = false,
                reasonCodes = setOf(FalsifierPolicyReasonCode.POLICY_OFF),
                runtimeConfigVersionId = "version\n\"\\",
                runtimeConfigHash = "hash:{}",
            ),
        )
        val command = command(intentId).copy(
            commandId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
            sizeBtc = BigDecimal("0.0100"),
            priceJpy = null,
            tradeGroupId = null,
            takeProfitPriceJpy = null,
            timeStopAt = Instant.parse("2026-07-31T00:00:00Z"),
            canonicalThesisId = "null\n\"\\{},:",
        )

        assertEquals(
            "runner-place-v2-0a94a75796538e410169e283579e55a5935eef57ed6f622802355d2ec252fe84",
            command.authorizedFingerprint(permit),
        )
    }

    @Test
    fun authorized_replay_accepts_one_buy_with_protective_sell_and_rejects_ambiguous_rows() = runBlocking {
        val decision = offEnterDecision()
        val permit = requireNotNull(FalsifierPolicyPermit.from(decision))
        val command = command(decision.intentId).let { value ->
            value.copy(auditContext = value.auditContext.copy(clientRequestId = value.authorizedFingerprint(permit)))
        }
        val exactBroker = brokerWith(decision, orderRows(command, includeProtectiveSell = true))

        assertTrue(exactBroker.placeAuthorizedOrder(AuthorizedPlaceOrder(command, permit)).isSuccess)

        val ambiguousBroker = brokerWith(decision, orderRows(command) + entryOrder(command, "entry-2"))
        val failure = ambiguousBroker.placeAuthorizedOrder(AuthorizedPlaceOrder(command, permit)).exceptionOrNull()

        assertTrue(failure is AuthorizedAuthorityIndeterminateException)
    }

    @Test
    fun public_preview_rejects_reserved_id_before_execution() = runBlocking {
        val broker = PaperBroker(
            ledgerRepository = InMemoryPaperLedgerRepository(),
            riskStateRepository = InMemoryRiskStateRepository(),
        )

        val failure = broker.previewOrder(command(UUID.randomUUID(), "runner-place-v2-spoof")).exceptionOrNull()

        assertTrue(failure is ReservedAuthorizedClientRequestIdException)
    }

    @Test
    fun authority_mismatch_partial_and_unavailable_fail_closed_before_replay() = runBlocking {
        val decision = offEnterDecision()
        val permit = requireNotNull(FalsifierPolicyPermit.from(decision))
        val command = command(decision.intentId).let { value ->
            value.copy(auditContext = value.auditContext.copy(clientRequestId = value.authorizedFingerprint(permit)))
        }
        val repository = InMemoryFalsifierPolicyDecisionRepository()
        repository.recordFalsifierPolicyDecision(FalsifierPolicyDecisionRequest(decision)).getOrThrow()
        val broker = PaperBroker(
            ledgerRepository = InMemoryPaperLedgerRepository(openOrders = orderRows(command)),
            riskStateRepository = InMemoryRiskStateRepository(),
            falsifierPolicyDecisionRepository = repository,
        )
        val mismatchedPermits = listOf(
            permit.copy(decisionId = UUID.randomUUID()),
            permit.copy(intentId = UUID.randomUUID()),
            permit.copy(attributes = permit.attributes.copy(action = DecisionAction.ADD_LONG)),
            permit.copy(attributes = permit.attributes.copy(policy = FalsifierPolicy.ALWAYS_ON_V1)),
            permit.copy(attributes = permit.attributes.copy(runtimeConfigHash = "other")),
            permit.copy(attributes = permit.attributes.copy(runtimeConfigVersionId = "other")),
            permit.copy(attributes = permit.attributes.copy(required = true)),
            permit.copy(attributes = permit.attributes.copy(reasonCodes = emptySet())),
        )

        val allMismatchesAreIndeterminate = mismatchedPermits.all { mismatch ->
            val failure = broker.placeAuthorizedOrder(AuthorizedPlaceOrder(command, mismatch)).exceptionOrNull()

            failure is AuthorizedAuthorityIndeterminateException
        }
        assertTrue(allMismatchesAreIndeterminate)

        val unavailable = PaperBroker(
            ledgerRepository = InMemoryPaperLedgerRepository(openOrders = orderRows(command)),
            riskStateRepository = InMemoryRiskStateRepository(),
            falsifierPolicyDecisionRepository = FailingPolicyRepository,
        )
        val unavailableFailure = unavailable.placeAuthorizedOrder(AuthorizedPlaceOrder(command, permit)).exceptionOrNull()
        assertTrue(unavailableFailure is AuthorizedAuthorityUnavailableException)

        val partialRepository = InMemoryFalsifierPolicyDecisionRepository()
        partialRepository.seedDecisionWithoutEvent(decision)
        val partial = PaperBroker(
            ledgerRepository = InMemoryPaperLedgerRepository(openOrders = orderRows(command)),
            riskStateRepository = InMemoryRiskStateRepository(),
            falsifierPolicyDecisionRepository = partialRepository,
        )
        val partialFailure = partial.placeAuthorizedOrder(AuthorizedPlaceOrder(command, permit)).exceptionOrNull()
        assertTrue(partialFailure is AuthorizedAuthorityIndeterminateException)
    }

    @Test
    fun replay_wrong_intent_null_or_other_trade_group_and_unsupported_fail_closed() = runBlocking {
        val decision = offEnterDecision()
        val permit = requireNotNull(FalsifierPolicyPermit.from(decision))
        val command = command(decision.intentId).let { value ->
            value.copy(auditContext = value.auditContext.copy(clientRequestId = value.authorizedFingerprint(permit)))
        }
        val entry = entryOrder(command, "entry-1")
        val corruptRows = listOf(
            listOf(entry.copy(intentId = UUID.randomUUID().toString())),
            listOf(entry.copy(tradeGroupId = null)),
            listOf(entry, entry.copy(orderId = "stop-1", side = OrderSide.SELL, tradeGroupId = UUID.randomUUID().toString())),
        )

        for (rows in corruptRows) {
            val failure = brokerWith(decision, rows).placeAuthorizedOrder(AuthorizedPlaceOrder(command, permit)).exceptionOrNull()
            assertTrue(failure is AuthorizedAuthorityIndeterminateException)
        }

        val unsupported = PaperBroker(
            ledgerRepository = object : PaperLedgerRepository by InMemoryPaperLedgerRepository() {},
            riskStateRepository = InMemoryRiskStateRepository(),
            falsifierPolicyDecisionRepository = policyRepositoryFor(decision),
        )
        val failure = unsupported.placeAuthorizedOrder(AuthorizedPlaceOrder(command, permit)).exceptionOrNull()
        assertTrue(failure is AuthorizedReplayUnsupportedException)
    }

    @Test
    fun seeded_exact_replay_wins_over_consumed_intent() = runBlocking {
        val decisions = InMemoryDecisionRepository()
        val initial = command(UUID.randomUUID())
        val intentId = requireNotNull(decisions.submitDecision(decisionSubmission(initial)).getOrThrow().tradeIntent).intentId
        decisions.appendIntentConsumption(intentId, UUID.randomUUID(), Instant.now()).getOrThrow()
        val decision = offEnterDecision(intentId)
        val permit = requireNotNull(FalsifierPolicyPermit.from(decision))
        val authorized = command(intentId).let { value ->
            value.copy(auditContext = value.auditContext.copy(clientRequestId = value.authorizedFingerprint(permit)))
        }
        val broker = PaperBroker(
            ledgerRepository = InMemoryPaperLedgerRepository(openOrders = orderRows(authorized)),
            riskStateRepository = InMemoryRiskStateRepository(),
            decisionRepository = decisions,
            falsifierPolicyDecisionRepository = policyRepositoryFor(decision),
        )

        assertTrue(broker.placeAuthorizedOrder(AuthorizedPlaceOrder(authorized, permit)).isSuccess)

        val missing = PaperBroker(
            ledgerRepository = InMemoryPaperLedgerRepository(),
            riskStateRepository = InMemoryRiskStateRepository(),
            decisionRepository = decisions,
            falsifierPolicyDecisionRepository = policyRepositoryFor(decision),
        )
        val missingFailure = missing.placeAuthorizedOrder(AuthorizedPlaceOrder(authorized, permit)).exceptionOrNull()
        assertTrue(missingFailure is AuthorizedNewMutationUnsupportedException)
    }

    private fun offEnterDecision(intentId: UUID = UUID.randomUUID()): FalsifierPolicyDecision {
        return FalsifierPolicyDecision.create(
            decisionId = UUID.randomUUID(),
            intentId = intentId,
            attributes = FalsifierPolicyDecisionAttributes(
                action = DecisionAction.ENTER,
                policy = FalsifierPolicy.OFF_V1,
                required = false,
                reasonCodes = setOf(FalsifierPolicyReasonCode.POLICY_OFF),
                runtimeConfigVersionId = "test-version",
                runtimeConfigHash = "test-hash",
            ),
            createdAt = Instant.parse("2026-07-31T00:00:00Z"),
        )
    }

    private fun command(intentId: UUID, clientRequestId: String? = null): PlaceOrderCommand {
        return PlaceOrderCommand(
            commandId = UUID.randomUUID(),
            intentId = intentId,
            symbol = TradingSymbol.BTC,
            side = OrderSide.BUY,
            orderType = OrderType.MARKET,
            sizeBtc = BigDecimal("0.01"),
            priceJpy = null,
            tradeGroupId = null,
            protectiveStopPriceJpy = BigDecimal("100"),
            takeProfitPriceJpy = null,
            estimatedWinProbability = BigDecimal("0.5"),
            reasonJa = "test",
            auditContext = PaperTradeAuditContext.EMPTY.copy(clientRequestId = clientRequestId),
        )
    }

    private suspend fun brokerWith(decision: FalsifierPolicyDecision, orders: List<Order>): PaperBroker {
        return PaperBroker(
            ledgerRepository = InMemoryPaperLedgerRepository(openOrders = orders),
            riskStateRepository = InMemoryRiskStateRepository(),
            falsifierPolicyDecisionRepository = policyRepositoryFor(decision),
        )
    }

    private suspend fun policyRepositoryFor(
        decision: FalsifierPolicyDecision,
    ): InMemoryFalsifierPolicyDecisionRepository {
        return InMemoryFalsifierPolicyDecisionRepository().also { repository ->
            repository.recordFalsifierPolicyDecision(FalsifierPolicyDecisionRequest(decision)).getOrThrow()
        }
    }

    private fun orderRows(command: PlaceOrderCommand, includeProtectiveSell: Boolean = false): List<Order> {
        val entry = entryOrder(command, "entry-1")
        if (!includeProtectiveSell) return listOf(entry)

        return listOf(entry, entry.copy(orderId = "stop-1", side = OrderSide.SELL, orderType = OrderType.STOP))
    }

    private fun entryOrder(command: PlaceOrderCommand, orderId: String): Order {
        return Order(
            orderId = orderId,
            intentId = requireNotNull(command.intentId).toString(),
            positionId = null,
            tradeGroupId = "00000000-0000-0000-0000-000000000001",
            symbol = "BTC",
            mode = me.matsumo.fukurou.trading.domain.TradingMode.PAPER,
            side = OrderSide.BUY,
            orderType = OrderType.MARKET,
            status = OrderStatus.FILLED,
            sizeBtc = "0.01",
            limitPriceJpy = null,
            triggerPriceJpy = null,
            protectiveStopPriceJpy = "100",
            takeProfitPriceJpy = null,
            estimatedWinProbability = "0.5",
            reasonJa = "test",
            clientRequestId = command.auditContext.clientRequestId,
            createdAt = "2026-07-31T00:00:00Z",
            updatedAt = "2026-07-31T00:00:00Z",
        )
    }

    private fun decisionSubmission(command: PlaceOrderCommand): DecisionSubmission {
        return DecisionSubmission(
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
    }
}

private object FailingPolicyRepository : FalsifierPolicyDecisionRepository {
    override suspend fun recordFalsifierPolicyDecision(
        request: FalsifierPolicyDecisionRequest,
    ): Result<FalsifierPolicyDecision> = Result.failure(IllegalStateException("unavailable"))

    override suspend fun findFalsifierPolicyDecision(intentId: UUID): Result<FalsifierPolicyDecision?> {
        return Result.failure(IllegalStateException("unavailable"))
    }
}
