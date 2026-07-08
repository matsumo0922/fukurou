package me.matsumo.fukurou.trading.reflection

import kotlinx.coroutines.CancellationException

/**
 * reflection report の収集・生成・vault 書き込みを 1 回実行する runner。
 *
 * @param dataCollector DB 由来データを収集する collector
 * @param reportBuilder Markdown report を組み立てる builder
 * @param vaultWriter report を vault へ書き込む writer
 */
class ReflectionRunner(
    private val dataCollector: ReflectionDataCollector,
    private val reportBuilder: ReflectionReportBuilder,
    private val vaultWriter: ReflectionVaultWriter,
) {

    /**
     * reflection report を 1 回生成して vault へ書き込む。
     */
    suspend fun runOnce(): Result<ReflectionWriteSummary> {
        return try {
            val dataset = dataCollector.collect().getOrThrow()
            val reports = reportBuilder.build(dataset).getOrThrow()

            vaultWriter.write(reports)
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }
}
