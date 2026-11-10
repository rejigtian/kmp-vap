@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.vapkmp

/**
 * @author tianruijie
 * Date: 2026/11/10
 * Desc: AVAssetReader顺序解码及Skia帧适配，无UIView。
 * 生成来源：Codex AI生成
 */

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.*
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatusCancelled
import platform.AVFoundation.AVAssetReaderStatusCompleted
import platform.AVFoundation.AVAssetReaderStatusFailed
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferGetNumSamples
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeConvertScale
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.kCMTimeRoundingMethod_RoundTowardZero
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetPixelFormatType
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.downloadTaskWithURL
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.getBytes
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToFileOffset
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 原生只提供按序视频帧；播放时钟、暂停、循环与 VAP/VAPX 合成都由 commonMain 管理。 */
internal actual suspend fun openVapDecoder(path: String): VapDecoder {
    var created: IosVapDecoder? = null
    try {
        return withContext(Dispatchers.Default) { IosVapDecoder(path).also { created = it } }
    } catch (failure: Throwable) {
        created?.close()
        throw failure
    }
}

private class IosVapDecoder(path: String) : VapDecoder {
    private val mutex = Mutex()
    private val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
    private val track = asset.tracksWithMediaType(AVMediaTypeVideo)
        .filterIsInstance<AVAssetTrack>().firstOrNull()
        ?: throw IllegalArgumentException("VAP file has no video track: $path")
    private var reader: AVAssetReader? = null
    private var output: AVAssetReaderTrackOutput? = null
    private var closed = false

    init { restartReader() }

