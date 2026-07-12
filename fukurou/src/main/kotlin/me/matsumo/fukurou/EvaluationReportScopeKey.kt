package me.matsumo.fukurou

/** Evaluation Report の versioned/unversioned scope identity。 */
internal data class EvaluationReportScopeKey(
    val base: String,
    val epochId: String? = null,
    val cohort: String? = null,
) {
    init {
        require(base.isNotBlank() && "|" !in base) { "REPORT_SCOPE_INVALID: invalid base scope." }
        require((epochId == null) == (cohort == null)) { "REPORT_SCOPE_INVALID: incomplete versioned scope." }
    }

    val versioned: Boolean get() = epochId != null

    fun encode(): String = if (epochId == null) base else "$base|EPOCH:$epochId|COHORT:$cohort"

    fun version(epochId: String, cohort: String): EvaluationReportScopeKey =
        EvaluationReportScopeKey(base, epochId, cohort)

    companion object {
        fun decode(value: String): EvaluationReportScopeKey {
            val parts = value.split("|EPOCH:", limit = 2)
            if (parts.size == 1) return EvaluationReportScopeKey(parts.single())
            val version = parts[1].split("|COHORT:", limit = 2)
            require(version.size == 2 && version.all(String::isNotBlank)) {
                "REPORT_SCOPE_INVALID: malformed versioned scope."
            }
            return EvaluationReportScopeKey(parts[0], version[0], version[1])
        }
    }
}
