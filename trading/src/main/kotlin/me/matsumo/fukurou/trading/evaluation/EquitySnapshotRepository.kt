package me.matsumo.fukurou.trading.evaluation

import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.TradingMode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * equity snapshot の取引日判定に使う timezone。
 */
val EQUITY_SNAPSHOT_TRADING_DATE_ZONE: ZoneId = ZoneId.of("Asia/Tokyo")

/**
 * equity_snapshots を追加する理由。
 */
enum class EquitySnapshotReason {
    /** account epoch の開始状態を記録した。 */
    EPOCH_START,

    /**
     * 約定により paper_account が変化した。
     */
    FILL,

    /**
     * JST 日次の口座状態を記録した。
     */
    DAILY,

    /**
     * schema bootstrap 時の初期口座状態を記録した。
     */
    BOOTSTRAP,
}

/**
 * equity_snapshots の保存 model。
 *
 * @param id snapshot ID
 * @param mode 取引 mode
 * @param reason snapshot 追加理由
 * @param tradingDate JST 取引日
 * @param capturedAt 取得時刻
 * @param cashJpy JPY 現金残高
 * @param btcQuantity BTC 保有数量
 * @param btcMarkPriceJpy BTC 評価価格
 * @param totalEquityJpy 総評価額
 * @param equityPeakJpy 総評価額の過去ピーク
 * @param drawdownRatio equityPeakJpy からの drawdown
 */
data class EquitySnapshotRecord(
    val id: UUID,
    val mode: TradingMode,
    val reason: EquitySnapshotReason,
    val tradingDate: LocalDate,
    val capturedAt: Instant,
    val cashJpy: BigDecimal,
    val btcQuantity: BigDecimal,
    val btcMarkPriceJpy: BigDecimal,
    val totalEquityJpy: BigDecimal,
    val equityPeakJpy: BigDecimal,
    val drawdownRatio: BigDecimal,
    val accountEpochId: UUID? = null,
)

/**
 * paper_account の append-only equity snapshot repository。
 */
interface EquitySnapshotRepository {
    /**
     * append-only snapshot を保存する。
     *
     * FILL / BOOTSTRAP は重複防御なしで保存し、DAILY は [appendDailyIfAbsent] を使う。
     */
    suspend fun append(snapshot: EquitySnapshotRecord): Result<Unit>

    /**
     * DAILY snapshot を同一 mode / trading_date で一度だけ保存する。
     */
    suspend fun appendDailyIfAbsent(snapshot: EquitySnapshotRecord): Result<Unit>

    /**
     * 保存済み snapshot を captured_at 昇順で返す。
     */
    suspend fun findAll(): Result<List<EquitySnapshotRecord>>
}

/**
 * AccountSnapshot を equity snapshot record に変換する。
 */
fun AccountSnapshot.toEquitySnapshotRecord(
    id: UUID,
    reason: EquitySnapshotReason,
    tradingDate: LocalDate,
    capturedAt: Instant,
): EquitySnapshotRecord {
    return EquitySnapshotRecord(
        id = id,
        mode = mode,
        reason = reason,
        tradingDate = tradingDate,
        capturedAt = capturedAt,
        cashJpy = cashJpy.toBigDecimal(),
        btcQuantity = btcQuantity.toBigDecimal(),
        btcMarkPriceJpy = btcMarkPriceJpy.toBigDecimal(),
        totalEquityJpy = totalEquityJpy.toBigDecimal(),
        equityPeakJpy = equityPeakJpy.toBigDecimal(),
        drawdownRatio = drawdownRatio.toBigDecimal(),
    )
}

/**
 * 約定時の account snapshot を FILL snapshot record に変換する。
 */
fun AccountSnapshot.toFillEquitySnapshotRecord(id: UUID, capturedAt: Instant): EquitySnapshotRecord {
    val tradingDate = capturedAt.atZone(EQUITY_SNAPSHOT_TRADING_DATE_ZONE).toLocalDate()

    return toEquitySnapshotRecord(
        id = id,
        reason = EquitySnapshotReason.FILL,
        tradingDate = tradingDate,
        capturedAt = capturedAt,
    )
}

/**
 * unit test と in-memory runtime 用の equity_snapshots repository。
 */
class InMemoryEquitySnapshotRepository : EquitySnapshotRepository {

    private val lock = Any()
    private val snapshots = mutableListOf<EquitySnapshotRecord>()

    override suspend fun append(snapshot: EquitySnapshotRecord): Result<Unit> {
        return runCatching {
            appendSnapshot(snapshot)
        }
    }

    override suspend fun appendDailyIfAbsent(snapshot: EquitySnapshotRecord): Result<Unit> {
        return runCatching {
            appendDailySnapshotIfAbsent(snapshot)
        }
    }

    override suspend fun findAll(): Result<List<EquitySnapshotRecord>> {
        return Result.success(
            synchronized(lock) {
                snapshots.sortedBy { snapshot -> snapshot.capturedAt }
            },
        )
    }

    /**
     * ledger の同期処理から snapshot を保存する。
     */
    fun appendSnapshot(snapshot: EquitySnapshotRecord) {
        synchronized(lock) {
            snapshots += snapshot
        }
    }

    /**
     * ledger / recorder の同期処理から DAILY snapshot を一度だけ保存する。
     */
    fun appendDailySnapshotIfAbsent(snapshot: EquitySnapshotRecord) {
        synchronized(lock) {
            val existingDaily = snapshots.any { existingSnapshot ->
                val sameMode = existingSnapshot.mode == snapshot.mode
                val sameDate = existingSnapshot.tradingDate == snapshot.tradingDate
                val dailyReason = existingSnapshot.reason == EquitySnapshotReason.DAILY

                sameMode && sameDate && dailyReason
            }

            if (!existingDaily) {
                snapshots += snapshot
            }
        }
    }
}
