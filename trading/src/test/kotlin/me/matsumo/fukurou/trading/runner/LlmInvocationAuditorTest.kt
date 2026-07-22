package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.audit.InMemoryLlmInputManifestRepository
import me.matsumo.fukurou.trading.audit.LlmInputManifestRepository
import me.matsumo.fukurou.trading.audit.LlmPhaseManifestRecorder
import me.matsumo.fukurou.trading.audit.LlmPhaseObservation
import me.matsumo.fukurou.trading.evaluation.LlmModelUsage
import me.matsumo.fukurou.trading.evaluation.LlmTokenUsage
import me.matsumo.fukurou.trading.evaluation.LlmUsageDetails
import me.matsumo.fukurou.trading.evaluation.LlmUsageParser
import me.matsumo.fukurou.trading.invoker.CODEX_OUTPUT_ADAPTER_VERSION
import me.matsumo.fukurou.trading.invoker.CODEX_STDERR_AUTH_FAILURES
import me.matsumo.fukurou.trading.invoker.DefaultLlmOutputParser
import me.matsumo.fukurou.trading.invoker.LlmArtifactCleanupQuarantine
import me.matsumo.fukurou.trading.invoker.LlmCommandRenderer
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvocationResult
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmMcpServerConfig
import me.matsumo.fukurou.trading.invoker.LlmProcessTreeTerminationRegistry
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.LlmProviderFailure
import me.matsumo.fukurou.trading.invoker.LlmProviderFailureCategory
import me.matsumo.fukurou.trading.invoker.ProcessRunResult
import me.matsumo.fukurou.trading.invoker.ProcessRunStatus
import me.matsumo.fukurou.trading.invoker.ProcessRunner
import me.matsumo.fukurou.trading.invoker.ProcessStartAwareRunner
import me.matsumo.fukurou.trading.invoker.ProcessTreeTerminationProvenCancellationException
import me.matsumo.fukurou.trading.invoker.RenderedLlmCommand
import me.matsumo.fukurou.trading.invoker.ShellLlmInvoker
import me.matsumo.fukurou.trading.invoker.ShellProcessRunner
import org.junit.Assume.assumeTrue
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * LlmInvocationAuditor の phase audit payload を検証するテスト。
 */
class LlmInvocationAuditorTest {

    @Test
    fun invokeAndAudit_appendsRedactedPhasePayloadWithClaudeUsage() = runBlocking {
        val commandEventLog = InMemoryCommandEventLog()
        val auditor = LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(setOf("reflection-secret-token")),
            clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
        )
        val request = LlmInvocationRequest(
            invocationId = "audit-run",
            provider = LlmProvider.CLAUDE,
            phase = LlmInvocationPhase.PROPOSER,
            prompt = "test prompt",
            timeout = Duration.ofSeconds(60),
            workingDirectory = Path.of(".").toAbsolutePath().normalize(),
            decisionRunContext = DecisionRunContext(
                decisionRunId = "audit-run",
                llmProvider = "claude",
                promptHash = "prompt-hash",
                systemPromptVersion = "system-prompt-v1",
                marketSnapshotId = "snapshot-1",
            ),
            mcpServer = null,
            environment = emptyMap(),
            toolPolicy = me.matsumo.fukurou.trading.invoker.ToolPolicy(emptySet(), emptyList()),
        )
        val invoker = StaticAuditLlmInvoker(
            stdout = """{"total_cost_usd":"0.0123","note":"reflection-secret-token"}""",
        )

        val result = auditor.invokeAndAudit(
            phaseName = "proposer",
            context = request.decisionRunContext,
            request = request,
            llmInvoker = invoker,
        )

        val event = commandEventLog.events().single()
        val payload = Json.parseToJsonElement(event.payload).jsonObject
        val details = requireNotNull(payload["details"]).jsonObject

