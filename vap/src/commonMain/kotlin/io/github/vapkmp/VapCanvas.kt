package io.github.vapkmp

/**
 * @author tianruijie
 * Date: 2026/11/10
 * Desc: 共享播放时钟、Compose Canvas透明合成和VAPX融合。
 * 生成来源：Codex AI生成
 */

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 三端唯一的播放器：有界顺序解码、共享时钟、Canvas 透明合成与 VAPX 融合。 */
@Composable
internal fun VapCanvas(source: String, modifier: Modifier, playing: Boolean, loopCount: Int,
    scaleType: VapScaleType, dynamicContent: VapDynamicContent, fileResolver: VapFileResolver?, events: VapEvents,
    imageCache: VapImageCache<ImageBitmap>) {
    val hostActive = rememberVapHostActive()
    val drawReport = remember(source, dynamicContent, loopCount, fileResolver) { VapDrawReport() }
    LaunchedEffect(drawReport, events) {
        for (renderedIndex in drawReport.indices) {
            // Channel可能在DrawScope中唤醒接收者；先退出绘制栈，再派发用户回调。
            yield()
            drawReport.frames.retireBefore(drawReport.frame)
            events.frame(renderedIndex)
        }
    }
    val active by rememberUpdatedState(playing && hostActive)
    val clock = remember { VapPlaybackClock(active) }
    var config by remember { mutableStateOf<VapConfig?>(null) }
    var resources by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var displayed by remember { mutableStateOf<VapDecodedFrame?>(null) }
    var index by remember { mutableIntStateOf(0) }
    SideEffect { clock.setActive(active); events.setPlaying(active) }
    LaunchedEffect(source, dynamicContent, loopCount, fileResolver) {
        var decoder: VapDecoder? = null
        // 已记录帧由平台绘制命令持有；Kotlin只保留当前记录帧及尚未记录的有界帧。
        val retained = drawReport.frames
        try {
            val path = fileResolver?.resolve(source) ?: resolveVapFile(source)
            val parsed = withContext(Dispatchers.Default) { readVapConfig(path) }
            val loaded = buildMap {
                parsed.resources.forEach { r ->
                    if (r.text) require(dynamicContent.texts.containsKey(r.tag)) { "Missing VAPX text: ${r.tag}" }
                    else {
                        val sourceImage = requireNotNull(dynamicContent.images[r.tag]) { "Missing VAPX image: ${r.tag}" }
                        val resolvedImage = if (sourceImage.startsWith("http://") || sourceImage.startsWith("https://"))
                            fileResolver?.resolve(sourceImage) ?: sourceImage else sourceImage
                        put(r.id, loadCachedVapImage(resolvedImage, imageCache))
                    }
                }
            }
            config = parsed; resources = loaded
            val opened = openVapDecoder(path)
            decoder = opened
            var completed = 0
            events.ready()
            do {
                var originNs: Long? = null
                var firstPts: Long? = null
                var produced = 0
                var lastTargetNs = 0L
                while (true) {
                    if (!active && (produced > 0 || completed > 0)) snapshotFlow { active }.first { it }
                    retained.retireBefore(drawReport.frame)
                    while (retained.size >= 3) {
                        withFrameNanos { }
                        retained.retireBefore(drawReport.frame)
                    }
                    val frame = opened.nextFrame() ?: break
                    retained.add(frame)
                    require(frame.image.width == parsed.videoWidth && frame.image.height == parsed.videoHeight) {
                        "Decoded frame does not match VAP packed dimensions"
                    }
                    val pts = frame.ptsUs
                    if (firstPts == null) firstPts = pts
                    val targetNs = (pts - requireNotNull(firstPts)) * 1000L
                    withFrameNanos { }
                    if (originNs == null) originNs = clock.nowNs()
                    // PTS 决定展示时刻；后台暂停由独立单调时钟扣除，不依赖停摆的 vsync。
                    while (true) {
                        if (!active && (produced > 0 || completed > 0)) snapshotFlow { active }.first { it }
                        if (clock.nowNs() - requireNotNull(originNs) >= targetNs) break
                        withFrameNanos { }
                    }
                    index = (((pts - requireNotNull(firstPts)) * parsed.fps / 1_000_000.0).roundToInt()).coerceIn(0, parsed.frames - 1)
                    displayed = frame
                    lastTargetNs = targetNs
                    retained.retireBefore(drawReport.frame)
                    produced++
                }
                require(produced > 0) { "Video decoder returned no frames" }
                // EOS 使用最后一帧 PTS 的截止点，不在解码结束后额外延长一帧。
                val endNs = lastTargetNs + 1_000_000_000L / parsed.fps
                while (clock.nowNs() - requireNotNull(originNs) < endNs) {
                    if (!active) snapshotFlow { active }.first { it }
                    withFrameNanos { }
                }
                completed++
                if (loopCount != 0 && completed >= loopCount) { events.finish(); break }
                if (!active) snapshotFlow { active }.first { it }
                opened.rewind()
                events.repeat()
            } while (isActive)
            // 独立帧可继续显示；自然完成立即归还硬解实例，不等页面退出。
            opened.close()
            decoder = null
            awaitCancellation()
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { events.fail(error) }
        finally {
            displayed = null
            drawReport.frame = null
            drawReport.indices.close()
            resources = emptyMap()
            config = null
            // 帧像素独立于解码器；先清理图像引用，让宿主移除Canvas的同一帧即可退休旧图像。
            try { retained.clear() }
            finally { withContext(NonCancellable) { decoder?.close() } }
        }
    }
    DisposableEffect(events) { onDispose { events.release() } }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    val textLayouts = remember(config, dynamicContent.texts, density, measurer) {
        config?.resources?.filter { it.text }?.associate { r ->
            r.id to measurer.measure(dynamicContent.texts.getValue(r.tag), TextStyle(
                color = parseVapColor(r.color), fontSize = (r.height * 0.8f / density).sp,
                fontWeight = if (r.bold) FontWeight.Bold else FontWeight.Normal,
            ), softWrap = false, maxLines = 1)
        }.orEmpty()
    }
    Canvas(modifier) {
        val cfg = config ?: return@Canvas
        val decoded = displayed ?: return@Canvas
        val video = decoded.image
        val sx = size.width / cfg.width; val sy = size.height / cfg.height
        val x: Float; val y: Float
        when (scaleType) {
            VapScaleType.Fit -> { x = min(sx, sy); y = x }
            VapScaleType.Crop -> { x = max(sx, sy); y = x }
            VapScaleType.Fill -> { x = sx; y = sy }
        }
        clipRect {
            translate((size.width - cfg.width * x) / 2, (size.height - cfg.height * y) / 2) {
                scale(x, y, Offset.Zero) {
                    val base = VapRect(0, 0, cfg.width, cfg.height)
                    masked(video, cfg.alpha, base, 0) { imageRegion(video, cfg.rgb, base, inset = true) }
                    cfg.overlays[index]?.forEach { overlay ->
                        val resource = cfg.resources.first { it.id == overlay.id }
                        masked(video, overlay.mask, overlay.rect, overlay.rotation) {
                            val rect = overlay.rect
                            val image = resources[resource.id]
                            if (image != null) {
                                val crop = if (resource.centerCrop) fusionCrop(image, resource, rect) else VapRect(0, 0, image.width, image.height)
                                imageRegion(image, crop, rect)
                            } else textLayouts[resource.id]?.let { text -> drawResourceText(text, rect) }
                        }
                    }
                }
            }
        }
        if (drawReport.frame !== decoded) {
            drawReport.frame = decoded
            val renderedIndex = index
            // DrawScope 完成后再派发，避免回调在绘制中写 Compose 状态；不阻塞下一帧调度。
            drawReport.indices.trySend(renderedIndex)
        }
    }
}
private class VapDrawReport {
    var frame: VapDecodedFrame? = null
    val frames = VapFrameRetainer<VapDecodedFrame> { it.release() }
    val indices = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.CONFLATED)
}

