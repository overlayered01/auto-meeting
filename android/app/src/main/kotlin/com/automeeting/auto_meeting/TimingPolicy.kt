package com.automeeting.auto_meeting

/** Pure policy: elapsed time is monotonic, never derived from the wall clock. */
object TimingPolicy {
    enum class Decision { WAIT, START, CANCEL_BACKGROUND, MISSED }

    fun validate(delayMs: Long, durationMs: Long, mode: String) {
        require(delayMs in 0L..120_000L) { "invalid_delay" }
        require(durationMs in 10_000L..60_000L) { "invalid_duration" }
        require(mode == "foreground" || mode == "standby") { "invalid_mode" }
    }

    fun decide(now: Long, due: Long, foreground: Boolean, mode: String): Decision = when {
        mode == "foreground" && !foreground -> Decision.CANCEL_BACKGROUND
        now - due > 5_000L -> Decision.MISSED
        now >= due -> Decision.START
        else -> Decision.WAIT
    }
}
