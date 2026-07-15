package me.matsumo.fukurou.trading.safety

import me.matsumo.fukurou.trading.broker.CancelOrderCommand
import me.matsumo.fukurou.trading.broker.ClosePositionCommand
import me.matsumo.fukurou.trading.broker.PaperExecutionConfig
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.broker.UpdateProtectionCommand
import me.matsumo.fukurou.trading.config.FomcBlackoutCalendar
import me.matsumo.fukurou.trading.config.FomcBlackoutCalendarState
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.EntryIntentSafetySnapshot
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.unsafeOrderFeeRateReasonOrNull
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.risk.RiskState
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * override 不可の安全床ルール。
 */
enum class SafetyFloorRule {
    /**
     * 1 trade group の最大損失が equity の 2% を超えた。
     */
    MAX_RISK_PER_TRADE,

    /**
     * long entry に保護 STOP がない、または entry より下にない。
     */
    STOP_LOSS_REQUIRED,

    /**
     * 含み損の long position に買い増そうとした。
     */
    NO_AVERAGING_DOWN,

    /**
     * ピラミッディングの最大追加回数を超えた。
     */
    PYRAMID_ADD_LIMIT,

    /**
     * ピラミッディングに必要な含み益 R に届かなかった。
     */
    PYRAMID_PROFIT_GATE,

    /**
     * ピラミッディング追加分の risk が初回 risk budget の 50% を超えた。
     */
    PYRAMID_ADD_RISK_LIMIT,

    /**
     * equity peak からの drawdown が HARD_HALT 閾値へ到達した。
     */
    MAX_DRAWDOWN_HALT,

    /**
     * 合計 BTC exposure が equity の 80% を超えた。
     */
    MAX_TOTAL_EXPOSURE,

    /**
     * 残高、取引所 rule、または cost 上限に違反した。
     */
    BALANCE_RATE_AND_COST_LIMIT,

    /**
     * EV 計算に必要な目標価格がない。
     */
    MISSING_TARGET_PRICE,

    /**
     * コード計算した EV が 0 以下だった。
     */
    NON_POSITIVE_EXPECTED_VALUE,

    /**
     * 推定勝率 p が確率の範囲外だった。
     */
    INVALID_WIN_PROBABILITY,

    /**
     * コード計算した EV が保守的な正の最小値を満たさなかった。
     */
    EXPECTED_VALUE_GATE,

    /**
     * 想定値幅が往復 cost に対して不足した。
     */
    EXPECTED_MOVE_TO_COST_RATIO,

    /**
     * STOP を損失拡大方向へ緩めようとした。
     */
    STOP_LOSS_LOOSENING,

    /**
     * STOP を ATR trailing 床より下へ置こうとした。
     */
    ATR_TRAILING_FLOOR,

    /**
     * STOP が即時約定方向の価格だった。
     */
    IMMEDIATE_STOP_TRIGGER,

    /**
     * 新規 entry に fresh APPROVED falsification がない。
     */
    MISSING_FRESH_FALSIFICATION,

    /**
     * entry intent が既に order に消費されている。
     */
    INTENT_CONSUMED,

    /**
     * entry intent 宣言値と place_order 実引数が一致しない。
     */
    INTENT_MISMATCH,

    /**
     * 高影響経済イベントの blackout window 内で新規 entry しようとした。
     */
    ECONOMIC_EVENT_BLACKOUT,

    /** FOMC calendar が空のため新規 entry を fail closed にした。 */
    FOMC_CALENDAR_MISSING,

    /** FOMC calendar が不正なため新規 entry を fail closed にした。 */
    FOMC_CALENDAR_INVALID,

    /** FOMC calendar の有効期限が切れたため新規 entry を fail closed にした。 */
    FOMC_CALENDAR_EXPIRED,

    /**
     * SOFT_HALT 中に新規 entry しようとした。
     */
    SOFT_HALT_ENTRY_BLOCKED,
}

/**
 * 高影響経済イベントの新規 entry blackout 設定。
 *
 * @param eventId 手動設定で安定して使うイベント ID
 * @param eventName 人間が読むイベント名
 * @param eventAt イベント発生時刻。UTC の Instant として扱う
 * @param blackoutBefore eventAt より前に新規 entry を止める時間
 * @param blackoutAfter eventAt より後に新規 entry を止める時間
 */
data class EconomicEventBlackout(
    val eventId: String,
    val eventName: String,
    val eventAt: Instant,
    val blackoutBefore: Duration,
    val blackoutAfter: Duration,
) {
    init {
        require(eventId.isNotBlank()) {
            "eventId must not be blank."
        }
        require(eventName.isNotBlank()) {
            "eventName must not be blank."
        }
        require(!blackoutBefore.isNegative) {
            "blackoutBefore must be greater than or equal to 0."
        }
        require(!blackoutAfter.isNegative) {
            "blackoutAfter must be greater than or equal to 0."
        }
    }

    /**
     * 指定時刻が blackout window 内なら true を返す。
     */
    fun contains(observedAt: Instant): Boolean {
        val window = toSafeWindow() ?: return false
        val beforeStart = observedAt.isBefore(window.startsAt)
        val afterEnd = observedAt.isAfter(window.endsAt)

        return !beforeStart && !afterEnd
    }
}

/** overflow を起こさず導出できた economic event window。 */
internal data class EconomicEventBlackoutWindow(
    val event: EconomicEventBlackout,
    val startsAt: Instant,
    val endsAt: Instant,
)

/** event window の両端を overflow-safe に導出する。 */
internal fun EconomicEventBlackout.toSafeWindow(): EconomicEventBlackoutWindow? {
    return runCatching {
        EconomicEventBlackoutWindow(
            event = this,
            startsAt = eventAt.minus(blackoutBefore),
            endsAt = eventAt.plus(blackoutAfter),
        )
    }.getOrNull()
}

/**
 * SafetyFloor の保守的な初期しきい値。
 *
 * @param maxRiskPerTradeRatio 1 trade group の最大損失割合
 * @param maxDrawdownRatio HARD_HALT を立てる drawdown
 * @param maxTotalExposureRatio 合計 exposure 上限
 * @param minExpectedValueR コード計算した entry plan に求める最小 EV
 * @param minExpectedMoveToCostRatio 想定値幅と往復 cost の最小比率
 * @param dataQualityCap 市場データ鮮度劣化時の p cap 設定
 * @param maxTakerFeeRatio 環境変数互換の名称。実際には order fee / maker rebate の絶対値上限
 * @param economicEventBlackouts 高影響経済イベントの新規 entry blackout 一覧
 * @param economicEventBlackoutsRaw active runtime config から読んだ未加工 JSON
 * @param fomcBlackoutCalendar FOMC event 群から導出した tolerant calendar projection
 * @param marketSlippageReserveBps MARKET / STOP の片道 slippage reserve
 */
