@file:Suppress("LongMethod", "TooManyFunctions")

package me.matsumo.fukurou.trading.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.reflection.MAX_REFLECTION_LLM_TIMEOUT
import me.matsumo.fukurou.trading.reflection.MIN_REFLECTION_MIN_INTERVAL
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/**
 * DB active runtime config の読み取り境界。
 */
fun interface ActiveRuntimeConfigSource {
    /**
     * active runtime config snapshot を返す。
     */
    fun activeSnapshot(): Result<ActiveRuntimeConfigSnapshot>
}

/**
 * DB から読んだ active runtime config snapshot。
 *
 * @param versionId runtime_config_versions の ID。複数 component を合成した snapshot は null
 * @param activatedAt active 化された時刻
 * @param values runtime config key ごとの保存値
 * @param hash values から算出した content hash
 */
data class ActiveRuntimeConfigSnapshot(
    val versionId: String,
    val activatedAt: Instant,
    val values: Map<String, String>,
    val hash: String = calculateRuntimeConfigHash(values),
)

/**
 * 監査に残す runtime config snapshot 識別子。
 *
 * @param versionId runtime_config_versions の ID
 * @param hash values から算出した content hash
 */
data class RuntimeConfigAuditSnapshot(
    val versionId: String?,
    val hash: String,
)

/**
 * active runtime config を typed config へ解決した結果。
 *
 * @param tradingConfig typed config
 * @param auditSnapshot 監査に残す snapshot 識別子
 * @param typedEnvironment typed config と等価な env map
 * @param catalogEnvironment catalog API 表示用の env map
 */
data class RuntimeConfigResolution(
    val tradingConfig: TradingBotConfig,
    val auditSnapshot: RuntimeConfigAuditSnapshot,
    val typedEnvironment: Map<String, String>,
    val catalogEnvironment: Map<String, String>,
)

/**
 * runtime config version の概要。
 *
 * @param id version ID
 * @param status version 状態
 * @param createdAt 作成時刻
 * @param activatedAt active 化された時刻
 * @param createdBy 作成元
 * @param note secret を含まない補足
 * @param hash 保存値から算出した content hash
 */
@Serializable
data class RuntimeConfigVersionSummary(
    val id: String,
    val status: String,
    val createdAt: String,
    val activatedAt: String?,
    val createdBy: String,
    val note: String?,
    val hash: String,
)

/**
 * runtime config draft 作成入力。
 *
 * @param baseVersionId draft の基準にする version ID。null の場合は現在の active version
 * @param values 変更する runtime config key と候補値
 * @param note secret を含まない補足
 * @param createdBy 作成元
 */
data class RuntimeConfigDraftCreation(
    val baseVersionId: String?,
    val values: Map<String, String>,
    val note: String?,
    val createdBy: String,
)

/**
 * runtime config validation error。
 *
 * @param code WebUI i18n 用の machine-readable code
 * @param key 関連する runtime config key
 * @param params i18n 展開用の補助値
 */
@Serializable
data class RuntimeConfigValidationError(
    val code: String,
    val key: String? = null,
    val params: Map<String, String> = emptyMap(),
)

/**
 * runtime config validation 結果。
 *
 * @param valid 候補値を active 化できるか
 * @param errors validation error 一覧
 */
@Serializable
data class RuntimeConfigValidationResult(
    val valid: Boolean,
    val errors: List<RuntimeConfigValidationError> = emptyList(),
)

/**
 * runtime config validation rejection。
 *
 * @param validation WebUI に返す validation 結果
 */
class RuntimeConfigValidationRejectedException(
    val validation: RuntimeConfigValidationResult,
) : IllegalArgumentException("runtime config validation failed")

/**
 * runtime config draft の基準 active version が activate 前に変わったことを表す。
 */
class RuntimeConfigActiveVersionChangedException : IllegalStateException("active runtime config version changed")

/**
 * 保存済み runtime config version の詳細。
 *
 * @param version version 概要
 * @param values version に保存されている runtime config 値
 * @param validation 現在の catalog / typed config に対する validation 結果
 */
@Serializable
data class RuntimeConfigVersionDetail(
    val version: RuntimeConfigVersionSummary,
    val values: Map<String, String>,
    val validation: RuntimeConfigValidationResult,
)

