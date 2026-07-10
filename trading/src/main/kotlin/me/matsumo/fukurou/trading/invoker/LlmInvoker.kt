package me.matsumo.fukurou.trading.invoker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant

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
     * command を実行する。
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
        val command = try {
            commandRenderer.render(request).getOrThrow()
        } catch (throwable: CancellationException) {
            throw throwable.classifyLlmFailure(request.provider)
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
            )
            val cleanupResult = cleanupNonCancellable(command)
            val cleanupFailure = cleanupResult.exceptionOrNull()

            if (cleanupFailure == null) {
                Result.success(invocationResult)
            } else {
                Result.failure(cleanupFailure.classifyLlmFailure(request.provider))
            }
        } catch (throwable: CancellationException) {
            val cleanupFailure = cleanupNonCancellable(command).exceptionOrNull()
            cleanupFailure?.let { failure -> throwable.addSuppressed(failure) }

            throw throwable.classifyLlmFailure(request.provider)
        } catch (throwable: Throwable) {
            val cleanupFailure = cleanupNonCancellable(command).exceptionOrNull()
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
