package me.matsumo.fukurou.trading.invoker

import kotlinx.coroutines.CancellationException

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
}

/**
 * Shell CLI を使う LLM 起動実装。
 *
 * @param commandRenderer provider ごとの command renderer
 * @param processRunner process 実行境界
 */
class ShellLlmInvoker(
    private val commandRenderer: LlmCommandRenderer,
    private val processRunner: ProcessRunner,
) : LlmInvoker {

    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        return try {
            val command = commandRenderer.render(request).getOrThrow()
            val processResult = processRunner.run(command).getOrThrow()

            Result.success(
                LlmInvocationResult(
                    request = request,
                    processResult = processResult,
                ),
            )
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }
}