// red -> alpha，RGB 清零；DstIn 在 GPU 合成，不逐像素处理透明通道。
private val maskColor: ColorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    0f,0f,0f,0f,0f, 0f,0f,0f,0f,0f, 0f,0f,0f,0f,0f, 1f,0f,0f,0f,0f)))
private val layerPaint: Paint = Paint()
private inline fun DrawScope.masked(video: ImageBitmap, mask: VapRect, dst: VapRect, rotation: Int, draw: DrawScope.() -> Unit) {
    val bounds = Rect(dst.x.toFloat(), dst.y.toFloat(), (dst.x + dst.w).toFloat(), (dst.y + dst.h).toFloat())
    drawContext.canvas.saveLayer(bounds, layerPaint)
    try {
        clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
            draw()
            if (rotation == 0) imageRegion(video, mask, dst, maskColor, BlendMode.DstIn, inset = true)
            else {
                val center = bounds.center
                withTransform({
                    translate(center.x, center.y)
                    rotate(90f, Offset.Zero)
                    translate(-dst.h / 2f, -dst.w / 2f)
                }) {
                    imageRegion(video, mask, VapRect(0, 0, dst.h, dst.w), maskColor, BlendMode.DstIn, inset = true)
                }
            }
        }
    } finally { drawContext.canvas.restore() }
}
private fun DrawScope.imageRegion(image: ImageBitmap, source: VapRect, dst: VapRect,
    filter: ColorFilter? = null, blend: BlendMode = BlendMode.SrcOver, inset: Boolean = false) {
    if (inset && source.w > 1 && source.h > 1) {
        // packed RGB/Alpha 紧邻其他区域，UV 边界内缩半纹素，避免双线性采样混入邻区。
        clipRect(dst.x.toFloat(), dst.y.toFloat(), (dst.x + dst.w).toFloat(), (dst.y + dst.h).toFloat()) {
            translate(dst.x.toFloat(), dst.y.toFloat()) {
                scale(dst.w.toFloat() / (source.w - 1), dst.h.toFloat() / (source.h - 1), Offset.Zero) {
                    drawImage(image, topLeft = Offset(-source.x - 0.5f, -source.y - 0.5f),
                        colorFilter = filter, blendMode = blend)
                }
            }
        }
    } else drawImage(image, source.offset, source.size, dst.offset, dst.size,
        colorFilter = filter, blendMode = blend, filterQuality = FilterQuality.Low)
}
private fun DrawScope.drawResourceText(text: TextLayoutResult, rect: VapRect) {
    val scale = min(rect.w.toFloat() / max(1, text.size.width), rect.h.toFloat() / max(1, text.size.height))
    translate(rect.x + (rect.w - text.size.width * scale) / 2, rect.y + (rect.h - text.size.height * scale) / 2) {
        scale(scale, scale, Offset.Zero) { drawText(text) }
    }
}
private fun fusionCrop(image: ImageBitmap, resource: VapResource, frame: VapRect): VapRect {
    // centerFull 使用素材固定资源尺寸，入场小框裁中间而非随框缩放，避免头像抖动。
    val resourceScale = max(resource.width.toFloat() / image.width, resource.height.toFloat() / image.height)
    val drawWidth = image.width * resourceScale; val drawHeight = image.height * resourceScale
    val cropWidth: Int; val cropHeight: Int
    if (frame.w <= drawWidth && frame.h <= drawHeight) {
        cropWidth = (frame.w / resourceScale).roundToInt().coerceIn(1, image.width)
        cropHeight = (frame.h / resourceScale).roundToInt().coerceIn(1, image.height)
    } else {
        val ratio = frame.w.toFloat() / frame.h
        if (image.width.toFloat() / image.height > ratio) {
            cropWidth = (image.height * ratio).roundToInt().coerceIn(1, image.width)
            cropHeight = image.height
        } else {
            cropWidth = image.width
            cropHeight = (image.width / ratio).roundToInt().coerceIn(1, image.height)
        }
    }
    return VapRect((image.width - cropWidth) / 2, (image.height - cropHeight) / 2, cropWidth, cropHeight)
}
internal fun parseVapColor(value: String): Color {
    val hex = value.removePrefix("#")
    require(hex.length == 6 || hex.length == 8) { "Invalid VAPX text color: $value" }
    return Color((if (hex.length == 6) 0xff000000L else 0L) or hex.toLong(16))
}
