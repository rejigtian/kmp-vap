package io.github.vapkmp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @author tianruijie
 * Date: 2026/11/10
 * Desc: 验证尚未记录的帧不会提前退休，以及取消后的释放边界。
 * 生成来源：Codex AI生成
 */
class VapFrameRetainerTest {
    @Test fun unrecordedFramesStayAlive() {
        val released = mutableListOf<Any>()
        val frames = VapFrameRetainer<Any> { released.add(it) }
        frames.add(Any()); frames.add(Any())
        frames.retireBefore(null)
        frames.retireBefore(Any())
        assertEquals(2, frames.size)
        assertEquals(0, released.size)
    }
    @Test fun drawingNewFrameRetiresOnlyOlderFrames() {
        val released = mutableListOf<Any>()
        val frames = VapFrameRetainer<Any> { released.add(it) }
        val old = Any(); val drawn = Any(); val pending = Any()
        frames.add(old); frames.add(drawn); frames.add(pending)
        frames.retireBefore(drawn)
        assertEquals(listOf(old), released)
        assertEquals(2, frames.size)
    }
    @Test fun clearReleasesRemainingFramesExactlyOnce() {
        val released = mutableListOf<Any>()
        val frames = VapFrameRetainer<Any> { released.add(it) }
        val first = Any(); val last = Any()
        frames.add(first); frames.add(last)
        frames.retireBefore(last)
        frames.clear(); frames.clear()
        assertEquals(listOf(first, last), released)
        assertEquals(0, frames.size)
    }
}