    private fun restartReader() {
        reader?.cancelReading()
        reader = null
        output = null
        val created = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            error.value = null
            AVAssetReader.assetReaderWithAsset(asset, error.ptr)
                ?: throw IllegalStateException(error.value?.localizedDescription ?: "Cannot create VAP asset reader")
        }
        // kCVPixelBufferPixelFormatTypeKey 是 CFStringRef；不能把 CPointer 当 NSDictionary 的 NSString key。
        // 系统常量的实际字符串值为 PixelFormatType；BGRA 由系统转换，Kotlin 不进行逐像素变换。
        val createdOutput = AVAssetReaderTrackOutput(
            track = track,
            outputSettings = mapOf("PixelFormatType" to kCVPixelFormatType_32BGRA.toLong()),
        )
        createdOutput.alwaysCopiesSampleData = false
        check(created.canAddOutput(createdOutput)) { "Cannot attach VAP video output" }
        created.addOutput(createdOutput)
        if (!created.startReading()) {
            val message = created.error?.localizedDescription ?: "Cannot start VAP video reader"
            created.cancelReading()
            throw IllegalStateException(message)
        }
        reader = created
        output = createdOutput
    }

    override suspend fun nextFrame(): VapDecodedFrame? = mutex.withLock {
        var ownedFrame: VapDecodedFrame? = null
        try { withContext(Dispatchers.Default) {
            check(!closed) { "VAP decoder is closed" }
            val activeReader = requireNotNull(reader)
            val activeOutput = requireNotNull(output)
            var result: VapDecodedFrame? = null
            while (result == null) {
                currentCoroutineContext().ensureActive()
                val sample = activeOutput.copyNextSampleBuffer()
                if (sample == null) {
                    when (activeReader.status) {
                        AVAssetReaderStatusCompleted -> return@withContext null
                        AVAssetReaderStatusCancelled -> throw CancellationException("VAP video reader was cancelled")
                        AVAssetReaderStatusFailed -> throw IllegalStateException(
                            activeReader.error?.localizedDescription ?: "VAP video decode failed"
                        )
                        else -> throw IllegalStateException("VAP video output ended before reader completed")
                    }
                }
                try {
                    // AVAssetReaderOutput 允许 marker-only sample；此时没有视频像素可供绘制。
                    if (CMSampleBufferGetNumSamples(sample) == 0L) continue
                    val pixel = requireNotNull(CMSampleBufferGetImageBuffer(sample)) { "VAP sample has no image buffer" }
                    check(CVPixelBufferGetPixelFormatType(pixel) == kCVPixelFormatType_32BGRA) {
                        "VAP decoder did not return BGRA pixels"
                    }
                    val time = CMSampleBufferGetPresentationTimeStamp(sample)
                    check(CMTimeGetSeconds(time).isFinite()) { "VAP frame has an invalid presentation timestamp" }
                    val ptsUs = CMTimeConvertScale(time, 1_000_000, kCMTimeRoundingMethod_RoundTowardZero)
                        .useContents { value }
                    val widthLong = CVPixelBufferGetWidth(pixel)
                    val heightLong = CVPixelBufferGetHeight(pixel)
                    val strideLong = CVPixelBufferGetBytesPerRow(pixel)
                    check(widthLong > 0uL && heightLong > 0uL && widthLong <= Int.MAX_VALUE.toULong() / 4uL &&
                        heightLong <= Int.MAX_VALUE.toULong() && strideLong <= Int.MAX_VALUE.toULong()) {
                        "VAP frame dimensions exceed supported size"
                    }
                    val width = widthLong.toInt()
                    val height = heightLong.toInt()
                    val sourceStride = strideLong.toInt()
                    val rowSize = width * 4
                    check(sourceStride >= rowSize && sourceStride.toLong() * height <= Int.MAX_VALUE) {
                        "VAP frame has invalid pixel storage"
                    }
                    check(CVPixelBufferLockBaseAddress(pixel, kCVPixelBufferLock_ReadOnly) == 0) {
                        "Cannot lock VAP frame pixels"
                    }
                    try {
                        val source = requireNotNull(CVPixelBufferGetBaseAddress(pixel)).reinterpret<ByteVar>()
                        val bitmap = Bitmap()
                        try {
                            check(bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))) {
                                "Cannot allocate VAP frame bitmap"
                            }
                            val pixels = requireNotNull(bitmap.peekPixels()) { "Cannot access VAP frame bitmap" }
                            try {
                                val target = requireNotNull(interpretCPointer<ByteVar>(pixels.addr))
                                // CVPixelBuffer 每行可能带 padding；只复制有效像素，避免末行 padding 越界。
                                for (row in 0 until height) {
                                    memcpy(target + row * bitmap.rowBytes, source + row * sourceStride, rowSize.toULong())
                                }
                            } finally { pixels.close() }
                            bitmap.setImmutable()
                            // asComposeImageBitmap 不复制像素；返回后所有权转交给 Frame/commonMain。
                            result = VapDecodedFrame(bitmap.asComposeImageBitmap(), ptsUs) {
                                if (!bitmap.isClosed) bitmap.close()
                            }
                            ownedFrame = result
                        } catch (error: Throwable) {
                            if (!bitmap.isClosed) bitmap.close()
                            throw error
                        }
                    } finally { CVPixelBufferUnlockBaseAddress(pixel, kCVPixelBufferLock_ReadOnly) }
                } finally {
                    // GetImageBuffer 返回借用引用，由 sample 持有；只释放 CopyNextSampleBuffer 的拥有引用。
                    CFRelease(sample)
                }
            }
            result
        } } catch (failure: Throwable) {
            // withContext 返回原 dispatcher 时可能立即取消；此时返回值未交给 commonMain。
            ownedFrame?.release?.invoke()
            throw failure
        }
    }

    override suspend fun rewind(): Unit = mutex.withLock {
        withContext(Dispatchers.Default) {
            check(!closed) { "VAP decoder is closed" }
            restartReader()
        }
    }

    override suspend fun close(): Unit = withContext(NonCancellable + Dispatchers.Default) {
        mutex.withLock {
            if (!closed) {
                closed = true
                reader?.cancelReading()
                reader = null
                output = null
            }
        }
    }
}

internal actual suspend fun resolveVapFile(source: String): String {
    require(source.isNotBlank()) { "Empty VAP resource" }
    if (source.startsWith('/')) return withContext(Dispatchers.IO) { requireLocalFile(source) }
    val url = requireNotNull(NSURL.URLWithString(source)) { "Invalid VAP resource URL" }
    return when (url.scheme?.lowercase()) {
        "file" -> withContext(Dispatchers.IO) { requireLocalFile(requireNotNull(url.path)) }
        "http", "https" -> {
            val destination = withContext(Dispatchers.IO) {
                val root = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
                    .firstOrNull() as? String ?: throw IllegalStateException("Cannot locate VAP cache directory")
                val directory = "$root/vap-kmp"
                memScoped {
                    val error = alloc<ObjCObjectVar<NSError?>>()
                    error.value = null
                    check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, error.ptr)) {
                        error.value?.localizedDescription ?: "Cannot create VAP cache directory"
                    }
                }
                "$directory/${sha256(source)}"
            }
            if (withContext(Dispatchers.IO) { NSFileManager.defaultManager.fileExistsAtPath(destination) }) destination
            else downloadVapResource(url, destination)
        }
        else -> throw IllegalArgumentException("VAP resource must be an absolute path, file URL or HTTP(S) URL")
    }
}

