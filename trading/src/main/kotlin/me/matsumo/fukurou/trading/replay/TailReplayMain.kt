package me.matsumo.fukurou.trading.replay

import me.matsumo.fukurou.trading.runtime.TradingDatabaseConfig
import java.math.BigDecimal
import kotlin.system.exitProcess

/** tail 事実シートの実行境界。対象期間・逆行の閾値倍数・対象件数上限・statement timeout を持つ。 */
data class TailReplayBounds(
    val window: ReplayWindow,
    val thresholdRMultiple: BigDecimal,
    val maxTargets: Int,
    val statementTimeoutSeconds: Int,
) {
    init {
        require(thresholdRMultiple > BigDecimal.ZERO) { "thresholdRMultiple must be positive." }
        require(maxTargets > 0) { "maxTargets must be positive." }
        require(statementTimeoutSeconds > 0) { "statementTimeoutSeconds must be positive." }
    }
}

/** tail 事実シート replay の CLI 設定。環境変数から組み立てる。 */
data class TailReplayCliConfig(
    val databaseConfig: TradingDatabaseConfig,
    val bounds: TailReplayBounds,
) {
    companion object {
        /** 対象期間 (epoch millis) の下端。position の `closed_at` に対する包含境界。 */
        const val WINDOW_FROM_MS_ENV = "FUKUROU_TAIL_REPLAY_FROM_MS"

        /** 対象期間 (epoch millis) の上端 (排他)。position の `closed_at` に対する排他境界。 */
        const val WINDOW_TO_MS_ENV = "FUKUROU_TAIL_REPLAY_TO_EXCLUSIVE_MS"

        /** 壊滅的 tail を数える初期リスク R の倍数。 */
        const val THRESHOLD_R_MULTIPLE_ENV = "FUKUROU_TAIL_REPLAY_THRESHOLD_R"

        /** 対象件数上限。 */
        const val MAX_TARGETS_ENV = "FUKUROU_TAIL_REPLAY_MAX_TARGETS"

        /** statement timeout 秒。 */
        const val STATEMENT_TIMEOUT_SECONDS_ENV = "FUKUROU_TAIL_REPLAY_STATEMENT_TIMEOUT_SECONDS"

        private const val DEFAULT_MAX_TARGETS = 5000
        private const val DEFAULT_STATEMENT_TIMEOUT_SECONDS = 30

        /**
         * 壊滅的 tail の既定閾値。損切り必須のため逆行が初期リスク 1R を超えるのは stop 超過を意味し、
         * 2R は計画リスクの倍に到達した壊滅的 tail を指す保守的な既定である。実データ分布に応じて env で上書きする。
         */
        val DEFAULT_THRESHOLD_R_MULTIPLE: BigDecimal = BigDecimal("2.0")

        /** 環境変数から CLI 設定を組み立てる。必須値が欠ける場合は例外にする。 */
        fun fromEnvironment(environment: Map<String, String>): TailReplayCliConfig {
            val databaseConfig = requireNotNull(TradingDatabaseConfig.fromEnvironment(environment)) {
                "DB_URL, DB_USER, and DB_PASSWORD are required for the tail replay."
            }
            val window = ReplayWindow(
                fromMs = requiredLong(environment, WINDOW_FROM_MS_ENV),
                toExclusiveMs = requiredLong(environment, WINDOW_TO_MS_ENV),
            )
            val threshold = environment[THRESHOLD_R_MULTIPLE_ENV]
                ?.let { raw -> requireNotNull(raw.toBigDecimalOrNull()) { "$THRESHOLD_R_MULTIPLE_ENV must be a decimal." } }
                ?: DEFAULT_THRESHOLD_R_MULTIPLE

            return TailReplayCliConfig(
                databaseConfig = databaseConfig,
                bounds = TailReplayBounds(
                    window = window,
                    thresholdRMultiple = threshold,
                    maxTargets = environment[MAX_TARGETS_ENV]?.toInt() ?: DEFAULT_MAX_TARGETS,
                    statementTimeoutSeconds = environment[STATEMENT_TIMEOUT_SECONDS_ENV]?.toInt()
                        ?: DEFAULT_STATEMENT_TIMEOUT_SECONDS,
                ),
            )
        }

        private fun requiredLong(environment: Map<String, String>, key: String): Long {
            val value = requireNotNull(environment[key]) { "$key is required for the tail replay." }

            return requireNotNull(value.toLongOrNull()) { "$key must be an epoch millis value." }
        }
    }
}

/** tail 事実シート replay の main entry point。JSON Lines を stdout へ書く。 */
fun main() {
    val exitCode = runTailReplayMain(
        environment = System.getenv(),
        stdout = { line -> println(line) },
        stderr = { line -> System.err.println(line) },
    )

    if (exitCode != TAIL_REPLAY_SUCCESS_EXIT_CODE) {
        exitProcess(exitCode)
    }
}

/** tail 事実シート replay を実行し、process exit code を返す。 */
internal fun runTailReplayMain(
    environment: Map<String, String>,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int {
    return runCatching {
        val config = TailReplayCliConfig.fromEnvironment(environment)
        ReplayReadOnlyRuntime.fromDatabaseConfig(config.databaseConfig).use { runtime ->
            TailFactSheet(runtime).run(config.bounds, stdout)
        }
    }.fold(
        onSuccess = { TAIL_REPLAY_SUCCESS_EXIT_CODE },
        onFailure = { failure ->
            stderr("tail-replay failed: ${failure.message}")
            TAIL_REPLAY_FAILURE_EXIT_CODE
        },
    )
}

private const val TAIL_REPLAY_SUCCESS_EXIT_CODE = 0
private const val TAIL_REPLAY_FAILURE_EXIT_CODE = 1