/**
 * runtime config activation / rollback 結果。
 *
 * @param activeVersion active 化された version
 * @param previousActiveVersionId 直前に active だった version ID
 * @param validation active 化前に実行した validation 結果
 */
@Serializable
data class RuntimeConfigActivationResult(
    val activeVersion: RuntimeConfigVersionSummary,
    val previousActiveVersionId: String?,
    val validation: RuntimeConfigValidationResult,
)

/**
 * runtime config の draft / validate / activate / rollback 操作境界。
 */
interface RuntimeConfigAdminService {
    /**
     * runtime config version 一覧を新しい順に返す。
     */
    fun listVersions(limit: Int = DEFAULT_RUNTIME_CONFIG_VERSION_LIMIT): Result<List<RuntimeConfigVersionSummary>>

    /**
     * active または指定 version を基準に draft version を作成する。
     */
    fun createDraft(request: RuntimeConfigDraftCreation): Result<RuntimeConfigVersionDetail>

    /**
     * 保存済み version を現在の catalog / typed config に対して検証する。
     */
    fun validateVersion(versionId: String): Result<RuntimeConfigVersionDetail>

    /**
     * draft version を active 化する。
     */
    fun activateDraft(versionId: String): Result<RuntimeConfigActivationResult>

    /**
     * 指定した active version が現在も正本である場合だけ draft version を active 化する。
     *
     * 単一 key patch を full snapshot として保存する間に別の activate が入った場合、古い full snapshot で
     * 新しい設定を上書きしないために使う。
     */
    fun activateDraftIfActive(
        versionId: String,
        expectedActiveVersionId: String,
    ): Result<RuntimeConfigActivationResult>

    /**
     * active 化されなかった draft version を破棄する。
     */
    fun discardDraft(versionId: String): Result<Unit>

    /**
     * 保存済み inactive version へ rollback する。
     */
    fun rollbackToVersion(versionId: String): Result<RuntimeConfigActivationResult>
}

/**
 * runtime config の version 一覧で既定取得する件数。
 */
const val DEFAULT_RUNTIME_CONFIG_VERSION_LIMIT = 20

/**
 * code default と同等または保守側だけを runtime config として受け入れる key。
 */
internal val conservativeOnlyRuntimeConfigKeys = setOf(
    "paper.fallbackMakerFeeRate",
    "paper.fallbackTakerFeeRate",
    "paper.fallbackSpreadBps",
    "safety.maxRiskPerTradeRatio",
    "safety.maxDrawdownRatio",
    "safety.maxTotalExposureRatio",
    "safety.minExpectedValueR",
    "safety.minExpectedMoveToCostRatio",
    "safety.maxTakerFeeRatio",
    "safety.marketSlippageReserveBps",
    "safety.dataQualityStaleAfter",
    "safety.dataQualityCappedProbability",
    "decision.falsificationFreshnessWindow",
    "decision.restingEntryOrderTtl",
    "runner.maxToolCallsPerRun",
    "runner.maxActToolCallsPerRun",
    "runner.perRunTimeout",
    "runner.maxInvocationsPerHour",
    "runner.maxInvocationsPerDay",
    "daemon.pollInterval",
    "daemon.flatHeartbeatInterval",
    "daemon.holdingCheckInterval",
    "killCriterion.minClosedTrades",
    "killCriterion.minProfitFactor",
    "gmoPublic.restPerSecond",
    "gmoPublic.restBurst",
)

/**
 * runtime config candidate を typed config まで構築して検証した結果。
 *
 * @param validation WebUI に返せる validation 結果
 * @param tradingConfig 検証に成功した typed config
 * @param typedEnvironment typed config 構築に使う env map
 * @param catalogEnvironment catalog 表示に使う env map
 */
data class RuntimeConfigValidatedCandidate(
    val validation: RuntimeConfigValidationResult,
    val tradingConfig: TradingBotConfig?,
    val typedEnvironment: Map<String, String>,
    val catalogEnvironment: Map<String, String>,
)

/**
 * runtime config candidate を現在の catalog と typed config validation で検証する。
 */
object RuntimeConfigCandidateValidator {

