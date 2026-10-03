package com.automeeting.auto_meeting

/** A concrete occurrence, not an unexpanded recurring event. All times are UTC epoch milliseconds. */
data class CalendarOccurrence(
    val key: String,
    val calendarId: String,
    val title: String,
    val calendarName: String,
    val account: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean = false,
    val cancelled: Boolean = false,
    val declined: Boolean = false,
) {
    fun toMap() = mapOf("key" to key, "calendarId" to calendarId, "title" to title,
        "calendarName" to calendarName, "account" to account, "begin" to begin, "end" to end)
}

object CalendarPolicy {
    const val SILENCE_MS = 60_000L
    const val MAX_LATENESS_MS = 30_000L
    const val SESSION_MS = 12 * 60 * 60_000L
    const val MAX_RECORDING_MS = 4 * 60 * 60_000L

    fun eligible(event: CalendarOccurrence): Boolean =
        !event.allDay && !event.cancelled && !event.declined && event.end > event.begin

    // Never catch up an event that was already in progress when the user armed the app.
    fun due(events: List<CalendarOccurrence>, now: Long, armedAt: Long, handled: Set<String>) =
        events.filter { eligible(it) && it.key !in handled && it.begin >= armedAt &&
            it.begin <= now && it.end > now }
            .distinctBy { it.key }.sortedWith(compareBy({ it.begin }, { it.key }))

    fun tooLate(event: CalendarOccurrence, now: Long) = now - event.begin > MAX_LATENESS_MS

    fun nextStart(events: List<CalendarOccurrence>, now: Long, armedAt: Long, handled: Set<String>) =
        events.filter { eligible(it) && it.key !in handled && it.begin >= armedAt && it.begin > now }
            .minOfOrNull { it.begin }
}

/** Only successfully captured, classified PCM advances this clock. Read errors are NOT silence. */
class SpeechSilencePolicy(private val limitMs: Long = CalendarPolicy.SILENCE_MS) {
    var silenceMs = 0L
        private set
    var speechSeen = false
        private set

    init { require(limitMs > 0) }

    fun accept(frameDurationMs: Long, isSpeech: Boolean): Boolean {
        require(frameDurationMs > 0)
        if (isSpeech) {
            speechSeen = true
            silenceMs = 0
        } else {
            silenceMs += frameDurationMs
        }
        return silenceMs >= limitMs
    }
}
