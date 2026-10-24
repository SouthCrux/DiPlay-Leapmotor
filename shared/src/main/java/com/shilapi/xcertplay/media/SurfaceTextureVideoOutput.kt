// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Optional SDR video input for the CPU compositor. Latches real SurfaceTexture buffers on its GL
 * worker and copies pixels only after their exact timestamp matches a registered decoder origin.
 * There is no physical output. GPU readback is an integration path, not a tuned production renderer.
 * Create off the UI thread; detach/retire the decoder before closing this target.
 * onError must retire the decoder and close this target; failure never releases a live codec's surface.
 */
class SurfaceTextureVideoOutput(
    private val width: Int,
    private val height: Int,
    private val onFrame: (VideoCompositionFrame) -> Unit,
    private val onError: () -> Unit = {},
) : VideoCompositionTarget, Closeable {
    private val closed = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val outstanding = AtomicInteger()
    private val origins = VideoSurfaceOrigins()
    private val thread = HandlerThread("diplay-ultra-video-input")
    private lateinit var handler: Handler
    private lateinit var outputSurface: Surface
    override val surface: Surface get() = outputSurface
    private val stopped = CountDownLatch(1)
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var pbuffer = EGL14.EGL_NO_SURFACE
    private var texture = 0
    private var program = 0
    private var source: SurfaceTexture? = null
    private val matrix = FloatArray(16)
    private lateinit var readback: ByteBuffer
    private val vertices = floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val coordinates = floats(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    init {
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 8_000_000)
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        thread.start()
        handler = Handler(thread.looper)
        handler.post {
            try { initialize() } catch (error: Throwable) { failure = error; close() }
            finally { ready.countDown() }
        }
        try {
            check(ready.await(10, TimeUnit.SECONDS)) { "Video GL initialization timed out" }
            check(failure == null) { "Video GL initialization failed" }
        } catch (error: Throwable) { close(); throw error }
    }

    override fun queueFrame(senderNanos: Long): Long = origins.queue(senderNanos)
    override fun discardFrame(token: Long) = origins.discard(token)

    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, null, 0, null, 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE,
        ), 0, configs, 0, 1, count, 0) && count[0] > 0)
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(
            EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        check(pbuffer != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context))
        program = link(
            "attribute vec2 position; attribute vec2 uv; uniform mat4 transform; varying vec2 tex; " +
                // Readback starts at the bottom row. Flip on the GPU so Bitmap receives top-first RGBA.
                "void main(){gl_Position=vec4(position.x,-position.y,0.0,1.0);tex=(transform*vec4(uv,0.0,1.0)).xy;}",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; " +
                "uniform samplerExternalOES video; varying vec2 tex; void main(){gl_FragColor=texture2D(video,tex);}",
        )
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0); texture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        source = SurfaceTexture(texture).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ latch() }, handler)
        }
        outputSurface = Surface(source)
        readback = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
    }

    private fun latch() {
        if (closed.get() || failed.get()) return
        try {
            val input = source ?: return
            input.updateTexImage()
            val token = input.timestamp
            val sender = origins.latched(token) ?: return
            if (outstanding.get() >= 3) return
            input.getTransformMatrix(matrix)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)
            val position = GLES20.glGetAttribLocation(program, "position")
            val uv = GLES20.glGetAttribLocation(program, "uv")
            GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, coordinates)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false, matrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "video"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            readback.clear()
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                readback.rewind()
                bitmap.copyPixelsFromBuffer(readback)
            } catch (failure: Throwable) {
                bitmap.recycle()
                throw failure
            }
            outstanding.incrementAndGet()
            val frame = VideoCompositionFrame(token, sender, bitmap) { outstanding.decrementAndGet() }
            if (closed.get()) frame.close()
            else try { onFrame(frame) } catch (_: Throwable) { frame.close(); throw IllegalStateException("Video consumer failed") }
        } catch (_: Throwable) {
            // The decoder may still render here. Stop accepting origins/callbacks, but let the owner
            // retire that codec before releasing its EGL/SurfaceTexture destination.
            if (failed.compareAndSet(false, true)) {
                origins.close()
                runCatching(onError)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        origins.close()
        handler.post {
            try {
                source?.setOnFrameAvailableListener(null)
                if (context != EGL14.EGL_NO_CONTEXT) GLES20.glFinish()
                if (::outputSurface.isInitialized) outputSurface.release()
                source?.release(); source = null
                if (program != 0) GLES20.glDeleteProgram(program)
                if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display); EGL14.eglReleaseThread()
                }
            } finally { stopped.countDown(); thread.quitSafely() }
        }
    }

    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis in 0..30_000)
        check(Thread.currentThread() !== thread) { "Cannot wait on the GL worker" }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        if (!stopped.await(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        val remaining = deadline - System.nanoTime()
        if (remaining > 0) thread.join((remaining / 1_000_000).coerceAtLeast(1))
        return !thread.isAlive
    }

    private fun floats(values: FloatArray) = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }

    private fun link(vertex: String, fragment: String): Int {
        fun shader(type: Int, text: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, text); GLES20.glCompileShader(id)
            val status = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) { GLES20.glDeleteShader(id); error("Video shader refused") }
            return id
        }
        val v = shader(GLES20.GL_VERTEX_SHADER, vertex)
        var f = 0
        var p = 0
        try {
            f = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
            p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
            val status = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Video program refused" }
            return p
        } catch (error: Throwable) { if (p != 0) GLES20.glDeleteProgram(p); throw error }
        finally { GLES20.glDeleteShader(v); if (f != 0) GLES20.glDeleteShader(f) }
    }
}
