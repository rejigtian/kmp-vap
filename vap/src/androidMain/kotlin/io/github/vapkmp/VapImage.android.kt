package io.github.vapkmp

/**
 * @author tianruijie
 * Date: 2026/11/10
 * Desc: Android硬解与原始packed帧适配，无播放器View。
 * 生成来源：Codex AI生成
 */

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.*
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.android.asCoroutineDispatcher

/** 仅适配 Android 解码帧与资源；不创建 View，不执行 VAP 合成。 */
@Composable internal actual fun rememberVapHostActive(): Boolean {
    val context = LocalContext.current.applicationContext
    SideEffect { androidContext = context }
    val owner = LocalLifecycleOwner.current
    var active by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> active = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return active
}
// 仅应用缓存路径，不持有 Activity；每个宿主进入组合时提供自己的 applicationContext。
private var androidContext: Context? = null
internal actual suspend fun resolveVapFile(source: String): String = withContext(Dispatchers.IO) {
    if (!source.startsWith("http://") && !source.startsWith("https://")) {
        return@withContext if (source.startsWith("file:")) File(URI(source)).absolutePath else source
    }
    val directory = File(requireNotNull(androidContext).cacheDir, "vap-kmp").apply { mkdirs() }
    val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
    val target = File(directory, digest)
    if (!target.isFile || target.length() == 0L) {
        val temporary = File.createTempFile("download-", ".part", directory)
        val connection = URL(source).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(chunk)
                        if (count < 0) break
                        output.write(chunk, 0, count)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            check(temporary.renameTo(target) || target.isFile) { "Cannot cache VAP resource" }
        } finally { connection.disconnect(); temporary.delete() }
    }
    target.absolutePath
}
internal actual suspend fun readVapFile(path: String, offset: Long, count: Int): ByteArray = withContext(Dispatchers.IO) {
    RandomAccessFile(path, "r").use { f ->
        f.seek(offset)
        val data = ByteArray(minOf(count.toLong(), (f.length() - offset).coerceAtLeast(0)).toInt())
        f.readFully(data); data
    }
}
internal actual suspend fun loadVapImage(source: String): ImageBitmap {
    val path = resolveVapFile(source)
    return withContext(Dispatchers.IO) { requireNotNull(BitmapFactory.decodeFile(path)) { "Cannot decode VAPX image" }.asImageBitmap() }
}
internal actual suspend fun decodeVapImage(encoded: ByteArray): ImageBitmap = withContext(Dispatchers.IO) {
    requireNotNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size)) { "Cannot decode VAPX image" }.asImageBitmap()
}
internal actual suspend fun openVapDecoder(path: String): VapDecoder {
    val worker = HandlerThread("vap-frame-adapter").apply { start() }
    val handler = Handler(worker.looper)
    val thread = handler.asCoroutineDispatcher("vap-frame-adapter")
    var created: AndroidVapDecoder? = null
    return try { withContext(thread) { AndroidVapDecoder(path, thread, handler, worker).also { created = it } } }
    catch (error: Throwable) {
        withContext(NonCancellable) { if (created != null) created?.close() else worker.quitSafely() }
        throw error
    }
}