        assertTrue(result.isSuccess)
        assertEquals("proposer", requireNotNull(payload["phase"]).jsonPrimitive.content)
        assertEquals("claude", requireNotNull(details["provider"]).jsonPrimitive.content)
        assertEquals("EXITED", requireNotNull(details["status"]).jsonPrimitive.content)
        assertTerminal(details, "NOT_APPLICABLE", "PROVEN_EXITED", "COMPLETED")
        assertEquals("0.0123", requireNotNull(details["usage"]).jsonObject["totalCostUsd"]?.jsonPrimitive?.content)
        assertTrue(requireNotNull(details["stdout"]).jsonPrimitive.content.contains("[REDACTED]"))
        assertFalse(event.payload.contains("reflection-secret-token"))
    }

    @Test
    fun invokeAndAudit_rejectsUnsafeCommandEventContextWithoutEchoingIt() = runBlocking {
        val known = "KnownContextCredential_7kN2pQ9xV4mZ8rT6"
        val values = listOf(
            known,
            "api_key=credential-value-123456789",
            "aB3dE5fG7hJ9kL2mN4pQ6rS8tV0xY2z",
            "invalid context whitespace",
            "x".repeat(129),
        )

        values.forEach { value ->
            val commandEventLog = InMemoryCommandEventLog()
            val auditor = LlmInvocationAuditor(
                commandEventLog = commandEventLog,
                redactor = SecretRedactor(setOf(known)),
                clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
            )
            val request = auditRequest(LlmProvider.CLAUDE).copy(
                decisionRunContext = DecisionRunContext(
                    decisionRunId = "audit-run",
                    llmProvider = "claude",
                    promptHash = "prompt-hash",
                    systemPromptVersion = "system-prompt-v1.14",
                    marketSnapshotId = value,
                ),
            )
            val failure = runCatching {
                auditor.invokeAndAudit(
                    "proposer",
                    request.decisionRunContext,
                    request,
                    StaticAuditLlmInvoker("{}"),
                )
            }.exceptionOrNull()

            assertTrue(failure != null)
            assertTrue(commandEventLog.events().isEmpty())
            assertFalse(failure.message.orEmpty().contains(value))
        }
    }

    @Test
    fun invokeAndAudit_preservesPartialCodexUsageWhileFailingClosed() = runBlocking {
        val commandEventLog = InMemoryCommandEventLog()
        val auditor = LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(emptySet()),
            clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
        )
        val request = auditRequest(LlmProvider.CODEX)
        val usage = LlmUsageDetails(
            totalCostUsd = null,
            numTurns = null,
            durationMs = null,
            usage = LlmTokenUsage(
                inputTokens = 10,
                outputTokens = 4,
                reasoningOutputTokens = 2,
                cacheCreationInputTokens = null,
                cacheReadInputTokens = 3,
            ),
            modelUsages = emptyList(),
        )
        val invoker = StaticStructuredAuditLlmInvoker(usage)

        val result = auditor.invokeAndAudit(
            phaseName = "falsifier",
            context = request.decisionRunContext,
            request = request,
            llmInvoker = invoker,
        )

        val payload = Json.parseToJsonElement(commandEventLog.events().single().payload).jsonObject
        val details = requireNotNull(payload["details"]).jsonObject
        val auditUsage = requireNotNull(details["usage"]).jsonObject

        assertTrue(result.isFailure)
        assertEquals("1", details["exitCode"]?.jsonPrimitive?.content)
        assertEquals("2", auditUsage["usage"]?.jsonObject?.get("reasoningOutputTokens")?.jsonPrimitive?.content)
        assertEquals("true", details["authFailureSuspected"]?.jsonPrimitive?.content)
        assertFalse(details.containsKey("stdout"))
        assertFalse(details.containsKey("stderr"))
        assertFalse(auditUsage.containsKey("totalCostUsd"))
        assertFalse(commandEventLog.events().single().payload.contains("private trading strategy"))
        assertFalse(commandEventLog.events().single().payload.contains("submit_decision"))
        assertFalse(commandEventLog.events().single().payload.contains("private/session/path"))
    }

    @Test
    fun invokeAndAudit_recordsCompletedUsageBeforeFailingOnCleanup() = runBlocking {
        val commandEventLog = InMemoryCommandEventLog()
        val humanLogs = mutableListOf<String>()
        val auditor = LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(emptySet()),
            clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
            humanLogger = humanLogs::add,
        )
        val request = auditRequest(LlmProvider.CODEX)
        val usage = LlmUsageDetails(
            totalCostUsd = null,
            numTurns = null,
            durationMs = null,
            usage = LlmTokenUsage(
                inputTokens = 12,
                outputTokens = 3,
                reasoningOutputTokens = 1,
                cacheCreationInputTokens = null,
                cacheReadInputTokens = 2,
            ),
            modelUsages = emptyList(),
        )
        val cleanupFailure = FileSystemException(
            "/private/codex-home/path-marker",
            null,
            "cleanup path-message-marker",
        )
        val invoker = CleanupFailingAuditLlmInvoker(usage, cleanupFailure)

        val result = auditor.invokeAndAudit(
            phaseName = "falsifier",
            context = request.decisionRunContext,
            request = request,
            llmInvoker = invoker,
        )

        val payload = Json.parseToJsonElement(commandEventLog.events().single().payload).jsonObject
        val details = requireNotNull(payload["details"]).jsonObject
        val auditUsage = requireNotNull(details["usage"]).jsonObject

        assertTrue(result.isFailure)
        assertEquals("EXITED", details["status"]?.jsonPrimitive?.content)
        assertEquals("0", details["exitCode"]?.jsonPrimitive?.content)
        assertEquals("true", details["cleanupFailed"]?.jsonPrimitive?.content)
        assertTerminal(details, "NOT_APPLICABLE", "PROVEN_EXITED", "FAILED")
        assertTrue(details["cleanupFailed"]?.jsonPrimitive?.isString == true)
        assertEquals("12", auditUsage["usage"]?.jsonObject?.get("inputTokens")?.jsonPrimitive?.content)
        assertFalse(commandEventLog.events().single().payload.contains("path-marker"))
        assertFalse(commandEventLog.events().single().payload.contains("path-message-marker"))
        assertEquals(emptyList(), humanLogs)
    }

    @Test
    fun invokeAndAudit_recordsRedactedOutputForCodexCleanupFailureWithNoAdapterFailure() = runBlocking {
        val commandEventLog = InMemoryCommandEventLog()
        val auditor = LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(emptySet()),
            clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
        )
        val request = auditRequest(LlmProvider.CODEX)
        // turn 完走・exit 0 の後、一時 artifact の cleanup にだけ失敗したケース
        val invoker = ConfigurableAuditLlmInvoker(
            processResult = ProcessRunResult(
                status = ProcessRunStatus.EXITED,
                exitCode = 0,
                stdout = COMPLETE_SUCCESSFUL_CODEX_EVENT_STREAM,
                stderr = "",
            ),
            cleanupFailure = FileSystemException("/private/codex-home/session", null, "cleanup failed"),
        )

        auditor.invokeAndAudit(
            phaseName = "falsifier",
            context = request.decisionRunContext,
            request = request,
            llmInvoker = invoker,
        )

        val details = auditedDetails(commandEventLog)

        assertEquals("CLEANUP", details["failureCategory"]?.jsonPrimitive?.content)
        assertEquals(COMPLETE_SUCCESSFUL_CODEX_EVENT_STREAM, details["stdout"]?.jsonPrimitive?.content)
    }

    @Test
    fun invokeAndAudit_recordsRedactedOutputForCodexProcessExitWithNoAdapterFailure() = runBlocking {
        val commandEventLog = InMemoryCommandEventLog()
        val auditor = LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(emptySet()),
            clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
        )
        val request = auditRequest(LlmProvider.CODEX)
        // stdout は DefaultLlmOutputParser.parseCodex() が providerFailure=null（完全な成功 turn）を
        // 返す形にする。ガード条件2（cliErrorReported==false）が本番で実際に成立しうるのは、
        // ここに示すような「turn 自体は完走したが、その後の process 終了が非ゼロになった」ケースであり、
        // launcher 起動失敗のような非 JSON 出力では schemaDrift により OUTPUT_CONTRACT になるため
        // このガードには到達しない
        val invoker = ConfigurableAuditLlmInvoker(
            processResult = ProcessRunResult(
                status = ProcessRunStatus.EXITED,
                exitCode = 1,
                stdout = COMPLETE_SUCCESSFUL_CODEX_EVENT_STREAM,
                stderr = "post-turn teardown crashed with exit 1",
            ),
        )

        auditor.invokeAndAudit(
            phaseName = "falsifier",
            context = request.decisionRunContext,
            request = request,
            llmInvoker = invoker,
        )

        val details = auditedDetails(commandEventLog)

        assertEquals("PROCESS_EXIT", details["failureCategory"]?.jsonPrimitive?.content)
        assertEquals(COMPLETE_SUCCESSFUL_CODEX_EVENT_STREAM, details["stdout"]?.jsonPrimitive?.content)
        assertEquals(
            "post-turn teardown crashed with exit 1",
            details["stderr"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun invokeAndAudit_recordsRedactedOutputForCodexProcessTimeoutWithNoAdapterFailure() = runBlocking {
        val commandEventLog = InMemoryCommandEventLog()
        val auditor = LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(emptySet()),
            clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
        )
        val request = auditRequest(LlmProvider.CODEX)
        // turn 完走後、process 自体が終了せず timeout したケース（起動失敗ではない）
        val invoker = ConfigurableAuditLlmInvoker(
            processResult = ProcessRunResult(
                status = ProcessRunStatus.TIMED_OUT,
                exitCode = null,
                stdout = COMPLETE_SUCCESSFUL_CODEX_EVENT_STREAM,
                stderr = "",
            ),
        )

        auditor.invokeAndAudit(
            phaseName = "falsifier",
            context = request.decisionRunContext,
            request = request,
            llmInvoker = invoker,
        )

        val details = auditedDetails(commandEventLog)

        assertEquals("PROCESS_TIMEOUT", details["failureCategory"]?.jsonPrimitive?.content)
        assertEquals(COMPLETE_SUCCESSFUL_CODEX_EVENT_STREAM, details["stdout"]?.jsonPrimitive?.content)
    }

    @Test
    fun invokeAndAudit_retainsRawOutputForCodexOutputInterpretedFailureCategoriesWhenNoAuthEvidenceObserved() = runBlocking {
        // issue #295: OUTPUT_CONTRACT/RATE_OR_SESSION_LIMIT/QUOTA_EXHAUSTED/UNKNOWN_PROVIDER_FAILURE は
        // authEvidenceObserved == false（既知の認証 evidence 文言が独立に観測されなかった場合）に限り、
        // 新設の output-interpreted 経路で raw output を記録できるようになった
        val outputInterpretedFailures = listOf(
            LlmProviderFailure(LlmProviderFailureCategory.RATE_OR_SESSION_LIMIT, "RATE_LIMIT", CODEX_OUTPUT_ADAPTER_VERSION),
            LlmProviderFailure(LlmProviderFailureCategory.QUOTA_EXHAUSTED, "QUOTA", CODEX_OUTPUT_ADAPTER_VERSION),
            LlmProviderFailure(LlmProviderFailureCategory.OUTPUT_CONTRACT, "SCHEMA_DRIFT", CODEX_OUTPUT_ADAPTER_VERSION),
            LlmProviderFailure(LlmProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE, null, CODEX_OUTPUT_ADAPTER_VERSION),
        )

        outputInterpretedFailures.forEach { failure ->
            val commandEventLog = InMemoryCommandEventLog()
            val auditor = LlmInvocationAuditor(
                commandEventLog = commandEventLog,
                redactor = SecretRedactor(emptySet()),
                clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
            )
            val request = auditRequest(LlmProvider.CODEX)
            val invoker = ConfigurableAuditLlmInvoker(
                processResult = ProcessRunResult(
                    status = ProcessRunStatus.EXITED,
                    // exitCode=0 なので、この category が process lifecycle 側の category に
                    // 上書きされず、adapter が返した category がそのまま primary になる
                    exitCode = 0,
                    stdout = "structured failure output",
                    stderr = "structured failure stderr",
                ),
                providerFailure = failure,
                authEvidenceObserved = false,
            )

            auditor.invokeAndAudit(
                phaseName = "falsifier",
                context = request.decisionRunContext,
                request = request,
                llmInvoker = invoker,
            )

            val details = auditedDetails(commandEventLog)

            assertEquals(failure.category.name, details["failureCategory"]?.jsonPrimitive?.content)
            assertEquals("structured failure output", details["stdout"]?.jsonPrimitive?.content, "${failure.category} should expose stdout")
            assertEquals("structured failure stderr", details["stderr"]?.jsonPrimitive?.content, "${failure.category} should expose stderr")
        }
    }

    @Test
    fun invokeAndAudit_omitsRawOutputForCodexOutputInterpretedFailureCategoriesWhenAuthEvidenceObserved() = runBlocking {
        val outputInterpretedFailures = listOf(
            LlmProviderFailure(LlmProviderFailureCategory.RATE_OR_SESSION_LIMIT, "RATE_LIMIT", CODEX_OUTPUT_ADAPTER_VERSION),
            LlmProviderFailure(LlmProviderFailureCategory.QUOTA_EXHAUSTED, "QUOTA", CODEX_OUTPUT_ADAPTER_VERSION),
            LlmProviderFailure(LlmProviderFailureCategory.OUTPUT_CONTRACT, "SCHEMA_DRIFT", CODEX_OUTPUT_ADAPTER_VERSION),
            LlmProviderFailure(LlmProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE, null, CODEX_OUTPUT_ADAPTER_VERSION),
        )

        outputInterpretedFailures.forEach { failure ->
            val commandEventLog = InMemoryCommandEventLog()
            val auditor = LlmInvocationAuditor(
                commandEventLog = commandEventLog,
                redactor = SecretRedactor(emptySet()),
                clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
            )
            val request = auditRequest(LlmProvider.CODEX)
            val invoker = ConfigurableAuditLlmInvoker(
                processResult = ProcessRunResult(
                    status = ProcessRunStatus.EXITED,
                    exitCode = 0,
                    stdout = "structured failure output",
                    stderr = "structured failure stderr",
                ),
                providerFailure = failure,
                authEvidenceObserved = true,
            )

            auditor.invokeAndAudit(
                phaseName = "falsifier",
                context = request.decisionRunContext,
                request = request,
                llmInvoker = invoker,
            )

            val details = auditedDetails(commandEventLog)

            assertEquals(failure.category.name, details["failureCategory"]?.jsonPrimitive?.content)
            assertFalse(details.containsKey("stdout"), "${failure.category} should not expose stdout")
            assertFalse(details.containsKey("stderr"), "${failure.category} should not expose stderr")
        }
    }

    @Test
    fun invokeAndAudit_omitsRawOutputWhenAdapterFailureCoexistsWithProcessExit() = runBlocking {
        val commandEventLog = InMemoryCommandEventLog()
        val auditor = LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(emptySet()),
            clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
        )
        val request = auditRequest(LlmProvider.CODEX)
        // stdout は DefaultLlmOutputParser.parseCodex() が実際に UNKNOWN_PROVIDER_FAILURE を
        // 返しうる形にする: turn.failed の error.message が既知の互換カテゴリ文言に一致しない場合
        val invoker = ConfigurableAuditLlmInvoker(
            processResult = ProcessRunResult(
                status = ProcessRunStatus.EXITED,
                exitCode = 1,
                stdout = """{"type":"turn.failed","error":{"message":"unrecognized diagnostic marker"}}""",
                stderr = "Not logged in",
            ),
            providerFailure = LlmProviderFailure(
                LlmProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE,
                "CODEX_ERROR_COMPATIBILITY",
                CODEX_OUTPUT_ADAPTER_VERSION,
            ),
        )

        auditor.invokeAndAudit(
            phaseName = "falsifier",
            context = request.decisionRunContext,
            request = request,
            llmInvoker = invoker,
        )

        val details = auditedDetails(commandEventLog)

        // primaryProviderFailure() は non-zero exitCode を adapter の UNKNOWN_PROVIDER_FAILURE より
        // 優先するため、表示される category は PROCESS_EXIT になる。それでも adapter が output text
        // から何かを検出している（cliErrorReported=true）ので、raw output は記録されないことを確認する
        assertEquals("PROCESS_EXIT", details["failureCategory"]?.jsonPrimitive?.content)
        assertEquals("true", details["cliErrorReported"]?.jsonPrimitive?.content)
        assertFalse(details.containsKey("stdout"))
        assertFalse(details.containsKey("stderr"))
    }

    @Test
    fun invokeAndAudit_omitsRawOutputWhenStderrCarriesKnownAuthSignatureAlongsideProcessExit() = runBlocking {
        val commandEventLog = InMemoryCommandEventLog()
        val auditor = LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(emptySet()),
            clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
        )
        val request = auditRequest(LlmProvider.CODEX)
        // #296 の condition 3（auditor 側の stderr 直接 inspect）は authEvidenceObserved に統合された。
        // このテストの意図（成功 event stream と既知認証文言 stderr の併存を排除する）は、
        // parser が独立に観測した authEvidenceObserved=true をこの double が明示することで再現する
        val invoker = ConfigurableAuditLlmInvoker(
            processResult = ProcessRunResult(
                status = ProcessRunStatus.EXITED,
                exitCode = 1,
                stdout = COMPLETE_SUCCESSFUL_CODEX_EVENT_STREAM,
                stderr = CODEX_STDERR_AUTH_FAILURES.first(),
            ),
            authEvidenceObserved = true,
        )

        auditor.invokeAndAudit(
            phaseName = "falsifier",
            context = request.decisionRunContext,
            request = request,
            llmInvoker = invoker,
        )

        val details = auditedDetails(commandEventLog)

        assertEquals("PROCESS_EXIT", details["failureCategory"]?.jsonPrimitive?.content)
        assertFalse(details.containsKey("stdout"))
        assertFalse(details.containsKey("stderr"))
    }

    @Test
    fun invokeAndAudit_masksKnownSecretInCodexLifecycleFailureOutput() = runBlocking {
        val commandEventLog = InMemoryCommandEventLog()
        val auditor = LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(setOf("super-secret-db-password")),
            clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
        )
        val request = auditRequest(LlmProvider.CODEX)
        // stdout は正常な success event stream の中に secret 値を埋め込み、DefaultLlmOutputParser
        // が providerFailure=null を返しうる形を維持する
        val secretBearingStdout = """
            {"type":"thread.started","thread_id":"thread-1"}
            {"type":"item.completed","item":{"type":"agent_message","text":"connecting with password=super-secret-db-password"}}
            {"type":"turn.completed","usage":{"input_tokens":10,"cached_input_tokens":0,"output_tokens":5,"reasoning_output_tokens":0}}
        """.trimIndent()
        val invoker = ConfigurableAuditLlmInvoker(
            processResult = ProcessRunResult(
                status = ProcessRunStatus.EXITED,
                exitCode = 1,
                stdout = secretBearingStdout,
                stderr = "auth failed for super-secret-db-password",
            ),
        )

        auditor.invokeAndAudit(
            phaseName = "falsifier",
            context = request.decisionRunContext,
            request = request,
            llmInvoker = invoker,
        )

        val details = auditedDetails(commandEventLog)

        assertEquals("PROCESS_EXIT", details["failureCategory"]?.jsonPrimitive?.content)
        assertEquals(
            secretBearingStdout.replace("super-secret-db-password", "[REDACTED]"),
            details["stdout"]?.jsonPrimitive?.content,
        )
        assertEquals("auth failed for [REDACTED]", details["stderr"]?.jsonPrimitive?.content)
        assertFalse(commandEventLog.events().single().payload.contains("super-secret-db-password"))
    }

    @Test
    fun invokeAndAudit_preservesClaudeStartFailureDetail() = runBlocking {
        val commandEventLog = InMemoryCommandEventLog()
        val auditor = LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(emptySet()),
            clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
        )
        val request = auditRequest(LlmProvider.CLAUDE)

        val result = auditor.invokeAndAudit(
            phaseName = "proposer",
            context = request.decisionRunContext,
            request = request,
            llmInvoker = FailingAuditLlmInvoker(IllegalStateException("synthetic claude failure")),
        )

        val payload = Json.parseToJsonElement(commandEventLog.events().single().payload).jsonObject
        val details = requireNotNull(payload["details"]).jsonObject

        assertTrue(result.isFailure)
        assertEquals("FAILED_TO_START", details["status"]?.jsonPrimitive?.content)
        assertTrue(details["error"]?.jsonPrimitive?.content.orEmpty().contains("synthetic claude failure"))
        assertEquals("UNKNOWN_PROVIDER_FAILURE", details["failureCategory"]?.jsonPrimitive?.content)
        assertTerminal(details, "NOT_APPLICABLE", "NOT_STARTED", "COMPLETED")
    }

    @Test
    fun invokeAndAudit_attemptsTerminalObservationWhenInvocationIsCancelled() = runBlocking {
        val repository = InMemoryLlmInputManifestRepository()
        val clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC)
        val recorder = LlmPhaseManifestRecorder(
            repository = repository,
            cliVersionProbe = { Result.success("fixture-cli 1.0") },
            runtimeConfigSnapshot = null,
            runtimeEnvironmentSnapshot = "MODE=PAPER",
            clock = clock,
        )
        val auditor = LlmInvocationAuditor(
            commandEventLog = InMemoryCommandEventLog(),
            redactor = SecretRedactor(emptySet()),
            clock = clock,
            phaseManifestRecorder = recorder,
        )
        val request = auditRequest(LlmProvider.CLAUDE).copy(phase = LlmInvocationPhase.PRE_FILTER)

        assertFailsWith<CancellationException> {
            auditor.invokeAndAudit(
                phaseName = "pre_filter",
                context = request.decisionRunContext,
                request = request,
                llmInvoker = object : LlmInvoker {
                    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
                        throw CancellationException("cancelled")
                    }
                },
            )
        }
        val observation = repository.findObservation("audit-run:PRE_FILTER").getOrThrow()

        assertEquals(
            me.matsumo.fukurou.trading.audit.LlmIdentityCoverageStatus.NOT_OBSERVABLE_BEFORE_START,
            observation?.modelCoverageStatus,
        )
    }

    @Test
    fun invokeAndAudit_doesNotMarkCancellationBeforeProcessStartAsStarted() = runBlocking {
        val repository = InMemoryLlmInputManifestRepository()
        val clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC)
        val auditor = manifestAuditor(repository, clock)
        val request = auditRequest(LlmProvider.CLAUDE).copy(phase = LlmInvocationPhase.PRE_FILTER)
        val invoker = ShellLlmInvoker(
            commandRenderer = object : LlmCommandRenderer {
                override fun render(request: LlmInvocationRequest): Result<RenderedLlmCommand> {
                    return Result.success(
                        RenderedLlmCommand(
                            executable = "fixture",
                            args = emptyList(),
                            environment = emptyMap(),
                            workingDirectory = request.workingDirectory,
                            timeout = request.timeout,
                            stdin = null,
                        ),
                    )
                }
            },
            processRunner = object : ProcessRunner {
                override suspend fun run(command: RenderedLlmCommand): Result<ProcessRunResult> {
                    throw CancellationException("cancelled after process start")
                }
            },
        )

        assertFailsWith<CancellationException> {
            auditor.invokeAndAudit("pre_filter", request.decisionRunContext, request, invoker)
        }
        val observation = repository.findObservation("audit-run:PRE_FILTER").getOrThrow()

        assertEquals(
            me.matsumo.fukurou.trading.audit.LlmIdentityCoverageStatus.NOT_OBSERVABLE_BEFORE_START,
            observation?.modelCoverageStatus,
        )
    }

    @Test
    fun invokeAndAudit_marksActualChildCancellationAfterProcessStartAsStarted() = runBlocking {
        assumeProcessDescendantEnumerationIsAvailable()
        val shellPath = Path.of("/bin/sh")
        assumeTrue(Files.isExecutable(shellPath))
        val repository = InMemoryLlmInputManifestRepository()
        val clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC)
        val auditor = manifestAuditor(repository, clock)
        val request = auditRequest(LlmProvider.CLAUDE).copy(phase = LlmInvocationPhase.PRE_FILTER)
        val startedFile = Files.createTempFile("llm-child-started", ".marker")
        Files.delete(startedFile)
        val invoker = ShellLlmInvoker(
            commandRenderer = object : LlmCommandRenderer {
                override fun render(request: LlmInvocationRequest): Result<RenderedLlmCommand> {
                    return Result.success(
                        RenderedLlmCommand(
                            executable = shellPath.toString(),
                            args = listOf(
                                "-c",
                                "echo started > '${startedFile.toString().replace("'", "'\\''")}'; /bin/sleep 30",
                            ),
                            environment = emptyMap(),
                            workingDirectory = request.workingDirectory,
                            timeout = request.timeout,
                            stdin = null,
                        ),
                    )
                }
            },
            processRunner = ShellProcessRunner(),
        )
        val invocation = async {
            auditor.invokeAndAudit("pre_filter", request.decisionRunContext, request, invoker)
        }
        repeat(100) {
            if (Files.exists(startedFile)) return@repeat
            delay(10.milliseconds)
        }
        assertTrue(Files.exists(startedFile))
        invocation.cancel()

        assertFailsWith<CancellationException> { invocation.await() }
        val observation = repository.findObservation("audit-run:PRE_FILTER").getOrThrow()

        assertEquals(
            me.matsumo.fukurou.trading.audit.LlmIdentityCoverageStatus.NOT_REPORTED_BY_PROVIDER,
            observation?.modelCoverageStatus,
        )
        Files.deleteIfExists(startedFile)
        Unit
    }

    @Test
    fun invokeAndAudit_marksProvenCancellationAfterStartCallbackAsStarted() = runBlocking {
        val failure = ProcessTreeTerminationProvenCancellationException(
            CancellationException("synthetic proven cancellation"),
        )

        assertProcessFailureCoverage(
            callbackInvoked = true,
            failure = failure,
            expectedCoverage = me.matsumo.fukurou.trading.audit.LlmIdentityCoverageStatus.NOT_REPORTED_BY_PROVIDER,
            expectedProcessExit = "PROVEN_EXITED",
        )
    }

    @Test
    fun invokeAndAudit_marksGenericFailureAfterStartCallbackAsStarted() = runBlocking {
        assertProcessFailureCoverage(
            callbackInvoked = true,
            failure = IllegalStateException("synthetic post-start failure"),
            expectedCoverage = me.matsumo.fukurou.trading.audit.LlmIdentityCoverageStatus.NOT_REPORTED_BY_PROVIDER,
            expectedProcessExit = "UNCONFIRMED",
        )
    }

    @Test
    fun invokeAndAudit_marksFailureBeforeStartCallbackAsNotObservable() = runBlocking {
        assertProcessFailureCoverage(
            callbackInvoked = false,
            failure = IllegalStateException("synthetic pre-start failure"),
            expectedCoverage = me.matsumo.fukurou.trading.audit.LlmIdentityCoverageStatus.NOT_OBSERVABLE_BEFORE_START,
            expectedProcessExit = "NOT_STARTED",
        )
    }

    @Test
    fun appendPhase_keepsDeterministicProducerPayloadWithoutTerminalProjection() = runBlocking {
        val commandEventLog = InMemoryCommandEventLog()
        val auditor = LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(emptySet()),
            clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
        )

        auditor.appendPhase(
            context = DecisionRunContext.EMPTY,
            phaseName = "deterministic",
            duration = Duration.ZERO,
            details = buildJsonObject { put("status", "COMPLETED") },
        ).getOrThrow()

        val payload = Json.parseToJsonElement(commandEventLog.events().single().payload).jsonObject
        assertFalse(requireNotNull(payload["details"]).jsonObject.containsKey("terminal"))
    }

    @Test
    fun invokeAndAudit_observesBindFailureAndQuarantinesManifestCleanupFailure() = runBlocking {
        LlmArtifactCleanupQuarantine.resetForTest()
        val repository = InMemoryLlmInputManifestRepository()
        val clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC)
        val cleanupFailure = IllegalStateException("synthetic unstarted manifest cleanup failure")
        val manifestPath = Files.createTempDirectory("llm-bind-failure").resolve("missing.json")
        val request = auditRequest(LlmProvider.CLAUDE).copy(
            phase = LlmInvocationPhase.PRE_FILTER,
            mcpServer = LlmMcpServerConfig(
                name = "fixture",
                command = "fixture",
                manifestId = "fixture",
                manifestPath = manifestPath,
            ),
        )
        var invoked = false
        val auditor = LlmInvocationAuditor(
            commandEventLog = InMemoryCommandEventLog(),
            redactor = SecretRedactor(emptySet()),
            clock = clock,
            phaseManifestRecorder = LlmPhaseManifestRecorder(
                repository = repository,
                cliVersionProbe = { Result.success("fixture-cli 1.0") },
                runtimeConfigSnapshot = null,
                runtimeEnvironmentSnapshot = "MODE=PAPER",
                clock = clock,
            ),
            unstartedManifestCleanup = { throw cleanupFailure },
        )
        try {
            val failure = runCatching {
                auditor.invokeAndAudit(
                    "pre_filter",
                    request.decisionRunContext,
                    request,
                    object : LlmInvoker {
                        override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
                            invoked = true
                            error("must not launch")
                        }
                    },
                )
            }.exceptionOrNull()
            val observation = repository.findObservation("audit-run:PRE_FILTER").getOrThrow()

            assertTrue(failure is java.nio.file.NoSuchFileException)
            assertTrue(requireNotNull(failure).suppressed.contains(cleanupFailure))
            assertFalse(invoked)
            assertEquals(
                me.matsumo.fukurou.trading.audit.LlmIdentityCoverageStatus.NOT_OBSERVABLE_BEFORE_START,
                observation?.modelCoverageStatus,
            )
            assertTrue(LlmArtifactCleanupQuarantine.requireClear().isFailure)
        } finally {
            LlmArtifactCleanupQuarantine.resetForTest()
            Files.deleteIfExists(manifestPath.parent)
        }
    }

    @Test
    fun invokeAndAudit_preservesProcessCleanupAndObservationFailures() = runBlocking {
        val delegate = InMemoryLlmInputManifestRepository()
        val observationFailure = IllegalStateException("synthetic observation failure")
        val appendFailure = IllegalStateException("synthetic audit append failure")
        val repository = object : LlmInputManifestRepository by delegate {
            override suspend fun appendObservation(observation: LlmPhaseObservation): Result<Unit> {
                return Result.failure(observationFailure)
            }
        }
        val clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC)
        val auditor = manifestAuditor(
            repository = repository,
            clock = clock,
            commandEventLog = object : me.matsumo.fukurou.trading.audit.CommandEventLog by InMemoryCommandEventLog() {
                override suspend fun append(event: me.matsumo.fukurou.trading.audit.CommandEvent): Result<Unit> {
                    return Result.failure(appendFailure)
                }
            },
        )
        val request = auditRequest(LlmProvider.CLAUDE).copy(phase = LlmInvocationPhase.PRE_FILTER)
        val cleanupFailure = IllegalStateException("synthetic cleanup failure")
        val invoker = object : LlmInvoker {
            override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
                return Result.success(
                    LlmInvocationResult(
                        request = request,
                        processResult = ProcessRunResult(ProcessRunStatus.EXITED, 1, "", ""),
                        responseText = "",
                        authEvidenceObserved = false,
                        cleanupFailure = cleanupFailure,
                    ),
                )
            }
        }

        val failure = auditor.invokeAndAudit(
            "pre_filter",
            request.decisionRunContext,
            request,
            invoker,
        ).exceptionOrNull()

        val terminalFailure = requireNotNull(failure)
        assertTrue(terminalFailure.suppressed.contains(cleanupFailure))
        assertTrue(terminalFailure.suppressed.contains(observationFailure))
        assertTrue(terminalFailure.suppressed.contains(appendFailure))
        assertTrue(cleanupFailure.suppressed.isEmpty())
    }

    @Test
    fun invokeAndAudit_rejectsUnsafeUsageModelWithoutEchoingIt() = runBlocking {
        val known = "KnownUsageCredential_7kN2pQ9xV4mZ8rT6"
        val values = listOf(
            known,
            "api_key=credential-value-123456789",
            "aB3dE5fG7hJ9kL2mN4pQ6rS8tV0xY2z",
            "x".repeat(me.matsumo.fukurou.trading.audit.ManifestPersistencePolicy.MAX_OBSERVED_IDENTITY_BYTES + 1),
        )

        values.forEach { value ->
            val auditor = LlmInvocationAuditor(
                commandEventLog = InMemoryCommandEventLog(),
                redactor = SecretRedactor(setOf(known)),
                clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
            )
            val usage = LlmUsageDetails(
                totalCostUsd = null,
                numTurns = null,
                durationMs = null,
                usage = null,
                modelUsages = listOf(
                    LlmModelUsage(
                        model = value,
                        usage = LlmTokenUsage(null, null, null, null, null),
                    ),
                ),
            )
            val failure = runCatching {
                auditor.invokeAndAudit(
                    "proposer",
                    DecisionRunContext.EMPTY,
                    auditRequest(LlmProvider.CLAUDE),
                    StaticAuditUsageInvoker(usage),
                )
            }.exceptionOrNull()

            assertTrue(failure != null)
            assertFalse(failure.message.orEmpty().contains(value))
        }
    }

    @Test
    fun invokeAndAudit_rejectsExcessiveModelCollectionsFromObservationAndEvent() = runBlocking {
        val modelSets = listOf(
            (0..me.matsumo.fukurou.trading.audit.ManifestPersistencePolicy.MAX_OBSERVED_MODEL_COUNT)
                .map { index -> "model-$index" },
            (0 until me.matsumo.fukurou.trading.audit.ManifestPersistencePolicy.MAX_OBSERVED_MODEL_COUNT)
                .map { index -> "model-$index-${"a".repeat(260)}" },
        )

        modelSets.forEach { models ->
            val repository = InMemoryLlmInputManifestRepository()
            val commandEventLog = InMemoryCommandEventLog()
            val clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC)
            val auditor = manifestAuditor(repository, clock, commandEventLog)
            val usage = LlmUsageDetails(
                totalCostUsd = null,
                numTurns = null,
                durationMs = null,
                usage = null,
                modelUsages = models.map { model ->
                    LlmModelUsage(model, LlmTokenUsage(null, null, null, null, null))
                },
            )
            val request = auditRequest(LlmProvider.CLAUDE).copy(phase = LlmInvocationPhase.PRE_FILTER)
            val failure = runCatching {
                auditor.invokeAndAudit("pre_filter", request.decisionRunContext, request, StaticAuditUsageInvoker(usage))
            }.exceptionOrNull()

            assertTrue(failure != null)
            assertEquals(null, repository.findObservation("audit-run:PRE_FILTER").getOrThrow())
            assertTrue(commandEventLog.events().isEmpty())
            assertFalse(failure.message.orEmpty().contains(models.last()))
        }
    }

    /**
     * issue #282 と同形の production 障害（`OUTPUT_CONTRACT`/`SCHEMA_DRIFT`、MCP handshake 失敗を模した
     * 非 JSON stdout）を、hand-built double ではなく実際の `DefaultLlmOutputParser` -> `ShellLlmInvoker` ->
     * `LlmInvocationAuditor` の配線を経由して証明する（Finding 4、double での代替不可）。
     */
    @Test
    fun invokeAndAudit_recordsRedactedOutputForCodexOutputContractThroughProductionWiring() = runBlocking {
        val commandEventLog = InMemoryCommandEventLog()
        val auditor = LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(emptySet()),
            clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
        )
        val request = auditRequest(LlmProvider.CODEX)
        val processResult = ProcessRunResult(
            status = ProcessRunStatus.EXITED,
            exitCode = 1,
            stdout = "mcp handshake failed: unexpected token at position 0",
            stderr = "codex: failed to initialize MCP server",
        )
        val shellInvoker = ShellLlmInvoker(
            commandRenderer = StaticAuditCommandRenderer,
            processRunner = FixedProcessRunner(processResult),
            outputParser = DefaultLlmOutputParser(),
        )

        auditor.invokeAndAudit(
            phaseName = "falsifier",
            context = request.decisionRunContext,
            request = request,
            llmInvoker = shellInvoker,
        )

        val details = auditedDetails(commandEventLog)

        assertEquals("OUTPUT_CONTRACT", details["failureCategory"]?.jsonPrimitive?.content)
        assertEquals("SCHEMA_DRIFT", details["providerCode"]?.jsonPrimitive?.content)
        assertEquals(processResult.stdout, details["stdout"]?.jsonPrimitive?.content)
        assertEquals(processResult.stderr, details["stderr"]?.jsonPrimitive?.content)
    }

    /**
     * 同じ配線・同じ非 JSON stdout 形状で、stdout に既知の認証 evidence 文言（"Not logged in"）が
     * 埋め込まれている場合は raw output が記録されないことを証明する（受け入れ条件2）。
     */
    @Test
    fun invokeAndAudit_omitsRawOutputForCodexOutputContractThroughProductionWiringWhenAuthEvidencePresent() = runBlocking {
        val commandEventLog = InMemoryCommandEventLog()
        val auditor = LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(emptySet()),
            clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
        )
        val request = auditRequest(LlmProvider.CODEX)
        val processResult = ProcessRunResult(
            status = ProcessRunStatus.EXITED,
            exitCode = 1,
            stdout = "Not logged in\nmcp handshake failed: unexpected token at position 0",
            stderr = "codex: failed to initialize MCP server",
        )
        val shellInvoker = ShellLlmInvoker(
            commandRenderer = StaticAuditCommandRenderer,
            processRunner = FixedProcessRunner(processResult),
            outputParser = DefaultLlmOutputParser(),
        )

        auditor.invokeAndAudit(
            phaseName = "falsifier",
            context = request.decisionRunContext,
            request = request,
            llmInvoker = shellInvoker,
        )

        val details = auditedDetails(commandEventLog)

        assertEquals("OUTPUT_CONTRACT", details["failureCategory"]?.jsonPrimitive?.content)
        assertFalse(details.containsKey("stdout"))
        assertFalse(details.containsKey("stderr"))
    }

    private fun manifestAuditor(
        repository: LlmInputManifestRepository,
        clock: Clock,
        commandEventLog: me.matsumo.fukurou.trading.audit.CommandEventLog = InMemoryCommandEventLog(),
    ): LlmInvocationAuditor {
        return LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(emptySet()),
            clock = clock,
            phaseManifestRecorder = LlmPhaseManifestRecorder(
                repository = repository,
                cliVersionProbe = { Result.success("fixture-cli 1.0") },
                runtimeConfigSnapshot = null,
                runtimeEnvironmentSnapshot = "MODE=PAPER",
                clock = clock,
            ),
        )
    }

    private suspend fun assertProcessFailureCoverage(
        callbackInvoked: Boolean,
        failure: Throwable,
        expectedCoverage: me.matsumo.fukurou.trading.audit.LlmIdentityCoverageStatus,
        expectedProcessExit: String,
    ) {
        val repository = InMemoryLlmInputManifestRepository()
        val commandEventLog = InMemoryCommandEventLog()
        val clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC)
        val request = auditRequest(LlmProvider.CLAUDE).copy(phase = LlmInvocationPhase.PRE_FILTER)
        val invoker = ShellLlmInvoker(
            commandRenderer = StaticAuditCommandRenderer,
            processRunner = FailingStartAwareProcessRunner(callbackInvoked, failure),
        )

        val result = runCatching {
            manifestAuditor(repository, clock, commandEventLog)
                .invokeAndAudit("pre_filter", request.decisionRunContext, request, invoker)
                .getOrThrow()
        }
        val observation = repository.findObservation("audit-run:PRE_FILTER").getOrThrow()

        assertSame(failure, result.exceptionOrNull())
        assertEquals(expectedCoverage, observation?.modelCoverageStatus)
        val payload = Json.parseToJsonElement(commandEventLog.events().single().payload).jsonObject
        assertTerminal(
            requireNotNull(payload["details"]).jsonObject,
            "NOT_APPLICABLE",
            expectedProcessExit,
            "COMPLETED",
        )
        LlmProcessTreeTerminationRegistry.resolve(request.invocationId)
    }

    private fun assertTerminal(
        details: kotlinx.serialization.json.JsonObject,
        semanticCommit: String,
        processExit: String,
        cleanup: String,
    ) {
        val terminal = requireNotNull(details["terminal"]).jsonObject
        assertEquals(semanticCommit, terminal["semanticCommit"]?.jsonPrimitive?.content)
        assertEquals(processExit, terminal["processExit"]?.jsonPrimitive?.content)
        assertEquals(cleanup, terminal["cleanup"]?.jsonPrimitive?.content)
    }

    private fun assumeProcessDescendantEnumerationIsAvailable() {
        val available = runCatching {
            ProcessHandle.current().descendants().use { descendants -> descendants.count() }
        }.isSuccess

        assumeTrue("ProcessHandle descendant enumeration is unavailable in this environment.", available)
    }
}

