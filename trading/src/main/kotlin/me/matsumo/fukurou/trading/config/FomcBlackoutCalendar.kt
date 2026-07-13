package me.matsumo.fukurou.trading.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.safety.EconomicEventBlackout
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** FOMC calendar の公式 source URL。 */
const val FOMC_CALENDAR_SOURCE_URL = "https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm"

/** FOMC calendar の安全状態。 */
enum class FomcBlackoutCalendarState {
    ACTIVE,
    MISSING,
    INVALID,
    EXPIRED,
}

/**
 * FOMC blackout 群から導出する calendar projection。
 *
 * @param state parse 時点の calendar 状態
 * @param events 正常に parse できた全 economic event
 * @param validThrough FOMC window の最後の終了時刻
 * @param reason INVALID の監査理由
 */
data class FomcBlackoutCalendar(
    val state: FomcBlackoutCalendarState,
    val events: List<EconomicEventBlackout>,
    val validThrough: Instant?,
    val reason: String? = null,
) {
    /** 指定時刻の expiry を反映した状態を返す。 */
    fun stateAt(now: Instant): FomcBlackoutCalendarState {
        if (state != FomcBlackoutCalendarState.ACTIVE) return state

        return if (now > requireNotNull(validThrough)) {
            FomcBlackoutCalendarState.EXPIRED
        } else {
            FomcBlackoutCalendarState.ACTIVE
        }
    }

    companion object {
        /** 保存済み JSON を壊れた active 値にも耐える projection へ変換する。 */
        fun fromRaw(raw: String?): FomcBlackoutCalendar {
            if (raw == null) return fromEvents(emptyList())

            return decodeEconomicEventBlackouts(raw).fold(
                onSuccess = ::fromEvents,
                onFailure = { error ->
                    FomcBlackoutCalendar(
                        state = FomcBlackoutCalendarState.INVALID,
                        events = emptyList(),
                        validThrough = null,
                        reason = error.message ?: error::class.simpleName,
                    )
                },
            )
        }

        /** domain event 群を検証して projection へ変換する。 */
        fun fromEvents(events: List<EconomicEventBlackout>): FomcBlackoutCalendar {
            val duplicateIds = events.groupingBy { event -> event.eventId }.eachCount().filterValues { count -> count > 1 }
            if (duplicateIds.isNotEmpty()) {
                return invalid("duplicate eventId: ${duplicateIds.keys.sorted().joinToString()}")
            }

            val fomcEvents = events.filter { event -> event.eventId.startsWith(FOMC_EVENT_ID_PREFIX) }
            if (fomcEvents.isEmpty()) {
                return FomcBlackoutCalendar(
                    state = FomcBlackoutCalendarState.MISSING,
                    events = events,
                    validThrough = null,
                )
            }

            return FomcBlackoutCalendar(
                state = FomcBlackoutCalendarState.ACTIVE,
                events = events,
                validThrough = fomcEvents.maxOf { event -> event.eventAt.plus(event.blackoutAfter) },
            )
        }

        /** Federal Reserve 公式 calendar に基づく 2026 年残り会合の候補値。 */
        fun candidateEvents(): List<EconomicEventBlackout> {
            return listOf(
                LocalDate.of(2026, 7, 29),
                LocalDate.of(2026, 9, 16),
                LocalDate.of(2026, 10, 28),
                LocalDate.of(2026, 12, 9),
            ).map { meetingDate ->
                val eventAt = meetingDate
                    .atTime(FOMC_ANNOUNCEMENT_LOCAL_TIME)
                    .atZone(FOMC_TIME_ZONE)
                    .toInstant()

                EconomicEventBlackout(
                    eventId = "$FOMC_EVENT_ID_PREFIX${meetingDate.toString().replace("-", "")}",
                    eventName = "FOMC meeting statement",
                    eventAt = eventAt,
                    blackoutBefore = FOMC_BLACKOUT_WIDTH,
                    blackoutAfter = FOMC_BLACKOUT_WIDTH,
                )
            }
        }

        private fun invalid(reason: String): FomcBlackoutCalendar {
            return FomcBlackoutCalendar(
                state = FomcBlackoutCalendarState.INVALID,
                events = emptyList(),
                validThrough = null,
                reason = reason,
            )
        }
    }
}

/** economic event JSON を厳格に domain へ変換する。 */
internal fun decodeEconomicEventBlackouts(raw: String): Result<List<EconomicEventBlackout>> {
    return runCatching {
        Json.decodeFromString<List<EconomicEventBlackoutJson>>(raw).map { entry -> entry.toDomain() }
    }
}

/** economic event 群を runtime config の保存 JSON へ変換する。 */
internal fun List<EconomicEventBlackout>.encodeEconomicEventBlackouts(): String {
    return Json.encodeToString(
        map { event ->
            EconomicEventBlackoutJson(
                eventId = event.eventId,
                eventName = event.eventName,
                eventAt = event.eventAt.toString(),
                blackoutBeforeSeconds = event.blackoutBefore.seconds,
                blackoutAfterSeconds = event.blackoutAfter.seconds,
            )
        },
    )
}

@Serializable
private data class EconomicEventBlackoutJson(
    val eventId: String,
    val eventName: String,
    val eventAt: String,
    val blackoutBeforeSeconds: Long,
    val blackoutAfterSeconds: Long,
) {
    fun toDomain(): EconomicEventBlackout {
        require(eventId.isNotBlank()) { "eventId must not be blank." }
        require(eventName.isNotBlank()) { "eventName must not be blank." }
        require(blackoutBeforeSeconds >= 0) { "blackoutBeforeSeconds must not be negative." }
        require(blackoutAfterSeconds >= 0) { "blackoutAfterSeconds must not be negative." }

        return EconomicEventBlackout(
            eventId = eventId,
            eventName = eventName,
            eventAt = Instant.parse(eventAt),
            blackoutBefore = Duration.ofSeconds(blackoutBeforeSeconds),
            blackoutAfter = Duration.ofSeconds(blackoutAfterSeconds),
        )
    }
}

private const val FOMC_EVENT_ID_PREFIX = "fomc-"
private val FOMC_TIME_ZONE: ZoneId = ZoneId.of("America/New_York")
private val FOMC_ANNOUNCEMENT_LOCAL_TIME: LocalTime = LocalTime.of(14, 0)
private val FOMC_BLACKOUT_WIDTH: Duration = Duration.ofMinutes(60)
