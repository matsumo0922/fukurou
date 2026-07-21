package me.matsumo.fukurou.trading.replay

import me.matsumo.fukurou.trading.runtime.TradingDatabaseConfig
import kotlin.system.exitProcess

/** TTL 短縮感度 replay の CLI 設定。環境変数から組み立てる。 */
data class TtlReplayCliConfig(
    val databaseConfig: TradingDatabaseConfig,
    val bounds: ReplayBounds,
) {
    companion object {
        /** 対象期間 (epoch millis) の下端。 */
        const val WINDOW_FROM_MS_ENV = "FUKUROU_TTL_REPLAY_FROM_MS"

        /** 対象期間 (epoch millis) の上端 (排他)。 */
        const val WINDOW_TO_MS_ENV = "FUKUROU_TTL_REPLAY_TO_EXCLUSIVE_MS"

        /** 短縮 TTL 候補秒のカンマ区切り。 */
        const val CANDIDATE_SECONDS_ENV = "FUKUROU_TTL_REPLAY_CANDIDATE_SECONDS"

        /** 対象件数上限。 */
        const val MAX_TARGETS_ENV = "FUKUROU_TTL_REPLAY_MAX_TARGETS"

        /** statement timeout 秒。 */
        const val STATEMENT_TIMEOUT_SECONDS_ENV = "FUKUROU_TTL_REPLAY_STATEMENT_TIMEOUT_SECONDS"

        private const val CURRENT_TTL_CAP_SECONDS = 1800L
        private const val DEFAULT_MAX_TARGETS = 5000
        private const val DEFAULT_STATEMENT_TIMEOUT_SECONDS = 30

        /** 現行 30 分を上限に含めた既定の短縮 TTL 探索格子。実データ分布に応じて env で上書きする。 */
        val DEFAULT_CANDIDATE_SECONDS: List<Long> =
            listOf(1800L, 1500L, 1200L, 900L, 600L, 300L, 180L, 120L, 60L)

        /** 環境変数から CLI 設定を組み立てる。必須値が欠ける場合は例外にする。 */
        fun fromEnvironment(environment: Map<String, String>): TtlReplayCliConfig {
            val databaseConfig = requireNotNull(TradingDatabaseConfig.fromEnvironment(environment)) {
                "DB_URL, DB_USER, and DB_PASSWORD are required for the TTL replay."
            }
            val window = ReplayWindow(
                fromMs = requiredLong(environment, WINDOW_FROM_MS_ENV),
                toExclusiveMs = requiredLong(environment, WINDOW_TO_MS_ENV),
            )
            val candidates = environment[CANDIDATE_SECONDS_ENV]
                ?.let(::parseCandidateSeconds)
                ?: DEFAULT_CANDIDATE_SECONDS

            return TtlReplayCliConfig(
                databaseConfig = databaseConfig,
                bounds = ReplayBounds(
                    window = window,
                    candidateTtlSeconds = candidates.filter { seconds -> seconds <= CURRENT_TTL_CAP_SECONDS },
                    maxTargets = environment[MAX_TARGETS_ENV]?.toInt() ?: DEFAULT_MAX_TARGETS,
                    statementTimeoutSeconds = environment[STATEMENT_TIMEOUT_SECONDS_ENV]?.toInt()
                        ?: DEFAULT_STATEMENT_TIMEOUT_SECONDS,
                ),
            )
        }

        private fun parseCandidateSeconds(raw: String): List<Long> {
            return raw.split(",")
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(String::toLong)
                .distinct()
                .sortedDescending()
        }

        private fun requiredLong(environment: Map<String, String>, key: String): Long {
            val value = requireNotNull(environment[key]) { "$key is required for the TTL replay." }

            return requireNotNull(value.toLongOrNull()) { "$key must be an epoch millis value." }
        }
    }
}

/** TTL 短縮感度 replay の main entry point。JSON Lines を stdout へ書く。 */
fun main() {
    val exitCode = runTtlReplayMain(
        environment = System.getenv(),
        stdout = { line -> println(line) },
        stderr = { line -> System.err.println(line) },
    )

    if (exitCode != TTL_REPLAY_SUCCESS_EXIT_CODE) {
        exitProcess(exitCode)
    }
}

/** TTL 短縮感度 replay を実行し、process exit code を返す。 */
internal fun runTtlReplayMain(
    environment: Map<String, String>,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int {
    return runCatching {
        val config = TtlReplayCliConfig.fromEnvironment(environment)
        ReplayReadOnlyRuntime.fromDatabaseConfig(config.databaseConfig).use { runtime ->
            TtlShorteningReplay(runtime).run(config.bounds, ReplayJsonLinesWriter(stdout))
        }
    }.fold(
        onSuccess = { TTL_REPLAY_SUCCESS_EXIT_CODE },
        onFailure = { failure ->
            stderr("ttl-replay failed: ${failure.message}")
            TTL_REPLAY_FAILURE_EXIT_CODE
        },
    )
}

private const val TTL_REPLAY_SUCCESS_EXIT_CODE = 0
private const val TTL_REPLAY_FAILURE_EXIT_CODE = 1