private object StaticAuditCommandRenderer : LlmCommandRenderer {
    override fun render(request: LlmInvocationRequest): Result<RenderedLlmCommand> {
        return Result.success(
            RenderedLlmCommand(
                executable = "fixture",
                args = emptyList(),
                environment = emptyMap(),
                workingDirectory = request.workingDirectory,
                timeout = request.timeout,
                stdin = null,
            ),
        )
    }
}

/**
 * production-wiring テスト用の、固定 [ProcessRunResult] を返すだけの fake `ProcessRunner`。
 * `ShellLlmInvokerTest.kt` の `RecordingProcessRunner` と同様、実際の parser を経由させるために使う。
 */
private class FixedProcessRunner(
    private val processResult: ProcessRunResult,
) : ProcessRunner {
    override suspend fun run(command: RenderedLlmCommand): Result<ProcessRunResult> {
        return Result.success(processResult)
    }
}

private class FailingStartAwareProcessRunner(
    private val callbackInvoked: Boolean,
    private val failure: Throwable,
) : ProcessStartAwareRunner {
    override suspend fun run(command: RenderedLlmCommand): Result<ProcessRunResult> {
        return run(command) {}
    }

    override suspend fun run(command: RenderedLlmCommand, onStarted: () -> Unit): Result<ProcessRunResult> {
        if (callbackInvoked) onStarted()

        return Result.failure(failure)
    }
}

