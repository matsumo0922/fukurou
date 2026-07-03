package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.invoker.DefaultLlmCommandRenderer
import me.matsumo.fukurou.trading.invoker.LlmCommandRendererConfig
import me.matsumo.fukurou.trading.invoker.ShellLlmInvoker
import me.matsumo.fukurou.trading.invoker.ShellProcessRunner
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import java.nio.file.Path

/**
 * 手動 one-shot runner の main entry point。
 */
fun main() = runBlocking {
    val environment = System.getenv()
    val tradingConfig = TradingBotConfig.fromEnvironment(environment)
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
                    config = LlmCommandRendererConfig.fromEnvironment(environment),
                ),
                processRunner = ShellProcessRunner(),
            ),
            parentEnvironment = environment,
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
        val result = runner.runOneShot(request).getOrThrow()

        println(
            "one-shot runner finished invocation=${result.invocationId} status=${result.status}",
        )
    } finally {
        tradingRuntime.close()
    }
}
