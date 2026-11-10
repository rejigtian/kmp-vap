package io.github.vapkmp

import kotlin.test.*

internal class VapClockTest {
    @Test fun backgroundTimeIsExcludedWithoutAnyFrameCallbacks() {
        var now = 100L
        val clock = VapPlaybackClock(true) { now }
        now = 150L
        clock.setActive(false)
        now = 9_000L
        assertEquals(150L, clock.nowNs())
        clock.setActive(true)
        now = 9_050L
        assertEquals(200L, clock.nowNs())
    }
    @Test fun repeatedPauseAndResumeDoNotAccumulateTwice() {
        var now = 0L
        val clock = VapPlaybackClock(false) { now }
        now = 100L; clock.setActive(false)
        now = 200L; clock.setActive(true)
        now = 250L; clock.setActive(true)
        assertEquals(50L, clock.nowNs())
        now = 300L; clock.setActive(false)
        now = 400L; clock.setActive(true)
        assertEquals(100L, clock.nowNs())
    }
}