private class StaticAuditUsageInvoker(private val usage: LlmUsageDetails) : LlmInvoker {
    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        return Result.success(
            LlmInvocationResult(
                request = request,
                processResult = ProcessRunResult(ProcessRunStatus.EXITED, 0, "", ""),
                responseText = "",
                authEvidenceObserved = false,
                usage = usage,
            ),
        )
    }
}

private suspend fun auditedDetails(commandEventLog: InMemoryCommandEventLog): kotlinx.serialization.json.JsonObject {
    val payload = Json.parseToJsonElement(commandEventLog.events().single().payload).jsonObject
    return requireNotNull(payload["details"]).jsonObject
}

/**
 * Codex の raw output allowlist を検証するための、process 結果と adapter failure を自由に組める invoker。
 */
private class ConfigurableAuditLlmInvoker(
    private val processResult: ProcessRunResult,
    private val providerFailure: LlmProviderFailure? = null,
    private val cleanupFailure: Throwable? = null,
    private val authEvidenceObserved: Boolean = false,
) : LlmInvoker {
    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        return Result.success(
            LlmInvocationResult(
                request = request,
                processResult = processResult,
                responseText = "response",
                authEvidenceObserved = authEvidenceObserved,
                usage = null,
                providerFailure = providerFailure,
                cleanupFailure = cleanupFailure,
            ),
        )
    }
}

