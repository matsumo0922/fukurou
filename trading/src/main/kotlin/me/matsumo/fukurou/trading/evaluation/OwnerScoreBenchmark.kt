package me.matsumo.fukurou.trading.evaluation

import me.matsumo.fukurou.trading.domain.Candle
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Owner score の計算 semantics version。 */
const val OWNER_SCORE_SEMANTICS_VERSION = "OWNER_SCORE_V1"

/** V1 が比較用の仮想清算へ使う taker fee。 */
val OWNER_SCORE_SYNTHETIC_TAKER_FEE_RATE: BigDecimal = BigDecimal("0.0005")

/** Owner score window の expected day 数。 */
const val OWNER_SCORE_EXPECTED_DAYS = 90

/** 勝敗を確定できる最小 valid day 数。 */
const val OWNER_SCORE_MIN_VALID_DAYS = 81

private const val OWNER_SCORE_SCALE = 10
private const val OWNER_SCORE_MATERIAL_GAP_SECONDS = 3_600L
private val OwnerScoreBoundaryTime = LocalTime.of(6, 0)

/** cutoff の種別。 */
enum class OwnerScoreCutoffMode {
    ROLLING,
    FIXED_CUTOFF,
}

/** Owner score の勝者。 */
enum class OwnerScoreWinner {
    BOT,
    BUY_AND_HOLD,
}

/** expected business day の評価状態。 */
enum class OwnerScoreDayState {
    VALID,
    UNKNOWN,
    OUTSIDE_ACCOUNT_EPOCH,
}

/** expected business day を無効にした理由。 */
enum class OwnerScoreUnknownReason {
    OUTSIDE_ACCOUNT_EPOCH,
    MISSING_CANDLE,
    INVALID_CANDLE,
    CONFLICTING_CANDLE,
    MISSING_ACCOUNT_SNAPSHOT,
    CONFLICTING_ACCOUNT_SNAPSHOT,
    MATERIAL_MARKET_DATA_GAP,
}

/** 保存済み market-data gap。 */
data class OwnerScoreMarketDataGap(
    val id: UUID,
    val startedAt: Instant,
    val recoveredAt: Instant?,
)

/** Owner score の bounded DB evidence。 */
data class OwnerScoreEvidence(
    val snapshots: List<EquitySnapshotRecord> = emptyList(),
    val marketDataGaps: List<OwnerScoreMarketDataGap> = emptyList(),
)

/** Owner score の固定90日 window。 */
data class OwnerScoreWindow(
    val fromInclusive: Instant,
    val firstCloseAt: Instant,
    val lastCloseAt: Instant,
    val expectedCloseSlots: List<Instant>,
) {
    companion object {
        /** cutoff 以下の最新06:00 JST境界から連続90 slotを作る。 */
        fun fromCutoff(cutoff: Instant, zoneId: ZoneId): OwnerScoreWindow {
            val cutoffLocal = cutoff.atZone(zoneId)
            val todayBoundary = cutoffLocal.toLocalDate().atTime(OwnerScoreBoundaryTime).atZone(zoneId).toInstant()
            val lastClose = if (todayBoundary <= cutoff) todayBoundary else todayBoundary.minus(Duration.ofDays(1))
            val firstClose = lastClose.minus(Duration.ofDays((OWNER_SCORE_EXPECTED_DAYS - 1).toLong()))
            val slots = (0 until OWNER_SCORE_EXPECTED_DAYS).map { index ->
                firstClose.plus(Duration.ofDays(index.toLong()))
            }

            return OwnerScoreWindow(
                fromInclusive = firstClose.minus(Duration.ofDays(1)),
                firstCloseAt = firstClose,
                lastCloseAt = lastClose,
                expectedCloseSlots = slots,
            )
        }
    }
}

/** Owner score の日次 liquidation point。 */
data class OwnerScorePoint(
    val date: LocalDate,
    val closeAt: Instant,
    val state: OwnerScoreDayState,
    val reasons: Set<OwnerScoreUnknownReason>,
    val closeJpy: BigDecimal?,
    val botLiquidationEquityJpy: BigDecimal?,
    val buyAndHoldLiquidationEquityJpy: BigDecimal?,
    val cashEquityJpy: BigDecimal?,
    val gapCount: Int,
    val gapSeconds: Long,
)

