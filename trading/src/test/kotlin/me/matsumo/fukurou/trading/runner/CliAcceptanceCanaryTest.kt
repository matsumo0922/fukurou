package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.invoker.CLAUDE_OUTPUT_ADAPTER_VERSION
import me.matsumo.fukurou.trading.invoker.CODEX_OUTPUT_ADAPTER_VERSION
import me.matsumo.fukurou.trading.invoker.LlmConfiguredModelIdentity
import me.matsumo.fukurou.trading.invoker.LlmConfiguredModelSource
import me.matsumo.fukurou.trading.invoker.LlmEffort
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvocationResult
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmObservedModelIdentity
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.LlmProviderContractException
import me.matsumo.fukurou.trading.invoker.LlmProviderFailure
import me.matsumo.fukurou.trading.invoker.LlmProviderFailureCategory
import me.matsumo.fukurou.trading.invoker.McpToolContractCatalog
import me.matsumo.fukurou.trading.invoker.ProcessRunResult
import me.matsumo.fukurou.trading.invoker.ProcessRunStatus
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** pinned CLI acceptance driver の phase matrix と fail-closed 判定を検証するテスト。 */
class CliAcceptanceCanaryTest {
    @Test
    fun `four phase matrix uses pinned models and canonical tool policies`() = runBlocking {
        val invoker = RecordingCanaryInvoker(::successfulResult)
        val result = canary(invoker).run(1)

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                LlmInvocationPhase.PRE_FILTER,
                LlmInvocationPhase.PROPOSER,
                LlmInvocationPhase.FALSIFIER,
                LlmInvocationPhase.REFLECTION,
            ),
            invoker.requests.map(LlmInvocationRequest::phase),
        )
        invoker.requests.forEach { request ->
            val expectedModel = if (request.provider == LlmProvider.CLAUDE) CLAUDE_CANARY_MODEL else CODEX_CANARY_MODEL
            assertEquals(expectedModel, request.model)
            assertFalse(request.useConfiguredModelFallback)
            assertEquals(McpToolContractCatalog.toolsFor(request.phase), request.allowedTools.map(::shortTool).toSet())
            assertEquals(request.phase in CANARY_MCP_PHASES, request.mcpServer != null)
        }
        assertEquals(LlmEffort.LOW, invoker.requests.single { it.phase == LlmInvocationPhase.FALSIFIER }.effort)
    }

    @Test
    fun `three repetitions execute each phase exactly three times`() = runBlocking {
        val invoker = RecordingCanaryInvoker(::successfulResult)

        assertTrue(canary(invoker).run(3).isSuccess)
        assertEquals(12, invoker.requests.size)
        assertFailsWith<IllegalArgumentException> { canary(invoker).run(2) }
        Unit
    }

    @Test
    fun `Claude observed model mismatch fails safely`() = runBlocking {
        val invoker = RecordingCanaryInvoker { request ->
            successfulResult(request).copy(observedModelIdentity = LlmObservedModelIdentity("wrong", "fixture"))
        }

        val failure = canary(invoker).run(1).exceptionOrNull() as CliAcceptanceFailure

        assertEquals(CliAcceptanceFailureCode.OBSERVED_MODEL, failure.code)
        assertEquals(LlmInvocationPhase.PRE_FILTER, failure.phase)
        assertFalse(failure.safeMessage().contains("wrong"))
    }

    @Test
    fun `Codex unavailable observed model remains valid`() = runBlocking {
        val invoker = RecordingCanaryInvoker(::successfulResult)

        assertTrue(canary(invoker).run(1).isSuccess)
        assertNull(invoker.results.single { it.request.provider == LlmProvider.CODEX }.observedModelIdentity)
    }

    @Test
    fun `missing auth contract failure keeps only typed safe code`() = runBlocking {
        val invoker = object : LlmInvoker {
            override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
                return Result.failure(
                    LlmProviderContractException(
                        LlmProviderFailure(
                            LlmProviderFailureCategory.AUTHENTICATION,
                            "CLAUDE_AUTH_SOURCE_MISSING",
                            CLAUDE_OUTPUT_ADAPTER_VERSION,
                        ),
                    ),
                )
            }
        }

        val failure = canary(invoker).run(1).exceptionOrNull() as CliAcceptanceFailure

        assertEquals(CliAcceptanceFailureCode.AUTHENTICATION, failure.code)
        assertFalse(failure.safeMessage().contains("SOURCE_MISSING"))
    }

    @Test
    fun `schema timeout exit cleanup and nonce failures are typed`() = runBlocking {
        val failures = listOf(
            CliAcceptanceFailureCode.OUTPUT_CONTRACT to { request: LlmInvocationRequest ->
                successfulResult(request).copy(
                    providerFailure = LlmProviderFailure(
                        LlmProviderFailureCategory.OUTPUT_CONTRACT,
                        "SCHEMA_DRIFT",
                        adapter(request),
                    ),
                )
            },
            CliAcceptanceFailureCode.TIMEOUT to { request ->
                successfulResult(request).copy(processResult = process(ProcessRunStatus.TIMED_OUT, null))
            },
            CliAcceptanceFailureCode.PROCESS_EXIT to { request ->
                successfulResult(request).copy(processResult = process(ProcessRunStatus.EXITED, 9))
            },
            CliAcceptanceFailureCode.CLEANUP to { request ->
                successfulResult(request).copy(cleanupFailure = IllegalStateException("secret/path"))
            },
            CliAcceptanceFailureCode.PROBE_MARKER to { request ->
                successfulResult(request).copy(responseText = "missing")
            },
        )

        failures.forEach { (expected, resultFactory) ->
            val failure = canary(RecordingCanaryInvoker(resultFactory)).run(1).exceptionOrNull()
            assertEquals(expected, (failure as CliAcceptanceFailure).code)
            assertFalse(failure.safeMessage().contains("secret"))
        }
    }

    private fun canary(invoker: LlmInvoker): CliAcceptanceCanary {
        return CliAcceptanceCanary(
            invoker = invoker,
            workingDirectory = Path.of("/tmp"),
            environment = mapOf("HOME" to "/canary-auth", "CODEX_HOME" to "/canary-auth/.codex"),
            fixtureCommand = "/fixture-mcp",
            nonceFactory = { phase, iteration -> "nonce-${phase.name.lowercase()}-$iteration" },
        )
    }
}