/**
 * `DefaultLlmOutputParser.parseCodex()` が `providerFailure=null`（完全な成功 turn）を返す、
 * 有効な Codex JSONL event stream。lifecycle failure ガードのテストで、実際に production の
 * parser を通しても到達しうる状態を模すために使う。
 */
private val COMPLETE_SUCCESSFUL_CODEX_EVENT_STREAM = """
    {"type":"thread.started","thread_id":"thread-1"}
    {"type":"item.completed","item":{"type":"agent_message","text":"final response"}}
    {"type":"turn.completed","usage":{"input_tokens":10,"cached_input_tokens":0,"output_tokens":5,"reasoning_output_tokens":0}}
""".trimIndent()

private fun auditRequest(provider: LlmProvider): LlmInvocationRequest {
    return LlmInvocationRequest(
        invocationId = "audit-run",
        provider = provider,
        phase = LlmInvocationPhase.FALSIFIER,
        prompt = "test prompt",
        timeout = Duration.ofSeconds(60),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        decisionRunContext = DecisionRunContext.EMPTY,
        mcpServer = null,
        environment = emptyMap(),
        toolPolicy = me.matsumo.fukurou.trading.invoker.ToolPolicy(emptySet(), emptyList()),
    )
}

private class StaticStructuredAuditLlmInvoker(
    private val usage: LlmUsageDetails,
) : LlmInvoker {
    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        return Result.success(
            LlmInvocationResult(
                request = request,
                processResult = ProcessRunResult(
                    status = ProcessRunStatus.EXITED,
                    exitCode = 1,
                    stdout = """
                        {"type":"thread.started","prompt":"private trading strategy"}
                        {"type":"item.completed","item":{"type":"tool_call","name":"submit_decision","arguments":{"path":"/private/session/path"}}}
                    """.trimIndent(),
                    stderr = "401 unauthorized at /private/session/path",
                ),
                responseText = "partial response",
                authEvidenceObserved = false,
                usage = usage,
                providerFailure = me.matsumo.fukurou.trading.invoker.LlmProviderFailure(
                    me.matsumo.fukurou.trading.invoker.LlmProviderFailureCategory.AUTHENTICATION,
                    "UNAUTHORIZED",
                    me.matsumo.fukurou.trading.invoker.CODEX_OUTPUT_ADAPTER_VERSION,
                ),
            ),
        )
    }
}

