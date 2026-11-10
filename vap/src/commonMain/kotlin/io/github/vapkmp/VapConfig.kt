package io.github.vapkmp

/**
 * @author tianruijie
 * Date: 2026/11/10
 * Desc: 共享VAP v2协议解析与区域校验。
 * 生成来源：Codex AI生成
 */

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.serialization.json.*

/** VAP v2 配置由三端共享解析，MP4 仅读取 box 头与 vapc，不读入整段视频。 */
internal data class VapRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val offset: IntOffset = IntOffset(x, y)
    val size: IntSize = IntSize(w, h)
}
internal data class VapResource(val id: String, val tag: String, val text: Boolean,
    val width: Int, val height: Int, val color: String, val bold: Boolean, val centerCrop: Boolean)
internal data class VapOverlay(val id: String, val rect: VapRect, val mask: VapRect, val rotation: Int)
internal data class VapConfig(val width: Int, val height: Int, val fps: Int, val frames: Int,
    val videoWidth: Int, val videoHeight: Int, val rgb: VapRect, val alpha: VapRect,
    val resources: List<VapResource>, val overlays: Map<Int, List<VapOverlay>>)

internal suspend fun readVapConfig(path: String): VapConfig {
    var offset = 0L
    while (true) {
        val header = readVapFile(path, offset, 8)
        require(header.size == 8) { "MP4 does not contain a vapc configuration" }
        var length = header.uint(0)
        val type = header.copyOfRange(4, 8).decodeToString()
        var headerSize = 8
        if (length == 1L) {
            val wide = readVapFile(path, offset + 8, 8)
            require(wide.size == 8 && wide.uint(0) <= 0x7fffffffL) { "Invalid MP4 box size" }
            length = (wide.uint(0) shl 32) or wide.uint(4)
            headerSize = 16
        }
        require(length >= headerSize && offset <= Long.MAX_VALUE - length) { "Invalid MP4 box size" }
        if (type == "vapc") {
            require(length - headerSize in 1..(16 * 1024 * 1024)) { "VAP configuration is too large" }
            val body = readVapFile(path, offset + headerSize, (length - headerSize).toInt())
            require(body.size == (length - headerSize).toInt()) { "Truncated VAP configuration" }
            return parseVapConfig(body.decodeToString())
        }
        offset += length
    }
}
private fun ByteArray.uint(at: Int): Long = (at until at + 4).fold(0L) { n, i -> (n shl 8) or (this[i].toLong() and 255) }
internal fun parseVapConfig(source: String): VapConfig {
    val root = Json.parseToJsonElement(source).jsonObject
    val info = root.getValue("info").jsonObject
    require(info.int("v") == 2) { "Only VAP v2 is supported" }
    val width = info.int("w"); val height = info.int("h")
    val vw = info.int("videoW"); val vh = info.int("videoH")
    val fps = info.int("fps"); val frames = info.int("f")
    require(width > 0 && height > 0 && vw > 0 && vh > 0 && fps > 0 && frames > 0) { "Invalid VAP dimensions or timing" }
    fun rect(value: JsonElement): VapRect {
        val a = value.jsonArray
        require(a.size == 4) { "Invalid VAP rectangle" }
        return VapRect(a[0].jsonPrimitive.int, a[1].jsonPrimitive.int, a[2].jsonPrimitive.int, a[3].jsonPrimitive.int).also {
            require(it.w > 0 && it.h > 0) { "Invalid VAP rectangle" }
        }
    }
    fun videoRect(value: JsonElement): VapRect = rect(value).also {
        require(it.x >= 0 && it.y >= 0 && it.x.toLong() + it.w <= vw && it.y.toLong() + it.h <= vh) { "VAP region exceeds packed video" }
    }
    val resources = root["src"]?.jsonArray.orEmpty().map { item ->
        val s = item.jsonObject
        val type = s.str("srcType")
        require(type == "img" || type == "txt") { "Unsupported VAPX resource: $type" }
        VapResource(s.str("srcId"), s.str("srcTag"), type == "txt", s.int("w"), s.int("h"),
            s["color"]?.jsonPrimitive?.content?.ifBlank { "#000000" } ?: "#000000",
            s["style"]?.jsonPrimitive?.content == "b", s["fitType"]?.jsonPrimitive?.content == "centerFull").also {
            parseVapColor(it.color)
            require(it.width > 0 && it.height > 0) { "Invalid VAPX resource size" }
        }
    }
    require(resources.map { it.id }.distinct().size == resources.size) { "Duplicate VAPX resource id" }
    val ids = resources.map { it.id }.toSet()
    val overlays = root["frame"]?.jsonArray.orEmpty().associate { item ->
        val f = item.jsonObject
        val index = f.int("i")
        require(index in 0 until frames) { "Invalid VAPX frame index" }
        index to f.getValue("obj").jsonArray.sortedBy { it.jsonObject.int("z") }.map { o ->
            val v = o.jsonObject
            VapOverlay(v.str("srcId"), rect(v.getValue("frame")), videoRect(v.getValue("mFrame")), v.int("mt")).also {
                require(it.id in ids && (it.rotation == 0 || it.rotation == 90)) { "Invalid VAPX overlay" }
            }
        }
    }
    return VapConfig(width, height, fps, frames, vw, vh, videoRect(info.getValue("rgbFrame")),
        videoRect(info.getValue("aFrame")), resources, overlays)
}
private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