data class SafetyFloorConfig(
    val maxRiskPerTradeRatio: BigDecimal = DEFAULT_MAX_RISK_PER_TRADE_RATIO,
    val maxDrawdownRatio: BigDecimal = SafetyFloorDefaults.maxDrawdownRatio,
    val maxTotalExposureRatio: BigDecimal = DEFAULT_MAX_TOTAL_EXPOSURE_RATIO,
    val minExpectedValueR: BigDecimal = DEFAULT_MIN_EXPECTED_VALUE_R,
    val minExpectedMoveToCostRatio: BigDecimal = SafetyFloorDefaults.minExpectedMoveToCostRatio,
    val dataQualityCap: DataQualityCapConfig = DataQualityCapConfig(),
    val maxTakerFeeRatio: BigDecimal = DEFAULT_MAX_TAKER_FEE_RATIO,
    val economicEventBlackouts: List<EconomicEventBlackout> = FomcBlackoutCalendar.candidateEvents(),
    val economicEventBlackoutsRaw: String? = null,
    val fomcBlackoutCalendar: FomcBlackoutCalendar = if (economicEventBlackoutsRaw != null) {
        FomcBlackoutCalendar.fromRaw(economicEventBlackoutsRaw)
    } else {
        FomcBlackoutCalendar.fromEvents(economicEventBlackouts)
    },
    val marketSlippageReserveBps: BigDecimal = DEFAULT_MARKET_SLIPPAGE_RESERVE_BPS,
) {
    init {
        val maxRiskPerTradeIsPositive = maxRiskPerTradeRatio > BigDecimal.ZERO
        val maxRiskPerTradeIsAtOrBelowDefault = maxRiskPerTradeRatio <= DEFAULT_MAX_RISK_PER_TRADE_RATIO
        val maxRiskPerTradeIsConservative = maxRiskPerTradeIsPositive && maxRiskPerTradeIsAtOrBelowDefault
        val maxDrawdownIsAtOrAboveDefault = maxDrawdownRatio >= SafetyFloorDefaults.maxDrawdownRatio
        val maxDrawdownIsNegative = maxDrawdownRatio < BigDecimal.ZERO
        val maxDrawdownIsConservative = maxDrawdownIsAtOrAboveDefault && maxDrawdownIsNegative
        val maxTotalExposureIsPositive = maxTotalExposureRatio > BigDecimal.ZERO
        val maxTotalExposureIsAtOrBelowDefault = maxTotalExposureRatio <= DEFAULT_MAX_TOTAL_EXPOSURE_RATIO
        val maxTotalExposureIsConservative = maxTotalExposureIsPositive && maxTotalExposureIsAtOrBelowDefault
        val maxOrderFeeIsNonNegative = maxTakerFeeRatio >= BigDecimal.ZERO
        val maxOrderFeeIsAtOrBelowDefault = maxTakerFeeRatio <= DEFAULT_MAX_TAKER_FEE_RATIO
        val maxOrderFeeIsConservative = maxOrderFeeIsNonNegative && maxOrderFeeIsAtOrBelowDefault

        require(maxRiskPerTradeIsConservative) {
            "maxRiskPerTradeRatio must be greater than 0 and less than or equal to 0.02."
        }
        require(maxDrawdownIsConservative) {
            "maxDrawdownRatio must be greater than or equal to -0.15 and less than 0."
        }
        require(maxTotalExposureIsConservative) {
            "maxTotalExposureRatio must be greater than 0 and less than or equal to 0.80."
        }
        require(minExpectedValueR >= DEFAULT_MIN_EXPECTED_VALUE_R) {
            "minExpectedValueR must be greater than or equal to 0.10."
        }
        require(minExpectedMoveToCostRatio >= SafetyFloorDefaults.minExpectedMoveToCostRatio) {
            val defaultRatio = SafetyFloorDefaults.minExpectedMoveToCostRatio.toPlainString()
            "minExpectedMoveToCostRatio must be greater than or equal to $defaultRatio."
        }
        require(maxOrderFeeIsConservative) {
            "maxTakerFeeRatio must stay between 0 and 0.0010 as the order fee / maker rebate cap."
        }
        require(marketSlippageReserveBps >= DEFAULT_MARKET_SLIPPAGE_RESERVE_BPS) {
            "marketSlippageReserveBps must be greater than or equal to 5."
        }
    }
}

/**
 * active runtime config に束縛された最大 drawdown 判定 policy。
 *
 * @param thresholdRatio HARD_HALT に到達したとみなす drawdown ratio
 */
class MaxDrawdownPolicy(
    val thresholdRatio: BigDecimal = SafetyFloorDefaults.maxDrawdownRatio,
) {
    init {
        require(thresholdRatio < BigDecimal.ZERO) {
            "thresholdRatio must be less than 0."
        }
    }

    /** drawdown が HARD_HALT 閾値へ到達しているなら true を返す。 */
    fun isHardHalt(drawdownRatio: BigDecimal): Boolean {
        return drawdownRatio.compareTo(thresholdRatio) <= 0
    }

    /** active config と policy が同じ数値の threshold に束縛されていることを検証する。 */
    fun requireMatches(config: SafetyFloorConfig) {
        require(config.maxDrawdownRatio.compareTo(thresholdRatio) == 0) {
            "maxDrawdownRatio must match MaxDrawdownPolicy thresholdRatio."
        }
    }
}

/**
 * 市場データ鮮度劣化時に EV 計算だけへ適用する probability cap。
 *
 * @param staleAfter この時間を超えて古い市場データを stale とみなす
 * @param cappedProbability stale 時に EV 計算へ使う p の上限
 */
