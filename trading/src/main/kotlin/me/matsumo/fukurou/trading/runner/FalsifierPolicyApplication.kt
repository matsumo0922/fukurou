package me.matsumo.fukurou.trading.runner

import me.matsumo.fukurou.trading.config.FalsifierPolicy
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.RuntimeConfigCatalog
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.config.calculateRuntimeConfigHash
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.FalsifierPolicyDecision
import me.matsumo.fukurou.trading.decision.FalsifierPolicyDecisionAttributes
import me.matsumo.fukurou.trading.decision.FalsifierPolicyReasonCode

/** policy decision に保存する runtime config identity。 */
internal data class FalsifierPolicyRuntimeConfigIdentity(
    val versionId: String,
    val hash: String,
)

/** policy/action から導出する固定の policy attributes。 */
internal object FalsifierPolicyAttributesResolver {
    fun resolve(policy: FalsifierPolicy, action: DecisionAction): FalsifierPolicyDecisionAttributes {
        val requiredAndReasons = when (policy) {
            FalsifierPolicy.ALWAYS_ON_V1 -> true to setOf(FalsifierPolicyReasonCode.ALWAYS_ON)
            FalsifierPolicy.CONDITIONAL_V1 -> true to setOf(FalsifierPolicyReasonCode.CONDITIONAL_NOT_APPLIED)
            FalsifierPolicy.OFF_V1 -> when (action) {
                DecisionAction.ENTER -> false to setOf(FalsifierPolicyReasonCode.POLICY_OFF)
                DecisionAction.ADD_LONG -> true to setOf(FalsifierPolicyReasonCode.ADD_LONG_REQUIRES_FALSIFIER)
                else -> true to setOf(FalsifierPolicyReasonCode.ALWAYS_ON)
            }
        }

        return FalsifierPolicyDecisionAttributes(
            action = action,
            policy = policy,
            required = requiredAndReasons.first,
            reasonCodes = requiredAndReasons.second,
            runtimeConfigVersionId = "unresolved",
            runtimeConfigHash = "unresolved",
        )
    }

    fun resolveRuntimeConfigIdentity(
        tradingConfig: TradingBotConfig,
        snapshot: RuntimeConfigAuditSnapshot?,
    ): FalsifierPolicyRuntimeConfigIdentity {
        val typedValues = RuntimeConfigCatalog.runtimeItems(tradingConfig).associate { item ->
            item.key to requireNotNull(item.effectiveValue) {
                "Runtime config effective value must not be null: ${item.key}"
            }
        }
        val typedHash = calculateRuntimeConfigHash(typedValues)
        if (snapshot != null) {
            require(snapshot.hash == typedHash) { "runtime config snapshot hash does not match typed config." }
            return FalsifierPolicyRuntimeConfigIdentity(snapshot.versionId, snapshot.hash)
        }

        return FalsifierPolicyRuntimeConfigIdentity(PROCESS_CONFIG_VERSION_ID, typedHash)
    }

    fun resolve(
        tradingConfig: TradingBotConfig,
        snapshot: RuntimeConfigAuditSnapshot?,
        action: DecisionAction,
    ): FalsifierPolicyDecisionAttributes {
        val identity = resolveRuntimeConfigIdentity(tradingConfig, snapshot)
        val unresolved = resolve(tradingConfig.decisionProtocol.falsifierPolicy, action)

        return unresolved.copy(
            runtimeConfigVersionId = identity.versionId,
            runtimeConfigHash = identity.hash,
        )
    }
}

/** OFF ENTER decision のみから再構築できる runner 内部専用 authority。 */
internal data class FalsifierPolicyPermit(
    val decisionId: java.util.UUID,
    val intentId: java.util.UUID,
    val attributes: FalsifierPolicyDecisionAttributes,
) {
    companion object {
        fun from(decision: FalsifierPolicyDecision): FalsifierPolicyPermit? {
            val attributes = decision.attributes
            val isOffEnter = attributes.action == DecisionAction.ENTER &&
                attributes.policy == FalsifierPolicy.OFF_V1 &&
                !attributes.required &&
                attributes.reasonCodes == setOf(FalsifierPolicyReasonCode.POLICY_OFF)
            if (!isOffEnter) return null

            return FalsifierPolicyPermit(
                decisionId = decision.decisionId,
                intentId = decision.intentId,
                attributes = attributes,
            )
        }
    }
}

private const val PROCESS_CONFIG_VERSION_ID = "process-config-v1"