private class RecordingCanaryInvoker(
    private val resultFactory: (LlmInvocationRequest) -> LlmInvocationResult,
) : LlmInvoker {
    val requests = mutableListOf<LlmInvocationRequest>()
    val results = mutableListOf<LlmInvocationResult>()

    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        requests += request
        val result = resultFactory(request)
        results += result

        return Result.success(result)
    }
}

private fun successfulResult(request: LlmInvocationRequest): LlmInvocationResult {
    val nonce = request.environment.getValue(CLI_CANARY_NONCE_ENV)
    val model = requireNotNull(request.model)
    val response = if (request.phase in CANARY_MCP_PHASES) nonce else "FUKUROU_CLI_CANARY_OK"

    return LlmInvocationResult(
        request = request,
        processResult = process(ProcessRunStatus.EXITED, 0),
        responseText = response,
        configuredModelIdentity = LlmConfiguredModelIdentity(model, LlmConfiguredModelSource.REQUEST),
        observedModelIdentity = if (request.provider == LlmProvider.CLAUDE) {
            LlmObservedModelIdentity(model, "CLAUDE_RESULT")
        } else {
            null
        },
    )
}

private fun process(status: ProcessRunStatus, exitCode: Int?): ProcessRunResult {
    return ProcessRunResult(status, exitCode, "raw-output", "raw-error")
}

private fun adapter(request: LlmInvocationRequest): String = when (request.provider) {
    LlmProvider.CLAUDE -> CLAUDE_OUTPUT_ADAPTER_VERSION
    LlmProvider.CODEX -> CODEX_OUTPUT_ADAPTER_VERSION
}

private fun shortTool(tool: String): String = tool.substringAfterLast("__")
