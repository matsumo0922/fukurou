package me.matsumo.fukurou.trading.evaluation

import kotlinx.coroutines.CancellationException
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Reconciler pass 内で JST 日次 equity snapshot を一度だけ保存する recorder。
 *
 * @param accountSource 現在の paper account snapshot を読む境界
 * @param repository equity snapshot repository
 * @param clock 日次判定と captured_at に使う clock
 * @param idGenerator snapshot ID generator
 * @param logger snapshot 記録失敗の warn 出力先
 */
class EquitySnapshotRecorder(
    private val accountSource: suspend () -> Result<AccountSnapshot>,
    private val repository: EquitySnapshotRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> UUID = { UUID.randomUUID() },
    private val logger: Logger = Logger.getLogger(EquitySnapshotRecorder::class.java.name),
) {

    private var lastRecordedDate: LocalDate? = null

    /**
     * JST 日付が未記録なら現在の paper account を DAILY snapshot として保存する。
     */
    suspend fun recordDailyIfNeeded(): Result<Unit> {
        return try {
            val capturedAt = Instant.now(clock)
            val tradingDate = capturedAt.atZone(EQUITY_SNAPSHOT_TRADING_DATE_ZONE).toLocalDate()

            if (lastRecordedDate == tradingDate) {
                return Result.success(Unit)
            }

            val previousRecordedDate = lastRecordedDate
            lastRecordedDate = tradingDate
            val accountSnapshot = accountSource().getOrElse { throwable ->
                lastRecordedDate = previousRecordedDate
                logger.log(Level.WARNING, "EquitySnapshotRecorder account source failed.", throwable)

                return Result.success(Unit)
            }
            val snapshot = runCatching {
                accountSnapshot.toEquitySnapshotRecord(
                    id = idGenerator(),
                    reason = EquitySnapshotReason.DAILY,
                    tradingDate = tradingDate,
                    capturedAt = capturedAt,
                )
            }.getOrElse { throwable ->
                lastRecordedDate = previousRecordedDate
                logger.log(Level.WARNING, "EquitySnapshotRecorder daily snapshot build failed.", throwable)

                return Result.success(Unit)
            }

            val appendResult = try {
                repository.appendDailyIfAbsent(snapshot)
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: Throwable) {
                lastRecordedDate = previousRecordedDate
                logger.log(Level.WARNING, "EquitySnapshotRecorder daily snapshot append failed.", throwable)

                return Result.success(Unit)
            }

            appendResult.onFailure { throwable ->
                lastRecordedDate = previousRecordedDate
                logger.log(Level.WARNING, "EquitySnapshotRecorder daily snapshot append failed.", throwable)
            }

            Result.success(Unit)
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            logger.log(Level.WARNING, "EquitySnapshotRecorder daily snapshot record failed.", throwable)

            Result.success(Unit)
        }
    }
}
