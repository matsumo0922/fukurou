package me.matsumo.fukurou.trading.decision

/** submission gateway が公開する有限の拒否理由。 */
enum class SubmissionRejectionCode(
    val wireValue: String,
    val message: String,
) {
    INVOCATION_BINDING_MISMATCH(
        wireValue = "invocation_binding_mismatch",
        message = "Submission invocation binding does not match.",
    ),
    PHASE_BINDING_MISMATCH(
        wireValue = "phase_binding_mismatch",
        message = "Submission phase binding does not match.",
    ),
    MANIFEST_BINDING_MISMATCH(
        wireValue = "manifest_binding_mismatch",
        message = "Submission manifest binding does not match.",
    ),
    EFFECTIVE_HASH_MISMATCH(
        wireValue = "effective_hash_mismatch",
        message = "Submission effective invocation hash does not match.",
    ),
    PHASE_NOT_AUTHORIZED(
        wireValue = "phase_not_authorized",
        message = "Submission operation is not authorized for this phase.",
    ),
    RISK_INCREASING_ACTION_REJECTED(
        wireValue = "risk_increasing_action_rejected",
        message = "Risk-increasing action is rejected for this phase.",
    ),
    DECISION_INVOCATION_MISMATCH(
        wireValue = "decision_invocation_mismatch",
        message = "Decision invocation does not match the gateway binding.",
    ),
    TERMINAL_EVIDENCE_CONTRACT_VIOLATION(
        wireValue = "terminal_evidence_contract_violation",
        message = "Terminal evidence request violates the gateway contract.",
    ),
    MALFORMED_REQUEST(
        wireValue = "malformed_request",
        message = "Submission gateway request is malformed.",
    ),
    SUBMISSION_CONFLICT(
        wireValue = "submission_conflict",
        message = "Decision submission conflicts with the existing authority.",
    ),
    SUBMISSION_UNKNOWN(
        wireValue = "submission_unknown",
        message = "Decision submission result is unknown.",
    ),
    UNCLASSIFIED(
        wireValue = "unclassified",
        message = "Submission gateway rejected the request.",
    ),
    ;

    companion object {
        fun fromWireValue(value: String): SubmissionRejectionCode? {
            return entries.firstOrNull { code -> code.wireValue == value }
        }
    }
}

/** submission gateway の拒否理由を型付きで保持する例外。 */
class SubmissionRejectedException(
    val code: SubmissionRejectionCode,
) : RuntimeException(code.message)

internal fun Throwable.gatewayRejectionCode(): SubmissionRejectionCode {
    return when (this) {
        is SubmissionRejectedException -> code
        is DecisionSubmissionConflictException -> SubmissionRejectionCode.SUBMISSION_CONFLICT
        is DecisionSubmissionUnknownException -> SubmissionRejectionCode.SUBMISSION_UNKNOWN
        else -> SubmissionRejectionCode.UNCLASSIFIED
    }
}

fun Throwable.submissionRejectionCodeOrNull(): SubmissionRejectionCode? {
    return when (this) {
        is SubmissionRejectedException -> code
        else -> (cause as? SubmissionRejectedException)?.code
    }
}
