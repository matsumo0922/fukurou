package me.matsumo.fukurou.trading.decision

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.config.FalsifierPolicy
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Falsifier policy decision に保存する、自由文を含まない理由。 */
enum class FalsifierPolicyReasonCode {
    ALWAYS_ON,
    POLICY_OFF,
    RISK_THRESHOLD,
    REGIME_UNKNOWN,
    REGIME_UNFAVORABLE,
    RECENT_LOSSES,
    RECENT_OUTCOME_UNKNOWN,
}

/** intent ごとに一意な、後続 policy application の監査正本。 */
data class FalsifierPolicyDecision(
    val decisionId: UUID,
    val intentId: UUID,
    val policy: FalsifierPolicy,
    val required: Boolean,
    val reasonCodes: Set<FalsifierPolicyReasonCode>,
    val runtimeConfigVersionId: String,
    val runtimeConfigHash: String,
    val createdAt: Instant,
) {
    init {
        require(runtimeConfigVersionId.isNotBlank()) { "runtimeConfigVersionId must not be blank." }
        require(runtimeConfigHash.isNotBlank()) { "runtimeConfigHash must not be blank." }
    }

    /** audit event に保存する canonical projection。 */
    fun canonicalPayload(): String = buildJsonObject {
        put("decisionId", decisionId.toString())
        put("intentId", intentId.toString())
        put("policy", policy.name)
        put("required", required)
        put("reasonCodes", buildJsonArray {
            reasonCodes.sortedBy(FalsifierPolicyReasonCode::name).forEach { reason -> add(JsonPrimitive(reason.name)) }
        })
        put("runtimeConfigVersionId", runtimeConfigVersionId)
        put("runtimeConfigHash", runtimeConfigHash)
        put("createdAt", createdAt.toString())
    }.toString()
}

/** policy decision 保存要求。payload は repository が canonical projection だけから生成する。 */
data class FalsifierPolicyDecisionRequest(
    val decision: FalsifierPolicyDecision,
    val decisionRunContext: DecisionRunContext = DecisionRunContext.EMPTY,
)

/** intent ごとの decision と canonical audit event を同一原子境界で保存する。 */
interface FalsifierPolicyDecisionRepository {
    suspend fun recordFalsifierPolicyDecision(request: FalsifierPolicyDecisionRequest): Result<FalsifierPolicyDecision>

    suspend fun findFalsifierPolicyDecision(intentId: UUID): Result<FalsifierPolicyDecision?>
}

/** 同一 intent / decision ID の別 payload、または監査片側欠損を fail closed にする。 */
class FalsifierPolicyDecisionConflictException(message: String) : IllegalStateException(message)

/** unit test と DB 未構成 runtime 用の atomic contract 実装。 */
class InMemoryFalsifierPolicyDecisionRepository(
    private val clock: Clock = Clock.systemUTC(),
) : FalsifierPolicyDecisionRepository {
    private val mutex = Mutex()
    private val decisionsByIntent = mutableMapOf<UUID, FalsifierPolicyDecision>()
    private val eventsById = mutableMapOf<UUID, CommandEvent>()

    override suspend fun recordFalsifierPolicyDecision(request: FalsifierPolicyDecisionRequest): Result<FalsifierPolicyDecision> = runCatching {
        mutex.withLock {
            val decision = request.decision
            val existingByIntent = decisionsByIntent[decision.intentId]
            val existingEvent = eventsById[decision.decisionId]

            if (existingByIntent != null || existingEvent != null) {
                return@withLock readExistingOrConflict(decision, existingByIntent, existingEvent)
            }

            val event = decision.toCommandEvent(request.decisionRunContext)
            decisionsByIntent[decision.intentId] = decision
            eventsById[event.id] = event
            decision
        }
    }

    override suspend fun findFalsifierPolicyDecision(intentId: UUID): Result<FalsifierPolicyDecision?> = runCatching {
        mutex.withLock { decisionsByIntent[intentId] }
    }

    /** test 用の片側 legacy state を投入する。 */
    internal suspend fun seedDecisionWithoutEvent(decision: FalsifierPolicyDecision) {
        mutex.withLock { decisionsByIntent[decision.intentId] = decision }
    }

    /** test 用の event snapshot。 */
    internal suspend fun events(): List<CommandEvent> = mutex.withLock { eventsById.values.toList() }

    private fun readExistingOrConflict(
        requested: FalsifierPolicyDecision,
        existingByIntent: FalsifierPolicyDecision?,
        existingEvent: CommandEvent?,
    ): FalsifierPolicyDecision {
        val existing = existingByIntent ?: throw FalsifierPolicyDecisionConflictException("policy decision event exists without decision.")
        if (existing.decisionId != requested.decisionId || existing != requested) {
            throw FalsifierPolicyDecisionConflictException("policy decision payload conflicts with existing intent.")
        }
        val event = existingEvent ?: throw FalsifierPolicyDecisionConflictException("policy decision exists without event.")
        if (!event.matches(existing)) throw FalsifierPolicyDecisionConflictException("policy decision event payload conflicts.")

        return existing
    }

    private fun FalsifierPolicyDecision.toCommandEvent(context: DecisionRunContext): CommandEvent = CommandEvent(
        id = decisionId,
        decisionRunContext = context,
        toolName = FALSIFIER_POLICY_EVENT_TOOL_NAME,
        toolCallId = null,
        clientRequestId = null,
        eventType = CommandEventType.FALSIFIER_POLICY_EVALUATED,
        payload = canonicalPayload(),
        occurredAt = createdAt,
    )

    private fun CommandEvent.matches(decision: FalsifierPolicyDecision): Boolean =
        eventType == CommandEventType.FALSIFIER_POLICY_EVALUATED &&
            toolName == FALSIFIER_POLICY_EVENT_TOOL_NAME &&
            payload == decision.canonicalPayload()
}

const val FALSIFIER_POLICY_EVENT_TOOL_NAME = "falsifier_policy"
