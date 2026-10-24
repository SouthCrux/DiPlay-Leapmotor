package com.shilapi.xcertplay.vehicle.leapmotor

import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit one-shot SDK capture. No caller is started automatically. Run on a worker in an
 * isolated diagnostic process: native ownership and coexistence still need a vehicle trial.
 * Callback layout/actual geometry are unknown. Bytes are evidence, never an encoded image claim.
 */
object LeapmotorCameraSnapshots {
    enum class Camera(val oemId: Int) { AVM_FRONT(2), AVM_BACK(3), AVM_LEFT(4), AVM_RIGHT(5) }
    enum class Status { FRAME_BYTES_CAPTURED, BUSY, INIT_FAILED, PARAMETERS_REJECTED, CALLBACK_REJECTED,
        OPEN_FAILED, START_FAILED, TIMEOUT, INVALID_FRAME, CANCELLED, ERROR }
    class Frame internal constructor(val camera: Camera, val requestedWidth: Int,
        val requestedHeight: Int, private val bytes: ByteArray) {
        val byteCount: Int get() = bytes.size
        val actualChannelVerified: Boolean get() = false
        val pixelLayoutVerified: Boolean get() = false
        val actualGeometryVerified: Boolean get() = false
        fun copyBytes(): ByteArray = bytes.copyOf()
    }
    data class Result(val status: Status, val frame: Frame? = null, val cleanupFailed: Boolean = false)
    interface Backend {
        fun initYuv420(): Boolean
        fun setParams(width: Int, height: Int, fps: Float): Boolean
        fun setCallback(callback: (ByteArray?) -> Unit): Boolean
        fun open(cameraId: Int): Boolean
        fun start(): Boolean
        fun stop(): Boolean
        fun close(): Boolean
        fun uninit(): Boolean
    }
    private val busy = AtomicBoolean(false)
    private const val MAX_BYTES = 16 * 1024 * 1024

