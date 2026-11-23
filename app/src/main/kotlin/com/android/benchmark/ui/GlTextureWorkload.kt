package com.android.benchmark.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import com.android.benchmark.ui.WorkloadViews.Scene
import com.android.benchmark.ui.automation.Automator.Workload
import java.util.concurrent.atomic.AtomicBoolean

/** A GL producer feeds a hardware TextureView in the benchmark's own window. */
internal class GlTextureWorkload(context: Context) : TextureView.SurfaceTextureListener, Workload {
    private val view = TextureView(context).apply { surfaceTextureListener = this@GlTextureWorkload }
    private var producer: TextureProducer? = null
    private var updates = 0
    private var firstUpdate = 0

    fun scene() = Scene(view, this)

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        producer = TextureProducer(Surface(texture), width, height)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        producer?.resize(width, height)
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        updates++
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val active = producer ?: return true
        producer = null
        active.stop(texture)
        return false
    }

    override fun ready(): Boolean = producer?.let { it.error != null || updates > 0 } ?: false

    override fun prepare() {
        producer?.error?.let { error(it) }
        check(updates > 0) { "The GL texture did not present its first buffer" }
        firstUpdate = updates
        view.cameraDistance = maxOf(view.width, view.height) * 2f
    }

    override fun advance(elapsedNanos: Long): Boolean {
        val active = checkNotNull(producer) { "The GL texture was detached" }
        active.error?.let { error(it) }
        val fraction = (elapsedNanos / 4_000_000_000f).coerceAtMost(1f)
        view.rotationY = fraction * 360f
        active.request(fraction)
        if (fraction >= 1f) {
            check(updates - firstUpdate >= 12) { "Too few GL texture buffers were produced" }
            return true
        }
        return false
    }

    override fun close() {
        producer?.pause()
    }
}

private class TextureProducer(private val surface: Surface, width: Int, height: Int) {
    private val worker = HandlerThread("UiBench GL").apply { start() }
    private val handler = Handler(worker.looper)
    private val scheduled = AtomicBoolean()
    @Volatile private var paused = false
    @Volatile var error: String? = null
        private set
    private var w = width
    private var h = height
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE

    init {
        handler.post {
            try {
                initialize()
                draw(0f)
            } catch (exception: RuntimeException) {
                error = exception.message ?: "The OpenGL context could not start"
            }
        }
    }

    fun resize(width: Int, height: Int) {
        handler.post {
            w = width
            h = height
        }
    }

    fun request(fraction: Float) {
        if (paused || !scheduled.compareAndSet(false, true)) {
            return
        }
        handler.post {
            try {
                if (!paused && error == null) {
                    draw(fraction)
                }
            } catch (exception: RuntimeException) {
                error = exception.message ?: "Drawing a texture buffer failed"
            } finally {
                scheduled.set(false)
            }
        }
    }

    fun pause() {
        paused = true
    }

    fun stop(texture: SurfaceTexture) {
        paused = true
        handler.post {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                    EGL14.eglReleaseThread()
                }
            } finally {
                surface.release()
                texture.release()
                worker.quitSafely()
            }
        }
    }

    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) {
            "EGL display initialization failed"
        }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, count, 0) &&
            count[0] > 0) { "No EGL window configuration is available" }
        val config = checkNotNull(configs[0])
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE &&
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            "EGL could not attach to the texture"
        }
    }

    private fun draw(fraction: Float) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0.14f, 0.19f, 0.27f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        for (i in 0..11) {
            val x = ((fraction * w + i * w / 12) % w).toInt()
            GLES20.glScissor(x, h / 8 + i % 3 * h / 5, maxOf(1, w / 8), h / 3)
            GLES20.glClearColor(0.24f + i * 0.047f, 0.53f, 0.72f - i * 0.035f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "Could not swap an OpenGL buffer" }
    }
}
