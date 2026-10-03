package com.automeeting.auto_meeting

import org.junit.Assert.assertEquals
import org.junit.Test

class TimingPolicyTest {
    @Test fun scheduledRecordingWaitsUntilDeadline() {
        assertEquals(TimingPolicy.Decision.WAIT, TimingPolicy.decide(999, 1000, true, "foreground"))
        assertEquals(TimingPolicy.Decision.START, TimingPolicy.decide(1000, 1000, true, "foreground"))
    }
    @Test fun foregroundModeCancelsWhenUserLeavesEvenBeforeDeadline() {
        assertEquals(TimingPolicy.Decision.CANCEL_BACKGROUND, TimingPolicy.decide(0, 1000, false, "foreground"))
        assertEquals(TimingPolicy.Decision.CANCEL_BACKGROUND, TimingPolicy.decide(1000, 1000, false, "foreground"))
    }
    @Test fun experimentalModeAllowsBackgroundAttemptButNotLateCatchup() {
        assertEquals(TimingPolicy.Decision.START, TimingPolicy.decide(1000, 1000, false, "standby"))
        assertEquals(TimingPolicy.Decision.MISSED, TimingPolicy.decide(6001, 1000, false, "standby"))
    }
    @Test(expected = IllegalArgumentException::class) fun longKeepaliveIsRejected() {
        TimingPolicy.validate(86_400_000, 60_000, "standby")
    }
    @Test(expected = IllegalArgumentException::class) fun invalidModeIsRejected() {
        TimingPolicy.validate(120_000, 60_000, "hidden")
    }
    @Test fun validLabPresetsAreAccepted() {
        TimingPolicy.validate(120_000, 60_000, "standby")
        TimingPolicy.validate(10_000, 10_000, "foreground")
        TimingPolicy.validate(0, 60_000, "foreground")
    }
}
