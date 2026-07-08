package me.matsumo.fukurou.trading.config

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
            val runtimeItems = RuntimeConfigCatalog.runtimeItems()
            val runtimeItemsByKey = runtimeItems.associateBy { item -> item.key }
            val runtimeKeys = runtimeItemsByKey.keys
            val activeKeys = activeSnapshot.values.keys
            val unknownKeys = activeKeys - runtimeKeys
            val missingKeys = runtimeKeys - activeKeys

            require(unknownKeys.isEmpty()) {
                "Active runtime config contains catalog-incompatible keys: ${unknownKeys.sorted()}"
            }
            require(missingKeys.isEmpty()) {
                "Active runtime config is missing catalog keys: ${missingKeys.sorted()}"
            }

            val defaultValues = RuntimeConfigCatalog.runtimeDefaultValues()
            val activeValues = defaultValues + activeSnapshot.values
            val runtimeTypedEnvironment = activeValues.mapValues { (key, value) ->
                validateRuntimeValue(
                    item = requireNotNull(runtimeItemsByKey[key]),
                    value = value,
                )
            }.mapKeys { (key, _) ->
                requireNotNull(runtimeItemsByKey[key]).legacyEnvName
            }
            val runtimeCatalogEnvironment = activeValues.mapKeys { (key, _) ->
                requireNotNull(runtimeItemsByKey[key]).legacyEnvName
            }
            val deploymentEnvironment = environment
                .filterKeys { key -> key !in RuntimeConfigCatalog.runtimeLegacyEnvNames() }
            val typedEnvironment = deploymentEnvironment + runtimeTypedEnvironment
            val catalogEnvironment = deploymentEnvironment + runtimeCatalogEnvironment
            val tradingConfig = TradingBotConfig.fromEnvironment(typedEnvironment)

            RuntimeConfigResolution(
                tradingConfig = tradingConfig,
                auditSnapshot = RuntimeConfigAuditSnapshot(
                    versionId = activeSnapshot.versionId,
                    hash = activeSnapshot.hash,
                ),
                typedEnvironment = typedEnvironment,
                catalogEnvironment = catalogEnvironment,
            )
        }
    }

    private fun validateRuntimeValue(item: RuntimeConfigItem, value: String): String {
        val trimmedValue = value.trim()

        require(trimmedValue.isNotEmpty()) {
            "Runtime config value must not be blank: ${item.key}"
        }

        when (item.valueType) {
            RuntimeConfigValueType.BOOLEAN -> require(trimmedValue.toBooleanStrictOrNull() != null) {
                "Runtime config value must be boolean: ${item.key}"
            }
            RuntimeConfigValueType.INT -> trimmedValue.toInt()
            RuntimeConfigValueType.DURATION_SECONDS -> trimmedValue.toLong()
            RuntimeConfigValueType.DECIMAL_STRING -> BigDecimal(trimmedValue)
            RuntimeConfigValueType.STRUCTURED_JSON_LIST -> require(trimmedValue.startsWith("[")) {
                "Runtime config structured list must be JSON array: ${item.key}"
            }
            RuntimeConfigValueType.ENUM,
            RuntimeConfigValueType.STRING,
            RuntimeConfigValueType.URL,
            RuntimeConfigValueType.STRING_LIST,
            RuntimeConfigValueType.SECRET_STATUS,
            -> Unit
        }

        return trimmedValue
    }
}

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