    /**
     * 保存候補の full runtime config map を検証する。
     */
    fun validate(
        values: Map<String, String>,
        environment: Map<String, String> = System.getenv(),
    ): RuntimeConfigValidatedCandidate {
        val runtimeItems = RuntimeConfigCatalog.runtimeItems()
        val runtimeItemsByKey = runtimeItems.associateBy { item -> item.key }
        val expectedKeys = runtimeItemsByKey.keys
        val candidateKeys = values.keys
        val errors = buildList {
            addKeySetErrors(
                unknownKeys = candidateKeys - expectedKeys,
                missingKeys = expectedKeys - candidateKeys,
            )
        }.toMutableList()
        val canonicalValues = buildMap {
            values.forEach { (key, value) ->
                val item = runtimeItemsByKey[key] ?: return@forEach
                val validationResult = validateRuntimeValue(item, value)
                val canonicalValue = validationResult.getOrNull()

                if (canonicalValue != null) {
                    put(key, canonicalValue)
                } else {
                    val failure = validationResult.exceptionOrNull() as RuntimeConfigValueValidationFailure
                    errors += failure.error
                }
            }
        }

        if (errors.isNotEmpty()) {
            return RuntimeConfigValidatedCandidate(
                validation = RuntimeConfigValidationResult(valid = false, errors = errors),
                tradingConfig = null,
                typedEnvironment = emptyMap(),
                catalogEnvironment = emptyMap(),
            )
        }

        val runtimeTypedEnvironment = canonicalValues.mapKeys { (key, _) ->
            requireNotNull(runtimeItemsByKey[key]).legacyEnvName
        }
        val runtimeCatalogEnvironment = canonicalValues.mapKeys { (key, _) ->
            requireNotNull(runtimeItemsByKey[key]).legacyEnvName
        }
        val deploymentEnvironment = environment
            .filterKeys { key -> key !in RuntimeConfigCatalog.runtimeLegacyEnvNames() }
        val typedEnvironment = deploymentEnvironment + runtimeTypedEnvironment
        val catalogEnvironment = deploymentEnvironment + runtimeCatalogEnvironment
        val typedConstraintErrors = validateTypedConfigConstraints(
            values = canonicalValues,
            runtimeItemsByKey = runtimeItemsByKey,
        )

        if (typedConstraintErrors.isNotEmpty()) {
            return RuntimeConfigValidatedCandidate(
                validation = RuntimeConfigValidationResult(valid = false, errors = typedConstraintErrors),
                tradingConfig = null,
                typedEnvironment = typedEnvironment,
                catalogEnvironment = catalogEnvironment,
            )
        }

        val tradingConfigResult = runCatching {
            TradingBotConfig.fromEnvironment(typedEnvironment)
        }

        return tradingConfigResult.fold(
            onSuccess = { tradingConfig ->
                RuntimeConfigValidatedCandidate(
                    validation = RuntimeConfigValidationResult(valid = true),
                    tradingConfig = tradingConfig,
                    typedEnvironment = typedEnvironment,
                    catalogEnvironment = catalogEnvironment,
                )
            },
            onFailure = { error ->
                RuntimeConfigValidatedCandidate(
                    validation = RuntimeConfigValidationResult(
                        valid = false,
                        errors = listOf(
                            RuntimeConfigValidationError(
                                code = "runtimeConfig.validation.typedConfig",
                                params = mapOf("reason" to error::class.simpleName.orEmpty()),
                            ),
                        ),
                    ),
                    tradingConfig = null,
                    typedEnvironment = typedEnvironment,
                    catalogEnvironment = catalogEnvironment,
                )
            },
        )
    }
}