/** Owner score evidence の coverage。 */
data class OwnerScoreCoverage(
    val expectedDays: Int,
    val validDays: Int,
    val gapDays: Int,
    val unknownDays: Int,
    val gapCount: Int,
    val gapSeconds: Long,
    val reasonCounts: Map<OwnerScoreUnknownReason, Int>,
)

/** Owner score の return。すべて共通元本を分母にする。 */
data class OwnerScoreReturns(
    val botReturn: BigDecimal,
    val buyAndHoldReturn: BigDecimal,
    val cashReturn: BigDecimal,
)

/** Owner score calculation request。 */
data class OwnerScoreCalculationRequest(
    val cutoff: Instant,
    val cutoffMode: OwnerScoreCutoffMode,
    val accountEpochId: UUID,
    val accountEpochStartedAt: Instant,
    val candles: List<Candle>,
    val snapshots: List<EquitySnapshotRecord>,
    val marketDataGaps: List<OwnerScoreMarketDataGap>,
    val zoneId: ZoneId,
)

/** Owner score calculation result。 */
data class OwnerScoreResult(
    val semanticsVersion: String,
    val cutoff: Instant,
    val cutoffMode: OwnerScoreCutoffMode,
    val window: OwnerScoreWindow,
    val syntheticTakerFeeRate: BigDecimal,
    val commonStartingCapitalJpy: BigDecimal?,
    val points: List<OwnerScorePoint>,
    val coverage: OwnerScoreCoverage,
    val returns: OwnerScoreReturns?,
    val ownerScore: BigDecimal?,
    val winner: OwnerScoreWinner?,
    val conclusive: Boolean,
)

/** DB非依存の Owner score 計算。 */
object OwnerScoreMath {
    /** active epoch の清算 equity と B&H/cash を比較する。 */
    fun benchmark(request: OwnerScoreCalculationRequest): OwnerScoreResult {
        val window = OwnerScoreWindow.fromCutoff(request.cutoff, request.zoneId)
        val candlesBySlot = request.candles.groupBy { candle -> candle.ownerScoreCloseSlotOrNull(request.zoneId) }
        val snapshots = request.snapshots
            .filter { snapshot -> snapshot.accountEpochId == request.accountEpochId }
            .sortedWith(compareBy(EquitySnapshotRecord::capturedAt, EquitySnapshotRecord::id))
        val dailyInputs = window.expectedCloseSlots.map { closeAt ->
            buildDailyInput(request, closeAt, candlesBySlot[closeAt].orEmpty(), snapshots)
        }
        val firstInput = dailyInputs.first()
        val commonStartingCapital = firstInput.botLiquidationEquityJpy?.takeIf { value -> value > BigDecimal.ZERO }
        val firstClose = firstInput.closeJpy?.takeIf { value -> value > BigDecimal.ZERO }
        val buyAndHoldBtc = if (commonStartingCapital != null && firstClose != null) {
            commonStartingCapital.ownerScoreDivide(
                firstClose.multiply(BigDecimal.ONE.add(OWNER_SCORE_SYNTHETIC_TAKER_FEE_RATE)),
            )
        } else {
            null
        }
        val points = dailyInputs.map { input -> input.toPoint(commonStartingCapital, buyAndHoldBtc, request.zoneId) }
        val coverage = points.toCoverage()
        val boundaryValid = points.first().state == OwnerScoreDayState.VALID &&
            points.last().state == OwnerScoreDayState.VALID
        val conclusive = coverage.validDays >= OWNER_SCORE_MIN_VALID_DAYS && boundaryValid &&
            commonStartingCapital != null && buyAndHoldBtc != null
        val returns = if (conclusive) {
            val end = points.last()
            OwnerScoreReturns(
                botReturn = requireNotNull(end.botLiquidationEquityJpy).ownerScoreReturn(commonStartingCapital),
                buyAndHoldReturn = requireNotNull(end.buyAndHoldLiquidationEquityJpy)
                    .ownerScoreReturn(commonStartingCapital),
                cashReturn = BigDecimal.ZERO.setScale(OWNER_SCORE_SCALE),
            )
        } else {
            null
        }
        val ownerScore = returns?.botReturn?.subtract(returns.buyAndHoldReturn)?.ownerScoreScale()

        return OwnerScoreResult(
            semanticsVersion = OWNER_SCORE_SEMANTICS_VERSION,
            cutoff = request.cutoff,
            cutoffMode = request.cutoffMode,
            window = window,
            syntheticTakerFeeRate = OWNER_SCORE_SYNTHETIC_TAKER_FEE_RATE,
            commonStartingCapitalJpy = commonStartingCapital,
            points = points,
            coverage = coverage,
            returns = returns,
            ownerScore = ownerScore,
            winner = ownerScore?.let { score ->
                if (score > BigDecimal.ZERO) OwnerScoreWinner.BOT else OwnerScoreWinner.BUY_AND_HOLD
            },
            conclusive = conclusive,
        )
    }
}

