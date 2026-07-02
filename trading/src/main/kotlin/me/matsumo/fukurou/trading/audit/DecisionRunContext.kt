package me.matsumo.fukurou.trading.audit

/**
 * daemon が MCP 子プロセスへ渡す decision run id の環境変数名。
 */
private const val FUKUROU_INVOCATION_ID_ENV = "FUKUROU_INVOCATION_ID"

/**
 * LLM provider 名を監査ログへ伝播する環境変数名。
 */
private const val FUKUROU_LLM_PROVIDER_ENV = "FUKUROU_LLM_PROVIDER"

/**
 * prompt hash を監査ログへ伝播する環境変数名。
 */
private const val FUKUROU_PROMPT_HASH_ENV = "FUKUROU_PROMPT_HASH"

/**
 * system prompt version を監査ログへ伝播する環境変数名。
 */
private const val FUKUROU_SYSTEM_PROMPT_VERSION_ENV = "FUKUROU_SYSTEM_PROMPT_VERSION"

/**
 * market snapshot id を監査ログへ伝播する環境変数名。
 */
private const val FUKUROU_MARKET_SNAPSHOT_ID_ENV = "FUKUROU_MARKET_SNAPSHOT_ID"

/**
 * 1 回の LLM 起動に紐づく監査コンテキスト。
 *
 * @param decisionRunId daemon が `FUKUROU_INVOCATION_ID` として渡す起動 ID
 * @param llmProvider LLM provider 名
 * @param promptHash 利用した prompt の hash
 * @param systemPromptVersion system prompt の版
 * @param marketSnapshotId 判断前に固定した market snapshot ID
 */
data class DecisionRunContext(
    val decisionRunId: String?,
    val llmProvider: String?,
    val promptHash: String?,
    val systemPromptVersion: String?,
    val marketSnapshotId: String?,
) {
    companion object {
        /**
         * LLM 起動に紐づかない worker / test 用の空コンテキスト。
         */
        val EMPTY = DecisionRunContext(
            decisionRunId = null,
            llmProvider = null,
            promptHash = null,
            systemPromptVersion = null,
            marketSnapshotId = null,
        )

        /**
         * 環境変数から decision run context を組み立てる。
         */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): DecisionRunContext {
            return DecisionRunContext(
                decisionRunId = environment.blankToNull(FUKUROU_INVOCATION_ID_ENV),
                llmProvider = environment.blankToNull(FUKUROU_LLM_PROVIDER_ENV),
                promptHash = environment.blankToNull(FUKUROU_PROMPT_HASH_ENV),
                systemPromptVersion = environment.blankToNull(FUKUROU_SYSTEM_PROMPT_VERSION_ENV),
                marketSnapshotId = environment.blankToNull(FUKUROU_MARKET_SNAPSHOT_ID_ENV),
            )
        }
    }
}

/**
 * 空白だけの環境変数を未設定として扱う。
 */
private fun Map<String, String>.blankToNull(key: String): String? {
    return this[key]?.takeIf { value -> value.isNotBlank() }
}