private fun validateTypedConfigConstraints(
    values: Map<String, String>,
    runtimeItemsByKey: Map<String, RuntimeConfigItem>,
): List<RuntimeConfigValidationError> {
    return buildList {
        requireDecimalGreaterThan(values, "paper.initialCashJpy", BigDecimal.ZERO)
        requireDecimalGreaterThanOrEqual(values, "paper.marketSlippageBps", BigDecimal.ZERO)
        requireDecimalGreaterThanOrEqualDefault(values, runtimeItemsByKey, "paper.fallbackMakerFeeRate")
        requireDecimalGreaterThanOrEqualDefault(values, runtimeItemsByKey, "paper.fallbackTakerFeeRate")
        requireDecimalGreaterThanOrEqualDefault(values, runtimeItemsByKey, "paper.fallbackSpreadBps")
        requireDecimalGreaterThanAndLessThanOrEqualDefault(values, runtimeItemsByKey, "safety.maxRiskPerTradeRatio")
        requireDecimalGreaterThanOrEqualDefaultAndLessThan(values, runtimeItemsByKey, "safety.maxDrawdownRatio", BigDecimal.ZERO)
        requireDecimalGreaterThanAndLessThanOrEqualDefault(values, runtimeItemsByKey, "safety.maxTotalExposureRatio")
        requireDecimalGreaterThanOrEqualDefault(values, runtimeItemsByKey, "safety.minExpectedValueR")
        requireDecimalGreaterThanOrEqualDefault(values, runtimeItemsByKey, "safety.minExpectedMoveToCostRatio")
        requireDecimalBetweenInclusive(
            values = values,
            key = "safety.maxTakerFeeRatio",
            min = BigDecimal.ZERO,
            max = defaultDecimal(runtimeItemsByKey, "safety.maxTakerFeeRatio"),
        )
        requireDecimalGreaterThanOrEqualDefault(values, runtimeItemsByKey, "safety.marketSlippageReserveBps")
        requireLongBetweenInclusive(
            values = values,
            key = "safety.dataQualityStaleAfter",
            min = 1,
            max = defaultLong(runtimeItemsByKey, "safety.dataQualityStaleAfter"),
        )
        requireDecimalBetweenInclusive(
            values = values,
            key = "safety.dataQualityCappedProbability",
            min = BigDecimal.ZERO,
            max = defaultDecimal(runtimeItemsByKey, "safety.dataQualityCappedProbability"),
        )
        requireLongBetweenInclusive(
            values = values,
            key = "decision.falsificationFreshnessWindow",
            min = 1,
            max = defaultLong(runtimeItemsByKey, "decision.falsificationFreshnessWindow"),
        )
        requireLongBetweenInclusive(
            values = values,
            key = "decision.restingEntryOrderTtl",
            min = 1,
            max = defaultLong(runtimeItemsByKey, "decision.restingEntryOrderTtl"),
        )
        requireIntBetweenInclusive(values, "runner.maxToolCallsPerRun", 1, defaultInt(runtimeItemsByKey, "runner.maxToolCallsPerRun"))
        requireIntBetweenInclusive(values, "runner.maxActToolCallsPerRun", 1, defaultInt(runtimeItemsByKey, "runner.maxActToolCallsPerRun"))
        requireLongBetweenInclusive(values, "runner.perRunTimeout", 1, defaultLong(runtimeItemsByKey, "runner.perRunTimeout"))
        requireIntBetweenInclusive(values, "runner.maxInvocationsPerHour", 1, defaultInt(runtimeItemsByKey, "runner.maxInvocationsPerHour"))
        requireIntBetweenInclusive(values, "runner.maxInvocationsPerDay", 1, defaultInt(runtimeItemsByKey, "runner.maxInvocationsPerDay"))
        requireIntLessThanOrEqualKey(values, "runner.maxActToolCallsPerRun", "runner.maxToolCallsPerRun")
        requireLongGreaterThanOrEqualDefault(values, runtimeItemsByKey, "daemon.pollInterval")
        requireLongGreaterThanOrEqualDefault(values, runtimeItemsByKey, "daemon.flatHeartbeatInterval")
        requireLongGreaterThanOrEqualDefault(values, runtimeItemsByKey, "daemon.holdingCheckInterval")
        requireDecimalGreaterThan(values, "daemon.priceMoveThresholdRatio", BigDecimal.ZERO)
        requireLongGreaterThanOrEqualKey(values, "daemon.priceMoveWindow", "daemon.pollInterval")
        requireLongGreaterThanOrEqualKey(values, "daemon.priceMoveCooldown", "daemon.pollInterval")
        requireLongGreaterThanOrEqualKey(values, "daemon.entryFillCooldown", "daemon.pollInterval")
        requireDecimalGreaterThan(values, "daemon.stopProximityRemainingRThreshold", BigDecimal.ZERO)
        requireLongGreaterThanOrEqualKey(values, "daemon.stopProximityCooldown", "daemon.pollInterval")
        requireLongGreaterThanOrEqual(
            values = values,
            key = "obsidian.writeInterval",
            min = MIN_OBSIDIAN_WRITE_INTERVAL.seconds,
        )
        requireLongGreaterThanOrEqual(
            values = values,
            key = "reflection.minInterval",
            min = MIN_REFLECTION_MIN_INTERVAL.seconds,
        )
        requireIntGreaterThan(values, "reflection.queryLimit", 0)
        requireIntGreaterThan(values, "reflection.calibrationLookbackDays", 0)
        requireIntGreaterThan(values, "reflection.recentDecisionLimit", 0)
        requireIntGreaterThan(values, "reflection.sampleWarningTradeCount", 0)
        requireStringOneOf(
            values = values,
            key = "reflection.promptCandidateProvider",
            allowedValues = LlmProvider.entries.map { provider -> provider.name }.toSet(),
        )
        requireLongBetweenInclusive(
            values = values,
            key = "reflection.promptCandidateTimeout",
            min = 1,
            max = MAX_REFLECTION_LLM_TIMEOUT.seconds,
        )
        requireIntGreaterThan(values, "reflection.promptCandidateMaxAttempts", 0)
        requireIntBetweenInclusive(
            values = values,
            key = "killCriterion.minClosedTrades",
            min = 1,
            max = defaultInt(runtimeItemsByKey, "killCriterion.minClosedTrades"),
        )
        requireDecimalGreaterThanOrEqualDefault(values, runtimeItemsByKey, "killCriterion.minProfitFactor")
        requireIntBetweenInclusive(values, "gmoPublic.restPerSecond", 1, defaultInt(runtimeItemsByKey, "gmoPublic.restPerSecond"))
        requireIntBetweenInclusive(values, "gmoPublic.restBurst", 1, defaultInt(runtimeItemsByKey, "gmoPublic.restBurst"))
        requireIntGreaterThan(values, "gmoPublic.retryMaxAttempts", 0)
        requireLongGreaterThan(values, "gmoPublic.retryInitialBackoff", 0)
        requireLongGreaterThan(values, "gmoPublic.retryMaxBackoff", 0)
        requireLongGreaterThan(values, "gmoPublic.retryBackoffMultiplier", 1)
        requireLongGreaterThan(values, "gmoPublic.connectTimeout", 0)
        requireLongGreaterThan(values, "gmoPublic.requestTimeout", 0)
        requireLongGreaterThan(values, "gmoPublic.symbolRulesCacheTtl", 0)
    }
}

