package me.matsumo.fukurou.trading.config

import me.matsumo.fukurou.trading.safety.EconomicEventBlackout
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FOMC calendar の公式候補値と tolerant projection を検証するテスト。 */
class FomcBlackoutCalendarTest {

    @Test
    fun candidateEvents_convertNewYorkMeetingTimeToExactUtc() {
        val events = FomcBlackoutCalendar.candidateEvents()

        assertEquals(
            listOf(
                "2026-07-29T18:00:00Z",
                "2026-09-16T18:00:00Z",
                "2026-10-28T18:00:00Z",
                "2026-12-09T19:00:00Z",
            ),
            events.map { event -> event.eventAt.toString() },
        )
        assertTrue(events.all { event -> event.blackoutBefore == Duration.ofMinutes(60) })
        assertTrue(events.all { event -> event.blackoutAfter == Duration.ofMinutes(60) })
        assertEquals(Instant.parse("2026-12-09T20:00:00Z"), FomcBlackoutCalendar.fromEvents(events).validThrough)
    }

    @Test
    fun fromRaw_projectsMissingInvalidAndExpiredWithoutThrowing() {
        val missing = FomcBlackoutCalendar.fromRaw("[]")
        val invalid = FomcBlackoutCalendar.fromRaw("not-json")
        val expired = FomcBlackoutCalendar.fromEvents(
            listOf(
                EconomicEventBlackout(
                    eventId = "fomc-past",
                    eventName = "FOMC",
                    eventAt = Instant.parse("2026-01-01T19:00:00Z"),
                    blackoutBefore = Duration.ofMinutes(60),
                    blackoutAfter = Duration.ofMinutes(60),
                ),
            ),
        )

        assertEquals(FomcBlackoutCalendarState.MISSING, missing.stateAt(Instant.EPOCH))
        assertEquals(FomcBlackoutCalendarState.INVALID, invalid.stateAt(Instant.EPOCH))
        assertEquals(FomcBlackoutCalendarState.EXPIRED, expired.stateAt(Instant.parse("2026-01-01T20:00:00.001Z")))
        assertEquals(FomcBlackoutCalendarState.ACTIVE, expired.stateAt(Instant.parse("2026-01-01T20:00:00Z")))
    }
}
