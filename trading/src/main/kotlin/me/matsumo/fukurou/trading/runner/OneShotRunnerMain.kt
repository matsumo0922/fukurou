package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.invoker.DefaultLlmCommandRenderer
import me.matsumo.fukurou.trading.invoker.LlmCommandRendererConfig
import me.matsumo.fukurou.trading.invoker.ShellLlmInvoker
import me.matsumo.fukurou.trading.invoker.ShellProcessRunner
import me.matsumo.fukurou.trading.invoker.safeCodexFailureOrNull
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * 手動 one-shot runner の main entry point。
 */
fun main() {
    val exitCode = runBlocking {
        runOneShotRunnerMain(environment = System.getenv())
    }

    if (exitCode != ONE_SHOT_RUNNER_SUCCESS_EXIT_CODE) {
        exitProcess(exitCode)
    }
}

/**
 * standalone one-shot runner を実行し、process exit code を返す。
 */
internal suspend fun runOneShotRunnerMain(
    environment: Map<String, String>,
    launch: suspend (Map<String, String>) -> OneShotRunnerResult = ::launchOneShotRunner,
    stdout: (String) -> Unit = { message -> System.out.println(message) },
    stderr: (String) -> Unit = { message -> System.err.println(message) },
): Int {
    val result = runCatching { launch(environment) }

    return result.fold(
        onSuccess = { runnerResult ->
            stdout("one-shot runner finished invocation=${runnerResult.invocationId} status=${runnerResult.status}")
            ONE_SHOT_RUNNER_SUCCESS_EXIT_CODE
        },
        onFailure = { failure ->
            val disclosure = failure.safeCodexFailureOrNull()

            if (disclosure == null) {
                throw failure
            }

            stderr("one-shot runner failed ${disclosure.toLogFields()}.")
            ONE_SHOT_RUNNER_FAILURE_EXIT_CODE
        },
    )
}

private suspend fun launchOneShotRunner(environment: Map<String, String>): OneShotRunnerResult {
    val runtimeConfigResolution = TradingRuntimeFactory.resolveRuntimeConfigFromEnvironment(environment)
    val tradingConfig = runtimeConfigResolution.tradingConfig
    val tradingRuntime = TradingRuntimeFactory.fromEnvironment(
        environment = environment,
        tradingConfig = tradingConfig,
    )

    try {
        val runner = OneShotLlmRunner(
            tradingRuntime = tradingRuntime,
            tradingConfig = tradingConfig,
            llmInvoker = ShellLlmInvoker(
                commandRenderer = DefaultLlmCommandRenderer(
                    config = LlmCommandRendererConfig.fromEnvironment(
                        environment = environment,
                        runtimeModels = tradingConfig.llmModels,
                    ),
                ),
                processRunner = ShellProcessRunner(),
            ),
            parentEnvironment = environment,
            runtimeConfigSnapshot = runtimeConfigResolution.auditSnapshot,
        )
        val repositoryRoot = Path.of(environment["FUKUROU_REPOSITORY_ROOT"] ?: ".")
            .toAbsolutePath()
            .normalize()
        val workingDirectory = Path.of(environment["FUKUROU_LLM_WORKING_DIRECTORY"] ?: ".")
            .toAbsolutePath()
            .normalize()
        val request = OneShotRunnerRequest(
            repositoryRoot = repositoryRoot,
            workingDirectory = workingDirectory,
            mcpJarPath = environment[FUKUROU_MCP_JAR_PATH_ENV] ?: "mcp/build/libs/fukurou-mcp-all.jar",
            cliConfig = OneShotRunnerCliConfig.fromEnvironment(environment),
            marketSnapshotId = environment["FUKUROU_MARKET_SNAPSHOT_ID"],
        )
        return runner.runOneShot(request).getOrThrow()
    } finally {
        tradingRuntime.close()
    }
}

private const val ONE_SHOT_RUNNER_SUCCESS_EXIT_CODE = 0
private const val ONE_SHOT_RUNNER_FAILURE_EXIT_CODE = 1