private fun MutableList<RuntimeConfigValidationError>.requireDecimalGreaterThan(
    values: Map<String, String>,
    key: String,
    min: BigDecimal,
) {
    val value = values.getValue(key).toBigDecimal()

    if (value <= min) {
        addTypedConfigError(key, "runtimeConfig.validation.typedGreaterThan", mapOf("min" to min.toPlainString()))
    }
}

private fun MutableList<RuntimeConfigValidationError>.requireDecimalGreaterThanOrEqual(
    values: Map<String, String>,
    key: String,
    min: BigDecimal,
) {
    val value = values.getValue(key).toBigDecimal()

    if (value < min) {
        addTypedConfigError(key, "runtimeConfig.validation.typedGreaterThanOrEqual", mapOf("min" to min.toPlainString()))
    }
}

private fun MutableList<RuntimeConfigValidationError>.requireDecimalGreaterThanOrEqualDefault(
    values: Map<String, String>,
    runtimeItemsByKey: Map<String, RuntimeConfigItem>,
    key: String,
) {
    requireDecimalGreaterThanOrEqual(values, key, defaultDecimal(runtimeItemsByKey, key))
}

private fun MutableList<RuntimeConfigValidationError>.requireDecimalGreaterThanAndLessThanOrEqualDefault(
    values: Map<String, String>,
    runtimeItemsByKey: Map<String, RuntimeConfigItem>,
    key: String,
) {
    val min = BigDecimal.ZERO
    val max = defaultDecimal(runtimeItemsByKey, key)
    val value = values.getValue(key).toBigDecimal()

    if (value <= min || value > max) {
        addTypedConfigError(
            key = key,
            code = "runtimeConfig.validation.typedBetweenExclusiveMinInclusiveMax",
            params = mapOf("min" to min.toPlainString(), "max" to max.toPlainString()),
        )
    }
}