private fun requireLocalFile(path: String): String {
    require(path.startsWith('/') && NSFileManager.defaultManager.fileExistsAtPath(path)) {
        "VAP file does not exist: $path"
    }
    return path
}

private fun sha256(source: String): String {
    val bytes = source.encodeToByteArray()
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    bytes.usePinned { input ->
        digest.usePinned { output ->
            CC_SHA256(input.addressOf(0), bytes.size.toUInt(), output.addressOf(0).reinterpret<UByteVar>())
        }
    }
    return digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

private suspend fun downloadVapResource(url: NSURL, destination: String): String =
    suspendCancellableCoroutine { continuation ->
        val task = NSURLSession.sharedSession.downloadTaskWithURL(url) { temporary, response, error ->
            if (!continuation.isActive) return@downloadTaskWithURL
            try {
                if (error != null) throw IllegalStateException(error.localizedDescription)
                val status = (response as? NSHTTPURLResponse)?.statusCode
                check(status != null && status in 200L..299L) { "VAP download failed: HTTP $status" }
                val temporaryPath = requireNotNull(temporary?.path) { "VAP download has no temporary file" }
                val manager = NSFileManager.defaultManager
                memScoped {
                    val moveError = alloc<ObjCObjectVar<NSError?>>()
                    moveError.value = null
                    val moved = manager.moveItemAtPath(temporaryPath, destination, moveError.ptr)
                    // 并发下载同一 URL 时，另一任务可能已原子移入完整文件。
                    check(moved || manager.fileExistsAtPath(destination)) {
                        moveError.value?.localizedDescription ?: "Cannot cache VAP resource"
                    }
                }
                continuation.resume(destination)
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            }
        }
        continuation.invokeOnCancellation { task.cancel() }
        task.resume()
    }

internal actual suspend fun readVapFile(path: String, offset: Long, count: Int): ByteArray =
    withContext(Dispatchers.IO) {
        require(offset >= 0 && count >= 0) { "Invalid VAP read range" }
        if (count == 0) return@withContext ByteArray(0)
        val file = requireNotNull(NSFileHandle.fileHandleForReadingAtPath(path)) { "Cannot open VAP file: $path" }
        try {
            file.seekToFileOffset(offset.toULong())
            val data = file.readDataOfLength(count.toULong())
            ByteArray(data.length.toInt()).also { bytes ->
                if (bytes.isNotEmpty()) bytes.usePinned { data.getBytes(it.addressOf(0), bytes.size.toULong()) }
            }
        } finally { file.closeFile() }
    }

internal actual suspend fun loadVapImage(source: String): ImageBitmap {
    val path = resolveVapFile(source)
    return withContext(Dispatchers.Default) {
        val data = requireNotNull(NSData.dataWithContentsOfFile(path)) { "Cannot read VAPX image: $path" }
        require(data.length in 1uL..Int.MAX_VALUE.toULong()) { "Invalid VAPX image file size" }
        val bytes = ByteArray(data.length.toInt())
        bytes.usePinned { data.getBytes(it.addressOf(0), bytes.size.toULong()) }
        decodeVapImage(bytes)
    }
}

internal actual suspend fun decodeVapImage(encoded: ByteArray): ImageBitmap = withContext(Dispatchers.Default) {
    val image = Image.makeFromEncoded(encoded)
    try { image.toComposeImageBitmap() } finally { image.close() }
}

@Composable
internal actual fun rememberVapHostActive(): Boolean {
    var active by remember {
        mutableStateOf(UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive)
    }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        val foreground = center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, queue) { active = true }
        val background = center.addObserverForName(UIApplicationWillResignActiveNotification, null, queue) { active = false }
        onDispose {
            center.removeObserver(foreground)
            center.removeObserver(background)
        }
    }
    return active
}