private data class OwnerScoreDailyInput(
    val closeAt: Instant,
    val state: OwnerScoreDayState,
    val reasons: Set<OwnerScoreUnknownReason>,
    val closeJpy: BigDecimal?,
    val botLiquidationEquityJpy: BigDecimal?,
    val gapCount: Int,
    val gapSeconds: Long,
)

private fun buildDailyInput(
    request: OwnerScoreCalculationRequest,
    closeAt: Instant,
    candles: List<Candle>,
    snapshots: List<EquitySnapshotRecord>,
): OwnerScoreDailyInput {
    val slotStart = closeAt.minus(Duration.ofDays(1))
    val outsideEpoch = slotStart < request.accountEpochStartedAt
    val closeValues = candles.mapNotNull { candle -> candle.close.toBigDecimalOrNull() }
        .distinctBy(BigDecimal::stripTrailingZeros)
    val conflictingCandle = closeValues.size > 1
    val closeJpy = closeValues.singleOrNull()?.takeIf { value -> value > BigDecimal.ZERO }
    val latestSnapshots = snapshots.filter { snapshot -> snapshot.capturedAt <= closeAt }
        .groupBy(EquitySnapshotRecord::capturedAt)
        .maxByOrNull { entry -> entry.key }
        ?.value
        .orEmpty()
    val conflictingSnapshot = latestSnapshots
        .map { snapshot -> snapshot.cashJpy.stripTrailingZeros() to snapshot.btcQuantity.stripTrailingZeros() }
        .distinct()
        .size > 1
    val snapshot = latestSnapshots.firstOrNull()
    val gapDurations = request.marketDataGaps.mapNotNull { gap ->
        val end = minOf(gap.recoveredAt ?: request.cutoff, closeAt)
        val start = maxOf(gap.startedAt, slotStart)
        Duration.between(start, end).takeIf { duration -> !duration.isNegative && !duration.isZero }
    }
    val gapSeconds = gapDurations.sumOf(Duration::toSeconds)
    val invalidCandle = candles.isNotEmpty() && closeJpy == null && !conflictingCandle
    val reasons = listOfNotNull(
        OwnerScoreUnknownReason.OUTSIDE_ACCOUNT_EPOCH.takeIf { outsideEpoch },
        OwnerScoreUnknownReason.MISSING_CANDLE.takeIf { candles.isEmpty() },
        OwnerScoreUnknownReason.INVALID_CANDLE.takeIf { invalidCandle },
        OwnerScoreUnknownReason.CONFLICTING_CANDLE.takeIf { conflictingCandle },
        OwnerScoreUnknownReason.MISSING_ACCOUNT_SNAPSHOT.takeIf { snapshot == null },
        OwnerScoreUnknownReason.CONFLICTING_ACCOUNT_SNAPSHOT.takeIf { conflictingSnapshot },
        OwnerScoreUnknownReason.MATERIAL_MARKET_DATA_GAP.takeIf {
            gapSeconds >= OWNER_SCORE_MATERIAL_GAP_SECONDS
        },
    ).toSet()
    val state = when {
        outsideEpoch -> OwnerScoreDayState.OUTSIDE_ACCOUNT_EPOCH
        reasons.isNotEmpty() -> OwnerScoreDayState.UNKNOWN
        else -> OwnerScoreDayState.VALID
    }
    val botEquity = calculateBotLiquidationEquity(state, closeJpy, snapshot)

    return OwnerScoreDailyInput(
        closeAt = closeAt,
        state = state,
        reasons = reasons,
        closeJpy = closeJpy,
        botLiquidationEquityJpy = botEquity,
        gapCount = gapDurations.size,
        gapSeconds = gapSeconds,
    )
}

