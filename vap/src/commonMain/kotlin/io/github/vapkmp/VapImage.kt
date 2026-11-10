package io.github.vapkmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap

/**
 * @author tianruijie
 * Date: 2026/11/09
 * Desc: VAP/VAPX 的跨平台播放契约，资源 tag 必须与素材一致。
 * 生成来源：Codex AI生成
 */
public data class VapDynamicContent(
    public val images: Map<String, String> = emptyMap(),
    public val texts: Map<String, String> = emptyMap(),
)

public enum class VapScaleType { Fit, Crop, Fill }

/** 应用可接入已有磁盘缓存；返回本地文件绝对路径，不返回整个 MP4 的字节数组。 */
public fun interface VapFileResolver {
    public suspend fun resolve(source: String): String
}

/**
 * 播放本地文件或 HTTP(S) VAP，支持按 tag 替换 VAPX 图片和文字，默认静音。
 * loopCount=0 无限循环，正数是总播放次数。playing=false 准备并展示首帧，播放后则暂停当前帧。
 * source、dynamicContent 或 loopCount 改变会重新准备；回调更新不会重启播放。
 * onFinished 仅表示全部循环自然完成；移除、失败、换源不产生完成事件。
 * 图片值支持本地绝对路径或 HTTP(S) 地址；文字保留素材中的字体、颜色和布局。
 */
@Composable
public fun VapImage(
    source: String,
    modifier: Modifier = Modifier,
    playing: Boolean = true,
    loopCount: Int = 0,
    scaleType: VapScaleType = VapScaleType.Fit,
    dynamicContent: VapDynamicContent = VapDynamicContent(),
    fileResolver: VapFileResolver? = null,
    onReady: (() -> Unit)? = null,
    onStarted: (() -> Unit)? = null,
    onPaused: (() -> Unit)? = null,
    onRepeat: ((Int) -> Unit)? = null,
    onFrame: ((Int) -> Unit)? = null,
    onFinished: (() -> Unit)? = null,
    onError: (Throwable) -> Unit = { println("[VapKmp] ${it.message}") },
) {
    require(loopCount >= 0) { "loopCount must be non-negative" }
    // 图文更换仍按契约重播，但不重复解码内容相同的图片；离开播放器即清空缓存引用。
    val imageCache = remember { VapImageCache<ImageBitmap>(8L * 1024 * 1024, ::vapImageCost) }
    DisposableEffect(imageCache) { onDispose { imageCache.clear() } }
    val callbacks by rememberUpdatedState(VapCallbacks(onReady, onStarted, onPaused, onRepeat, onFrame, onFinished, onError))
    key(source, dynamicContent, loopCount, fileResolver) {
        val events = remember { VapEvents(playing) { callbacks } }
        VapCanvas(source, modifier, playing, loopCount, scaleType, dynamicContent, fileResolver, events, imageCache)
    }
}

internal class VapCallbacks(
    val ready: (() -> Unit)?, val started: (() -> Unit)?, val paused: (() -> Unit)?,
    val repeat: ((Int) -> Unit)?, val frame: ((Int) -> Unit)?, val finished: (() -> Unit)?,
    val error: (Throwable) -> Unit,
)

/** 平台在主线程派发实际渲染帧；隐藏、退出与失败不会伪造播放完成。 */
internal class VapEvents(private var playing: Boolean, private val callbacks: () -> VapCallbacks) {
    private var ready = false
    private var started = false
    private var paused = false
    private var terminal = false
    private var released = false
    private var lastFrame = -1
    private var repeats = 0

    fun setPlaying(value: Boolean) {
        if (playing == value || terminal || released) return
        playing = value
        if (!value && started && !paused) {
            paused = true
            callbacks().paused?.invoke()
        }
    }
    fun ready() {
        if (ready || terminal || released) return
        ready = true
        callbacks().ready?.invoke()
    }
    fun frame(index: Int) {
        if (terminal || released) return
        ready()
        if (playing && (!started || paused)) {
            started = true
            paused = false
            callbacks().started?.invoke()
        }
        if (index != lastFrame) callbacks().frame?.invoke(index)
        lastFrame = index
    }
    fun repeat() {
        if (terminal || released) return
        lastFrame = -1
        callbacks().repeat?.invoke(++repeats)
    }
    fun finish() {
        if (terminal || released) return
        terminal = true
        callbacks().finished?.invoke()
    }
    fun fail(error: Throwable) {
        if (terminal || released) return
        terminal = true
        callbacks().error(error)
    }
    fun release() { released = true }
}