data class DataQualityCapConfig(
    val staleAfter: Duration = DEFAULT_DATA_QUALITY_STALE_AFTER,
    val cappedProbability: BigDecimal = DEFAULT_DATA_QUALITY_CAPPED_PROBABILITY,
) {
    init {
        val staleAfterIsPositive = !staleAfter.isNegative && !staleAfter.isZero
        val staleAfterIsConservative = staleAfterIsPositive && staleAfter <= DEFAULT_DATA_QUALITY_STALE_AFTER
        val cappedProbabilityInRange = cappedProbability >= BigDecimal.ZERO && cappedProbability <= BigDecimal.ONE
        val cappedProbabilityIsConservative = cappedProbabilityInRange && cappedProbability <= DEFAULT_DATA_QUALITY_CAPPED_PROBABILITY

        require(staleAfterIsConservative) {
            "staleAfter must be greater than 0 and less than or equal to ${DEFAULT_DATA_QUALITY_STALE_AFTER.seconds} seconds."
        }
        require(cappedProbabilityIsConservative) {
            "cappedProbability must be between 0 and ${DEFAULT_DATA_QUALITY_CAPPED_PROBABILITY.toPlainString()}."
        }
    }
}

/**
 * SafetyFloor 検証に必要な最新状態。
 *
 * @param account paper account snapshot
 * @param riskState DB risk_state snapshot
 * @param positions open position 一覧
 * @param openOrders open order 一覧
 * @param tradeGroupOrders 評価対象 trade group に紐づく order 履歴
 * @param tradeGroupExecutions 評価対象 trade group に紐づく execution 履歴
 * @param ticker 最新 ticker
 * @param orderbook SafetyFloor 評価時点で取得できた板情報
 * @param orderbookLookupAttempted 板情報の取得を試みたなら true
 * @param symbolRules 取引所 symbol rule
 * @param entryIntent entry intent / falsification / consumption snapshot
 * @param atr14Jpy 5分足 ATR(14)
 * @param marketDataObservedAt EV 判定に使った ticker が取引所から返した時刻
 */
data class SafetyFloorContext(
    val account: AccountSnapshot,
    val riskState: RiskState,
    val positions: List<Position>,
    val openOrders: List<Order>,
    val tradeGroupOrders: List<Order> = emptyList(),
    val tradeGroupExecutions: List<Execution> = emptyList(),
    val ticker: Ticker,
    val orderbook: Orderbook? = null,
    val orderbookLookupAttempted: Boolean = false,
    val symbolRules: SymbolRules,
    val entryIntent: EntryIntentSafetySnapshot? = null,
    val atr14Jpy: BigDecimal? = null,
    val marketDataObservedAt: Instant? = null,
)

/**
 * SafetyFloor の検証結果。
 */
sealed interface SafetyFloorVerdict {
    /**
     * 安全床を通過した。
     */
    data object Accepted : SafetyFloorVerdict

    /**
     * 安全床により拒否された。
     *
     * @param violation 拒否内容
     */
    data class Rejected(
        val violation: SafetyViolation,
    ) : SafetyFloorVerdict
}

/**
 * SafetyFloor 拒否の監査内容。
 *
 * @param id violation ID
 * @param rule 違反した rule
 * @param messageJa 呼び出し元向け日本語 message
 * @param measuredValue 実測または申告された値
 * @param limitValue 安全床の上限または下限
 * @param commandName tool / command 名
 * @param commandId command ID
 * @param orderId 関連 order ID
 * @param decisionRunId decision run ID
 * @param toolCallId tool call ID
 * @param clientRequestId client request ID
 * @param hardHaltRequired HARD_HALT と掃引が必要か
 * @param payloadJson 監査用 JSON payload
 * @param createdAt 作成時刻
 */
data class SafetyViolation(
    val id: UUID = UUID.randomUUID(),
    val rule: SafetyFloorRule,
    val messageJa: String,
    val measuredValue: String,
    val limitValue: String,
    val commandName: String,
    val commandId: UUID?,
    val orderId: UUID?,
    val decisionRunId: String?,
    val toolCallId: String?,
    val clientRequestId: String?,
    val hardHaltRequired: Boolean,
    val payloadJson: String,
    val createdAt: Instant,
)

/**
 * SafetyFloor 拒否理由の rule と比較値。
 *
 * @param rule 違反した rule
 * @param messageJa 呼び出し元向け日本語 message
 * @param measuredValue 実測または申告された値
 * @param limitValue 安全床の上限または下限
 * @param hardHaltRequired HARD_HALT と掃引が必要か
 */
private data class SafetyViolationDetails(
    val rule: SafetyFloorRule,
    val messageJa: String,
    val measuredValue: String,
    val limitValue: String,
    val hardHaltRequired: Boolean = false,
)

/**
 * place_order / preview_order の SafetyFloor 計算詳細。
 *
 * @param estimatedEntryPriceJpy SafetyFloor が見積もった entry 価格
 * @param orderRiskJpy 今回注文単体の最大損失見積もり
 * @param groupRiskBeforeOrderJpy 注文前 trade group risk
 * @param groupRiskAfterOrderJpy 注文後 trade group risk
 * @param maxRiskPerTradeJpy 1 trade group の risk 上限
 * @param currentExposureJpy 注文前 exposure
 * @param orderExposureJpy 今回注文単体の exposure
 * @param totalExposureAfterOrderJpy 注文後 exposure
 * @param maxTotalExposureJpy total exposure 上限
 * @param availableCashJpy 未約定買い予約を差し引いた利用可能 cash
 * @param requiredCashJpy 今回注文に必要な cash 見積もり
 * @param expectedValueR EV の R 倍
 * @param expectedMoveToCostRatio 期待値幅 / 往復 cost 比
 * @param probabilityUsedForExpectedValue EV 計算に使った勝率
 * @param probabilityCapApplied データ鮮度 cap が適用されたか
 */
data class SafetyFloorPlaceOrderRiskDetails(
    val estimatedEntryPriceJpy: String,
    val orderRiskJpy: String,
    val groupRiskBeforeOrderJpy: String,
    val groupRiskAfterOrderJpy: String,
    val maxRiskPerTradeJpy: String,
    val currentExposureJpy: String,
    val orderExposureJpy: String,
    val totalExposureAfterOrderJpy: String,
    val maxTotalExposureJpy: String,
    val availableCashJpy: String,
    val requiredCashJpy: String,
    val expectedValueR: String?,
    val expectedMoveToCostRatio: String?,
    val probabilityUsedForExpectedValue: String,
    val probabilityCapApplied: Boolean,
)

