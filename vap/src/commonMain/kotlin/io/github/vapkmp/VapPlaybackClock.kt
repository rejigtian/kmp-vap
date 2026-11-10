package io.github.vapkmp

import kotlin.time.TimeSource

/**
 * @author tianruijie
 * Date: 2026/11/10
 * Desc: 不依赖宿主 vsync 的单调播放时钟，后台停止帧回调也不会丢失暂停时长。
 * 生成来源：Codex AI生成
 */
internal class VapPlaybackClock(active: Boolean, private val nanos: () -> Long = monotonicSource()) {
    private var pausedAt: Long? = if (active) null else nanos()
    private var pausedTotal = 0L
    fun setActive(active: Boolean) {
        val now = nanos()
        if (!active && pausedAt == null) pausedAt = now
        else if (active && pausedAt != null) {
            pausedTotal += now - requireNotNull(pausedAt)
            pausedAt = null
        }
    }
    fun nowNs(): Long = (pausedAt ?: nanos()) - pausedTotal
}
private fun monotonicSource(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeNanoseconds }
}