private fun MutableList<RuntimeConfigValidationError>.requireDecimalGreaterThanOrEqualDefaultAndLessThan(
    values: Map<String, String>,
    runtimeItemsByKey: Map<String, RuntimeConfigItem>,
    key: String,
    max: BigDecimal,
) {
    val min = defaultDecimal(runtimeItemsByKey, key)
    val value = values.getValue(key).toBigDecimal()

    if (value < min || value >= max) {
        addTypedConfigError(
            key = key,
            code = "runtimeConfig.validation.typedBetweenInclusiveMinExclusiveMax",
            params = mapOf("min" to min.toPlainString(), "max" to max.toPlainString()),
        )
    }
}

private fun MutableList<RuntimeConfigValidationError>.requireDecimalBetweenInclusive(
    values: Map<String, String>,
    key: String,
    min: BigDecimal,
    max: BigDecimal,
) {
    val value = values.getValue(key).toBigDecimal()

    if (value < min || value > max) {
        addTypedConfigError(
            key = key,
            code = "runtimeConfig.validation.typedBetweenInclusive",
            params = mapOf("min" to min.toPlainString(), "max" to max.toPlainString()),
        )
    }
}

private fun MutableList<RuntimeConfigValidationError>.requireIntBetweenInclusive(
    values: Map<String, String>,
    key: String,
    min: Int,
    max: Int,
) {
    val value = values.getValue(key).toInt()

    if (value < min || value > max) {
        addTypedConfigError(
            key = key,
            code = "runtimeConfig.validation.typedBetweenInclusive",
            params = mapOf("min" to min.toString(), "max" to max.toString()),
        )
    }
}

private fun MutableList<RuntimeConfigValidationError>.requireLongBetweenInclusive(
    values: Map<String, String>,
    key: String,
    min: Long,
    max: Long,
) {
    val value = values.getValue(key).toLong()

    if (value < min || value > max) {
        addTypedConfigError(
            key = key,
            code = "runtimeConfig.validation.typedBetweenInclusive",
            params = mapOf("min" to min.toString(), "max" to max.toString()),
        )
    }
}

private fun MutableList<RuntimeConfigValidationError>.requireIntGreaterThan(
    values: Map<String, String>,
    key: String,
    min: Int,
) {
    val value = values.getValue(key).toInt()

    if (value <= min) {
        addTypedConfigError(key, "runtimeConfig.validation.typedGreaterThan", mapOf("min" to min.toString()))
    }
}

private fun MutableList<RuntimeConfigValidationError>.requireLongGreaterThan(
    values: Map<String, String>,
    key: String,
    min: Long,
) {
    val value = values.getValue(key).toLong()

    if (value <= min) {
        addTypedConfigError(key, "runtimeConfig.validation.typedGreaterThan", mapOf("min" to min.toString()))
    }
}

private fun MutableList<RuntimeConfigValidationError>.requireLongGreaterThanOrEqualDefault(
    values: Map<String, String>,
    runtimeItemsByKey: Map<String, RuntimeConfigItem>,
    key: String,
) {
    requireLongGreaterThanOrEqual(
        values = values,
        key = key,
        min = defaultLong(runtimeItemsByKey, key),
    )
}

private fun MutableList<RuntimeConfigValidationError>.requireLongGreaterThanOrEqual(
    values: Map<String, String>,
    key: String,
    min: Long,
) {
    val value = values.getValue(key).toLong()

    if (value < min) {
        addTypedConfigError(key, "runtimeConfig.validation.typedGreaterThanOrEqual", mapOf("min" to min.toString()))
    }
}

private fun MutableList<RuntimeConfigValidationError>.requireIntLessThanOrEqualKey(
    values: Map<String, String>,
    key: String,
    maxKey: String,
) {
    val value = values.getValue(key).toInt()
    val max = values.getValue(maxKey).toInt()

    if (value > max) {
        addTypedConfigError(
            key = key,
            code = "runtimeConfig.validation.typedLessThanOrEqualKey",
            params = mapOf("maxKey" to maxKey, "max" to max.toString()),
        )
    }
}

private fun MutableList<RuntimeConfigValidationError>.requireLongGreaterThanOrEqualKey(
    values: Map<String, String>,
    key: String,
    minKey: String,
) {
    val value = values.getValue(key).toLong()
    val min = values.getValue(minKey).toLong()

    if (value < min) {
        addTypedConfigError(
            key = key,
            code = "runtimeConfig.validation.typedGreaterThanOrEqualKey",
            params = mapOf("minKey" to minKey, "min" to min.toString()),
        )
    }
}