/**
 * Broker 副作用の手前で強制する安全床。
 *
 * @param config 安全床しきい値
 * @param clock violation timestamp に使う clock
 * @param paperExecutionConfig paper 約定近似設定
 * @param maxDrawdownPolicy active runtime config に束縛された最大 drawdown policy
 */
@Suppress("LargeClass") // placement と fill が同じ rule authority を共有するため、rule set を分散させない。
class SafetyFloor(
    private val config: SafetyFloorConfig = SafetyFloorConfig(),
    private val clock: Clock = Clock.systemUTC(),
    private val paperExecutionConfig: PaperExecutionConfig = PaperExecutionConfig(),
    internal val maxDrawdownPolicy: MaxDrawdownPolicy = MaxDrawdownPolicy(config.maxDrawdownRatio),
) {
    private val riskCalculator = SafetyFloorRiskCalculator(config, clock, paperExecutionConfig)

    init {
        maxDrawdownPolicy.requireMatches(config)
    }

    /**
     * place_order の entry intent を検証する。
     */
    fun evaluatePlaceOrder(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyFloorVerdict {
        detectHardHalt(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        detectSoftHalt(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateEconomicEventBlackout(command)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateFalsifierGate(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateStopLoss(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateNoAveragingDown(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validatePyramidingGates(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateSymbolFeeRates(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateGroupRisk(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateTotalExposure(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateBalanceAndCost(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateExpectedValue(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateExpectedMoveToCost(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }

        return SafetyFloorVerdict.Accepted
    }

    /**
     * resting entry の約定直前に、現在状態で変化し得る安全条件だけを再評価する。
     *
     * intent consumption と fresh falsification は placement 時だけの条件なので再評価しない。
     */
    internal fun evaluateRestingEntryFill(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyFloorVerdict {
        detectHardHalt(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        detectSoftHalt(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateEconomicEventBlackout(command)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateStopLoss(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateNoAveragingDown(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validatePyramidingGates(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateSymbolFeeRates(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateGroupRisk(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateTotalExposure(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateBalanceAndCost(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateExpectedValue(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateExpectedMoveToCost(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }

        return SafetyFloorVerdict.Accepted
    }

    /**
     * place_order / preview_order の主要な risk 計算詳細を返す。
     */
    fun placeOrderRiskDetails(
        command: PlaceOrderCommand,
        context: SafetyFloorContext,
    ): SafetyFloorPlaceOrderRiskDetails {
        return riskCalculator.placeOrderRiskDetails(command, context)
    }

    /**
     * update_protection の STOP 更新だけを硬く検証する。TP は soft 領域なので制限しない。
     */
    fun evaluateUpdateProtection(command: UpdateProtectionCommand, context: SafetyFloorContext): SafetyFloorVerdict {
        detectHardHalt(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }

        val newStopPrice = command.newStopPriceJpy ?: return SafetyFloorVerdict.Accepted
        val position = context.positions.firstOrNull { position ->
            position.positionId == command.positionId.toString()
        }
        if (position == null) {
            return SafetyFloorVerdict.Accepted
        }

        validateStopTightening(command, position, newStopPrice)?.let { violation ->
            return SafetyFloorVerdict.Rejected(violation)
        }
        validateTrailingFloor(command, position, newStopPrice, context)?.let { violation ->
            return SafetyFloorVerdict.Rejected(violation)
        }
        validateImmediateStop(command, context, newStopPrice)?.let { violation ->
            return SafetyFloorVerdict.Rejected(violation)
        }

        return SafetyFloorVerdict.Accepted
    }

    /**
     * close_position は risk-reducing 操作なので HARD_HALT 中も許可する。
     */
    @Suppress("UNUSED_PARAMETER")
    fun evaluateClosePosition(command: ClosePositionCommand, context: SafetyFloorContext): SafetyFloorVerdict {
        return SafetyFloorVerdict.Accepted
    }

    /**
     * cancel_order の実行前に HARD_HALT 到達だけを検証する。
     */
    fun evaluateCancelOrder(command: CancelOrderCommand, context: SafetyFloorContext): SafetyFloorVerdict {
        detectHardHalt(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }

        return SafetyFloorVerdict.Accepted
    }

    private fun detectHardHalt(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        return detectHardHalt(commandName = "place_order", command = command, context = context)
    }

    private fun detectHardHalt(command: UpdateProtectionCommand, context: SafetyFloorContext): SafetyViolation? {
        return detectHardHalt(commandName = "update_protection", command = command, context = context)
    }

    private fun detectHardHalt(command: CancelOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        return detectHardHalt(commandName = "cancel_order", command = command, context = context)
    }

    private fun detectHardHalt(
        commandName: String,
        command: Any,
        context: SafetyFloorContext,
    ): SafetyViolation? {
        val accountDrawdown = context.account.drawdownRatio.toBigDecimal()
        val riskStateDrawdown = context.riskState.drawdownRatio
        val measuredDrawdown = minOf(accountDrawdown, riskStateDrawdown)
        val hardHaltEnabled = context.riskState.state == RiskHaltState.HARD_HALT
        val hardHaltReached = hardHaltEnabled || maxDrawdownPolicy.isHardHalt(measuredDrawdown)

        if (!hardHaltReached) {
            return null
        }

        return violation(
            commandName = commandName,
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.MAX_DRAWDOWN_HALT,
                messageJa = "最大 DD が HARD_HALT 閾値に到達したため、全注文取消と全建玉 close を実行して取引を停止します。",
                measuredValue = measuredDrawdown.toPlainString(),
                limitValue = maxDrawdownPolicy.thresholdRatio.toPlainString(),
                hardHaltRequired = true,
            ),
        )
    }

    private fun detectSoftHalt(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        if (context.riskState.state != RiskHaltState.SOFT_HALT) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.SOFT_HALT_ENTRY_BLOCKED,
                messageJa = "SOFT_HALT 中のため、新規 entry は拒否します。",
                measuredValue = RiskHaltState.SOFT_HALT.name,
                limitValue = RiskHaltState.RUNNING.name,
            ),
        )
    }

    private fun validateFalsifierGate(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        val intentId = command.intentId
            ?: return violation(
                commandName = "place_order",
                command = command,
                details = SafetyViolationDetails(
                    rule = SafetyFloorRule.MISSING_FRESH_FALSIFICATION,
                    messageJa = "intent_id がないため、新規 entry は拒否します。",
                    measuredValue = "null",
                    limitValue = "fresh_approved_intent_required",
                ),
            )
        val snapshot = context.entryIntent
            ?: return violation(
                commandName = "place_order",
                command = command,
                details = SafetyViolationDetails(
                    rule = SafetyFloorRule.MISSING_FRESH_FALSIFICATION,
                    messageJa = "指定された intent_id に対応する fresh APPROVED falsification がありません。",
                    measuredValue = intentId.toString(),
                    limitValue = "fresh_approved_intent_required",
                ),
            )

        if (snapshot.consumed) {
            return violation(
                commandName = "place_order",
                command = command,
                details = SafetyViolationDetails(
                    rule = SafetyFloorRule.INTENT_CONSUMED,
                    messageJa = "指定された intent_id は既に消費済みです。",
                    measuredValue = intentId.toString(),
                    limitValue = "unused_intent_required",
                ),
            )
        }
        if (!snapshot.freshApproved) {
            val verdict = snapshot.falsification?.verdict?.name ?: "none"

            return violation(
                commandName = "place_order",
                command = command,
                details = SafetyViolationDetails(
                    rule = SafetyFloorRule.MISSING_FRESH_FALSIFICATION,
                    messageJa = "fresh な APPROVED falsification がないため、新規 entry は拒否します。",
                    measuredValue = verdict,
                    limitValue = "fresh_approved",
                ),
            )
        }

        return validateIntentMatchesCommand(command, snapshot.tradeIntent.draft)
    }

    private fun validateIntentMatchesCommand(command: PlaceOrderCommand, intent: EntryIntentDraft): SafetyViolation? {
        val symbolMatches = command.symbol == intent.symbol
        val sideMatches = command.side == intent.side
        val orderTypeMatches = command.orderType == intent.orderType
        val sizeMatches = command.sizeBtc.sameAmountAs(intent.sizeBtc)
        val entryPriceMatches = command.priceJpy.sameAmountAs(intent.priceJpy)
        val stopPriceMatches = command.protectiveStopPriceJpy.sameAmountAs(intent.protectiveStopPriceJpy)
        val takeProfitPriceMatches = command.takeProfitPriceJpy.sameAmountAs(intent.takeProfitPriceJpy)
        val intentMatches = listOf(
            symbolMatches,
            sideMatches,
            orderTypeMatches,
            sizeMatches,
            entryPriceMatches,
            stopPriceMatches,
            takeProfitPriceMatches,
        ).all { matched -> matched }

        if (intentMatches) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.INTENT_MISMATCH,
                messageJa = "entry intent の宣言値と place_order の実引数が一致しません。",
                measuredValue = command.intentComparisonValue(),
                limitValue = intent.intentComparisonValue(),
            ),
        )
    }

    private fun validateStopLoss(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        val entryPrice = riskCalculator.estimatedEntryPrice(command, context)
        val stopPrice = command.protectiveStopPriceJpy
        val stopLossValid = stopPrice > BigDecimal.ZERO && stopPrice < entryPrice

        if (stopLossValid) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.STOP_LOSS_REQUIRED,
                messageJa = "protective_stop_price_jpy は long entry の想定 entry 価格より下に必須です。",
                measuredValue = stopPrice.toPlainString(),
                limitValue = "entry_price_below_${entryPrice.toPlainString()}",
            ),
        )
    }

    private fun validateNoAveragingDown(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        val targetTradeGroupId = command.tradeGroupId?.toString()
        val targetPositions = context.positions.filterTargetPositions(targetTradeGroupId)
        val losingPosition = targetPositions.firstOrNull { position -> position.isLosingLong() }

        if (losingPosition == null) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.NO_AVERAGING_DOWN,
                messageJa = "含み損の BTC long に対する買い増しはナンピンとして拒否します。",
                measuredValue = losingPosition.unrealizedPnlJpy,
                limitValue = ">=0",
            ),
        )
    }

    private fun validatePyramidingGates(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        val targetTradeGroupId = command.tradeGroupId?.toString() ?: return null
        val targetPositions = context.positions.filterTargetPositions(targetTradeGroupId)

        if (targetPositions.isEmpty()) {
            return null
        }

        validatePyramidStopTightening(command, targetPositions)?.let { violation -> return violation }
        validatePyramidAddCount(command, targetPositions)?.let { violation -> return violation }
        validatePyramidProfit(command, targetPositions)?.let { violation -> return violation }
        validatePyramidAddRisk(command, context, targetTradeGroupId)?.let { violation -> return violation }

        return null
    }

    private fun validatePyramidStopTightening(
        command: PlaceOrderCommand,
        targetPositions: List<Position>,
    ): SafetyViolation? {
        val loosenedPosition = targetPositions.firstOrNull { position ->
            val currentStop = position.currentStopLossJpy?.toBigDecimal()

            currentStop != null && command.protectiveStopPriceJpy < currentStop
        } ?: return null
        val currentStop = requireNotNull(loosenedPosition.currentStopLossJpy)

        return violation(
            commandName = "place_order",
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.STOP_LOSS_LOOSENING,
                messageJa = "ADD_LONG の protective_stop_price_jpy は既存 STOP を損失拡大方向へ緩められません。",
                measuredValue = command.protectiveStopPriceJpy.toPlainString(),
                limitValue = ">=${currentStop.toBigDecimal().toPlainString()}",
            ),
        )
    }

    private fun validatePyramidAddCount(
        command: PlaceOrderCommand,
        targetPositions: List<Position>,
    ): SafetyViolation? {
        val maxAddCount = targetPositions.maxOf { position -> position.pyramidAddCount }

        if (maxAddCount < MAX_PYRAMID_ADD_COUNT) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.PYRAMID_ADD_LIMIT,
                messageJa = "ピラミッディング追加回数が上限 2 回に達しているため、ADD_LONG を拒否します。",
                measuredValue = maxAddCount.toString(),
                limitValue = "<$MAX_PYRAMID_ADD_COUNT",
            ),
        )
    }

    private fun validatePyramidProfit(command: PlaceOrderCommand, targetPositions: List<Position>): SafetyViolation? {
        val insufficientPosition = targetPositions.firstOrNull { position ->
            val requiredR = BigDecimal(position.pyramidAddCount + 1)

            position.unrealizedR.toBigDecimal() < requiredR
        } ?: return null
        val requiredR = BigDecimal(insufficientPosition.pyramidAddCount + 1)

        return violation(
            commandName = "place_order",
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.PYRAMID_PROFIT_GATE,
                messageJa = "含み益 R がピラミッディング条件に届かないため、ADD_LONG を拒否します。",
                measuredValue = insufficientPosition.unrealizedR,
                limitValue = ">=${requiredR.toPlainString()}",
            ),
        )
    }

    private fun validatePyramidAddRisk(
        command: PlaceOrderCommand,
        context: SafetyFloorContext,
        targetTradeGroupId: String,
    ): SafetyViolation? {
        val addRisk = riskCalculator.orderRisk(command, context)
        val initialRiskBudget = riskCalculator.initialTradeGroupRiskBudget(context, targetTradeGroupId)
            ?: riskCalculator.groupRiskBeforeOrder(context, targetTradeGroupId)
        val addRiskLimit = initialRiskBudget
            .multiply(PYRAMID_ADD_RISK_RATIO)
            .safetyScale()

        if (addRisk <= addRiskLimit) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.PYRAMID_ADD_RISK_LIMIT,
                messageJa = "ADD_LONG の追加 risk が初回 risk budget の 50% を超えるため拒否します。",
                measuredValue = addRisk.toPlainString(),
                limitValue = addRiskLimit.toPlainString(),
            ),
        )
    }

    private fun validateGroupRisk(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        val groupRisk = riskCalculator.groupRiskAfterOrder(command, context)
        val limit = context.account.totalEquityJpy.toBigDecimal()
            .multiply(config.maxRiskPerTradeRatio)
            .safetyScale()

        if (groupRisk <= limit) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.MAX_RISK_PER_TRADE,
                messageJa = "trade group の最大損失見積もりが equity の 2% を超えています。",
                measuredValue = groupRisk.toPlainString(),
                limitValue = limit.toPlainString(),
            ),
        )
    }

    private fun validateTotalExposure(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        val exposureAfterOrder = riskCalculator.currentExposure(context)
            .add(riskCalculator.orderExposure(command, context))
            .safetyScale()
        val limit = context.account.totalEquityJpy.toBigDecimal()
            .multiply(config.maxTotalExposureRatio)
            .safetyScale()

        if (exposureAfterOrder <= limit) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.MAX_TOTAL_EXPOSURE,
                messageJa = "合計 exposure が equity の 80% 上限を超えています。",
                measuredValue = exposureAfterOrder.toPlainString(),
                limitValue = limit.toPlainString(),
            ),
        )
    }

    private fun validateSymbolFeeRates(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        val unsafeReason = unsafeOrderFeeRateReasonOrNull(
            symbolRules = context.symbolRules,
            maxFeeRate = config.maxTakerFeeRatio,
        )

        if (unsafeReason != null) {
            val measuredFeeRates = "takerFee=${context.symbolRules.takerFee}, makerFee=${context.symbolRules.makerFee}; $unsafeReason"

            return violation(
                commandName = "place_order",
                command = command,
                details = SafetyViolationDetails(
                    rule = SafetyFloorRule.BALANCE_RATE_AND_COST_LIMIT,
                    messageJa = "取引所 fee rate が SafetyFloor の許容範囲外です。",
                    measuredValue = measuredFeeRates,
                    limitValue = config.maxTakerFeeRatio.toPlainString(),
                ),
            )
        }

        return null
    }

    private fun validateBalanceAndCost(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        val availableCash = riskCalculator.availableCash(context)
        val requiredCash = riskCalculator.orderRequiredCash(command, context)
        if (requiredCash <= availableCash) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.BALANCE_RATE_AND_COST_LIMIT,
                messageJa = "paper JPY 残高と未約定買い予約を超える発注は拒否します。",
                measuredValue = requiredCash.toPlainString(),
                limitValue = availableCash.toPlainString(),
            ),
        )
    }

    private fun validateExpectedValue(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        val probability = command.estimatedWinProbability
        val probabilityInRange = probability >= BigDecimal.ZERO && probability <= BigDecimal.ONE

        if (!probabilityInRange) {
            return violation(
                commandName = "place_order",
                command = command,
                details = SafetyViolationDetails(
                    rule = SafetyFloorRule.INVALID_WIN_PROBABILITY,
                    messageJa = "estimated_win_probability は 0 以上 1 以下である必要があります。",
                    measuredValue = probability.toPlainString(),
                    limitValue = "0..1",
                ),
            )
        }

        val takeProfitPrice = command.takeProfitPriceJpy
            ?: return violation(
                commandName = "place_order",
                command = command,
                details = SafetyViolationDetails(
                    rule = SafetyFloorRule.MISSING_TARGET_PRICE,
                    messageJa = "take_profit_price_jpy がないため、EV を計算できない entry は拒否します。",
                    measuredValue = "null",
                    limitValue = "required",
                ),
            )
        val expectedValueDetails = riskCalculator.expectedValueDetails(command, context, takeProfitPrice)
        val expectedValueR = expectedValueDetails.expectedValueR
        val probabilityCapSuffix = if (expectedValueDetails.probabilityCapApplied) {
            probabilityCapSuffix(expectedValueDetails.probabilityUsed)
        } else {
            ""
        }

        if (expectedValueR <= BigDecimal.ZERO) {
            return violation(
                commandName = "place_order",
                command = command,
                details = SafetyViolationDetails(
                    rule = SafetyFloorRule.NON_POSITIVE_EXPECTED_VALUE,
                    messageJa = "推定勝率、目標価格、STOP、往復 cost から計算した EV が 0 以下です。$probabilityCapSuffix",
                    measuredValue = expectedValueR.toPlainString(),
                    limitValue = ">0",
                ),
            )
        }

        if (expectedValueR >= config.minExpectedValueR) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.EXPECTED_VALUE_GATE,
                messageJa = "推定勝率、目標価格、STOP、往復 cost から計算した EV が保守的な初期しきい値を下回っています。$probabilityCapSuffix",
                measuredValue = expectedValueR.toPlainString(),
                limitValue = config.minExpectedValueR.toPlainString(),
            ),
        )
    }

    private fun validateExpectedMoveToCost(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        val impliedRatio = riskCalculator.expectedMoveToCostRatioOrNull(command, context) ?: return null

        if (impliedRatio >= config.minExpectedMoveToCostRatio) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.EXPECTED_MOVE_TO_COST_RATIO,
                messageJa = "TP から逆算した想定値幅 / 往復 cost 比が不足しています。",
                measuredValue = impliedRatio.toPlainString(),
                limitValue = config.minExpectedMoveToCostRatio.toPlainString(),
            ),
        )
    }

    private fun validateStopTightening(
        command: UpdateProtectionCommand,
        position: Position,
        newStopPrice: BigDecimal,
    ): SafetyViolation? {
        val currentStop = position.currentStopLossJpy?.toBigDecimal() ?: return null

        if (newStopPrice >= currentStop) {
            return null
        }

        return violation(
            commandName = "update_protection",
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.STOP_LOSS_LOOSENING,
                messageJa = "STOP を損失拡大方向へ緩める update_protection は拒否します。",
                measuredValue = newStopPrice.toPlainString(),
                limitValue = ">=${currentStop.toPlainString()}",
            ),
        )
    }

    private fun validateTrailingFloor(
        command: UpdateProtectionCommand,
        position: Position,
        newStopPrice: BigDecimal,
        context: SafetyFloorContext,
    ): SafetyViolation? {
        val atrValue = context.atr14Jpy ?: return null
        val atrFloor = position.highestPriceSinceEntryJpy.toBigDecimal()
            .subtract(atrValue.multiply(SafetyFloorDefaults.trailingAtrMultiplier))
            .floorToStep(context.symbolRules.tickSize.toBigDecimal())

        if (newStopPrice >= atrFloor) {
            return null
        }

        return violation(
            commandName = "update_protection",
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.ATR_TRAILING_FLOOR,
                messageJa = "STOP を ATR trailing 床より下へ置く update_protection は拒否します。",
                measuredValue = newStopPrice.toPlainString(),
                limitValue = ">=${atrFloor.toPlainString()}",
            ),
        )
    }

    private fun validateImmediateStop(
        command: UpdateProtectionCommand,
        context: SafetyFloorContext,
        newStopPrice: BigDecimal,
    ): SafetyViolation? {
        val bidPrice = context.ticker.bid.toBigDecimal()

        if (newStopPrice < bidPrice) {
            return null
        }

        return violation(
            commandName = "update_protection",
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.IMMEDIATE_STOP_TRIGGER,
                messageJa = "SELL STOP が即時約定方向の価格なので拒否します。",
                measuredValue = newStopPrice.toPlainString(),
                limitValue = "<${bidPrice.toPlainString()}",
            ),
        )
    }

    private fun violation(
        commandName: String,
        command: Any,
        details: SafetyViolationDetails,
    ): SafetyViolation {
        val auditContext = command.auditContextOrNull()

        return SafetyViolation(
            rule = details.rule,
            messageJa = details.messageJa,
            measuredValue = details.measuredValue,
            limitValue = details.limitValue,
            commandName = commandName,
            commandId = command.commandIdOrNull(),
            orderId = command.orderIdOrNull(),
            decisionRunId = auditContext?.decisionRunContext?.decisionRunId,
            toolCallId = auditContext?.toolCallId,
            clientRequestId = auditContext?.clientRequestId,
            hardHaltRequired = details.hardHaltRequired,
            payloadJson = violationPayload(
                rule = details.rule,
                measuredValue = details.measuredValue,
                limitValue = details.limitValue,
                hardHaltRequired = details.hardHaltRequired,
            ),
            createdAt = Instant.now(clock),
        )
    }

    private fun validateEconomicEventBlackout(command: PlaceOrderCommand): SafetyViolation? {
        val observedAt = Instant.now(clock)
        val calendarState = config.fomcBlackoutCalendar.stateAt(observedAt)
        val calendarRule = when (calendarState) {
            FomcBlackoutCalendarState.ACTIVE -> null
            FomcBlackoutCalendarState.MISSING -> SafetyFloorRule.FOMC_CALENDAR_MISSING
            FomcBlackoutCalendarState.INVALID -> SafetyFloorRule.FOMC_CALENDAR_INVALID
            FomcBlackoutCalendarState.EXPIRED -> SafetyFloorRule.FOMC_CALENDAR_EXPIRED
        }
        if (calendarRule != null) {
            return violation(
                commandName = "place_order",
                command = command,
                details = SafetyViolationDetails(
                    rule = calendarRule,
                    messageJa = "FOMC calendar が ${calendarState.name} のため、新規 entry は拒否します。",
                    measuredValue = calendarState.name,
                    limitValue = config.fomcBlackoutCalendar.validThrough?.toString() ?: "valid FOMC calendar required",
                ),
            )
        }

        val activeEvent = config.fomcBlackoutCalendar.events.firstOrNull { event -> event.contains(observedAt) }
            ?: return null

        val activeWindow = requireNotNull(activeEvent.toSafeWindow())

        return violation(
            commandName = "place_order",
            command = command,
            details = SafetyViolationDetails(
                rule = SafetyFloorRule.ECONOMIC_EVENT_BLACKOUT,
                messageJa = "高影響経済イベント ${activeEvent.eventName} の blackout window 中のため、新規 entry は拒否します。",
                measuredValue = observedAt.toString(),
                limitValue = "${activeWindow.startsAt}..${activeWindow.endsAt}",
            ),
        )
    }

    private fun probabilityCapSuffix(cappedProbability: BigDecimal): String {
        return "データ鮮度劣化により p を ${cappedProbability.toPlainString()} に cap しました。"
    }
}

