package io.github.vapkmp

import kotlin.test.Test
import kotlin.test.assertEquals

class VapEventsTest {
    @Test fun pausedFirstFrameDoesNotStartOrFinishAndReleaseIgnoresLateCallbacks() {
        val log = mutableListOf<String>()
        val events = VapEvents(false) { callbacks(log) }
        events.ready(); events.frame(0)
        assertEquals(listOf("ready", "frame:0"), log)
        events.setPlaying(true); events.frame(1)
        events.setPlaying(false); events.setPlaying(false)
        assertEquals(listOf("ready", "frame:0", "started", "frame:1", "paused"), log)
        events.release(); events.frame(2); events.finish(); events.fail(Exception("late"))
        assertEquals(5, log.size)
    }

    @Test fun singleFrameLoopsNotifyEveryRepeatAndFinishOnlyOnce() {
        val log = mutableListOf<String>()
        val events = VapEvents(true) { callbacks(log) }
        events.frame(0); events.repeat(); events.frame(0); events.repeat(); events.frame(0)
        events.finish(); events.finish()
        assertEquals(listOf("ready", "started", "frame:0", "repeat:1", "frame:0", "repeat:2", "frame:0", "finished"), log)
    }

    @Test fun failureSuppressesCompletionAndCallbacksCanChangeWithoutRestarting() {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        var current = callbacks(first)
        val events = VapEvents(true) { current }
        events.frame(0)
        current = callbacks(second)
        events.frame(1); events.fail(Exception("broken")); events.finish(); events.repeat()
        assertEquals(listOf("ready", "started", "frame:0"), first)
        assertEquals(listOf("frame:1", "error:broken"), second)
    }

    private fun callbacks(log: MutableList<String>): VapCallbacks = VapCallbacks(
        { log += "ready" }, { log += "started" }, { log += "paused" },
        { log += "repeat:$it" }, { log += "frame:$it" }, { log += "finished" },
        { log += "error:${it.message}" },
    )
}
