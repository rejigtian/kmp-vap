// 参考安卓：AndroidVapDecoder 的顺序解码、帧 PTS、rewind 与释放契约。
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
package io.github.vapkmp

/**
 * @author tianruijie
 * Date: 2026/11/10
 * Desc: 鸿蒙buffer模式顺序解码及系统像素转换，无ArkUI播放器。
 * 生成来源：Codex AI生成
 */

import androidx.compose.ui.graphics.asComposeImageBitmap
import cnames.structs.OH_AVBuffer
import cnames.structs.OH_AVCodec
import cnames.structs.OH_AVDemuxer
import cnames.structs.OH_AVFormat
import cnames.structs.OH_AVSource
import cnames.structs.OH_PixelmapNative
import cnames.structs.OH_Pixelmap_InitializationOptions
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.AVCodecKit.AVCapability.*
import platform.AVCodecKit.AVDemuxer.*
import platform.AVCodecKit.AVSource.*
import platform.AVCodecKit.CodecBase.*
import platform.AVCodecKit.Core.*
import platform.AVCodecKit.VideoDecoder.*
import platform.ImageKit.Image_NativeModule.*
import platform.posix.O_RDONLY
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.lseek
import platform.posix.memcpy
import platform.posix.open

/** 只解码完整 packed 视频；透明合成、VAPX、时间控制和回调全部属于 commonMain。 */
internal actual suspend fun openVapDecoder(path: String): VapDecoder {
    var owned: OhosVapDecoder? = null
    try {
        return withContext(Dispatchers.Default) {
            val decoder = OhosVapDecoder(path).also { owned = it }
            decoder.initialize()
            decoder
        }
    } catch (error: Throwable) { owned?.close(); throw error }
}

private fun avCheck(code: UInt, operation: String) {
    check(code == AV_ERR_OK) { "$operation failed: $code" }
}
private fun imageCheck(code: UInt, operation: String) {
    check(code == IMAGE_SUCCESS) { "$operation failed: $code" }
}
private fun readInt(format: CPointer<OH_AVFormat>, key: CPointer<ByteVar>?): Int? = memScoped {
    val out = alloc<IntVar>()
    if (OH_AVFormat_GetIntValue(format, key?.toKString(), out.ptr)) out.value else null
}
private fun readString(format: CPointer<OH_AVFormat>, key: CPointer<ByteVar>?): String? = memScoped {
    val out = alloc<CPointerVar<ByteVar>>()
    out.value = null
    if (OH_AVFormat_GetStringValue(format, key?.toKString(), out.ptr)) out.value?.toKString() else null
}

private data class OhosCodecBuffer(val index: UInt, val buffer: CPointer<OH_AVBuffer>)

/** 原生回调只移交解码器拥有的缓冲；未归还索引数由解码器限制，形成有界背压。 */
private class OhosDecodeSession(val codec: CPointer<OH_AVCodec>, val pixelFormat: Int) {
    val active = AtomicBoolean(true)
    val failure = AtomicReference<Throwable?>(null)
    val inputs = Channel<OhosCodecBuffer>(Channel.UNLIMITED)
    val outputs = Channel<OhosCodecBuffer>(Channel.UNLIMITED)
    val stable = StableRef.create(this)
    var feeder: Job? = null
    var eos = false
    fun fail(error: Throwable) {
        if (failure.compareAndSet(null, error)) {
            inputs.close(error)
            outputs.close(error)
        }
    }
}

