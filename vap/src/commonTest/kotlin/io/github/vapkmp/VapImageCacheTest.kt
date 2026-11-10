package io.github.vapkmp

import kotlin.test.*

/**
 * @author tianruijie
 * Date: 2026/11/10
 * Desc: 验证图片内容失效、哈希碰撞、LRU预算及缓存快照所有权。
 * 生成来源：Codex AI生成
 */
internal class VapImageCacheTest {
    private class Picture(val name: String, val bytes: Long = 16)
    private fun cache(budget: Long = 1024) = VapImageCache<Picture>(budget) { it.bytes }

    @Test fun identicalEncodedContentReusesTheSameDecodedImage() {
        val cache = cache()
        val image = Picture("original")
        cache.put(byteArrayOf(1, 2, 3), image)
        assertSame(image, cache.get(byteArrayOf(1, 2, 3)))
        assertNull(cache.get(byteArrayOf(1, 2, 4)))
    }

    @Test fun hashCollisionDoesNotMixDynamicImages() {
        val first = byteArrayOf(0, 31)
        val second = byteArrayOf(1, 0)
        assertEquals(first.contentHashCode(), second.contentHashCode())
        val cache = cache()
        val a = Picture("A"); val b = Picture("B")
        cache.put(first, a); cache.put(second, b)
        assertSame(a, cache.get(first))
        assertSame(b, cache.get(second))
    }

    @Test fun changingInputBytesCannotCorruptAStoredKey() {
        val cache = cache()
        val bytes = byteArrayOf(4, 5)
        val image = Picture("A")
        cache.put(bytes, image)
        bytes[0] = 9
        assertSame(image, cache.get(byteArrayOf(4, 5)))
        assertNull(cache.get(bytes))
    }

    @Test fun recentlyUsedEntrySurvivesEvictionAndBudgetIncludesEncodedBytes() {
        // 每条1字节编码 + 16字节像素 + 256字节条目预算，恰好容纳两条。
        val cache = cache(546)
        val a = Picture("A"); val b = Picture("B"); val c = Picture("C")
        cache.put(byteArrayOf(1), a); cache.put(byteArrayOf(2), b)
        assertSame(a, cache.get(byteArrayOf(1)))
        cache.put(byteArrayOf(3), c)
        assertNull(cache.get(byteArrayOf(2)))
        assertSame(a, cache.get(byteArrayOf(1)))
        assertSame(c, cache.get(byteArrayOf(3)))
        assertEquals(546L, cache.cachedBytes)
        assertEquals(2, cache.count)
    }

    @Test fun oversizedImageDoesNotDisplaceUsefulEntries() {
        val cache = cache(300)
        val small = Picture("small")
        cache.put(byteArrayOf(1), small)
        cache.put(byteArrayOf(2), Picture("large", 1000))
        assertSame(small, cache.get(byteArrayOf(1)))
        assertNull(cache.get(byteArrayOf(2)))
        assertEquals(273L, cache.cachedBytes)
    }

    @Test fun replacementAndClearKeepAccountingCorrect() {
        val cache = cache()
        cache.put(byteArrayOf(1), Picture("A"))
        val replacement = Picture("B", 24)
        cache.put(byteArrayOf(1), replacement)
        assertSame(replacement, cache.get(byteArrayOf(1)))
        assertEquals(281L, cache.cachedBytes)
        cache.clear()
        assertEquals(0L, cache.cachedBytes)
        assertEquals(0, cache.count)
        assertNull(cache.get(byteArrayOf(1)))
    }
}
