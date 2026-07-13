@file:Suppress("ImportOrdering")

package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.LLM_LAUNCH_DISABLED
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationOutcome
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.invoker.DefaultLlmCommandRenderer
import me.matsumo.fukurou.trading.invoker.LlmCommandRendererConfig
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.ShellLlmInvoker
import me.matsumo.fukurou.trading.invoker.ShellProcessRunner
import me.matsumo.fukurou.trading.invoker.safeCodexFailureOrNull
import me.matsumo.fukurou.trading.runtime.TradingRuntime
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.UUID
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
    requireLaunchAllowed: suspend (Map<String, String>) -> Unit = ::requireOneShotLaunchAllowed,
    launch: suspend (Map<String, String>) -> OneShotRunnerResult = ::launchOneShotRunner,
    stdout: (String) -> Unit = { message -> System.out.println(message) },
    stderr: (String) -> Unit = { message -> System.err.println(message) },
): Int {
    val result = runCatching {
        requireLaunchAllowed(environment)
        launch(environment)
    }

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

            stderr("one-shot runner failed ${disclosure.format()}.")
            ONE_SHOT_RUNNER_FAILURE_EXIT_CODE
        },
    )
}

@Suppress("LongMethod")
private suspend fun launchOneShotRunner(environment: Map<String, String>): OneShotRunnerResult {
    val runtimeConfigResolution = TradingRuntimeFactory.resolveRuntimeConfigFromEnvironment(environment)
    val tradingConfig = runtimeConfigResolution.tradingConfig
    val tradingRuntime = TradingRuntimeFactory.fromEnvironment(
        environment = environment,
        tradingConfig = tradingConfig,
    )

    try {
        return launchOneShotRunnerWithRuntime(
            environment = environment,
            tradingConfig = tradingConfig,
            tradingRuntime = tradingRuntime,
            runtimeConfigSnapshot = runtimeConfigResolution.auditSnapshot,
            llmInvoker = ShellLlmInvoker(
                commandRenderer = DefaultLlmCommandRenderer(
                    config = LlmCommandRendererConfig.fromEnvironment(
                        environment = environment,
                    ),
                ),
                processRunner = ShellProcessRunner(tradingConfig.runner.processTerminationGrace),
            ),
            invocationId = UUID.randomUUID().toString(),
        )
    } finally {
        tradingRuntime.close()
    }
}

@Suppress("LongMethod", "LongParameterList")
internal suspend fun launchOneShotRunnerWithRuntime(
    environment: Map<String, String>,
    tradingConfig: TradingBotConfig,
    tradingRuntime: TradingRuntime,
    runtimeConfigSnapshot: RuntimeConfigAuditSnapshot?,
    llmInvoker: LlmInvoker,
    invocationId: String,
    clock: Clock = Clock.systemUTC(),
): OneShotRunnerResult {
    val reservation = tradingRuntime.launchReservationRepository.tryReserve(
        LlmLaunchReservationRequest(
            invocationId = invocationId,
            triggerKind = LlmDaemonTriggerKind.MANUAL,
            triggerKey = "direct:$invocationId",
            reservedAt = clock.instant(),
            runnerConfig = tradingConfig.runner,
            hourlyWindow = Duration.ofHours(1),
            dailyWindow = Duration.ofHours(24),
            activeReservationStaleAfter = tradingConfig.daemon.launchReservationStaleAfter,
        ),
    ).getOrThrow()
    check(reservation is LlmLaunchReservationOutcome.Reserved) {
        "LLM launch reservation rejected: ${(reservation as LlmLaunchReservationOutcome.Rejected).reason.name}"
    }
    val runner = OneShotLlmRunner(
        tradingRuntime = tradingRuntime,
        tradingConfig = tradingConfig,
        llmInvoker = llmInvoker,
        parentEnvironment = environment,
        runtimeConfigSnapshot = runtimeConfigSnapshot,
        clock = clock,
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
        proposerAssignment = tradingConfig.llmRoleAssignments.proposer,
        falsifierAssignment = tradingConfig.llmRoleAssignments.falsifier,
        invocationId = invocationId,
        triggerKind = LlmDaemonTriggerKind.MANUAL,
    )
    return runner.runOneShot(request).getOrThrow()
}

private fun requireOneShotLaunchAllowed(environment: Map<String, String>) {
    val tradingConfig = TradingRuntimeFactory.resolveRuntimeConfigFromEnvironment(environment).tradingConfig
    check(tradingConfig.daemon.launchEnabled) { LLM_LAUNCH_DISABLED }
}

private const val ONE_SHOT_RUNNER_SUCCESS_EXIT_CODE = 0
private const val ONE_SHOT_RUNNER_FAILURE_EXIT_CODE = 1
