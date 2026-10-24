package lmos.framework.core.cam

/** Synthetic SDK contract fixture; no JNI or vehicle access. */
class CameraInfo {
    fun setWidth(value: Int) { require(value > 0) }
    fun setHeight(value: Int) { require(value > 0) }
    fun setFps(value: Float) { require(value > 0) }
}
interface ICamCallback { fun onDataCallback(bytes: ByteArray) }
class CamManager private constructor() {
    val calls = mutableListOf<String>()
    private var callback: ICamCallback? = null
    fun init(yuv: Boolean): Boolean { calls += "init:$yuv"; return true }
    fun setParams(info: CameraInfo): Boolean { calls += info.javaClass.simpleName; return true }
    fun setCallback(value: ICamCallback): Boolean { callback = value; return true }
    fun openCamera(id: Int): Boolean { calls += "open:$id"; return true }
    fun start(): Boolean { callback!!.onDataCallback(byteArrayOf(1, 2, 3)); return true }
    fun stop(): Boolean { calls += "stop"; return true }
    fun closeCamera(): Boolean { calls += "close"; return true }
    fun uninit(): Boolean { calls += "uninit"; return true }
    companion object {
        private val instance = CamManager()
        @JvmStatic fun getInstance(): CamManager { instance.calls += "get"; return instance }
    }
}