private fun calculateBotLiquidationEquity(
    state: OwnerScoreDayState,
    closeJpy: BigDecimal?,
    snapshot: EquitySnapshotRecord?,
): BigDecimal? {
    if (state != OwnerScoreDayState.VALID) return null
    val validCloseJpy = closeJpy ?: return null
    val validSnapshot = snapshot ?: return null

    return validSnapshot.cashJpy.add(
        validSnapshot.btcQuantity.multiply(validCloseJpy)
            .multiply(BigDecimal.ONE.subtract(OWNER_SCORE_SYNTHETIC_TAKER_FEE_RATE)),
    ).ownerScoreScale()
}

private fun OwnerScoreDailyInput.toPoint(
    commonStartingCapital: BigDecimal?,
    buyAndHoldBtc: BigDecimal?,
    zoneId: ZoneId,
): OwnerScorePoint {
    val buyAndHold = calculateBuyAndHoldLiquidationEquity(state, closeJpy, buyAndHoldBtc)

    return OwnerScorePoint(
        date = closeAt.atZone(zoneId).toLocalDate(),
        closeAt = closeAt,
        state = state,
        reasons = reasons,
        closeJpy = closeJpy.takeIf { state == OwnerScoreDayState.VALID },
        botLiquidationEquityJpy = botLiquidationEquityJpy,
        buyAndHoldLiquidationEquityJpy = buyAndHold,
        cashEquityJpy = commonStartingCapital?.takeIf { state == OwnerScoreDayState.VALID }?.ownerScoreScale(),
        gapCount = gapCount,
        gapSeconds = gapSeconds,
    )
}

private fun calculateBuyAndHoldLiquidationEquity(
    state: OwnerScoreDayState,
    closeJpy: BigDecimal?,
    buyAndHoldBtc: BigDecimal?,
): BigDecimal? {
    if (state != OwnerScoreDayState.VALID) return null
    val validCloseJpy = closeJpy ?: return null
    val validBuyAndHoldBtc = buyAndHoldBtc ?: return null

    return validBuyAndHoldBtc.multiply(validCloseJpy)
        .multiply(BigDecimal.ONE.subtract(OWNER_SCORE_SYNTHETIC_TAKER_FEE_RATE))
        .ownerScoreScale()
}

private fun List<OwnerScorePoint>.toCoverage(): OwnerScoreCoverage {
    val reasons = flatMap { point -> point.reasons }.groupingBy { reason -> reason }.eachCount()

    return OwnerScoreCoverage(
        expectedDays = size,
        validDays = count { point -> point.state == OwnerScoreDayState.VALID },
        gapDays = count { point -> OwnerScoreUnknownReason.MATERIAL_MARKET_DATA_GAP in point.reasons },
        unknownDays = count { point -> point.state != OwnerScoreDayState.VALID },
        gapCount = sumOf(OwnerScorePoint::gapCount),
        gapSeconds = sumOf(OwnerScorePoint::gapSeconds),
        reasonCounts = reasons,
    )
}

private fun Candle.ownerScoreCloseSlotOrNull(zoneId: ZoneId): Instant? {
    val openedAt = runCatching { Instant.parse(openTime) }.getOrNull() ?: return null
    val businessDate = openedAt.atZone(zoneId).minusHours(6).toLocalDate()

    return businessDate.plusDays(1).atTime(OwnerScoreBoundaryTime).atZone(zoneId).toInstant()
}

private fun BigDecimal.ownerScoreDivide(divisor: BigDecimal): BigDecimal {
    require(divisor > BigDecimal.ZERO)
    return divide(divisor, OWNER_SCORE_SCALE, RoundingMode.HALF_UP)
}

private fun BigDecimal.ownerScoreReturn(startingCapital: BigDecimal): BigDecimal {
    return divide(startingCapital, OWNER_SCORE_SCALE, RoundingMode.HALF_UP)
        .subtract(BigDecimal.ONE)
        .ownerScoreScale()
}

private fun BigDecimal.ownerScoreScale(): BigDecimal = setScale(OWNER_SCORE_SCALE, RoundingMode.HALF_UP)
