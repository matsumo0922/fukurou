package me.matsumo.fukurou.trading.audit

import kotlinx.coroutines.CancellationException
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.invoker.LlmCliVersionProbe
import me.matsumo.fukurou.trading.invoker.LlmCommandRendererConfig
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.McpLaunchManifestWriter
import me.matsumo.fukurou.trading.invoker.McpToolContractCatalog
import me.matsumo.fukurou.trading.invoker.renderedEffortOrNull
import java.time.Clock

/** CLI launch の直前/直後に phase manifest と observation を appendする。 */
class LlmPhaseManifestRecorder(
    private val repository: LlmInputManifestRepository,
    private val cliVersionProbe: LlmCliVersionProbe,
    private val runtimeConfigSnapshot: RuntimeConfigAuditSnapshot?,
    private val runtimeEnvironmentSnapshot: String,
    private val clock: Clock,
    private val commandRendererConfig: LlmCommandRendererConfig = LlmCommandRendererConfig(),
) {
    suspend fun appendInput(request: LlmInvocationRequest): LlmPhaseInputManifest {
        return try {
            appendInputBody(request)
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: LlmPhaseInputCaptureException) {
            throw throwable
        } catch (throwable: Throwable) {
            if (request.phase == LlmInvocationPhase.RISK_REDUCTION_ONLY) throw throwable
            throw LlmPhaseInputCaptureException(throwable)
        }
    }

    @Suppress("LongMethod")
    private suspend fun appendInputBody(request: LlmInvocationRequest): LlmPhaseInputManifest {
        val rootKind = when (request.phase) {
            LlmInvocationPhase.REFLECTION -> LlmAuditRootKind.REFLECTION
            LlmInvocationPhase.EVALUATION_REPORT -> LlmAuditRootKind.EVALUATION_REPORT
            else -> LlmAuditRootKind.DECISION_ATTEMPT
        }
        val existingRoot = repository.findRoot(request.invocationId).getOrThrow()
        if (existingRoot == null) {
            repository.appendRoot(
                LlmInvocationAuditRoot(request.invocationId, rootKind, clock.instant()),
            ).getOrThrow()
        } else {
            require(existingRoot.kind == rootKind) { "invocation audit root kind mismatch." }
        }
        val cliVersionResult = cliVersionProbe.probe(
            commandRendererConfig.versionProbeRequest(
                provider = request.provider,
                immutableFingerprint = request.environment["FUKUROU_LLM_IMAGE_DIGEST"],
            ),
        )
        val cliVersion = if (request.phase == LlmInvocationPhase.RISK_REDUCTION_ONLY) {
            cliVersionResult.getOrElse { "CLI_VERSION_UNAVAILABLE" }
        } else {
            cliVersionResult.getOrElse { throwable -> throw LlmPhaseInputCaptureException(throwable) }
        }
        val shortTools = request.allowedTools.map { tool -> tool.substringAfterLast("__") }
        McpToolContractCatalog.requireCanonical(request.phase, shortTools)
        val decisionPhase = request.phase in DECISION_PHASES
        val runManifest = if (decisionPhase) {
            requireNotNull(repository.findRun(request.invocationId).getOrThrow()) {
                "decision phase requires a persisted run manifest."
            }
        } else {
            null
        }
        val base = LlmPhaseInputManifest(
            phaseManifestId = "${request.invocationId}:${request.phase.name}",
            rootId = request.invocationId,
            invocationId = request.invocationId,
            phase = request.phase,
            prompt = request.prompt,
            role = request.phase.name,
            provider = request.provider,
            configuredModel = request.model,
            configuredEffort = request.effort,
            renderedEffort = request.effort.renderedEffortOrNull(),
            cliVersion = cliVersion,
            toolAllowlist = shortTools.sorted(),
            canonicalToolSchema = McpToolContractCatalog.canonicalSchemaBundle(request.phase),
            runtimeConfigHash = runtimeConfigSnapshot?.hash,
            runtimeConfigSnapshot = runtimeEnvironmentSnapshot,
            runManifestInvocationId = request.invocationId.takeIf { decisionPhase },
            runManifestContentHash = runManifest?.canonicalContentHash,
            materialInvocationId = request.invocationId.takeIf { decisionPhase },
            materialContentHash = runManifest?.materialContentHash,
            notApplicableReason = if (decisionPhase) null else request.phase.notApplicableReason(),
            capturedAt = clock.instant(),
            effectiveInvocationHash = "",
        )
        val manifest = base.copy(effectiveInvocationHash = LlmManifestJsonCodec.effectiveInvocationHash(base))
        repository.appendPhase(manifest).getOrThrow()

        return manifest
    }

    /** 永続化済み phase identity を起動前の MCP manifest へ固定する。 */
    fun bindInput(request: LlmInvocationRequest, manifest: LlmPhaseInputManifest) {
        request.mcpServer?.let { server ->
            McpLaunchManifestWriter.bindPhaseIdentity(
                path = server.manifestPath,
                phaseManifestId = manifest.phaseManifestId,
                effectiveInvocationHash = manifest.effectiveInvocationHash,
            )
        }
    }

    suspend fun appendObservation(
        manifest: LlmPhaseInputManifest,
        observedModels: List<String>,
        started: Boolean,
    ) {
        val modelStatus = when {
            !started -> LlmIdentityCoverageStatus.NOT_OBSERVABLE_BEFORE_START
            observedModels.isEmpty() -> LlmIdentityCoverageStatus.NOT_REPORTED_BY_PROVIDER
            else -> LlmIdentityCoverageStatus.OBSERVED
        }
        repository.appendObservation(
            LlmPhaseObservation(
                phaseManifestId = manifest.phaseManifestId,
                observedModels = observedModels.distinct().sorted(),
                observedEffort = null,
                modelCoverageStatus = modelStatus,
                effortCoverageStatus = if (started) {
                    LlmIdentityCoverageStatus.NOT_REPORTED_BY_PROVIDER
                } else {
                    LlmIdentityCoverageStatus.NOT_OBSERVABLE_BEFORE_START
                },
                terminatedAt = clock.instant(),
            ),
        ).getOrThrow()
    }
}

/** standard phase inputを起動前に固定できなかったことを表す failure。 */
class LlmPhaseInputCaptureException(cause: Throwable) : IllegalStateException(
    "LLM phase input manifest capture failed.",
    cause,
)

private val DECISION_PHASES = setOf(
    LlmInvocationPhase.PROPOSER,
    LlmInvocationPhase.FALSIFIER,
    LlmInvocationPhase.RISK_REDUCTION_ONLY,
)

private fun LlmInvocationPhase.notApplicableReason(): LlmManifestNotApplicableReason = when (this) {
    LlmInvocationPhase.PRE_FILTER -> LlmManifestNotApplicableReason.PRE_FILTER_BEFORE_FULL_RUN
    LlmInvocationPhase.REFLECTION,
    LlmInvocationPhase.EVALUATION_REPORT,
    -> LlmManifestNotApplicableReason.NON_DECISION_INVOCATION
    else -> error("Decision phase has applicable run/material references.")
}
