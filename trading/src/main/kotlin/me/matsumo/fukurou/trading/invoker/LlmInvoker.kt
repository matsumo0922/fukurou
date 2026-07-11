package me.matsumo.fukurou.trading.invoker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LLM CLI の起動境界。
 */
interface LlmInvoker {
    /**
     * LLM CLI を 1 回起動する。
     */
    suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult>
}

/**
 * LLM CLI の command line を組み立てる境界。
 */
interface LlmCommandRenderer {
    /**
     * provider ごとの CLI command を生成する。
     */
    fun render(request: LlmInvocationRequest): Result<RenderedLlmCommand>
}

/**
 * 子 process 起動・timeout・stdout/stderr 取得の境界。
 */
interface ProcessRunner {
    /**
     * command を実行する。renderer が生成した一時 artifact は削除しないため、
     * 呼び出し側は output の解析後に [cleanup] を必ず呼ぶ。
     */
    suspend fun run(command: RenderedLlmCommand): Result<ProcessRunResult>

    /**
     * renderer が生成した一時 artifact を削除する。
     */
    suspend fun cleanup(command: RenderedLlmCommand): Result<Unit> = Result.success(Unit)
}

/**
 * provider 固有 output を semantic response と usage に正規化する境界。
 */
interface LlmOutputParser {
    /**
     * process output と invocation 中の artifact を best-effort で解析する。
     */
    fun parse(
        request: LlmInvocationRequest,
        command: RenderedLlmCommand,
        processResult: ProcessRunResult,
        startedAt: Instant,
        completedAt: Instant,
    ): ParsedLlmOutput
}

/**
 * Shell CLI を使う LLM 起動実装。
 *
 * @param commandRenderer provider ごとの command renderer
 * @param processRunner process 実行境界
 * @param outputParser provider output parser
 * @param clock invocation 時刻を記録する clock
 */
class ShellLlmInvoker(
    private val commandRenderer: LlmCommandRenderer,
    private val processRunner: ProcessRunner,
    private val outputParser: LlmOutputParser = DefaultLlmOutputParser(),
    private val clock: Clock = Clock.systemUTC(),
) : LlmInvoker {

    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        LlmArtifactCleanupQuarantine.requireClear()
            .exceptionOrNull()
            ?.let { failure -> return Result.failure(failure.classifyLlmFailure(request.provider)) }
        val command = try {
            commandRenderer.render(request).getOrThrow()
        } catch (throwable: CancellationException) {
            throw throwable.classifyLlmFailure(request.provider)
        } catch (throwable: LlmArtifactCleanupException) {
            LlmArtifactCleanupQuarantine.activate(throwable)
            return Result.failure(throwable.classifyLlmFailure(request.provider))
        } catch (throwable: Throwable) {
            return Result.failure(throwable.classifyLlmFailure(request.provider))
        }

        return try {
            val startedAt = clock.instant()
            val processResult = processRunner.run(command).getOrThrow()
            val completedAt = clock.instant()
            val parsedOutput = outputParser.parse(
                request = request,
                command = command,
                processResult = processResult,
                startedAt = startedAt,
                completedAt = completedAt,
            )

            val invocationResult = LlmInvocationResult(
                request = request,
                responseText = parsedOutput.responseText,
                usage = parsedOutput.usage,
                processResult = processResult,
                cleanupFailure = cleanupNonCancellable(command).exceptionOrNull()?.let { failure ->
                    LlmArtifactCleanupQuarantine.activate(failure)
                    failure.classifyLlmFailure(request.provider)
                },
            )

            Result.success(invocationResult)
        } catch (throwable: CancellationException) {
            val cleanupFailure = cleanupNonCancellable(command).exceptionOrNull()
            cleanupFailure?.let { failure -> LlmArtifactCleanupQuarantine.activate(failure) }
            cleanupFailure?.let { failure -> throwable.addSuppressed(failure) }

            throw throwable.classifyLlmFailure(request.provider)
        } catch (throwable: Throwable) {
            val cleanupFailure = cleanupNonCancellable(command).exceptionOrNull()
            cleanupFailure?.let { failure -> LlmArtifactCleanupQuarantine.activate(failure) }
            cleanupFailure?.let { failure -> throwable.addSuppressed(failure) }

            Result.failure(throwable.classifyLlmFailure(request.provider))
        }
    }

    private suspend fun cleanupNonCancellable(command: RenderedLlmCommand): Result<Unit> {
        return withContext(NonCancellable) {
            try {
                processRunner.cleanup(command)
            } catch (throwable: Throwable) {
                Result.failure(throwable)
            }
        }
    }
}

/** request/render 中に生成済み artifact を回収できなかった failure。 */
internal class LlmArtifactCleanupException(cause: Throwable) : IllegalStateException(
    "LLM generated artifact cleanup failed.",
    cause,
)

/**
 * cleanup failure 後の追加 LLM run を current process で拒否する quarantine。
 *
 * marker と per-run artifact は同じ tmpfs に置く。marker 書き込みに失敗しても process 内の gate は維持し、
 * container restart では tmpfs 上の artifact と marker を同時に破棄して解除する。
 */
internal object LlmArtifactCleanupQuarantine {
    private val processQuarantined = AtomicBoolean(false)
    private val markerPath: Path
        get() = Path.of(
            System.getProperty(
                QUARANTINE_PATH_PROPERTY,
                Path.of(System.getProperty("java.io.tmpdir"), ".fukurou-llm-cleanup-quarantine").toString(),
            ),
        )

    fun requireClear(): Result<Unit> = runCatching {
        check(Files.isDirectory(markerPath.parent) && Files.isWritable(markerPath.parent)) {
            "LLM artifact cleanup quarantine storage is unavailable; remediation or container restart is required."
        }
        check(!processQuarantined.get() && !Files.exists(markerPath)) {
            "LLM artifact cleanup quarantine is active; remediation or container restart is required."
        }
    }

    fun activate(cause: Throwable) {
        processQuarantined.set(true)
        runCatching {
            Files.writeString(
                markerPath,
                "cleanup_failed\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }.onFailure { markerFailure -> cause.addSuppressed(markerFailure) }
    }

    internal fun resetForTest() {
        processQuarantined.set(false)
        Files.deleteIfExists(markerPath)
    }

    internal fun simulateRestartForTest() {
        processQuarantined.set(false)
    }
}

private const val QUARANTINE_PATH_PROPERTY = "fukurou.llm.cleanupQuarantinePath"
