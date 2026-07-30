package me.matsumo.fukurou.trading.broker

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.decision.FalsifierPolicyDecisionConflictException
import me.matsumo.fukurou.trading.decision.FalsifierPolicyDecisionRepository
import me.matsumo.fukurou.trading.runner.FalsifierPolicyPermit
import java.math.BigDecimal
import java.security.MessageDigest

/** public command に authority を露出しないための module-internal envelope。 */
internal data class AuthorizedPreviewOrder(
    val command: PlaceOrderCommand,
    val permit: FalsifierPolicyPermit,
)

/** public command に authority を露出しないための module-internal envelope。 */
internal data class AuthorizedPlaceOrder(
    val command: PlaceOrderCommand,
    val permit: FalsifierPolicyPermit,
)

/** A1 の inactive authority / exact replay boundary。新規 mutation は実装しない。 */
internal class AuthorizedFalsifierPolicyBoundary(
    private val policyDecisionRepository: FalsifierPolicyDecisionRepository,
    private val replayReader: AuthorizedPlaceOrderReplayReader?,
    private val preview: suspend (PlaceOrderCommand) -> Result<PreviewOrderResult>,
) {
    suspend fun previewOrder(request: AuthorizedPreviewOrder): Result<PreviewOrderResult> = runCatching {
        verifyAuthority(request.command, request.permit)
        preview(request.command).getOrThrow()
    }

    suspend fun placeOrder(request: AuthorizedPlaceOrder): Result<PaperTradeResult> = runCatching {
        verifyAuthority(request.command, request.permit)
        verifyFingerprint(request.command, request.permit)

        val replayReader = replayReader
            ?: throw AuthorizedReplayUnsupportedException()
        val replay = replayReader.findAuthorizedPlaceOrderReplay(
            clientRequestId = requireNotNull(request.command.auditContext.clientRequestId),
            intentId = request.command.intentId ?: throw AuthorizedAuthorityIndeterminateException(),
        ).getOrElse { throw AuthorizedAuthorityIndeterminateException(it) }

        when (replay) {
            is AuthorizedPlaceOrderReplay.Exact -> replay.result
            AuthorizedPlaceOrderReplay.Missing -> throw AuthorizedNewMutationUnsupportedException()
            AuthorizedPlaceOrderReplay.Ambiguous -> throw AuthorizedAuthorityIndeterminateException()
        }
    }

    private suspend fun verifyAuthority(command: PlaceOrderCommand, permit: FalsifierPolicyPermit) {
        val intentId = command.intentId ?: throw AuthorizedAuthorityIndeterminateException()
        if (intentId != permit.intentId) throw AuthorizedAuthorityIndeterminateException()

        val durable = policyDecisionRepository.findFalsifierPolicyDecision(intentId).getOrElse { error ->
            if (error is FalsifierPolicyDecisionConflictException) throw AuthorizedAuthorityIndeterminateException(error)
            throw AuthorizedAuthorityUnavailableException(error)
        } ?: throw AuthorizedAuthorityIndeterminateException()

        if (FalsifierPolicyPermit.from(durable) != permit) throw AuthorizedAuthorityIndeterminateException()
    }

    private fun verifyFingerprint(command: PlaceOrderCommand, permit: FalsifierPolicyPermit) {
        val clientRequestId = command.auditContext.clientRequestId
            ?: throw AuthorizedFingerprintMismatchException()
        if (clientRequestId != command.authorizedFingerprint(permit)) throw AuthorizedFingerprintMismatchException()
    }
}

internal fun PaperLedgerRepository.authorizedReplayReaderOrNull(): AuthorizedPlaceOrderReplayReader? {
    val inMemory = this as? InMemoryPaperLedgerRepository ?: return null

    return object : AuthorizedPlaceOrderReplayReader {
        override suspend fun findAuthorizedPlaceOrderReplay(
            clientRequestId: String,
            intentId: java.util.UUID,
        ): Result<AuthorizedPlaceOrderReplay> {
            return inMemory.findAuthorizedPlaceOrderReplay(clientRequestId, intentId)
        }
    }
}

internal class AuthorizedAuthorityIndeterminateException(cause: Throwable? = null) : IllegalStateException(
    "authorized order authority is indeterminate.",
    cause,
)

internal class AuthorizedAuthorityUnavailableException(cause: Throwable) : IllegalStateException(
    "authorized order authority is unavailable.",
    cause,
)

internal class AuthorizedFingerprintMismatchException : IllegalArgumentException(
    "authorized order fingerprint does not match the command and authority.",
)

internal class AuthorizedReplayUnsupportedException : IllegalStateException(
    "authorized replay reader is unsupported by this ledger.",
)

internal class AuthorizedNewMutationUnsupportedException : IllegalStateException(
    "authorized new order mutation is unsupported in A1.",
)

internal fun PlaceOrderCommand.authorizedFingerprint(permit: FalsifierPolicyPermit): String {
    val canonicalJson = buildJsonObject {
        put("schemaVersion", "falsifier-authority-v1")
        put(
            "command",
            authorizedCommandJson(),
        )
        put(
            "authority",
            permit.authorizedAuthorityJson(),
        )
    }.toString()
    val digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson.toByteArray(Charsets.UTF_8))

    return AUTHORIZED_CLIENT_REQUEST_ID_PREFIX + digest.joinToString("") { byte -> "%02x".format(byte) }
}

private fun PlaceOrderCommand.authorizedCommandJson() = buildJsonObject {
    put("intentId", jsonStringOrNull(intentId?.toString()))
    put("symbol", symbol.name)
    put("side", side.name)
    put("orderType", orderType.name)
    put("sizeBtc", sizeBtc.canonicalDecimal())
    put("priceJpy", jsonDecimalOrNull(priceJpy))
    put("tradeGroupId", jsonStringOrNull(tradeGroupId?.toString()))
    put("protectiveStopPriceJpy", protectiveStopPriceJpy.canonicalDecimal())
    put("takeProfitPriceJpy", jsonDecimalOrNull(takeProfitPriceJpy))
    put("estimatedWinProbability", estimatedWinProbability.canonicalDecimal())
    put("timeStopAt", jsonStringOrNull(timeStopAt?.toString()))
    put("canonicalThesisId", jsonStringOrNull(canonicalThesisId))
}

private fun FalsifierPolicyPermit.authorizedAuthorityJson() = buildJsonObject {
    put("decisionId", decisionId.toString())
    put("intentId", intentId.toString())
    put("action", attributes.action.name)
    put("policy", attributes.policy.name)
    put("required", attributes.required)
    put(
        "reasonCodes",
        buildJsonArray {
            attributes.reasonCodes.sortedBy { it.name }.forEach { reasonCode ->
                add(JsonPrimitive(reasonCode.name))
            }
        },
    )
    put("runtimeConfigVersionId", attributes.runtimeConfigVersionId)
    put("runtimeConfigHash", attributes.runtimeConfigHash)
}

private fun BigDecimal.canonicalDecimal(): JsonPrimitive = JsonPrimitive(toPlainString())

private fun jsonStringOrNull(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

private fun jsonDecimalOrNull(value: BigDecimal?): JsonElement = value?.canonicalDecimal() ?: JsonNull
