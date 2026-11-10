package io.github.vapkmp

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig

/**
 * @author tianruijie
 * Date: 2026/11/10
 * Desc: 单个播放器持有的原尺寸图片缓存，以完整编码内容识别图片，不缓存视频帧。
 * 生成来源：Codex AI生成
 */
internal class VapImageCache<T : Any>(private val maxBytes: Long, private val sizeOf: (T) -> Long) {
    private class Key(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = other is Key && bytes.contentEquals(other.bytes)
    }
    private class Entry<T>(val key: Key, val image: T, val cost: Long)
    private val entries = LinkedHashMap<Key, Entry<T>>()
    internal var cachedBytes: Long = 0
        private set
    internal val count: Int get() = entries.size

    // 由播放器的Compose线程串行调用；平台解码返回后再写入，无全局缓存或后台填充任务。
    fun get(encoded: ByteArray): T? {
        val entry = entries.remove(Key(encoded)) ?: return null
        entries[entry.key] = entry
        return entry.image
    }

    fun put(encoded: ByteArray, image: T) {
        // 计入编码快照、像素存储估算及条目开销。超预算图像仍可播放，只是不驻留缓存。
        val cost = encoded.size.toLong() + sizeOf(image) + 256L
        if (cost > maxBytes) return
        val key = Key(encoded.copyOf())
        entries.remove(key)?.let { cachedBytes -= it.cost }
        while (cachedBytes > maxBytes - cost) {
            val oldest = entries.entries.first()
            cachedBytes -= oldest.value.cost
            entries.remove(oldest.key)
        }
        entries[key] = Entry(key, image, cost)
        cachedBytes += cost
    }

    fun clear() { entries.clear(); cachedBytes = 0 }
}

internal fun vapImageCost(image: ImageBitmap): Long {
    val bytesPerPixel = when (image.config) {
        ImageBitmapConfig.Alpha8 -> 1
        ImageBitmapConfig.Rgb565 -> 2
        ImageBitmapConfig.F16 -> 8
        else -> 4
    }
    return image.width.toLong() * image.height * bytesPerPixel
}

internal suspend fun loadCachedVapImage(source: String, cache: VapImageCache<ImageBitmap>): ImageBitmap {
    val path = resolveVapFile(source)
    // 只为小型替换图片构建内容键；较大的编码文件沿用原加载路径，避免无限复制文件。
    val encodedLimit = 2 * 1024 * 1024
    val encoded = readVapFile(path, 0, encodedLimit + 1)
    if (encoded.size > encodedLimit) return loadVapImage(path)
    return cache.get(encoded) ?: decodeVapImage(encoded).also { cache.put(encoded, it) }
}