private class AndroidVapDecoder(path: String, private val thread: CoroutineDispatcher, handler: Handler, private val worker: HandlerThread) : VapDecoder {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var gl: PackedFrameAdapter? = null
    private val info = MediaCodec.BufferInfo()
    private var inputEnded = false
    private var outputEnded = false
    private var closed = false
    init {
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("MP4 has no video track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            gl = PackedFrameAdapter(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), handler)
            val created = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = created
            created.configure(format, requireNotNull(gl).surface, null, 0)
            created.start()
        } catch (error: Throwable) { dispose(); throw error }
    }
    override suspend fun nextFrame(): VapDecodedFrame? {
        var owned: VapDecodedFrame? = null
        try { return withContext(thread) {
        check(!closed)
        if (outputEnded) return@withContext null
        val decoder = requireNotNull(codec)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!inputEnded) {
                val i = decoder.dequeueInputBuffer(0)
                if (i >= 0) {
                    val buffer = requireNotNull(decoder.getInputBuffer(i))
                    val bytes = extractor.readSampleData(buffer, 0)
                    if (bytes < 0) { decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                    else { decoder.queueInputBuffer(i, 0, bytes, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val output = decoder.dequeueOutputBuffer(info, 10_000)
            if (output >= 0) {
                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val pts = info.presentationTimeUs
                val hasFrame = info.size > 0
                decoder.releaseOutputBuffer(output, hasFrame)
                if (hasFrame) return@withContext requireNotNull(gl).frame(pts).also { owned = it }
                if (outputEnded) return@withContext null
            }
            yield()
        }
        @Suppress("UNREACHABLE_CODE") null
        } } catch (error: Throwable) { owned?.release?.invoke(); throw error }
    }
    override suspend fun rewind(): Unit = withContext(thread) {
        check(!closed)
        requireNotNull(codec).flush()
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        inputEnded = false; outputEnded = false
    }
    override suspend fun close() {
        withContext(NonCancellable + thread) { if (!closed) { closed = true; dispose() } }
        worker.quitSafely()
    }
    private fun dispose() {
        try { codec?.stop() } catch (_: IllegalStateException) { /* 部分初始化未启动。 */ }
        codec?.release(); codec = null
        extractor.release()
        gl?.close(); gl = null
    }
}

/** OES解码纹理一次GPU绘制到独立packed帧；不做VAP合成，不复用尚在消费的缓冲。 */
private class PackedFrameAdapter(private val width: Int, private val height: Int, private val handler: Handler) {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: android.opengl.EGLSurface = EGL14.EGL_NO_SURFACE
    private var hardwareTarget = 0L
    private var texture: SurfaceTexture? = null
    lateinit var surface: Surface
        private set
    private val available = Channel<Unit>(Channel.CONFLATED)
    private var textureId = 0
    private var program = 0
    private var positionLocation = 0
    private var uvLocation = 0
    private var matrixLocation = 0
    private var samplerLocation = 0
    private val matrix = FloatArray(16)
    private val coordinates = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // SurfaceTexture矩阵负责解码裁剪；FBO/软件首行方向在初始化中统一。
        put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)); position(0)
    }
    private val softwarePixels = if (Build.VERSION.SDK_INT < 29) ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder()) else null
    init {
        try {
            val versions = IntArray(2)
            check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "Cannot initialize EGL frame adapter" }
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE),0,configs,0,1,count,0) && count[0] > 0)
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
            eglSurface = EGL14.eglCreatePbufferSurface(display,config,
                intArrayOf(EGL14.EGL_WIDTH,if (Build.VERSION.SDK_INT >= 29) 1 else width,
                    EGL14.EGL_HEIGHT,if (Build.VERSION.SDK_INT >= 29) 1 else height,EGL14.EGL_NONE),0)
            check(context != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display,eglSurface,eglSurface,context))
            val names = IntArray(1); GLES20.glGenTextures(1,names,0); textureId = names[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
            texture = SurfaceTexture(textureId).also {
                it.setDefaultBufferSize(width,height)
                it.setOnFrameAvailableListener({ available.trySend(Unit) },handler)
            }
            surface = Surface(requireNotNull(texture))
            program = GLES20.glCreateProgram()
            fun shader(type: Int, code: String): Int {
                val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s,code); GLES20.glCompileShader(s)
                val ok = IntArray(1); GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0)
                check(ok[0] != 0) { GLES20.glGetShaderInfoLog(s) }
                GLES20.glAttachShader(program,s); return s
            }
            // FBO与glReadPixels均以底行为原点；翻转顶点，让Bitmap的首行保持视频顶部。
            for (i in 0 until 4) coordinates.put(i * 4 + 1, -coordinates.get(i * 4 + 1))
            val vertex = shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 p; attribute vec2 uv; uniform mat4 matrix; varying vec2 t; void main(){ gl_Position=vec4(p,0.,1.); t=(matrix*vec4(uv,0.,1.)).xy; }")
            val fragment = shader(GLES20.GL_FRAGMENT_SHADER,"#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES video; varying vec2 t; void main(){ gl_FragColor=vec4(texture2D(video,t).rgb,1.); }")
            GLES20.glLinkProgram(program); GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
            val ok = IntArray(1); GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0)
            check(ok[0] != 0) { GLES20.glGetProgramInfoLog(program) }
            positionLocation = GLES20.glGetAttribLocation(program,"p")
            uvLocation = GLES20.glGetAttribLocation(program,"uv")
            matrixLocation = GLES20.glGetUniformLocation(program,"matrix")
            samplerLocation = GLES20.glGetUniformLocation(program,"video")
        } catch (error: Throwable) { close(); throw error }
    }
    suspend fun frame(pts: Long): VapDecodedFrame {
        try { withTimeout(10_000) { available.receive() } }
        catch (timeout: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); error("Timed out waiting for decoded video frame") }
        requireNotNull(texture).updateTexImage(); requireNotNull(texture).getTransformMatrix(matrix)
        var output: HardwareBuffer? = null
        if (Build.VERSION.SDK_INT >= 29) {
            output = HardwareBuffer.create(width,height,HardwareBuffer.RGBA_8888,1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            try {
                if (hardwareTarget == 0L) hardwareTarget = HardwareFrameTarget.create()
                HardwareFrameTarget.bind(hardwareTarget,output)
            } catch (error: Throwable) { output.close(); throw error }
        }
        try {
        GLES20.glViewport(0,0,width,height); GLES20.glUseProgram(program)
        val p = positionLocation; val uv = uvLocation
        coordinates.position(0); GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,16,coordinates); GLES20.glEnableVertexAttribArray(p)
        coordinates.position(2); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,coordinates); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glUniformMatrix4fv(matrixLocation,1,false,matrix,0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
        GLES20.glUniform1i(samplerLocation,0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        if (output != null) {
            // 独立帧不会交回可复用队列；写入完成后才交给共享Canvas。
            GLES20.glFinish()
            val buffer = output
            val bitmap = requireNotNull(Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB)))
            return VapDecodedFrame(bitmap.asImageBitmap(),pts) {
                bitmap.recycle()
                buffer.close()
            }
        }
        // API23–28 使用复用的direct buffer批量适配，帧本身在退休时显式释放。
        val pixels = requireNotNull(softwarePixels).apply { rewind() }
        GLES20.glReadPixels(0,0,width,height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels)
        val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(pixels)
        return VapDecodedFrame(bitmap.asImageBitmap(),pts) { bitmap.recycle() }
        } catch (error: Throwable) { output?.close(); throw error }
    }
    fun close() {
        if (hardwareTarget != 0L) { HardwareFrameTarget.destroy(hardwareTarget); hardwareTarget = 0L }
        if (::surface.isInitialized) surface.release()
        texture?.release(); texture = null
        if (context != EGL14.EGL_NO_CONTEXT) {
            if (program != 0) GLES20.glDeleteProgram(program)
            if (textureId != 0) GLES20.glDeleteTextures(1,intArrayOf(textureId),0)
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,eglSurface)
            EGL14.eglDestroyContext(display,context)
        }
        EGL14.eglTerminate(display)
        available.close()
    }
}

/** 仅桥接公开NDK的AHardwareBuffer/EGLImage，所有权与解码调度仍由Kotlin管理。 */
private object HardwareFrameTarget {
    init { System.loadLibrary("vap_frame") }
    external fun create(): Long
    external fun bind(handle: Long, buffer: HardwareBuffer)
    external fun destroy(handle: Long)
}