private fun MutableList<RuntimeConfigValidationError>.requireStringOneOf(
    values: Map<String, String>,
    key: String,
    allowedValues: Set<String>,
) {
    val value = values.getValue(key)

    if (value !in allowedValues) {
        addTypedConfigError(
            key = key,
            code = "runtimeConfig.validation.typedOneOf",
            params = mapOf("values" to allowedValues.sorted().joinToString(", ")),
        )
    }
}

private fun MutableList<RuntimeConfigValidationError>.addTypedConfigError(
    key: String,
    code: String,
    params: Map<String, String>,
) {
    add(RuntimeConfigValidationError(code = code, key = key, params = params))
}

private fun defaultDecimal(runtimeItemsByKey: Map<String, RuntimeConfigItem>, key: String): BigDecimal {
    return defaultValue(runtimeItemsByKey, key).toBigDecimal()
}

private fun defaultInt(runtimeItemsByKey: Map<String, RuntimeConfigItem>, key: String): Int {
    return defaultValue(runtimeItemsByKey, key).toInt()
}

private fun defaultLong(runtimeItemsByKey: Map<String, RuntimeConfigItem>, key: String): Long {
    return defaultValue(runtimeItemsByKey, key).toLong()
}

private fun defaultValue(runtimeItemsByKey: Map<String, RuntimeConfigItem>, key: String): String {
    return requireNotNull(runtimeItemsByKey.getValue(key).defaultValue) {
        "Runtime config default value must not be null: $key"
    }
}

/**
 * DB active runtime config を catalog default と合成して typed config を構築する。
 *
 * @param activeSource active snapshot の読み取り境界
 */
class RuntimeConfigResolver(
    private val activeSource: ActiveRuntimeConfigSource,
) {
    /**
     * active DB runtime config を typed config へ解決する。
     */
    fun resolve(environment: Map<String, String> = System.getenv()): Result<RuntimeConfigResolution> {
        return runCatching {
            val activeSnapshot = activeSource.activeSnapshot().getOrThrow()
            val validation = RuntimeConfigCandidateValidator.validate(
                values = activeSnapshot.values,
                environment = environment,
            )
            validation.validation.requireValid()

            RuntimeConfigResolution(
                tradingConfig = requireNotNull(validation.tradingConfig),
                auditSnapshot = RuntimeConfigAuditSnapshot(
                    versionId = activeSnapshot.versionId,
                    hash = activeSnapshot.hash,
                ),
                typedEnvironment = validation.typedEnvironment,
                catalogEnvironment = validation.catalogEnvironment,
            )
        }
    }
}

internal fun RuntimeConfigValidationResult.requireValid() {
    if (!valid) {
        throw RuntimeConfigValidationRejectedException(this)
    }
}

private fun MutableList<RuntimeConfigValidationError>.addKeySetErrors(
    unknownKeys: Set<String>,
    missingKeys: Set<String>,
) {
    if (unknownKeys.isNotEmpty()) {
        add(
            RuntimeConfigValidationError(
                code = "runtimeConfig.validation.unknownKeys",
                params = mapOf("keys" to unknownKeys.sorted().joinToString(", ")),
            ),
        )
    }

    if (missingKeys.isNotEmpty()) {
        add(
            RuntimeConfigValidationError(
                code = "runtimeConfig.validation.missingKeys",
                params = mapOf("keys" to missingKeys.sorted().joinToString(", ")),
            ),
        )
    }
}