private fun inputCallback(codec: CPointer<OH_AVCodec>?, index: UInt, buffer: CPointer<OH_AVBuffer>?, data: COpaquePointer?) {
    val session = data?.asStableRef<OhosDecodeSession>()?.get() ?: return
    if (!session.active.load()) return
    if (codec != session.codec || buffer == null) { session.fail(IllegalStateException("Invalid VAP input buffer")); return }
    session.inputs.trySend(OhosCodecBuffer(index, buffer))
}
private fun outputCallback(codec: CPointer<OH_AVCodec>?, index: UInt, buffer: CPointer<OH_AVBuffer>?, data: COpaquePointer?) {
    val session = data?.asStableRef<OhosDecodeSession>()?.get() ?: return
    if (!session.active.load()) return
    if (codec != session.codec || buffer == null) { session.fail(IllegalStateException("Invalid VAP output buffer")); return }
    session.outputs.trySend(OhosCodecBuffer(index, buffer))
}
private fun errorCallback(codec: CPointer<OH_AVCodec>?, error: Int, data: COpaquePointer?) {
    val session = data?.asStableRef<OhosDecodeSession>()?.get() ?: return
    if (session.active.load()) session.fail(IllegalStateException("VAP decoder error: $error"))
}
private fun formatCallback(codec: CPointer<OH_AVCodec>?, format: CPointer<OH_AVFormat>?, data: COpaquePointer?) {
    // format is valid only inside this callback. nextFrame obtains its own owned output description.
}

private class OhosVapDecoder(private val path: String) : VapDecoder {
    private val lifecycle = Mutex()
    private val nativeAccess = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var fd = -1
    private var source: CPointer<OH_AVSource>? = null
    private var demuxer: CPointer<OH_AVDemuxer>? = null
    private var trackFormat: CPointer<OH_AVFormat>? = null
    private var trackId: UInt = 0u
    private var session: OhosDecodeSession? = null
    private var closed = false
    private var width = 0
    private var height = 0
    private var mime = ""

    suspend fun initialize() {
        fd = open(path, O_RDONLY)
        check(fd >= 0) { "Cannot open VAP file: $path" }
        val length = lseek(fd, 0, SEEK_END)
        check(length > 0 && lseek(fd, 0, SEEK_SET) >= 0) { "Cannot read VAP file size" }
        source = checkNotNull(OH_AVSource_CreateWithFD(fd, 0, length)) { "Cannot create VAP source" }
        val global = checkNotNull(OH_AVSource_GetSourceFormat(source))
        val count = try { readInt(global, OH_MD_KEY_TRACK_COUNT) ?: 0 } finally { OH_AVFormat_Destroy(global) }
        for (index in 0 until count) {
            val candidate = OH_AVSource_GetTrackFormat(source, index.toUInt()) ?: continue
            if (readInt(candidate, OH_MD_KEY_TRACK_TYPE) == MEDIA_TYPE_VID.toInt()) {
                trackFormat = candidate
                trackId = index.toUInt()
                break
            }
            OH_AVFormat_Destroy(candidate)
        }
        val format = checkNotNull(trackFormat) { "VAP contains no video track" }
        width = readInt(format, OH_MD_KEY_WIDTH) ?: 0
        height = readInt(format, OH_MD_KEY_HEIGHT) ?: 0
        require(width > 0 && height > 0 && width.toLong() * height <= 33_554_432L) { "Invalid VAP video dimensions" }
        mime = checkNotNull(readString(format, OH_MD_KEY_CODEC_MIME)) { "Missing VAP codec MIME" }
        demuxer = checkNotNull(OH_AVDemuxer_CreateWithSource(source)) { "Cannot create VAP demuxer" }
        avCheck(OH_AVDemuxer_SelectTrackByID(demuxer, trackId), "Select video track")
        startSession()
    }

