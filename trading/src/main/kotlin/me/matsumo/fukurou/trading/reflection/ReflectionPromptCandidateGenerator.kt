package me.matsumo.fukurou.trading.reflection

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationFinish
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationOutcome
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRepository
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_CANCELLED
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LlmRunFinish
import me.matsumo.fukurou.trading.evaluation.LlmInvocationTimedOutException
import me.matsumo.fukurou.trading.evaluation.LlmRunTerminalCause
import me.matsumo.fukurou.trading.evaluation.LlmRunRepository
import me.matsumo.fukurou.trading.evaluation.LlmRunStart
import me.matsumo.fukurou.trading.invoker.CODEX_FAILURE_DETAILS_OMITTED
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.runner.CHILD_ENV_ALLOWLIST
import me.matsumo.fukurou.trading.runner.LlmInvocationAuditor
import me.matsumo.fukurou.trading.runner.MAX_DAILY_INVOCATION_COUNT_WINDOW
import me.matsumo.fukurou.trading.runner.MAX_INVOCATION_COUNT_WINDOW
import me.matsumo.fukurou.trading.runner.SECRET_ENV_KEYS
import me.matsumo.fukurou.trading.runner.SecretRedactor
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * PromptCandidates 生成の LLM 実行境界。
 *
 * @param llmInvoker LLM CLI 起動境界
 * @param auditor phase audit writer
 * @param workingDirectory LLM CLI process の working directory
 * @param parentEnvironment LLM CLI child process に渡す親環境
 * @param redactor LLM 出力と note 保存前の redaction helper
 */
data class ReflectionPromptCandidateLlmRuntime(
    val llmInvoker: LlmInvoker,
    val auditor: LlmInvocationAuditor,
    val workingDirectory: Path,
    val parentEnvironment: Map<String, String>,
    val redactor: SecretRedactor,
)

/**
 * PromptCandidates 生成の永続化境界。
 *
 * @param llmRunRepository llm_runs 保存先
 * @param launchReservationRepository 低優先 reflection 予算 gate
 */
data class ReflectionPromptCandidatePersistence(
    val llmRunRepository: LlmRunRepository,
    val launchReservationRepository: LlmLaunchReservationRepository,
)

/**
 * PromptCandidates generator の実行時依存。
 *
 * @param llm LLM 実行境界
 * @param persistence 永続化境界
 * @param clock retry / audit timestamp 用 clock
 * @param idGenerator invocation ID generator
 * @param logger 運用ログ出力
 */
data class ReflectionPromptCandidateGeneratorRuntime(
    val llm: ReflectionPromptCandidateLlmRuntime,
    val persistence: ReflectionPromptCandidatePersistence,
    val clock: Clock = Clock.systemUTC(),
    val idGenerator: () -> UUID = { UUID.randomUUID() },
    val logger: (String) -> Unit = {},
)

/**
 * 週次 reflection から人間承認前提の PromptCandidates note を生成する LLM-backed generator。
 *
 * @param tradingConfig reflection / runner 設定
 * @param runtime generator runtime boundary
 */
