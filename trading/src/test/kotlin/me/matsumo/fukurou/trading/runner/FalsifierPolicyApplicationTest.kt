package me.matsumo.fukurou.trading.runner

import me.matsumo.fukurou.trading.config.DecisionProtocolConfig
import me.matsumo.fukurou.trading.config.FalsifierPolicy
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.RuntimeConfigCatalog
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.config.calculateRuntimeConfigHash
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.FalsifierPolicyDecision
import me.matsumo.fukurou.trading.decision.FalsifierPolicyReasonCode
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FalsifierPolicyApplicationTest {
    @Test
    fun resolver_returnsCanonicalAttributesForOnOffAndConditionalPolicies() {
        val alwaysOn = FalsifierPolicyAttributesResolver.resolve(FalsifierPolicy.ALWAYS_ON_V1, DecisionAction.ENTER)
        val offEnter = FalsifierPolicyAttributesResolver.resolve(FalsifierPolicy.OFF_V1, DecisionAction.ENTER)
        val offAddLong = FalsifierPolicyAttributesResolver.resolve(FalsifierPolicy.OFF_V1, DecisionAction.ADD_LONG)
        val conditional = FalsifierPolicyAttributesResolver.resolve(FalsifierPolicy.CONDITIONAL_V1, DecisionAction.ENTER)

        assertTrue(alwaysOn.required)
        assertEquals(setOf(FalsifierPolicyReasonCode.ALWAYS_ON), alwaysOn.reasonCodes)
        assertEquals(false, offEnter.required)
        assertEquals(setOf(FalsifierPolicyReasonCode.POLICY_OFF), offEnter.reasonCodes)
        assertTrue(offAddLong.required)
        assertEquals(setOf(FalsifierPolicyReasonCode.ADD_LONG_REQUIRES_FALSIFIER), offAddLong.reasonCodes)
        assertTrue(conditional.required)
        assertEquals(setOf(FalsifierPolicyReasonCode.CONDITIONAL_NOT_APPLIED), conditional.reasonCodes)
    }

    @Test
    fun resolver_usesVerifiedSnapshotAndRejectsMismatchedHash() {
        val config = TradingBotConfig()
        val values = RuntimeConfigCatalog.runtimeItems(config).associate { item ->
            item.key to requireNotNull(item.effectiveValue)
        }
        val hash = calculateRuntimeConfigHash(values)

        val identity = FalsifierPolicyAttributesResolver.resolveRuntimeConfigIdentity(
            tradingConfig = config,
            snapshot = RuntimeConfigAuditSnapshot(versionId = "runtime-v1", hash = hash),
        )

        assertEquals("runtime-v1", identity.versionId)
        assertEquals(hash, identity.hash)
        assertFailsWith<IllegalArgumentException> {
            FalsifierPolicyAttributesResolver.resolveRuntimeConfigIdentity(
                tradingConfig = config,
                snapshot = RuntimeConfigAuditSnapshot(versionId = "runtime-v1", hash = "different"),
            )
        }
    }

    @Test
    fun offPermit_requiresCanonicalOffEnterDecision() {
        val attributes = FalsifierPolicyAttributesResolver.resolve(
            tradingConfig = TradingBotConfig(
                decisionProtocol = DecisionProtocolConfig(falsifierPolicy = FalsifierPolicy.OFF_V1),
            ),
            snapshot = null,
            action = DecisionAction.ENTER,
        )
        val decision = FalsifierPolicyDecision.create(
            decisionId = UUID.randomUUID(),
            intentId = UUID.randomUUID(),
            attributes = attributes,
            createdAt = Instant.EPOCH,
        )

        assertEquals(decision.decisionId, requireNotNull(FalsifierPolicyPermit.from(decision)).decisionId)
        assertNull(FalsifierPolicyPermit.from(decision.copyWith(action = DecisionAction.ADD_LONG)))
    }
}

private fun FalsifierPolicyDecision.copyWith(action: DecisionAction): FalsifierPolicyDecision {
    return FalsifierPolicyDecision.create(
        decisionId = decisionId,
        intentId = intentId,
        attributes = attributes.copy(action = action),
        createdAt = createdAt,
    )
}
