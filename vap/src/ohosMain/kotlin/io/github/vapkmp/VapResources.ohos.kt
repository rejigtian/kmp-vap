// 参考安卓：VapResources.android.kt 的本地资源读取与宿主生命周期；鸿蒙 API12 网络通过 VapFileResolver 注入。
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.github.vapkmp

/**
 * @author tianruijie
 * Date: 2026/11/10
 * Desc: 鸿蒙本地资源和宿主生命周期适配。
 * 生成来源：Codex AI生成
 */

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import platform.posix.EINTR
import platform.posix.O_RDONLY
import platform.posix.SEEK_END
import platform.posix.close
import platform.posix.errno
import platform.posix.lseek
import platform.posix.open
import platform.posix.pread
import kotlin.coroutines.coroutineContext

/** API12 无跨 OpenHarmony 的 native HTTP/file-cache 标准实现；应用可注入任意下载缓存，播放器本身无 ArkTS/View/HAR。 */
internal actual suspend fun resolveVapFile(source: String): String = withContext(Dispatchers.Default) {
    require(!source.startsWith("http://", ignoreCase = true) && !source.startsWith("https://", ignoreCase = true)) {
        "HarmonyOS API12 network VAP/image sources require VapFileResolver to return a local absolute path"
    }
    val path = source.removePrefix("file://")
    require(path.startsWith('/')) { "VAP local file must be an absolute path: $source" }
    val fd = open(path, O_RDONLY)
    check(fd >= 0) { "Cannot read VAP file: $path" }
    close(fd)
    path
}

internal actual suspend fun readVapFile(path: String, offset: Long, count: Int): ByteArray = withContext(Dispatchers.Default) {
    require(offset >= 0 && count >= 0)
    if (count == 0) return@withContext ByteArray(0)
    val fd = open(path, O_RDONLY)
    check(fd >= 0) { "Cannot open VAP resource: $path" }
    try {
        val result = ByteArray(count)
        var total = 0
        result.usePinned { pinned ->
            while (total < count) {
                coroutineContext.ensureActive()
                val n = pread(fd, pinned.addressOf(total), (count - total).toULong(), offset + total)
                if (n < 0) {
                    if (errno == EINTR) continue
                    error("Cannot read VAP resource at $offset: errno=$errno")
                }
                if (n == 0L) break
                total += n.toInt()
            }
        }
        if (total == count) result else result.copyOf(total)
    } finally { close(fd) }
}

internal actual suspend fun loadVapImage(source: String): ImageBitmap = withContext(Dispatchers.Default) {
    val path = resolveVapFile(source)
    val fd = open(path, O_RDONLY)
    check(fd >= 0) { "Cannot open VAPX image: $path" }
    val size = try { lseek(fd, 0, SEEK_END) } finally { close(fd) }
    require(size in 1..16L * 1024 * 1024) { "Invalid VAPX image file size: $size" }
    val bytes = readVapFile(path, 0, size.toInt())
    check(bytes.size == size.toInt()) { "Incomplete VAPX image file: $path" }
    decodeVapImage(bytes)
}

internal actual suspend fun decodeVapImage(encoded: ByteArray): ImageBitmap = withContext(Dispatchers.Default) {
    val data = Data.makeFromBytes(encoded)
    try {
        val codec = Codec.makeFromData(data)
        try {
            val info = codec.imageInfo
            require(info.width > 0 && info.height > 0 && info.width.toLong() * info.height <= 16_777_216L) {
                "VAPX image exceeds pixel budget"
            }
        } finally { codec.close() }
    } finally { data.close() }
    val image = Image.makeFromEncoded(encoded)
    try { image.toComposeImageBitmap() } finally { image.close() }
}

/** Requires org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.4-1.1.0 in the OHOS source set. */
@Composable
internal actual fun rememberVapHostActive(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return state.isAtLeast(Lifecycle.State.STARTED)
}
