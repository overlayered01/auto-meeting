package com.automeeting.auto_meeting

import org.junit.Assert.*
import org.junit.Test

class CalendarPolicyTest {
    private fun event(key: String = "one", begin: Long = 10_000, end: Long = 90_000) =
        CalendarOccurrence(key, "calendar", "Personal event", "My calendar", "account", begin, end)

    @Test fun personalEventDoesNotRequireAttendees() {
        assertEquals(listOf(event()), CalendarPolicy.due(listOf(event()), 10_000, 0, emptySet()))
    }
    @Test fun allDayCancelledDeclinedAndInvalidIntervalsAreExcluded() {
        val events = listOf(event().copy(allDay = true), event().copy(cancelled = true),
            event().copy(declined = true), event(end = 10_000))
        assertTrue(CalendarPolicy.due(events, 10_000, 0, emptySet()).isEmpty())
    }
    @Test fun noEarlyStartOrRetroactiveCatchupOnArming() {
        assertTrue(CalendarPolicy.due(listOf(event()), 9_999, 0, emptySet()).isEmpty())
        assertTrue(CalendarPolicy.due(listOf(event()), 20_000, 15_000, emptySet()).isEmpty())
    }
    @Test fun completedOrSilentOccurrenceCannotRestart() {
        assertTrue(CalendarPolicy.due(listOf(event()), 20_000, 0, setOf("one")).isEmpty())
    }
    @Test fun sameOccurrenceAcrossCalendarsIsDeduplicated() {
        assertEquals(1, CalendarPolicy.due(listOf(event(), event().copy(calendarId = "other")),
            10_000, 0, emptySet()).size)
    }
    @Test fun overlappingOccurrencesAreReturnedInDeterministicOrder() {
        assertEquals(listOf("a", "b"), CalendarPolicy.due(listOf(event("b"), event("a")),
            10_000, 0, emptySet()).map { it.key })
    }
    @Test fun lateAndEndedEventsAreNotStarted() {
        assertFalse(CalendarPolicy.tooLate(event(), 40_000))
        assertTrue(CalendarPolicy.tooLate(event(), 40_001))
        assertTrue(CalendarPolicy.due(listOf(event()), 90_000, 0, emptySet()).isEmpty())
    }
    @Test fun nextAlarmIgnoresHandledAndIneligibleEvents() {
        assertEquals(20_000L, CalendarPolicy.nextStart(listOf(event(), event("two", 20_000)),
            0, 0, setOf("one")))
    }
    @Test fun sixtySecondsWithNoSpeechStopsEvenIfNobodyEverSpoke() {
        val policy = SpeechSilencePolicy()
        repeat(1874) { assertFalse(policy.accept(32, false)) }
        assertTrue(policy.accept(32, false))
        assertFalse(policy.speechSeen)
    }
    @Test fun speechResetsTheWholeSilenceWindow() {
        val policy = SpeechSilencePolicy()
        repeat(1800) { policy.accept(32, false) }
        assertFalse(policy.accept(32, true))
        assertEquals(0L, policy.silenceMs)
        assertTrue(policy.speechSeen)
        repeat(1874) { assertFalse(policy.accept(32, false)) }
        assertTrue(policy.accept(32, false))
    }
    @Test fun noPcmMeansNoSilenceProgress() {
        val policy = SpeechSilencePolicy()
        assertEquals(0L, policy.silenceMs)
    }
}