private fun validateRuntimeValue(item: RuntimeConfigItem, value: String): Result<String> {
    return try {
        val trimmedValue = value.trim()
        val canonicalValue = canonicalizeRuntimeConfigValue(item, trimmedValue)

        if (canonicalValue.isEmpty() && !item.blankAllowed) {
            throw RuntimeConfigValidationException("runtimeConfig.validation.blank")
        }

        when (item.valueType) {
            RuntimeConfigValueType.BOOLEAN -> validateBooleanValue(canonicalValue)
            RuntimeConfigValueType.INT -> validateIntValue(canonicalValue)
            RuntimeConfigValueType.DURATION_SECONDS -> validateLongValue(
                code = "runtimeConfig.validation.invalidDurationSeconds",
                value = canonicalValue,
            )
            RuntimeConfigValueType.DECIMAL_STRING -> validateDecimalValue(canonicalValue)
            RuntimeConfigValueType.STRUCTURED_JSON_LIST -> validateJsonListValue(canonicalValue)
            RuntimeConfigValueType.ENUM,
            RuntimeConfigValueType.STRING,
            RuntimeConfigValueType.URL,
            RuntimeConfigValueType.STRING_LIST,
            RuntimeConfigValueType.SECRET_STATUS,
            -> Unit
        }

        Result.success(canonicalValue)
    } catch (throwable: Exception) {
        Result.failure(
            RuntimeConfigValueValidationFailure(
                RuntimeConfigValidationError(
                    code = when (throwable) {
                        is RuntimeConfigValidationException -> throwable.code
                        else -> "runtimeConfig.validation.invalidValue"
                    },
                    key = item.key,
                    params = mapOf(
                        "valueType" to item.valueType.name,
                        "message" to (throwable.message ?: throwable::class.simpleName.orEmpty()),
                    ),
                ),
            ),
        )
    }
}

/**
 * runtime config 値を value type ごとの保存形式へ正規化する。
 */
internal fun canonicalizeRuntimeConfigValue(item: RuntimeConfigItem, value: String): String {
    return when (item.valueType) {
        RuntimeConfigValueType.ENUM -> value.uppercase()
        else -> value
    }
}

private fun validateBooleanValue(value: String) {
    if (value.toBooleanStrictOrNull() == null) {
        throw RuntimeConfigValidationException("runtimeConfig.validation.invalidBoolean")
    }
}

private fun validateIntValue(value: String) {
    value.toIntOrNull() ?: throw RuntimeConfigValidationException("runtimeConfig.validation.invalidInt")
}

private fun validateLongValue(code: String, value: String) {
    value.toLongOrNull() ?: throw RuntimeConfigValidationException(code)
}

private fun validateDecimalValue(value: String) {
    runCatching { BigDecimal(value) }.getOrElse {
        throw RuntimeConfigValidationException("runtimeConfig.validation.invalidDecimal")
    }
}

private fun validateJsonListValue(value: String) {
    val element = runCatching { Json.parseToJsonElement(value) }.getOrElse {
        throw RuntimeConfigValidationException("runtimeConfig.validation.invalidJsonList")
    }

    if (element !is JsonArray) {
        throw RuntimeConfigValidationException("runtimeConfig.validation.invalidJsonList")
    }

    element.forEach { item ->
        val objectValue = item as? JsonObject
            ?: throw RuntimeConfigValidationException("runtimeConfig.validation.invalidJsonList")
        requireJsonString(objectValue, "eventId")
        requireJsonString(objectValue, "eventName")
        requireJsonString(objectValue, "eventAt")
        requireJsonLong(objectValue, "blackoutBeforeSeconds")
        requireJsonLong(objectValue, "blackoutAfterSeconds")
    }
}

private fun requireJsonString(value: JsonObject, key: String) {
    val primitive = value[key] as? JsonPrimitive
        ?: throw RuntimeConfigValidationException("runtimeConfig.validation.invalidJsonList")

    primitive.content
}

private fun requireJsonLong(value: JsonObject, key: String) {
    val primitive = value[key]?.jsonPrimitive
        ?: throw RuntimeConfigValidationException("runtimeConfig.validation.invalidJsonList")

    primitive.content.toLongOrNull()
        ?: throw RuntimeConfigValidationException("runtimeConfig.validation.invalidJsonList")
}

private class RuntimeConfigValidationException(val code: String) : IllegalArgumentException(code)

private class RuntimeConfigValueValidationFailure(
    val error: RuntimeConfigValidationError,
) : IllegalArgumentException(error.code)

/**
 * runtime config values の canonical content hash を算出する。
 */
fun calculateRuntimeConfigHash(values: Map<String, String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("runtime-config-v1\n".toByteArray(StandardCharsets.UTF_8))

    values.toSortedMap().forEach { (key, value) ->
        digest.update(key.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(value.toByteArray(StandardCharsets.UTF_8))
        digest.update('\n'.code.toByte())
    }

    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