/**
 * resting entry fill 時に current invariant だけを評価する evaluator。
 *
 * @param safetyFloor placement と同じ active config を使う安全床
 */
class RestingEntryFillInvariantEvaluator(
    private val safetyFloor: SafetyFloor,
) {
    /** current DB state から構築した context で fill 可否を返す。 */
    fun evaluate(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyFloorVerdict {
        return safetyFloor.evaluateRestingEntryFill(command, context)
    }
}

internal fun List<Position>.filterTargetPositions(tradeGroupId: String?): List<Position> {
    if (tradeGroupId != null) {
        return filter { position -> position.tradeGroupId == tradeGroupId && position.status == PositionStatus.OPEN }
    }

    return filter { position -> position.status == PositionStatus.OPEN }
}

internal fun List<Order>.filterTargetOrders(tradeGroupId: String?): List<Order> {
    if (tradeGroupId != null) {
        return filter { order -> order.tradeGroupId == tradeGroupId }
    }

    return this
}

private fun Position.isLosingLong(): Boolean {
    val unrealizedLoss = unrealizedPnlJpy.toBigDecimal() < BigDecimal.ZERO
    val belowEntry = currentPriceJpy.toBigDecimal() < averageEntryPriceJpy.toBigDecimal()

    return unrealizedLoss || belowEntry
}

private fun BigDecimal?.sameAmountAs(other: BigDecimal?): Boolean {
    if (this == null || other == null) {
        return this == null && other == null
    }

    return compareTo(other) == 0
}

private fun PlaceOrderCommand.intentComparisonValue(): String {
    return intentComparisonValue(
        value = EntryIntentComparisonValue(
            symbolText = symbol.apiSymbol,
            sideText = side.name,
            orderTypeText = orderType.name,
            sizeBtc = sizeBtc,
            priceJpy = priceJpy,
            protectiveStopPriceJpy = protectiveStopPriceJpy,
            takeProfitPriceJpy = takeProfitPriceJpy,
        ),
    )
}

private fun EntryIntentDraft.intentComparisonValue(): String {
    return intentComparisonValue(
        value = EntryIntentComparisonValue(
            symbolText = symbol.apiSymbol,
            sideText = side.name,
            orderTypeText = orderType.name,
            sizeBtc = sizeBtc,
            priceJpy = priceJpy,
            protectiveStopPriceJpy = protectiveStopPriceJpy,
            takeProfitPriceJpy = takeProfitPriceJpy,
        ),
    )
}

private fun intentComparisonValue(value: EntryIntentComparisonValue): String {
    return "symbol=${value.symbolText},side=${value.sideText},type=${value.orderTypeText}," +
        "size=${value.sizeBtc.toPlainString()},entry=${value.priceJpy?.toPlainString()}," +
        "stop=${value.protectiveStopPriceJpy.toPlainString()},tp=${value.takeProfitPriceJpy?.toPlainString()}"
}

/**
 * entry intent と place_order の比較文字列に使う値。
 *
 * @param symbolText symbol 表示文字列
 * @param sideText side 表示文字列
 * @param orderTypeText order type 表示文字列
 * @param sizeBtc BTC size
 * @param priceJpy entry price
 * @param protectiveStopPriceJpy protective stop price
 * @param takeProfitPriceJpy take profit price
 */
private data class EntryIntentComparisonValue(
    val symbolText: String,
    val sideText: String,
    val orderTypeText: String,
    val sizeBtc: BigDecimal,
    val priceJpy: BigDecimal?,
    val protectiveStopPriceJpy: BigDecimal,
    val takeProfitPriceJpy: BigDecimal?,
)

internal fun BigDecimal.maxZero(): BigDecimal {
    return maxOf(this, BigDecimal.ZERO)
}

internal fun BigDecimal.divideOrZero(divisor: BigDecimal): BigDecimal {
    if (divisor.compareTo(BigDecimal.ZERO) == 0) {
        return BigDecimal.ZERO.safetyScale()
    }

    return divide(divisor, SAFETY_SCALE, RoundingMode.HALF_UP).safetyScale()
}

internal fun BigDecimal.safetyScale(): BigDecimal {
    return setScale(SAFETY_SCALE, RoundingMode.HALF_UP)
}

internal fun BigDecimal.floorToStep(step: BigDecimal): BigDecimal {
    if (step.compareTo(BigDecimal.ZERO) == 0) {
        return this
    }

    return divide(step, 0, RoundingMode.DOWN)
        .multiply(step)
        .safetyScale()
}

private fun Any.auditContextOrNull() = when (this) {
    is PlaceOrderCommand -> auditContext
    is UpdateProtectionCommand -> auditContext
    is ClosePositionCommand -> auditContext
    is CancelOrderCommand -> auditContext
    else -> null
}

private fun Any.commandIdOrNull(): UUID? = when (this) {
    is PlaceOrderCommand -> commandId
    is UpdateProtectionCommand -> commandId
    is ClosePositionCommand -> commandId
    is CancelOrderCommand -> commandId
    else -> null
}

private fun Any.orderIdOrNull(): UUID? = when (this) {
    is CancelOrderCommand -> orderId
    else -> null
}

private fun violationPayload(
    rule: SafetyFloorRule,
    measuredValue: String,
    limitValue: String,
    hardHaltRequired: Boolean,
): String {
    return """
        {
          "rule": "${rule.name}",
          "measuredValue": "$measuredValue",
          "limitValue": "$limitValue",
          "hardHaltRequired": $hardHaltRequired
        }
    """.trimIndent()
}

/**
 * SafetyFloor 計算 scale。
 */
internal const val SAFETY_SCALE = 8

/**
 * ピラミッディングの最大追加回数。
 */
private const val MAX_PYRAMID_ADD_COUNT = 2

/**
 * 追加 risk の初回 risk budget に対する上限比率。
 */
private val PYRAMID_ADD_RISK_RATIO = BigDecimal("0.50")

/**
 * bps 分母。
 */
internal val BPS_DIVISOR = BigDecimal("10000")

/**
 * 1 trade group の既定最大損失割合。
 */
private val DEFAULT_MAX_RISK_PER_TRADE_RATIO = BigDecimal("0.02")

/**
 * 既定 exposure 上限。
 */
private val DEFAULT_MAX_TOTAL_EXPOSURE_RATIO = BigDecimal("0.80")

/**
 * entry plan の既定最小 EV。
 */
private val DEFAULT_MIN_EXPECTED_VALUE_R = BigDecimal("0.10")

/**
 * 既定 order fee / maker rebate 絶対値上限。
 */
private val DEFAULT_MAX_TAKER_FEE_RATIO = BigDecimal("0.0010")

/**
 * 片道 slippage reserve。
 */
private val DEFAULT_MARKET_SLIPPAGE_RESERVE_BPS = BigDecimal("5")

/**
 * データ品質 p cap の既定 stale 秒数。
 */
private val DEFAULT_DATA_QUALITY_STALE_AFTER = Duration.ofSeconds(60)

/**
 * データ品質 p cap の既定 probability 上限。
 */
private val DEFAULT_DATA_QUALITY_CAPPED_PROBABILITY = BigDecimal("0.5")
