package io.github.vapkmp

/**
 * @author tianruijie
 * Date: 2026/11/10
 * Desc: 三端原始视频帧适配契约。
 * 生成来源：Codex AI生成
 */

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** 平台只提供原始 packed 视频帧；不解析 VAP、不计时、不绘制播放器 View。 */
internal interface VapDecoder {
    suspend fun nextFrame(): VapDecodedFrame?
    suspend fun rewind()
    suspend fun close()
}

internal class VapDecodedFrame(val image: ImageBitmap, val ptsUs: Long, val release: () -> Unit = {})

/** 以Canvas已记录的帧为退休边界，不靠经过几次vsync猜测是否仍会读旧图像。 */
internal class VapFrameRetainer<T : Any>(private val release: (T) -> Unit) {
    private val frames = ArrayDeque<T>()
    val size: Int get() = frames.size
    fun add(frame: T) { frames.addLast(frame) }
    fun retireBefore(recorded: T?) {
        if (recorded == null || frames.none { it === recorded }) return
        while (frames.first() !== recorded) release(frames.removeFirst())
    }
    fun clear() { while (frames.isNotEmpty()) release(frames.removeFirst()) }
}
internal expect suspend fun openVapDecoder(path: String): VapDecoder
internal expect suspend fun resolveVapFile(source: String): String
internal expect suspend fun readVapFile(path: String, offset: Long, count: Int): ByteArray
internal expect suspend fun loadVapImage(source: String): ImageBitmap
internal expect suspend fun decodeVapImage(encoded: ByteArray): ImageBitmap
@Composable internal expect fun rememberVapHostActive(): Boolean