class ReflectionPromptCandidateGenerator(
    private val tradingConfig: TradingBotConfig,
    private val runtime: ReflectionPromptCandidateGeneratorRuntime,
) {

    private val llmInvoker: LlmInvoker
        get() = runtime.llm.llmInvoker

    private val llmRunRepository: LlmRunRepository
        get() = runtime.persistence.llmRunRepository

    private val launchReservationRepository: LlmLaunchReservationRepository
        get() = runtime.persistence.launchReservationRepository

    private val auditor: LlmInvocationAuditor
        get() = runtime.llm.auditor

    private val workingDirectory: Path
        get() = runtime.llm.workingDirectory

    private val parentEnvironment: Map<String, String>
        get() = runtime.llm.parentEnvironment

    private val redactor: SecretRedactor
        get() = runtime.llm.redactor

    private val clock: Clock
        get() = runtime.clock

    private val idGenerator: () -> UUID
        get() = runtime.idGenerator

    private val logger: (String) -> Unit
        get() = runtime.logger

    /**
     * 週次 PromptCandidates note を生成する。呼び出し不要な状態では file を返さない。
     */
    suspend fun generate(
        dataset: ReflectionDataset,
        existingState: ReflectionPromptCandidateNoteState?,
    ): Result<ReflectionPromptCandidateGeneration> {
        return try {
            Result.success(generateUnsafe(dataset, existingState))
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    private suspend fun generateUnsafe(
        dataset: ReflectionDataset,
        existingState: ReflectionPromptCandidateNoteState?,
    ): ReflectionPromptCandidateGeneration {
        val now = clock.instant()
        val skipReason = skipReason(existingState, now)

        if (skipReason != null) {
            return ReflectionPromptCandidateGeneration(emptyList(), skipReason)
        }
        if (dataset.weekly.truncation.any) {
            return terminalStatusFile(
                dataset = dataset,
                status = ReflectionPromptCandidateGenerationStatus.INPUT_TRUNCATED,
                attemptCount = existingState?.attemptCount ?: 0,
                nextRetryAfter = null,
                rejectionReason = "input_truncated",
            )
        }

        val invocationId = idGenerator().toString()
        val reserved = reserveReflectionInvocation(dataset, invocationId, now)

        if (reserved !is LlmLaunchReservationOutcome.Reserved) {
            return budgetDeferredFile(
                dataset = dataset,
                existingState = existingState,
                now = now,
                rejectionReason = (reserved as LlmLaunchReservationOutcome.Rejected).reason.name.lowercase(),
            )
        }

        return invokePromptCandidateLlm(
            dataset = dataset,
            existingState = existingState,
            invocationId = invocationId,
        )
    }

    private suspend fun invokePromptCandidateLlm(
        dataset: ReflectionDataset,
        existingState: ReflectionPromptCandidateNoteState?,
        invocationId: String,
    ): ReflectionPromptCandidateGeneration {
        val attemptCount = (existingState?.attemptCount ?: 0) + 1
        val startedAt = clock.instant()
        val start = LlmRunStart(
            invocationId = invocationId,
            mode = tradingConfig.mode,
            symbol = tradingConfig.symbol,
            triggerKind = LlmDaemonTriggerKind.REFLECTION,
            startedAt = startedAt,
        )

        recordLlmRunStarted(start)

        return try {
            val request = llmRequest(
                dataset = dataset,
                invocationId = invocationId,
                startedAt = startedAt,
            )
            val auditResult = auditor.invokeAndAudit(
                phaseName = REFLECTION_PHASE_NAME,
                context = request.decisionRunContext,
                request = request,
                llmInvoker = llmInvoker,
            )
            val failure = auditResult.exceptionOrNull()

            if (failure != null) {
                finishFailedRun(start, failure)

                return failedAttemptFile(dataset, attemptCount, failure)
            }

            val responseText = auditResult.getOrThrow().invocationResult.responseText
            val validation = validateOutput(responseText, dataset)

            promptCandidateFileForValidation(
                dataset = dataset,
                attemptCount = attemptCount,
                start = start,
                validation = validation,
            )
        } catch (throwable: CancellationException) {
            finishFailedRun(start, throwable)

            throw throwable
        } catch (throwable: Throwable) {
            finishFailedRun(start, throwable)

            failedAttemptFile(dataset, attemptCount, throwable)
        }
    }

    private suspend fun promptCandidateFileForValidation(
        dataset: ReflectionDataset,
        attemptCount: Int,
        start: LlmRunStart,
        validation: PromptCandidateValidation,
    ): ReflectionPromptCandidateGeneration {
        return when (validation) {
            is PromptCandidateValidation.Valid -> {
                finishSucceededRun(start, ReflectionPromptCandidateGenerationStatus.GENERATED.wireValue)

                generatedFile(dataset, attemptCount, validation.candidates)
            }
            is PromptCandidateValidation.Invalid -> terminalStatusFile(
                dataset = dataset,
                status = ReflectionPromptCandidateGenerationStatus.INVALID_OUTPUT,
                attemptCount = attemptCount,
                nextRetryAfter = null,
                rejectionReason = validation.reason,
            ).also {
                finishSucceededRun(start, ReflectionPromptCandidateGenerationStatus.INVALID_OUTPUT.wireValue)
            }
        }
    }

    private fun skipReason(
        existingState: ReflectionPromptCandidateNoteState?,
        now: Instant,
    ): ReflectionPromptCandidateSkipReason? {
        if (existingState == null) {
            return null
        }
        if (existingState.status.terminal) {
            return ReflectionPromptCandidateSkipReason.TERMINAL_STATUS
        }

        val nextRetryAfter = existingState.nextRetryAfter ?: return null

        return if (now.isBefore(nextRetryAfter)) {
            ReflectionPromptCandidateSkipReason.BACKOFF_ACTIVE
        } else {
            null
        }
    }

    private suspend fun reserveReflectionInvocation(
        dataset: ReflectionDataset,
        invocationId: String,
        observedAt: Instant,
    ): LlmLaunchReservationOutcome {
        return launchReservationRepository.tryReserve(
            LlmLaunchReservationRequest(
                invocationId = invocationId,
                triggerKind = LlmDaemonTriggerKind.REFLECTION,
                triggerKey = "reflection:${dataset.weekId}",
                reservedAt = observedAt,
                runnerConfig = tradingConfig.runner,
                hourlyWindow = MAX_INVOCATION_COUNT_WINDOW,
                dailyWindow = MAX_DAILY_INVOCATION_COUNT_WINDOW,
                activeReservationStaleAfter = tradingConfig.daemon.launchReservationStaleAfter,
            ),
        ).getOrThrow()
    }

    private suspend fun recordLlmRunStarted(start: LlmRunStart) {
        llmRunRepository.insertRunning(start)
            .onFailure { throwable ->
                logger(
                    "reflection llm run start record failed invocation=${start.invocationId} " +
                        "error=${throwable.javaClass.simpleName}",
                )
            }
    }

    private suspend fun finishSucceededRun(start: LlmRunStart, status: String) {
        finishRun(
            start = start,
            status = REFLECTION_LLM_RUN_STATUS_SUCCEEDED,
            reservationStatus = LlmLaunchReservationStatus.FINISHED,
            reason = LlmRunTerminalCause.NORMAL_COMPLETION.name,
            cause = null,
        )
    }

    private suspend fun finishFailedRun(start: LlmRunStart, cause: Throwable) {
        val status = if (cause is CancellationException) LLM_RUN_STATUS_CANCELLED else LLM_RUN_STATUS_FAILED

        finishRun(
            start = start,
            status = status,
            reservationStatus = LlmLaunchReservationStatus.FAILED,
            reason = terminalCauseFor(cause).name,
            cause = cause,
        )
    }

    private suspend fun finishRun(
        start: LlmRunStart,
        status: String,
        reservationStatus: LlmLaunchReservationStatus,
        reason: String,
        cause: Throwable?,
    ) {
        val finishedAt = clock.instant()
        val redactedMessage = cause?.let { throwable ->
            if (tradingConfig.reflection.promptCandidateProvider == LlmProvider.CODEX) {
                CODEX_FAILURE_DETAILS_OMITTED
            } else {
                redactor.redactAndTruncate("${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}")
            }
        }

        llmRunRepository.finish(
            LlmRunFinish(
                invocationId = start.invocationId,
                mode = start.mode,
                symbol = start.symbol,
                triggerKind = start.triggerKind,
                status = status,
                startedAt = start.startedAt,
                finishedAt = finishedAt,
                errorMessage = redactedMessage,
                terminalCause = cause?.let(::terminalCauseFor) ?: LlmRunTerminalCause.NORMAL_COMPLETION,
            ),
        ).onFailure { throwable ->
            logger(
                "reflection llm run finish record failed invocation=${start.invocationId} " +
                    "error=${throwable.javaClass.simpleName}",
            )
        }
        launchReservationRepository.finish(
            LlmLaunchReservationFinish(
                invocationId = start.invocationId,
                status = reservationStatus,
                reason = reason,
                finishedAt = finishedAt,
            ),
        ).onFailure { throwable ->
            logger(
                "reflection reservation finish failed invocation=${start.invocationId} " +
                    "error=${throwable.javaClass.simpleName}",
            )
        }
    }

    private fun terminalCauseFor(cause: Throwable): LlmRunTerminalCause = when (cause) {
        is CancellationException -> LlmRunTerminalCause.CALLER_CANCELLED
        is LlmInvocationTimedOutException -> LlmRunTerminalCause.TIMED_OUT
        else -> LlmRunTerminalCause.RUNNER_FAILED
    }

    private fun llmRequest(
        dataset: ReflectionDataset,
        invocationId: String,
        startedAt: Instant,
    ): LlmInvocationRequest {
        val provider = tradingConfig.reflection.promptCandidateProvider
        val prompt = prompt(dataset)
        val context = DecisionRunContext(
            decisionRunId = invocationId,
            llmProvider = provider.name.lowercase(),
            promptHash = prompt.sha256(),
            systemPromptVersion = REFLECTION_PROMPT_VERSION,
            marketSnapshotId = "reflection-${dataset.weekId}",
        )

        return LlmInvocationRequest(
            invocationId = invocationId,
            provider = provider,
            phase = LlmInvocationPhase.REFLECTION,
            prompt = prompt,
            timeout = tradingConfig.reflection.promptCandidateTimeout,
            workingDirectory = workingDirectory,
            decisionRunContext = context,
            mcpServer = null,
            environment = reflectionChildEnvironment(context, startedAt),
            allowedTools = emptyList(),
        )
    }

    private fun prompt(dataset: ReflectionDataset): String {
        val weekly = dataset.weekly
        val actionCounts = weekly.actionCounts.joinToString(", ") { actionCount ->
            "${actionCount.action}=${actionCount.count}"
        }
        val setupTags = weekly.closedTrades.asSequence()
            .flatMap { fact -> fact.setupTags.asSequence() }
            .map { tag -> tag.trim() }
            .filter { tag -> tag.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString(", ")

        return """
            |You are the Fukurou weekly reflection prompt-candidate generator.
            |Return only valid JSON. Do not wrap it in Markdown.
            |All candidates must target SystemPromptV1 and must require human approval.
            |Do not apply any prompt or trading-config change.
            |
            |Required JSON schema:
            |{
            |  "candidates": [
            |    {
            |      "id": "prompt-candidate-${dataset.weekId}-001",
            |      "title": "short Japanese title",
            |      "target": "SystemPromptV1",
            |      "problem": "observed issue",
            |      "evidence": ["decision_runs / decisions / closed_trades / llm_known_cost_usd / cost coverage / setup tag evidence"],
            |      "proposedChangeJa": "候補文",
            |      "expectedImpact": "expected impact",
            |      "risk": "risk",
            |      "requiresHumanApproval": true
            |    }
            |  ],
            |  "narrativeDrift": [],
            |  "tagTaxonomySuggestions": []
            |}
            |
            |Deterministic weekly report ID: ${weekly.period.id}
            |period_start: ${weekly.period.from}
            |period_end: ${weekly.period.toExclusive}
            |decision_runs: ${weekly.decisionRunCount}
            |decisions: ${weekly.decisions.size}
            |closed_trades: ${weekly.closedTrades.size}
            |llm_runs: ${weekly.llmRuns.size}
            |action_counts: $actionCounts
            |llm_phase_count: ${weekly.llmPhaseUsages.size}
            |setup_tags: ${setupTags.ifBlank { "none" }}
            |truncated: ${weekly.truncation.any}
            |
            |Use only the deterministic fields above as evidence. If there is not enough evidence, return an empty candidates array.
        """.trimMargin()
    }

    private fun reflectionChildEnvironment(context: DecisionRunContext, startedAt: Instant): Map<String, String> {
        val baseEnvironment = parentEnvironment
            .filterKeys { key -> key in CHILD_ENV_ALLOWLIST }
            .filterKeys { key -> key.uppercase() !in SECRET_ENV_KEYS }

        return baseEnvironment + mapOf(
            "FUKUROU_INVOCATION_ID" to requireNotNull(context.decisionRunId),
            "FUKUROU_LLM_PROVIDER" to requireNotNull(context.llmProvider),
            "FUKUROU_PROMPT_HASH" to requireNotNull(context.promptHash),
            "FUKUROU_SYSTEM_PROMPT_VERSION" to requireNotNull(context.systemPromptVersion),
            "FUKUROU_MARKET_SNAPSHOT_ID" to requireNotNull(context.marketSnapshotId),
            "FUKUROU_REFLECTION_STARTED_AT" to startedAt.toString(),
        )
    }

    private fun failedAttemptFile(
        dataset: ReflectionDataset,
        attemptCount: Int,
        failure: Throwable,
    ): ReflectionPromptCandidateGeneration {
        val maxAttempts = tradingConfig.reflection.promptCandidateMaxAttemptsPerPeriod
        val status = if (attemptCount >= maxAttempts) {
            ReflectionPromptCandidateGenerationStatus.FAILED_BACKOFF
        } else {
            ReflectionPromptCandidateGenerationStatus.LLM_FAILED
        }
        val nextRetryAfter = if (status == ReflectionPromptCandidateGenerationStatus.LLM_FAILED) {
            clock.instant().plus(tradingConfig.reflection.promptCandidateRetryBackoff)
        } else {
            null
        }

        return terminalStatusFile(
            dataset = dataset,
            status = status,
            attemptCount = attemptCount,
            nextRetryAfter = nextRetryAfter,
            rejectionReason = failure.javaClass.simpleName,
        )
    }

    private fun budgetDeferredFile(
        dataset: ReflectionDataset,
        existingState: ReflectionPromptCandidateNoteState?,
        now: Instant,
        rejectionReason: String,
    ): ReflectionPromptCandidateGeneration {
        return terminalStatusFile(
            dataset = dataset,
            status = ReflectionPromptCandidateGenerationStatus.BUDGET_DEFERRED,
            attemptCount = existingState?.attemptCount ?: 0,
            nextRetryAfter = now.plus(tradingConfig.reflection.promptCandidateBudgetRetryDelay),
            rejectionReason = rejectionReason,
        )
    }

    private fun generatedFile(
        dataset: ReflectionDataset,
        attemptCount: Int,
        candidates: List<ReflectionPromptCandidate>,
    ): ReflectionPromptCandidateGeneration {
        val state = ReflectionPromptCandidateNoteState(
            status = ReflectionPromptCandidateGenerationStatus.GENERATED,
            attemptCount = attemptCount,
            nextRetryAfter = null,
        )

        return statusFile(
            dataset = dataset,
            state = state,
            candidates = candidates,
            rejectionReason = null,
        )
    }

    private fun terminalStatusFile(
        dataset: ReflectionDataset,
        status: ReflectionPromptCandidateGenerationStatus,
        attemptCount: Int,
        nextRetryAfter: Instant?,
        rejectionReason: String,
    ): ReflectionPromptCandidateGeneration {
        val state = ReflectionPromptCandidateNoteState(
            status = status,
            attemptCount = attemptCount,
            nextRetryAfter = nextRetryAfter,
        )

        return statusFile(
            dataset = dataset,
            state = state,
            candidates = emptyList(),
            rejectionReason = rejectionReason,
        )
    }

    private fun statusFile(
        dataset: ReflectionDataset,
        state: ReflectionPromptCandidateNoteState,
        candidates: List<ReflectionPromptCandidate>,
        rejectionReason: String?,
    ): ReflectionPromptCandidateGeneration {
        val file = ReflectionMarkdownFile(
            relativePath = "Knowledge/PromptCandidates/${dataset.weekId}.md",
            content = promptCandidatesMarkdown(
                dataset = dataset,
                state = state,
                candidates = candidates,
                rejectionReason = rejectionReason,
            ),
        )

        return ReflectionPromptCandidateGeneration(
            files = listOf(file),
            skipReason = null,
        )
    }

    private fun validateOutput(rawOutput: String, dataset: ReflectionDataset): PromptCandidateValidation {
        val redactedOutput = redactor.redact(rawOutput)

        if (redactedOutput.containsSuspiciousSecret()) {
            return PromptCandidateValidation.Invalid("suspicious_secret_output")
        }

        val root = runCatching {
            reflectionPromptCandidateJson.parseToJsonElement(redactedOutput).jsonObject
        }.getOrElse {
            return PromptCandidateValidation.Invalid("invalid_json")
        }
        val candidatesArray = root["candidates"] as? JsonArray
            ?: return PromptCandidateValidation.Invalid("missing_candidates")
        val evidenceTerms = dataset.weekly.evidenceTerms()
        val candidates = candidatesArray.mapIndexed { index, element ->
            val candidateObject = element as? JsonObject
                ?: return PromptCandidateValidation.Invalid("invalid_candidate_$index")

            candidateObject.toPromptCandidateOrInvalid(index, evidenceTerms)
                ?: return PromptCandidateValidation.Invalid("invalid_candidate_$index")
        }

        return PromptCandidateValidation.Valid(candidates)
    }
}

/**
 * PromptCandidates note の生成状態。
 *
 * @param wireValue frontmatter に保存する値
 * @param terminal 同じ週で再試行しない状態か
 */
enum class ReflectionPromptCandidateGenerationStatus(
    val wireValue: String,
    val terminal: Boolean,
) {
    /**
     * LLM output を候補として採用した。
     */
    GENERATED("generated", terminal = true),

    /**
     * LLM output が schema を満たさないため採用しなかった。
     */
    INVALID_OUTPUT("invalid_output", terminal = true),

    /**
     * 入力 data が切り詰められており LLM を呼ばなかった。
     */
    INPUT_TRUNCATED("input_truncated", terminal = true),

    /**
     * 低優先 budget gate により LLM を呼ばなかった。
     */
    BUDGET_DEFERRED("budget_deferred", terminal = false),

    /**
     * LLM process が失敗し、backoff 後に再試行する。
     */
    LLM_FAILED("llm_failed", terminal = false),

    /**
     * 期間内の最大試行回数に達した。
     */
    FAILED_BACKOFF("failed_backoff", terminal = true),
    ;

    companion object {
        /**
         * frontmatter value から status を復元する。
         */
        fun fromWireValue(value: String): ReflectionPromptCandidateGenerationStatus? {
            return entries.firstOrNull { status -> status.wireValue == value }
        }
    }
}

/**
 * PromptCandidates note frontmatter の retry state。
 *
 * @param status generation status
 * @param attemptCount LLM を実際に呼んだ回数
 * @param nextRetryAfter 次に再試行可能になる時刻
 */
data class ReflectionPromptCandidateNoteState(
    val status: ReflectionPromptCandidateGenerationStatus,
    val attemptCount: Int,
    val nextRetryAfter: Instant?,
)

/**
 * PromptCandidates 生成結果。
 *
 * @param files vault へ書く Markdown file
 * @param skipReason LLM 呼び出しも file 書き込みも不要だった理由
 */
data class ReflectionPromptCandidateGeneration(
    val files: List<ReflectionMarkdownFile>,
    val skipReason: ReflectionPromptCandidateSkipReason?,
)

/**
 * PromptCandidates 生成を skip した理由。
 */
enum class ReflectionPromptCandidateSkipReason {
    /**
     * 既存 note が terminal status だった。
     */
    TERMINAL_STATUS,

    /**
     * 既存 note の next_retry_after 前だった。
     */
    BACKOFF_ACTIVE,
}

/**
 * 人間承認前提の system prompt 変更候補。
 *
 * @param id candidate ID
 * @param title title
 * @param target 対象 prompt 名
 * @param problem 問題の要約
 * @param evidence deterministic report に基づく根拠
 * @param proposedChangeJa prompt へ追加する候補文
 * @param expectedImpact 期待される効果
 * @param risk 想定リスク
 * @param requiresHumanApproval 人間承認が必要か
 */
data class ReflectionPromptCandidate(
    val id: String,
    val title: String,
    val target: String,
    val problem: String,
    val evidence: List<String>,
    val proposedChangeJa: String,
    val expectedImpact: String,
    val risk: String,
    val requiresHumanApproval: Boolean,
)

private sealed interface PromptCandidateValidation {
    /**
     * schema validation に成功した。
     */
    data class Valid(
        val candidates: List<ReflectionPromptCandidate>,
    ) : PromptCandidateValidation

    /**
     * schema validation に失敗した。
     */
    data class Invalid(
        val reason: String,
    ) : PromptCandidateValidation
}

private fun JsonObject.toPromptCandidateOrInvalid(index: Int, evidenceTerms: Set<String>): ReflectionPromptCandidate? {
    val fields = promptCandidateFieldsOrNull() ?: return null

    if (!fields.hasValidHeader()) return null
    if (!fields.requiresHumanApproval) return null
    if (!fields.hasReportEvidence(evidenceTerms)) return null
    if (fields.hasBlankDetails()) return null

    return fields.toPromptCandidate(index)
}

private fun JsonObject.promptCandidateFieldsOrNull(): PromptCandidateFields? {
    return PromptCandidateFields(
        id = requiredString("id") ?: return null,
        title = requiredString("title") ?: return null,
        target = requiredString("target") ?: return null,
        problem = requiredString("problem") ?: return null,
        evidence = requiredStringArray("evidence") ?: return null,
        proposedChangeJa = requiredString("proposedChangeJa") ?: return null,
        expectedImpact = requiredString("expectedImpact") ?: return null,
        risk = requiredString("risk") ?: return null,
        requiresHumanApproval = (this["requiresHumanApproval"] as? JsonPrimitive)?.booleanOrNull ?: return null,
    )
}

private fun PromptCandidateFields.hasValidHeader(): Boolean {
    if (id.isBlank()) return false
    if (title.isBlank()) return false

    return target == "SystemPromptV1"
}

private fun PromptCandidateFields.hasReportEvidence(evidenceTerms: Set<String>): Boolean {
    if (evidence.isEmpty()) {
        return false
    }

    return evidence.any { value ->
        evidenceTerms.any { term -> value.contains(term, ignoreCase = true) }
    }
}

private fun PromptCandidateFields.hasBlankDetails(): Boolean {
    return listOf(problem, proposedChangeJa, expectedImpact, risk)
        .any { value -> value.isBlank() }
}

private fun PromptCandidateFields.toPromptCandidate(index: Int): ReflectionPromptCandidate {
    return ReflectionPromptCandidate(
        id = id.ifBlank { "prompt-candidate-$index" },
        title = title,
        target = target,
        problem = problem,
        evidence = evidence,
        proposedChangeJa = proposedChangeJa,
        expectedImpact = expectedImpact,
        risk = risk,
        requiresHumanApproval = requiresHumanApproval,
    )
}

private data class PromptCandidateFields(
    val id: String,
    val title: String,
    val target: String,
    val problem: String,
    val evidence: List<String>,
    val proposedChangeJa: String,
    val expectedImpact: String,
    val risk: String,
    val requiresHumanApproval: Boolean,
)

private fun JsonObject.requiredString(key: String): String? {
    return (this[key] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
}

private fun JsonObject.requiredStringArray(key: String): List<String>? {
    val array = this[key] as? JsonArray ?: return null

    return array.map { element ->
        (element as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?: return null
    }
}

private fun ReflectionWindowData.evidenceTerms(): Set<String> {
    val actionNames = actionCounts.map { actionCount -> actionCount.action }
    val setupTags = closedTrades.flatMap { fact -> fact.setupTags }

    return (
        listOf(
            period.id,
            "decision_runs",
            "decisions",
            "closed_trades",
            "llm_runs",
            "llm_known_cost_usd",
            "setup",
            "truncated",
        ) + actionNames + setupTags
        )
        .map { value -> value.trim() }
        .filter { value -> value.isNotBlank() }
        .toSet()
}

private fun promptCandidatesMarkdown(
    dataset: ReflectionDataset,
    state: ReflectionPromptCandidateNoteState,
    candidates: List<ReflectionPromptCandidate>,
    rejectionReason: String?,
): String {
    return buildString {
        appendLine("---")
        appendLine("type: \"prompt_candidates\"")
        appendLine("week: ${dataset.weekId.yamlQuoted()}")
        appendLine("generation_status: ${state.status.wireValue.yamlQuoted()}")
        appendLine("attempt_count: ${state.attemptCount}")
        appendLine("next_retry_after: ${state.nextRetryAfter?.toString()?.yamlQuoted() ?: "null"}")
        appendLine("candidate_count: ${candidates.size}")
        appendLine("requires_human_approval: true")
        appendLine("tags:")
        appendLine("  - \"reflection\"")
        appendLine("  - \"prompt-candidates\"")
        appendLine("  - ${dataset.weekly.period.id.yamlQuoted()}")
        appendLine("---")
        appendLine()
        appendLine("# Prompt Candidates ${dataset.weekId}")
        appendLine()
        appendLine("- generation_status: ${state.status.wireValue}")
        appendLine("- attempt_count: ${state.attemptCount}")
        appendLine("- next_retry_after: ${state.nextRetryAfter ?: "null"}")
        rejectionReason?.let { reason ->
            appendLine("- rejection_reason: ${reason.markdownText()}")
        }
        appendLine()
        appendLine("## Candidates")
        appendLine()
        if (candidates.isEmpty()) {
            appendLine("- none")
            appendLine()
            return@buildString
        }

        candidates.forEach { candidate ->
            appendLine("### ${candidate.id.markdownText()}")
            appendLine()
            appendLine("- title: ${candidate.title.markdownText()}")
            appendLine("- target: ${candidate.target.markdownText()}")
            appendLine("- requires_human_approval: ${candidate.requiresHumanApproval}")
            appendLine("- problem: ${candidate.problem.markdownText()}")
            appendLine("- expected_impact: ${candidate.expectedImpact.markdownText()}")
            appendLine("- risk: ${candidate.risk.markdownText()}")
            appendLine("- evidence:")
            candidate.evidence.forEach { evidence ->
                appendLine("  - ${evidence.markdownText()}")
            }
            appendLine()
            appendLine("```text")
            appendLine(candidate.proposedChangeJa.trim())
            appendLine("```")
            appendLine()
        }
    }
}

private fun String.containsSuspiciousSecret(): Boolean {
    return SUSPICIOUS_SECRET_PATTERNS.any { pattern -> pattern.containsMatchIn(this) }
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())

    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

private fun String.yamlQuoted(): String {
    return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

private fun String.markdownText(): String {
    return replace("\n", " ")
        .replace("\r", " ")
        .trim()
}

private val reflectionPromptCandidateJson = Json {
    ignoreUnknownKeys = true
}

private val SUSPICIOUS_SECRET_PATTERNS = listOf(
    Regex("(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)\\s*[:=]"),
    Regex("sk-[A-Za-z0-9_-]{16,}"),
    Regex("gh[pousr]_[A-Za-z0-9_]{20,}"),
)

private const val REFLECTION_PHASE_NAME = "reflection"
private const val REFLECTION_PROMPT_VERSION = "reflection-prompt-candidates-v1"
private const val REFLECTION_LLM_RUN_STATUS_SUCCEEDED = "SUCCEEDED"
