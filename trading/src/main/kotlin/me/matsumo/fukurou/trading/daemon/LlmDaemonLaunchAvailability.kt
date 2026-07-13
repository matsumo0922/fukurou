package me.matsumo.fukurou.trading.daemon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.matsumo.fukurou.trading.exchange.gmo.GmoExchangeStatus
import me.matsumo.fukurou.trading.exchange.gmo.GmoExchangeStatusReader
import me.matsumo.fukurou.trading.exchange.gmo.GmoMonotonicTimeSource
import me.matsumo.fukurou.trading.exchange.gmo.SystemGmoMonotonicTimeSource
import me.matsumo.fukurou.trading.market.GmoApiStatusException
import me.matsumo.fukurou.trading.market.MarketDataParseException
import java.net.http.HttpTimeoutException
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** LLM daemon の自動起動を抑止する infrastructure reason。 */
enum class LlmDaemonLaunchSuppressionReason {
    /** GMO 公式の毎週土曜日 09:00〜11:00（JST）に該当する。 */
    SCHEDULED_MAINTENANCE,

    /** GMO status API が maintenance 中を返した。 */
    STATUS_MAINTENANCE,

    /** GMO status API が pre-open を返した。 */
    STATUS_PREOPEN,

    /** GMO status API が timeout した。 */
    STATUS_TIMEOUT,

    /** GMO status API response を解釈できなかった。 */
    STATUS_MALFORMED,

    /** GMO status API の transport または request audit が失敗した。 */
    STATUS_TRANSPORT_FAILURE,
}

/** scheduler-only の LLM 自動起動可否を返す境界。 */
interface LlmDaemonLaunchAvailability {
    /** 既知の定期メンテナンス窓なら network access 前に抑止理由を返す。 */
    fun scheduledSuppressionAt(observedAt: Instant): LlmDaemonLaunchSuppressionReason?

    /** 定期窓外の起動候補に対し、GMO status に基づく抑止理由を返す。 */
    suspend fun statusSuppressionAt(observedAt: Instant): LlmDaemonLaunchSuppressionReason?
}

/** test や scheduler 以外の composition で使う常時許可境界。 */
object AlwaysAvailableLlmDaemonLaunchAvailability : LlmDaemonLaunchAvailability {
    override fun scheduledSuppressionAt(observedAt: Instant): LlmDaemonLaunchSuppressionReason? = null

    override suspend fun statusSuppressionAt(observedAt: Instant): LlmDaemonLaunchSuppressionReason? = null
}

/**
 * GMO の公式定期窓と Public API status から scheduler の自動起動可否を決める。
 *
 * cache は scheduler/client instance ごとに直近 1 entry だけを保持する。失敗も cache し、
 * status endpoint 障害時の request storm を避けながら fail closed を維持する。
 *
 * @param statusReader GMO status 読み取り境界
 * @param cacheTtl status 結果を再利用する期間
 * @param marketZone 公式定期窓を判定する timezone
 */
class GmoLlmDaemonLaunchAvailability(
    private val statusReader: GmoExchangeStatusReader,
    private val cacheTtl: Duration = DEFAULT_GMO_STATUS_CACHE_TTL,
    private val marketZone: ZoneId = GMO_MAINTENANCE_ZONE,
    private val monotonicTimeSource: GmoMonotonicTimeSource = SystemGmoMonotonicTimeSource,
) : LlmDaemonLaunchAvailability {

    private val cacheMutex = Mutex()
    private var cachedResult: CachedStatusResult? = null

    init {
        require(!cacheTtl.isNegative && !cacheTtl.isZero) {
            "cacheTtl must be positive: $cacheTtl"
        }
    }

    override fun scheduledSuppressionAt(observedAt: Instant): LlmDaemonLaunchSuppressionReason? {
        val localDateTime = observedAt.atZone(marketZone)
        val isSaturday = localDateTime.dayOfWeek == DayOfWeek.SATURDAY
        val localTime = localDateTime.toLocalTime()
        val isMaintenanceTime = !localTime.isBefore(GMO_MAINTENANCE_START) && localTime.isBefore(GMO_MAINTENANCE_END)

        return LlmDaemonLaunchSuppressionReason.SCHEDULED_MAINTENANCE.takeIf {
            isSaturday && isMaintenanceTime
        }
    }

    override suspend fun statusSuppressionAt(observedAt: Instant): LlmDaemonLaunchSuppressionReason? {
        scheduledSuppressionAt(observedAt)?.let { reason -> return reason }

        return cacheMutex.withLock {
            val currentNanos = monotonicTimeSource.nanoTime()
            val cached = cachedResult?.takeIf { entry ->
                val elapsedNanos = currentNanos - entry.cachedAtNanos

                elapsedNanos >= 0 && elapsedNanos < cacheTtl.toNanos()
            }
            if (cached != null) return@withLock cached.reason

            readAndCacheStatus()
        }
    }

    private suspend fun readAndCacheStatus(): LlmDaemonLaunchSuppressionReason? {
        val reason = statusReader.readStatus().fold(
            onSuccess = { status -> status.toSuppressionReason() },
            onFailure = { throwable -> throwable.toSuppressionReason() },
        )
        cachedResult = CachedStatusResult(
            reason = reason,
            cachedAtNanos = monotonicTimeSource.nanoTime(),
        )

        return reason
    }

    /** status 取得結果の単一 cache entry。 */
    private data class CachedStatusResult(
        val reason: LlmDaemonLaunchSuppressionReason?,
        val cachedAtNanos: Long,
    )
}

private fun GmoExchangeStatus.toSuppressionReason(): LlmDaemonLaunchSuppressionReason? {
    return when (this) {
        GmoExchangeStatus.OPEN -> null
        GmoExchangeStatus.MAINTENANCE -> LlmDaemonLaunchSuppressionReason.STATUS_MAINTENANCE
        GmoExchangeStatus.PREOPEN -> LlmDaemonLaunchSuppressionReason.STATUS_PREOPEN
    }
}

private fun Throwable.toSuppressionReason(): LlmDaemonLaunchSuppressionReason {
    causeChain<CancellationException>()?.let { cancellation -> throw cancellation }

    return when {
        this is MarketDataParseException || this is GmoApiStatusException ->
            LlmDaemonLaunchSuppressionReason.STATUS_MALFORMED
        causeChainContains<HttpTimeoutException>() -> LlmDaemonLaunchSuppressionReason.STATUS_TIMEOUT
        else -> LlmDaemonLaunchSuppressionReason.STATUS_TRANSPORT_FAILURE
    }
}

private inline fun <reified T : Throwable> Throwable.causeChainContains(): Boolean {
    return causeChain<T>() != null
}

private inline fun <reified T : Throwable> Throwable.causeChain(): T? {
    var current: Throwable? = this

    while (current != null) {
        if (current is T) return current
        current = current.cause
    }

    return null
}

/** GMO status cache の既定 TTL。 */
val DEFAULT_GMO_STATUS_CACHE_TTL: Duration = Duration.ofSeconds(60)

private val GMO_MAINTENANCE_ZONE = ZoneId.of("Asia/Tokyo")
private val GMO_MAINTENANCE_START = LocalTime.of(9, 0)
private val GMO_MAINTENANCE_END = LocalTime.of(11, 0)