    private suspend fun startSession() {
        val capability = checkNotNull(OH_AVCodec_GetCapability(mime, false)) { "Unsupported VAP codec: $mime" }
        val supported = memScoped {
            val values = alloc<CPointerVar<IntVar>>()
            val count = alloc<UIntVar>()
            values.value = null; count.value = 0u
            avCheck(OH_AVCapability_GetVideoSupportedPixelFormats(capability, values.ptr, count.ptr), "Query pixel formats")
            val pointer = checkNotNull(values.value)
            (0 until count.value.toInt()).map { pointer[it] }
        }
        val selected = listOf(AV_PIXEL_FORMAT_RGBA, AV_PIXEL_FORMAT_NV12, AV_PIXEL_FORMAT_NV21)
            .map { it.toInt() }.firstOrNull { it in supported }
            ?: error("VAP decoder does not support RGBA/NV12/NV21: $supported")
        val name = checkNotNull(OH_AVCapability_GetName(capability)?.toKString())
        val codec = checkNotNull(OH_VideoDecoder_CreateByName(name)) { "Cannot create VAP codec: $name" }
        val current = OhosDecodeSession(codec, selected)
        session = current
        memScoped {
            val callbacks = alloc<OH_AVCodecCallback> {
                onError = staticCFunction(::errorCallback)
                onStreamChanged = staticCFunction(::formatCallback)
                onNeedInputBuffer = staticCFunction(::inputCallback)
                onNewOutputBuffer = staticCFunction(::outputCallback)
            }
            avCheck(OH_VideoDecoder_RegisterCallback(codec, callbacks.readValue(), current.stable.asCPointer()), "Register callbacks")
        }
        check(OH_AVFormat_SetIntValue(trackFormat, OH_MD_KEY_PIXEL_FORMAT?.toKString(), selected))
        avCheck(OH_VideoDecoder_Configure(codec, trackFormat), "Configure VAP decoder")
        avCheck(OH_VideoDecoder_Prepare(codec), "Prepare VAP decoder")
        // Launch before Start: Start may synchronously report the first available buffers.
        current.feeder = scope.launch {
            try {
                for (input in current.inputs) {
                    if (!current.active.load()) break
                    val eos = nativeAccess.withLock {
                        if (!current.active.load()) return@withLock true
                        avCheck(OH_AVDemuxer_ReadSampleBuffer(demuxer, trackId, input.buffer), "Read VAP sample")
                        val end = memScoped {
                            val attr = alloc<OH_AVCodecBufferAttr>()
                            avCheck(OH_AVBuffer_GetBufferAttr(input.buffer, attr.ptr), "Read input attributes")
                            attr.flags and AVCODEC_BUFFER_FLAGS_EOS != 0u
                        }
                        avCheck(OH_VideoDecoder_PushInputBuffer(codec, input.index), "Submit VAP sample")
                        end
                    }
                    if (eos) break
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Throwable) { if (current.active.load()) current.fail(error) }
        }
        avCheck(OH_VideoDecoder_Start(codec), "Start VAP decoder")
    }

    override suspend fun nextFrame(): VapDecodedFrame? {
        var owned: VapDecodedFrame? = null
        try { return withContext(Dispatchers.Default) {
        val current = lifecycle.withLock { check(!closed); checkNotNull(session) }
        if (current.eos) return@withContext null
        current.failure.load()?.let { throw it }
        val output = current.outputs.receive()
        nativeAccess.withLock {
            check(current.active.load()) { "VAP decoder session is closed" }
            try {
                memScoped {
                    val attr = alloc<OH_AVCodecBufferAttr>()
                    avCheck(OH_AVBuffer_GetBufferAttr(output.buffer, attr.ptr), "Read output attributes")
                    if (attr.flags and AVCODEC_BUFFER_FLAGS_EOS != 0u) {
                        current.eos = true
                        if (attr.size == 0) return@memScoped null
                    }
                    val format = checkNotNull(OH_VideoDecoder_GetOutputDescription(current.codec))
                    val layout = try { outputLayout(format, current.pixelFormat, width, height) }
                    finally { OH_AVFormat_Destroy(format) }
                    val base = checkNotNull(OH_AVBuffer_GetAddr(output.buffer))
                    val capacity = OH_AVBuffer_GetCapacity(output.buffer)
                    require(attr.offset >= 0 && attr.size > 0 && attr.offset.toLong() + attr.size <= capacity.toLong()) {
                        "Invalid VAP output buffer bounds"
                    }
                    val bitmap = videoBitmap(checkNotNull(base + attr.offset), attr.size, layout)
                    try {
                        bitmap.setImmutable()
                        val released = AtomicBoolean(false)
                        VapDecodedFrame(bitmap.asComposeImageBitmap(), attr.pts) {
                            if (released.compareAndSet(false, true)) bitmap.close()
                        }.also { owned = it }
                    } catch (error: Throwable) { bitmap.close(); throw error }
                }
            } finally { avCheck(OH_VideoDecoder_FreeOutputBuffer(current.codec, output.index), "Return VAP output buffer") }
        }
        } } catch (error: Throwable) { owned?.release?.invoke(); throw error }
    }

    override suspend fun rewind(): Unit = withContext(Dispatchers.Default) {
        lifecycle.withLock {
            check(!closed)
            stopSession()
            avCheck(OH_AVDemuxer_SeekToTime(demuxer, 0, SEEK_MODE_PREVIOUS_SYNC), "Rewind VAP")
            startSession()
        }
    }

    private suspend fun stopSession(): Unit = withContext(NonCancellable) {
        val current = session ?: return@withContext
        session = null
        current.active.store(false)
        current.inputs.close()
        current.outputs.close()
        current.feeder?.cancelAndJoin()
        nativeAccess.withLock {
            // Destroy/Stop do not run inside a codec callback. StableRef stays valid until native callbacks end.
            OH_VideoDecoder_Stop(current.codec)
            OH_VideoDecoder_Destroy(current.codec)
            current.stable.dispose()
            // Stop/Destroy invalidates outstanding indices; never FreeOutputBuffer queued old indices afterwards.
            while (current.inputs.tryReceive().isSuccess) { }
            while (current.outputs.tryReceive().isSuccess) { }
        }
    }

    override suspend fun close(): Unit = withContext(NonCancellable + Dispatchers.Default) {
        lifecycle.withLock {
            if (closed) return@withLock
            closed = true
            stopSession()
            trackFormat?.let { OH_AVFormat_Destroy(it) }; trackFormat = null
            demuxer?.let { OH_AVDemuxer_Destroy(it) }; demuxer = null
            source?.let { OH_AVSource_Destroy(it) }; source = null
            if (fd >= 0) { platform.posix.close(fd); fd = -1 }
            scope.cancel()
        }
    }
}

private data class OhosVideoLayout(
    val pixelFormat: Int, val stride: Int, val sliceHeight: Int,
    val cropX: Int, val cropY: Int, val width: Int, val height: Int,
)
private fun outputLayout(format: CPointer<OH_AVFormat>, requested: Int, fallbackW: Int, fallbackH: Int): OhosVideoLayout {
    val w = readInt(format, OH_MD_KEY_WIDTH) ?: fallbackW
    val h = readInt(format, OH_MD_KEY_HEIGHT) ?: fallbackH
    val pixelFormat = readInt(format, OH_MD_KEY_PIXEL_FORMAT) ?: requested
    val rgba = pixelFormat == AV_PIXEL_FORMAT_RGBA.toInt()
    val stride = readInt(format, OH_MD_KEY_VIDEO_STRIDE) ?: if (rgba) w * 4 else w
    val slice = readInt(format, OH_MD_KEY_VIDEO_SLICE_HEIGHT) ?: h
    val left = readInt(format, OH_MD_KEY_VIDEO_CROP_LEFT) ?: 0
    val top = readInt(format, OH_MD_KEY_VIDEO_CROP_TOP) ?: 0
    val right = readInt(format, OH_MD_KEY_VIDEO_CROP_RIGHT) ?: (w - 1)
    val bottom = readInt(format, OH_MD_KEY_VIDEO_CROP_BOTTOM) ?: (h - 1)
    require(w > 0 && h > 0 && stride > 0 && slice >= h && left >= 0 && top >= 0 && right >= left && bottom >= top && right < w && bottom < h) {
        "Invalid VAP decoder output layout"
    }
    return OhosVideoLayout(pixelFormat, stride, slice, left, top, right-left+1, bottom-top+1)
}

/** System conversion writes directly to native Skia pixels; there is no Kotlin pixel loop or ByteArray frame copy. */
private fun videoBitmap(pixels: CPointer<UByteVar>, byteCount: Int, layout: OhosVideoLayout): Bitmap {
    val bitmap = Bitmap()
    try {
        val info = ImageInfo(layout.width, layout.height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
        check(bitmap.allocPixels(info, layout.width * 4)) { "Cannot allocate VAP frame" }
        val pixmap = checkNotNull(bitmap.peekPixels())
        try {
            val destination = checkNotNull(interpretCPointer<UByteVar>(pixmap.addr))
            if (layout.pixelFormat == AV_PIXEL_FORMAT_RGBA.toInt()) {
                require(layout.stride >= (layout.cropX + layout.width) * 4)
                require(byteCount.toLong() >= (layout.cropY + layout.height - 1L) * layout.stride + (layout.cropX + layout.width) * 4L)
                repeat(layout.height) { y ->
                    memcpy(destination + y * bitmap.rowBytes,
                        pixels + (layout.cropY + y) * layout.stride + layout.cropX * 4,
                        (layout.width * 4).toULong())
                }
            } else {
                require(layout.pixelFormat == AV_PIXEL_FORMAT_NV12.toInt() || layout.pixelFormat == AV_PIXEL_FORMAT_NV21.toInt()) {
                    "Unsupported VAP output format: ${layout.pixelFormat}"
                }
                require(layout.stride % 2 == 0 && layout.sliceHeight % 2 == 0)
                require(byteCount.toLong() >= layout.stride.toLong() * layout.sliceHeight * 3 / 2)
                nvToSkia(pixels, byteCount, layout, destination, bitmap.computeByteSize().toULong())
            }
        } finally { pixmap.close() }
        return bitmap
    } catch (error: Throwable) { bitmap.close(); throw error }
}

private fun nvToSkia(pixels: CPointer<UByteVar>, byteCount: Int, layout: OhosVideoLayout, destination: CPointer<UByteVar>, destinationSize: ULong): Unit = memScoped {
    val options = alloc<CPointerVar<OH_Pixelmap_InitializationOptions>>()
    val pixelmap = alloc<CPointerVar<OH_PixelmapNative>>()
    options.value = null; pixelmap.value = null
    try {
        imageCheck(OH_PixelmapInitializationOptions_Create(options.ptr), "Create pixel options")
        // Interpret YUV padded storage, then crop the converted RGB picture to its visible packed-video extent.
        imageCheck(OH_PixelmapInitializationOptions_SetWidth(options.value, layout.stride.toUInt()), "Set storage width")
        imageCheck(OH_PixelmapInitializationOptions_SetHeight(options.value, layout.sliceHeight.toUInt()), "Set storage height")
        val sourceFormat = if (layout.pixelFormat == AV_PIXEL_FORMAT_NV12.toInt()) PIXEL_FORMAT_NV12 else PIXEL_FORMAT_NV21
        imageCheck(OH_PixelmapInitializationOptions_SetSrcPixelFormat(options.value, sourceFormat.toInt()), "Set source pixel format")
        imageCheck(OH_PixelmapInitializationOptions_SetPixelFormat(options.value, PIXEL_FORMAT_RGBA_8888.toInt()), "Set destination pixel format")
        imageCheck(OH_PixelmapInitializationOptions_SetAlphaType(options.value, PIXELMAP_ALPHA_TYPE_OPAQUE.toInt()), "Set opaque alpha")
        imageCheck(OH_PixelmapNative_CreatePixelmap(pixels, byteCount.toULong(), options.value, pixelmap.ptr), "Convert NV12/NV21 frame to RGBA")
        if (layout.cropX != 0 || layout.cropY != 0 || layout.width != layout.stride || layout.height != layout.sliceHeight) {
            val region = alloc<Image_Region> {
                x = layout.cropX.toUInt(); y = layout.cropY.toUInt()
                width = layout.width.toUInt(); height = layout.height.toUInt()
            }
            imageCheck(OH_PixelmapNative_Crop(pixelmap.value, region.ptr), "Crop VAP frame")
        }
        val size = alloc<ULongVar> { value = destinationSize }
        imageCheck(OH_PixelmapNative_ReadPixels(pixelmap.value, destination, size.ptr), "Read converted VAP frame")
    } finally {
        pixelmap.value?.let { OH_PixelmapNative_Release(it) }
        options.value?.let { OH_PixelmapInitializationOptions_Release(it) }
    }
}
