package me.matsumo.fukurou.trading.exchange.gmo

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.market.GmoRequestAuditException
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** GMO Public REST client の発生主体。 */
enum class GmoPublicClientType { FUKUROU_MCP, KTOR_LLM_RUNTIME, KTOR_RECONCILER, KTOR_EVALUATION, STANDALONE_MCP }

/** GMO Public REST request を起こした論理 role。 */
enum class GmoPublicClientRole { PROPOSER, FALSIFIER, RUNNER, UNSPECIFIED }

/** 監査対象の論理 operation。 */
enum class GmoPublicOperation { GET_TICKER, GET_CANDLES, GET_ORDERBOOK, GET_TRADES, GET_SYMBOL_RULES, GET_STATUS }

/** URI を保存せず識別する固定 endpoint。 */
enum class GmoPublicEndpoint { TICKER, KLINES, ORDERBOOK, TRADES, SYMBOLS, STATUS }

/** 実 HTTP attempt の安全な結果分類。 */
enum class GmoPublicRequestOutcome { HTTP_RESPONSE, HTTP_429, JSON_ERR_5003, NETWORK_ERROR, INTERRUPTED }

/** lexical scope 内だけで伝播する request 相関情報。 */
data class GmoPublicRequestCorrelation(
    val decisionRunContext: DecisionRunContext,
    val toolCallId: String?,
    val clientRole: GmoPublicClientRole,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GmoPublicRequestCorrelation>
}

/** GMO Public request 相関を coroutine scope に限定して付与する。 */
suspend fun <T> withGmoPublicRequestCorrelation(correlation: GmoPublicRequestCorrelation, block: suspend () -> T): T {
    return withContext(correlation) { block() }
}

/** 永続化する request-level allowlist payload。 */
@Serializable
data class GmoPublicRequestAuditEvent(
    val requestId: String,
    val operationId: String,
    val clientInstanceId: String,
    val processId: String,
    val decisionRunId: String? = null,
    val toolCallId: String? = null,
    val clientType: GmoPublicClientType,
    val clientRole: GmoPublicClientRole,
    val operation: GmoPublicOperation,
    val endpoint: GmoPublicEndpoint,
    val attempt: Int,
    val maxAttempts: Int,
    val requestSequence: Int,
    val requestStartedAt: String,
    val completedAt: String,
    val requestDurationMillis: Long,
    val permitWaitMillis: Long,
    val outcome: GmoPublicRequestOutcome,
    val httpStatusCode: Int? = null,
    val gmoMessageCode: String? = null,
)

/** request audit の保存境界。 */
fun interface GmoPublicRequestAuditSink {
    /** allowlist event を保存する。 */
    suspend fun append(event: GmoPublicRequestAuditEvent): Result<Unit>
}

/** test や明示的に監査不要な呼び出しで使う sink。 */
object NoopGmoPublicRequestAuditSink : GmoPublicRequestAuditSink {
    override suspend fun append(event: GmoPublicRequestAuditEvent): Result<Unit> = Result.success(Unit)
}

/** command_event_log へ request event を保存する adapter。 */
class CommandEventLogGmoPublicRequestAuditSink(
    private val commandEventLog: CommandEventLog,
) : GmoPublicRequestAuditSink {
    override suspend fun append(event: GmoPublicRequestAuditEvent): Result<Unit> {
        val context = currentCoroutineContext()[GmoPublicRequestCorrelation]

        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = context?.decisionRunContext ?: DecisionRunContext.EMPTY,
                toolName = "gmo_public_rest",
                toolCallId = event.toolCallId,
                clientRequestId = event.requestId,
                eventType = CommandEventType.GMO_PUBLIC_REST_REQUEST_COMPLETED,
                payload = AuditJson.encodeToString(event),
                occurredAt = Instant.parse(event.completedAt),
            ),
        )
    }
}

/** runtime 構築の循環依存を解く 1 回 bind の fail-closed sink。 */
class DeferredGmoPublicRequestAuditSink : GmoPublicRequestAuditSink {
    @Volatile private var delegate: GmoPublicRequestAuditSink? = null

    /** sink を一度だけ bind する。 */
    fun bind(sink: GmoPublicRequestAuditSink) {
        check(delegate == null) { "GMO public request audit sink is already bound." }
        delegate = sink
    }

    override suspend fun append(event: GmoPublicRequestAuditEvent): Result<Unit> {
        return delegate?.append(event) ?: Result.failure(GmoRequestAuditException())
    }
}

/** standalone MCP 用に allowlist payload だけを stderr へ出す sink。 */
class LoggingGmoPublicRequestAuditSink : GmoPublicRequestAuditSink {
    override suspend fun append(event: GmoPublicRequestAuditEvent): Result<Unit> = runCatching {
        System.err.println(AuditJson.encodeToString(event))
    }
}

internal val GmoProcessId: String = runCatching { ManagementFactory.getRuntimeMXBean().name.substringBefore('@') }
    .getOrDefault("unknown")
internal fun newGmoAuditId(): String = UUID.randomUUID().toString()

private val AuditJson = Json {
    encodeDefaults = true
    explicitNulls = false
}
