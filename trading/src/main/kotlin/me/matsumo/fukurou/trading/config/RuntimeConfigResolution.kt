@file:Suppress("LongMethod", "TooManyFunctions")

package me.matsumo.fukurou.trading.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
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
 * @param versionId runtime_config_versions の ID
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
    val versionId: String,
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
     * 保存済み inactive version へ rollback する。
     */
    fun rollbackToVersion(versionId: String): Result<RuntimeConfigActivationResult>
}

/**
 * runtime config の version 一覧で既定取得する件数。
 */
const val DEFAULT_RUNTIME_CONFIG_VERSION_LIMIT = 20

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
                                params = mapOf("message" to (error.message ?: error::class.simpleName.orEmpty())),
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

        if (trimmedValue.isEmpty()) {
            throw RuntimeConfigValidationException("runtimeConfig.validation.blank")
        }

        when (item.valueType) {
            RuntimeConfigValueType.BOOLEAN -> validateBooleanValue(trimmedValue)
            RuntimeConfigValueType.INT -> validateIntValue(trimmedValue)
            RuntimeConfigValueType.DURATION_SECONDS -> validateLongValue(
                code = "runtimeConfig.validation.invalidDurationSeconds",
                value = trimmedValue,
            )
            RuntimeConfigValueType.DECIMAL_STRING -> validateDecimalValue(trimmedValue)
            RuntimeConfigValueType.STRUCTURED_JSON_LIST -> validateJsonListValue(trimmedValue)
            RuntimeConfigValueType.ENUM,
            RuntimeConfigValueType.STRING,
            RuntimeConfigValueType.URL,
            RuntimeConfigValueType.STRING_LIST,
            RuntimeConfigValueType.SECRET_STATUS,
            -> Unit
        }

        Result.success(trimmedValue)
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