    /** No fallback to a render service, cabin camera, ADAS camera or screen capture. */
    fun capture(backend: Backend, camera: Camera, width: Int = 1920, height: Int = 1536,
        timeoutMillis: Long = 1500): Result {
        require(width in 2..4096 && height in 2..4096 && width % 2 == 0 && height % 2 == 0)
        require(width.toLong() * height <= 4_194_304 && timeoutMillis in 1..5000)
        if (!busy.compareAndSet(false, true)) return Result(Status.BUSY)
        val lock = Any()
        val ready = CountDownLatch(1)
        var accepting = true
        var streamRequested = false
        var received = false
        var captured: ByteArray? = null
        var initialized = false
        var opened = false
        var started = false
        var cleanupFailed = false
        var result: Result
        try {
            result = when {
                !backend.initYuv420() -> Result(Status.INIT_FAILED)
                else -> {
                    initialized = true
                    when {
                        !backend.setParams(width, height, 5f) -> Result(Status.PARAMETERS_REJECTED)
                        !backend.setCallback { bytes ->
                            synchronized(lock) {
                                if (accepting && streamRequested && !received) {
                                    received = true
                                    captured = bytes?.takeIf { it.isNotEmpty() && it.size <= MAX_BYTES }?.copyOf()
                                    ready.countDown()
                                }
                            }
                        } -> Result(Status.CALLBACK_REJECTED)
                        !backend.open(camera.oemId) -> Result(Status.OPEN_FAILED)
                        else -> {
                            opened = true
                            synchronized(lock) { streamRequested = true }
                            if (!backend.start()) Result(Status.START_FAILED) else {
                                started = true
                                val signalled = ready.await(timeoutMillis, TimeUnit.MILLISECONDS)
                                synchronized(lock) {
                                    accepting = false
                                    val bytes = captured
                                    when {
                                        !signalled -> Result(Status.TIMEOUT)
                                        bytes == null -> Result(Status.INVALID_FRAME)
                                        else -> Result(Status.FRAME_BYTES_CAPTURED,
                                            Frame(camera, width, height, bytes))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            result = Result(Status.CANCELLED)
        } catch (_: Exception) {
            result = Result(Status.ERROR)
        } catch (_: LinkageError) {
            result = Result(Status.ERROR)
        } finally {
            synchronized(lock) { accepting = false; captured = null }
            fun release(action: () -> Boolean) {
                try { if (!action()) cleanupFailed = true }
                catch (_: Exception) { cleanupFailed = true }
                catch (_: LinkageError) { cleanupFailed = true }
            }
            if (started) release(backend::stop)
            if (opened) release(backend::close)
            if (initialized) release(backend::uninit)
            busy.set(false)
        }
        return result.copy(cleanupFailed = cleanupFailed)
    }

    /** Method discovery does not initialize SDK classes or load the JNI library. Native work only
     * begins with an explicit capture(). Presence of the API is not proof of device access.
     */
    fun reflectiveBackend(loader: ClassLoader): Backend? = try {
        val manager = Class.forName("lmos.framework.core.cam.CamManager", false, loader)
        val info = Class.forName("lmos.framework.core.cam.CameraInfo", false, loader)
        val callback = Class.forName("lmos.framework.core.cam.ICamCallback", false, loader)
        ReflectiveBackend(manager, info, callback, loader)
    } catch (_: ReflectiveOperationException) { null }
    catch (_: LinkageError) { null }
    catch (_: SecurityException) { null }

    private class ReflectiveBackend(managerClass: Class<*>, private val infoClass: Class<*>,
        private val callbackClass: Class<*>, private val loader: ClassLoader) : Backend {
        private val singleton = managerClass.getMethod("getInstance")
        private val init = managerClass.getMethod("init", Boolean::class.javaPrimitiveType)
        private val params = managerClass.getMethod("setParams", infoClass)
        private val callback = managerClass.getMethod("setCallback", callbackClass)
        private val open = managerClass.getMethod("openCamera", Int::class.javaPrimitiveType)
        private val start = managerClass.getMethod("start")
        private val stop = managerClass.getMethod("stop")
        private val close = managerClass.getMethod("closeCamera")
        private val uninit = managerClass.getMethod("uninit")
        private val constructor = infoClass.getConstructor()
        private val width = infoClass.getMethod("setWidth", Int::class.javaPrimitiveType)
        private val height = infoClass.getMethod("setHeight", Int::class.javaPrimitiveType)
        private val fps = infoClass.getMethod("setFps", Float::class.javaPrimitiveType)
        init {
            require(callbackClass.isInterface)
            callbackClass.getMethod("onDataCallback", ByteArray::class.java)
        }
        private var manager: Any? = null
        override fun initYuv420(): Boolean {
            manager = singleton.invoke(null)
            return init.invoke(manager, true) == true
        }
        override fun setParams(width: Int, height: Int, fps: Float): Boolean {
            val info = constructor.newInstance()
            this.width.invoke(info, width); this.height.invoke(info, height); this.fps.invoke(info, fps)
            return params.invoke(manager, info) == true
        }
        override fun setCallback(callback: (ByteArray?) -> Unit): Boolean {
            val proxy = Proxy.newProxyInstance(callbackClass.classLoader ?: loader, arrayOf(callbackClass)) { obj, method, args ->
                when (method.name) {
                    "onDataCallback" -> { callback(args?.firstOrNull() as? ByteArray); null }
                    "hashCode" -> System.identityHashCode(obj)
                    "equals" -> obj === args?.firstOrNull()
                    "toString" -> "DiPlayCameraCallback"
                    else -> null
                }
            }
            return this.callback.invoke(manager, proxy) == true
        }
        override fun open(cameraId: Int): Boolean = open.invoke(manager, cameraId) == true
        override fun start(): Boolean = start.invoke(manager) == true
        override fun stop(): Boolean = stop.invoke(manager) == true
        override fun close(): Boolean = close.invoke(manager) == true
        override fun uninit(): Boolean = uninit.invoke(manager) == true
    }
}
