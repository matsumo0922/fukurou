package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.invoker.CLAUDE_OUTPUT_ADAPTER_VERSION
import me.matsumo.fukurou.trading.invoker.CODEX_OUTPUT_ADAPTER_VERSION
import me.matsumo.fukurou.trading.invoker.LlmArtifactCleanupException
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
import me.matsumo.fukurou.trading.invoker.classifyLlmFailure
import java.nio.file.Files
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
            assertEquals(request.phase in CANARY_MCP_PHASES, CLI_CANARY_RECORD_PATH_ENV in request.environment)
            request.mcpServer?.let { server ->
                assertEquals(Path.of("/tmp"), server.manifestPath.parent)
                val expectedForwardedEnvironment = if (request.provider == LlmProvider.CODEX) {
                    listOf(CLI_CANARY_RECORD_PATH_ENV)
                } else {
                    emptyList()
                }
                assertEquals(expectedForwardedEnvironment, server.forwardedEnvironmentVariables)
            }
        }
        val proposer = invoker.requests.single { it.phase == LlmInvocationPhase.PROPOSER }
        assertEquals(emptyList(), proposer.mcpServer?.autoApprovedTools)
        assertTrue(proposer.prompt.contains("mcp__canary__submit_decision"))
        val falsifier = invoker.requests.single { it.phase == LlmInvocationPhase.FALSIFIER }
        assertEquals(LlmEffort.LOW, falsifier.effort)
        assertEquals(listOf("submit_falsification"), falsifier.mcpServer?.autoApprovedTools)
        assertTrue(falsifier.prompt.contains("mcp__canary__submit_falsification"))
        invoker.requests.mapNotNull { it.environment[CLI_CANARY_RECORD_PATH_ENV] }
            .forEach { path -> assertFalse(Files.exists(Path.of(path))) }
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
        assertEquals(CliAcceptanceCleanupStatus.COMPLETED, failure.cleanupStatus)
        assertFalse(failure.safeMessage().contains("SOURCE_MISSING"))
    }

    @Test
    fun `semantic failure remains primary when cleanup also fails`() = runBlocking {
        val invoker = RecordingCanaryInvoker { request ->
            successfulResult(request).copy(
                providerFailure = LlmProviderFailure(
                    LlmProviderFailureCategory.AUTHENTICATION,
                    "AUTH_SECRET",
                    adapter(request),
                ),
                cleanupFailure = IllegalStateException("cleanup/secret"),
            )
        }

        val failure = canary(invoker).run(1).exceptionOrNull() as CliAcceptanceFailure
        assertEquals(CliAcceptanceFailureCode.AUTHENTICATION, failure.code)
        assertEquals(CliAcceptanceCleanupStatus.FAILED, failure.cleanupStatus)
        assertTrue(failure.safeMessage().endsWith("cleanup=FAILED"))
        assertFalse(failure.safeMessage().contains("secret", ignoreCase = true))
    }

    @Test
    fun `render cleanup failure preserves suppressed provider reason`() = runBlocking {
        val primary = LlmProviderContractException(
            LlmProviderFailure(LlmProviderFailureCategory.AUTHENTICATION, "secret", CLAUDE_OUTPUT_ADAPTER_VERSION),
        )
        val cleanup = LlmArtifactCleanupException(IllegalStateException("cleanup"))
        cleanup.addSuppressed(primary)
        val failure = canary(object : LlmInvoker {
            override suspend fun invoke(request: LlmInvocationRequest) = Result.failure<LlmInvocationResult>(cleanup)
        }).run(1).exceptionOrNull() as CliAcceptanceFailure

        assertEquals(CliAcceptanceFailureCode.AUTHENTICATION, failure.code)
        assertEquals(CliAcceptanceCleanupStatus.FAILED, failure.cleanupStatus)
    }

    @Test
    fun `Codex classification marker is not cleanup failure`() = runBlocking {
        val classified = IllegalStateException("secret").classifyLlmFailure(LlmProvider.CODEX)
        val failure = canary(object : LlmInvoker {
            override suspend fun invoke(request: LlmInvocationRequest) = Result.failure<LlmInvocationResult>(classified)
        }).run(1).exceptionOrNull() as CliAcceptanceFailure

        assertEquals(CliAcceptanceCleanupStatus.COMPLETED, failure.cleanupStatus)
    }

    @Test
    fun `schema timeout exit cleanup marker and tool call failures are typed`() = runBlocking {
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
            CliAcceptanceFailureCode.TOOL_CALL to { request ->
                successfulResult(request).also {
                    request.environment[CLI_CANARY_RECORD_PATH_ENV]?.let { path ->
                        Files.writeString(Path.of(path), "${request.phase.name}\tget_balance\n")
                    }
                }
            },
        )

        failures.forEach { (expected, resultFactory) ->
            val failure = canary(RecordingCanaryInvoker(resultFactory)).run(1).exceptionOrNull()
            assertEquals(expected, (failure as CliAcceptanceFailure).code)
            if (expected == CliAcceptanceFailureCode.CLEANUP) {
                assertEquals(CliAcceptanceCleanupStatus.FAILED, failure.cleanupStatus)
            }
            assertFalse(failure.safeMessage().contains("secret"))
        }
    }

    @Test
    fun `CLI environment uses the production child allowlist`() {
        val filtered = cliCanaryEnvironment(
            mapOf("HOME" to "/canary-auth", "CODEX_HOME" to "/canary-auth/.codex", "DB_PASSWORD" to "secret"),
        )
        assertEquals(setOf("HOME", "CODEX_HOME"), filtered.keys)
    }

    private fun canary(invoker: LlmInvoker) = CliAcceptanceCanary(
        invoker = invoker,
        workingDirectory = Path.of("/tmp"),
        environment = mapOf("HOME" to "/canary-auth", "CODEX_HOME" to "/canary-auth/.codex"),
        fixtureCommand = "/fixture-mcp",
    )
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
    val model = requireNotNull(request.model)
    request.environment[CLI_CANARY_RECORD_PATH_ENV]?.let { recordPath ->
        Files.writeString(Path.of(recordPath), "${request.phase.name}\t${cliCanaryProbeTool(request.phase)}\n")
    }

    return LlmInvocationResult(
        request = request,
        processResult = process(ProcessRunStatus.EXITED, 0),
        responseText = "FUKUROU_CLI_CANARY_OK",
        authEvidenceObserved = false,
        configuredModelIdentity = LlmConfiguredModelIdentity(model, LlmConfiguredModelSource.REQUEST),
        observedModelIdentity = if (request.provider == LlmProvider.CLAUDE) {
            LlmObservedModelIdentity(model, "CLAUDE_RESULT")
        } else {
            null
        },
    )
}

private fun process(status: ProcessRunStatus, exitCode: Int?) =
    ProcessRunResult(status, exitCode, "raw-output", "raw-error")

private fun adapter(request: LlmInvocationRequest): String = when (request.provider) {
    LlmProvider.CLAUDE -> CLAUDE_OUTPUT_ADAPTER_VERSION
    LlmProvider.CODEX -> CODEX_OUTPUT_ADAPTER_VERSION
}

private fun shortTool(tool: String): String = tool.substringAfterLast("__")