private class CleanupFailingAuditLlmInvoker(
    private val usage: LlmUsageDetails,
    private val cleanupFailure: Throwable,
) : LlmInvoker {
    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        return Result.success(
            LlmInvocationResult(
                request = request,
                processResult = ProcessRunResult(
                    status = ProcessRunStatus.EXITED,
                    exitCode = 0,
                    stdout = "private raw output",
                    stderr = "",
                ),
                responseText = "semantic response",
                authEvidenceObserved = false,
                usage = usage,
                cleanupFailure = cleanupFailure,
            ),
        )
    }
}

/**
 * auditor test 用の失敗する LLM invoker。
 */
private class FailingAuditLlmInvoker(
    private val failure: Throwable,
) : LlmInvoker {
    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        return Result.failure(failure)
    }
}

private class StaticAuditLlmInvoker(
    private val stdout: String,
) : LlmInvoker {

    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        return Result.success(
            LlmInvocationResult(
                request = request,
                processResult = ProcessRunResult(
                    status = ProcessRunStatus.EXITED,
                    exitCode = 0,
                    stdout = stdout,
                    stderr = "",
                ),
                responseText = stdout,
                authEvidenceObserved = false,
                usage = LlmUsageParser.parseClaudeStdout(stdout),
            ),
        )
    }
}
