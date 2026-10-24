package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.app.ActivityOptions
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayVideoListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.io.Closeable

/**
 * iOS 27 video in car (see [VideoInCar]). The iPhone hands the car a media URL (insertPlayQueueItem)
 * and drives it (setRate, seek, stop); the car plays it in [CarPlayVideoActivity], which opens as soon
 * as the iPhone starts the item (or sends requestUI "videoplayback:") and only while the car is in P.
 */
internal object CarPlayVideo : CarPlayVideoListener {
    private const val TAG = "DiPlay-Video"
    const val SKIP_MILLIS = 10_000
    private const val URL_TIMEOUT_SECONDS = 10L

    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor { Thread(it, "diplay-video-reply").apply { isDaemon = true } }
    @Volatile private var appContext: Context? = null
    @Volatile private var controller: CarPlayController? = null
    private var controllerVideoBinding: Closeable? = null
    private val routes = CarPlayVideoRouteState()
    private val itemGeneration = AtomicLong()
    const val EXTRA_VIDEO_SESSION = "com.shilapi.xcertplay.VIDEO_SESSION"
    const val EXTRA_VIDEO_LAUNCH = "com.shilapi.xcertplay.VIDEO_LAUNCH"

    // Main-thread state; CarPlayVideoActivity applies it. The stream and item are also read by loader threads.
    @Volatile private var streamId: Long? = null
    @Volatile private var itemUuid: Any? = null
    var url: String? = null
        private set
    /** The iPhone called the item streaming (HLS) rather than a file. */
    var streaming = false
        private set
    var startMillis = 0
        private set
    var playing = false
        private set
    var pendingSeekMillis: Int? = null
    var activity: CarPlayVideoActivity? = null
        private set

    fun attach(
        context: Context, next: CarPlayController,
        parkedSourceFactory: (() -> CarPlayVideoParkedSource?)? = null,
    ) {
        val sourceContext = context.applicationContext
        appContext = sourceContext
        if (controller === next && routes.isSession(routes.currentSession())) return
        controllerVideoBinding?.close()
        streamId = null
        var generation = routes.beginSession()
        stop()
        activity = null
        streamId = null
        controller = next
        val parked = CarPlayVideoParkedSession(parkedSourceFactory) {
            BydNavigationOutputs.parked(sourceContext)
        }
        val bindingLock = Any()
        var retired = false
        // A retired controller cannot send UI/player events into its replacement session.
        val binding = object : CarPlayVideoListener, Closeable {
            override fun onVideoSessionStarted() {
                synchronized(bindingLock) {
                    if (retired || controller !== next) return
                    invalidateItemAtSessionBoundary()
                    parked.begin()
                    generation = routes.beginSession()
                    postForSession(generation) { stop(); streamId = null }
                }
            }
            override fun readParked(): Boolean? {
                val value = parked.readParked()
                return synchronized(bindingLock) { value.takeIf { !retired && controller === next } }
            }
            override fun onVideoAllowedChanged(allowed: Boolean) {
                synchronized(bindingLock) {
                    if (!retired && controller === next) postForSession(generation) {
                        if (!allowed) closePlayer("the car left P")
                    }
                }
            }
            override fun onVideoSessionEnded() {
                synchronized(bindingLock) {
                    if (retired || controller !== next) return
                    invalidateItemAtSessionBoundary()
                    parked.end()
                    endSession(generation)
                }
            }
            override fun onVideoUiRequested() {
                synchronized(bindingLock) {
                    if (!retired && controller === next) postForSession(generation) { show() }
                }
            }
            override fun onVideoMessage(streamId: Long, message: Map<String, Any?>) {
                synchronized(bindingLock) {
                    if (!retired && controller === next) postForSession(generation) { handle(streamId, message) }
                }
            }
            override fun close() {
                synchronized(bindingLock) {
                    if (retired) return
                    retired = true
                    invalidateItemAtSessionBoundary()
                    parked.close()
                    endSession(generation)
                }
            }
        }
        controllerVideoBinding = binding
        next.videoListener = binding
    }

    /** Explicit opt-in for this session only. Does not enable video, invent P or reconnect CarPlay. */
    fun selectPassengerDestinationForCurrentSession(context: Context): Boolean {
        val selected = CarPlayPassengerDisplay.find(context)?.let(routes::selectPassenger) ?: false
        if (!selected) {
            routes.blockPassengerDestination()
            val previous = activity
            activity = null
            if (playing) setPlaying(false)
            previous?.closeForLostDestination()
        }
        return selected
    }

    fun selectDefaultDestinationForCurrentSession(): Boolean = routes.selectDefault()

    private fun postForSession(generation: Long, action: () -> Unit) {
        main.post { if (routes.isSession(generation)) action() }
    }

    private fun endSession(generation: Long) {
        postForSession(generation) {
            routes.endSession(generation)
            stop()
            activity = null
            streamId = null
        }
    }

    override fun readParked(): Boolean? = appContext?.let(BydNavigationOutputs::parked)

    override fun onVideoAllowedChanged(allowed: Boolean) {
        postForSession(routes.currentSession()) { if (!allowed) closePlayer("the car left P") }
    }

    override fun onVideoSessionEnded() {
        endSession(routes.currentSession())
    }

    override fun onVideoUiRequested() {
        postForSession(routes.currentSession()) { show() }
    }

    override fun onVideoMessage(streamId: Long, message: Map<String, Any?>) {
        postForSession(routes.currentSession()) { handle(streamId, message) }
    }

    /**
     * A steering-wheel media key (CarPlayMediaButton index) while the player is on screen: play and pause
     * toggle it, next and previous skip 10 s. The iPhone is not asked: a CarPlay play/pause makes it end
     * the video session. Returns false when no player is open. Main thread.
     */
    fun onMediaKey(index: Int): Boolean {
        val player = activity ?: return false
        when (index) {
            CarPlayMediaButton.NEXT -> player.skip(SKIP_MILLIS)
            CarPlayMediaButton.PREVIOUS -> player.skip(-SKIP_MILLIS)
            else -> setPlaying(!playing)
        }
        return true
    }

    /** Play or pause from the car (wheel or on-screen button); tells the iPhone at once. Main thread. */
    fun setPlaying(next: Boolean) {
        val owner = captureResourceOwner()
        playing = next
        activity?.applyRate()
        owner?.let {
            reply(it.stream, VideoInCar.playbackStateNotification(next, it.uuid), it.session) {
                resourceOwnerCurrent(it)
            }
        }
    }

    /**
     * The car's player cannot play the item, for example protected video: tell the iPhone as Apple's
     * receiver does, say so on the car screen and go back to CarPlay instead of showing black. Main thread.
     */
    fun onPlayerFailed(code: Int) {
        Log.w(TAG, "item cannot play here code=$code")
        streamId?.let { reply(it, VideoInCar.errorNotification(itemUuid, code)) }
        appContext?.let { Toast.makeText(it, R.string.video_cannot_play, Toast.LENGTH_LONG).show() }
        stop()
    }

    /** The player closed on the car (Back, or the car left P): pause, so the iPhone shows it paused. */
    fun onPlayerClosed(positionMillis: Int?) {
        positionMillis?.let { startMillis = it }
        if (playing) setPlaying(false)
    }

    private fun handle(streamId: Long, message: Map<String, Any?>) {
        this.streamId = streamId
        val request = message["kind"] == "request"
        when (val type = message["type"] as? String) {
            "insertPlayQueueItem" -> {
                // An app's own scheme is loaded through the iPhone (IphoneResolvingDataSource).
                val item = VideoInCar.parseItem(message, iphoneLoadsAppSchemes = true)
                if (item == null) {
                    Log.w(TAG, "queue item this player cannot play")
                    return
                }
                itemGeneration.incrementAndGet()
                cancelPendingUrls()
                Log.i(TAG, "queue item app=${(message["item"] as? Map<*, *>)?.get("clientBundleID")}")
                url = item.url
                itemUuid = item.uuid
                val queued = message["item"] as? Map<*, *>
                streaming = queued?.get("mediaType") == "streaming"
                Log.i(TAG, "queue item host=${android.net.Uri.parse(item.url).host} mediaType=${queued?.get("mediaType")}")
                startMillis = item.startMillis
                activity?.load()
            }
            "setRate" -> {
                playing = ((message["rate"] as? Number)?.toDouble() ?: 0.0) > 0.0
                // The iPhone starts the item at once, as Apple's receiver plays it straight
                // away, so open the player now instead of waiting for Now Playing's video button.
                if (playing && activity == null) show()
                activity?.applyRate()
            }
            "seek" -> {
                VideoInCar.seekMillis(message)?.let {
                    pendingSeekMillis = it
                    activity?.applySeek()
                }
                if (request) reply(streamId, VideoInCar.seekResponse(message["messageID"]))
            }
            "playbackInfo" -> if (request) {
                reply(streamId, VideoInCar.playbackInfoResponse(message["messageID"], itemUuid, playerState()))
            }
            "property" -> if (request) {
                reply(streamId, VideoInCar.propertyResponse(message["messageID"], message["property"], playerState()))
            }
            "unhandledURL" -> onUrlLoaded(message)
            "stop", "removePlayQueueItem" -> stop()
            "setProperty" -> Unit
            else -> Log.i(TAG, "video message $type keys=${message.keys}")
        }
    }

    /** What the iPhone answered to [resolveOnIphone]. */
    class LoadedUrl(val status: Int?, val data: ByteArray?, val location: String?)

    internal class ResourceOwner internal constructor(
        internal val session: Long, internal val item: Long, internal val stream: Long,
        internal val uuid: Any?, internal val lease: CarPlayVideoRouteLease?,
    )
    private class PendingUrl(val owner: ResourceOwner, val answer: CompletableFuture<Map<*, *>>)
    private val pendingUrls = ConcurrentHashMap<Long, PendingUrl>()
    private val nextUrlRequest = AtomicLong(1)

    fun captureResourceOwner(lease: CarPlayVideoRouteLease? = null): ResourceOwner? {
        val generation = routes.currentSession()
        val stream = streamId ?: return null
        val owner = ResourceOwner(generation, itemGeneration.get(), stream, itemUuid, lease)
        return owner.takeIf(::resourceOwnerCurrent)
    }

    private fun resourceOwnerCurrent(owner: ResourceOwner): Boolean = routes.isSession(owner.session) &&
        itemGeneration.get() == owner.item && streamId == owner.stream &&
        (owner.lease == null || routes.accepts(owner.lease))

    private fun cancelPendingUrls() {
        pendingUrls.entries.forEach { (id, pending) ->
            if (pendingUrls.remove(id, pending)) pending.answer.cancel(false)
        }
    }

    /** Metadata can be fenced off-thread; stopping/finishing the Activity remains on main. */
    private fun invalidateItemAtSessionBoundary() {
        streamId = null
        itemGeneration.incrementAndGet()
        cancelPendingUrls()
    }

    /**
     * Asks the iPhone to load a URL the car cannot (an app's own scheme), as Apple's receiver
     * does: unhandledURL request with FCUP_Response_URL / _IsContentKeyRequest / _RequestID, answered
     * with FCUP_Response_Data, _StatusCode and _Headers (Location). Blocking; a loader thread.
     */
    fun resolveOnIphone(url: String): LoadedUrl? = captureResourceOwner()?.let { resolveOnIphone(url, it) }

    fun resolveOnIphone(url: String, owner: ResourceOwner): LoadedUrl? {
        if (!resourceOwnerCurrent(owner)) return null
        val id = nextUrlRequest.getAndIncrement()
        val answer = CompletableFuture<Map<*, *>>()
        pendingUrls[id] = PendingUrl(owner, answer)
        if (!resourceOwnerCurrent(owner)) {
            pendingUrls.remove(id)
            return null
        }
        reply(owner.stream, linkedMapOf(
            "type" to "unhandledURL",
            "kind" to "request",
            "request" to linkedMapOf(
                "FCUP_Response_URL" to url,
                "FCUP_Response_IsContentKeyRequest" to false,
                "FCUP_Response_RequestID" to id,
            ),
            "item" to linkedMapOf("uuid" to owner.uuid),
        ), owner.session) { resourceOwnerCurrent(owner) }
        Log.i(TAG, "asked the iPhone to load a ${android.net.Uri.parse(url).scheme} URL request=$id")
        val response = try {
            answer.get(URL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            Log.w(TAG, "no iPhone answer for request=$id")
            null
        } finally {
            pendingUrls.remove(id)
        }
        response ?: return null
        if (!resourceOwnerCurrent(owner)) return null
        val headers = response["FCUP_Response_Headers"] as? Map<*, *>
        return LoadedUrl(
            status = (response["FCUP_Response_StatusCode"] as? Number)?.toInt(),
            data = response["FCUP_Response_Data"] as? ByteArray,
            location = (headers?.get("Location") ?: headers?.get("location")) as? String,
        )
    }

    private fun onUrlLoaded(message: Map<String, Any?>) {
        val response = message["response"] as? Map<*, *>
        val id = (response?.get("FCUP_Response_RequestID") as? Number)?.toLong()
        Log.i(TAG, "iPhone unhandledURL answer request=$id keys=${message.keys} responseKeys=${response?.keys}")
        if (id != null) pendingUrls[id]?.let { pending ->
            if (resourceOwnerCurrent(pending.owner)) pending.answer.complete(response)
        }
    }

    // While the player is closed the item stays paused where it was, so the iPhone keeps its position.
    private fun playerState(): VideoInCar.PlayerState? =
        activity?.state() ?: url?.let { VideoInCar.PlayerState(false, false, startMillis / 1000.0, 0.0, 0.0) }

    private fun reply(
        streamId: Long, message: Map<String, Any?>,
        generation: Long = routes.currentSession(), stillCurrent: () -> Boolean = { true },
    ) {
        val target = controller ?: return
        sender.execute {
            if (!routes.isSession(generation) || controller !== target || !stillCurrent()) return@execute
            // A reply that cannot be sent must never take CarPlay down with it.
            val sent = runCatching { target.sendVideoMessage(streamId, message) }
                .onFailure { Log.w(TAG, "reply ${message["type"]} failed: ${it.javaClass.simpleName}") }
                .getOrDefault(false)
            if (!sent) Log.w(TAG, "reply ${message["type"]} not sent")
        }
    }

    private fun show() {
        val context = appContext ?: return
        when {
            url == null -> Log.w(TAG, "video player requested without a playable item")
            !VideoInCar.allowed -> Log.w(TAG, "video player requested while not parked")
            activity != null -> Unit
            else -> {
                val lease = routes.reserveLaunch() ?: return
                val intent = Intent(context, CarPlayVideoActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_VIDEO_SESSION, lease.session)
                    .putExtra(EXTRA_VIDEO_LAUNCH, lease.launch)
                try {
                    val passenger = lease.passenger
                    if (passenger == null) context.startActivity(intent)
                    else if (Build.VERSION.SDK_INT >= 26) {
                        val display = CarPlayPassengerDisplay.display(context, passenger)
                            ?: error("Verified passenger destination unavailable")
                        context.startActivity(intent,
                            ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId).toBundle())
                    } else error("Passenger launch unavailable on this Android version")
                } catch (error: Exception) {
                    routes.release(lease)
                    Log.w(TAG, "video destination launch failed: ${error.javaClass.simpleName}")
                    setPlaying(false)
                }
            }
        }
    }

    fun activityLease(intent: Intent, actualDisplayId: Int): CarPlayVideoRouteLease? {
        val lease = routes.findLease(intent.getLongExtra(EXTRA_VIDEO_SESSION, -1),
            intent.getLongExtra(EXTRA_VIDEO_LAUNCH, -1)) ?: return null
        if (!destinationAvailable(lease, actualDisplayId)) {
            routes.release(lease)
            return null
        }
        return lease
    }

    fun destinationAvailable(lease: CarPlayVideoRouteLease, actualDisplayId: Int): Boolean {
        if (!routes.accepts(lease) || !VideoInCar.allowed) return false
        val passenger = lease.passenger ?: return true
        val context = appContext ?: return false
        return actualDisplayId == passenger.logicalDisplayId &&
            CarPlayPassengerDisplay.display(context, passenger) != null
    }

    fun playerReady(player: CarPlayVideoActivity, lease: CarPlayVideoRouteLease): Boolean {
        if (!destinationAvailable(lease, player.videoDisplayId())) return false
        if (activity != null && activity !== player) return false
        activity = player
        return true
    }

    fun isCurrentPlayer(player: CarPlayVideoActivity, lease: CarPlayVideoRouteLease?): Boolean =
        lease != null && activity === player && routes.accepts(lease)

    /** Revoke before Android's asynchronous finish: a queued setRate cannot reactivate this player. */
    fun destinationLost(player: CarPlayVideoActivity, lease: CarPlayVideoRouteLease?) {
        if (lease == null || !isCurrentPlayer(player, lease)) return
        if (lease.passenger != null) routes.blockPassengerDestination() else routes.release(lease)
        activity = null
        if (playing) setPlaying(false)
    }

    fun playerDestroyed(player: CarPlayVideoActivity, lease: CarPlayVideoRouteLease?, positionMillis: Int?) {
        if (lease == null) return
        val owner = isCurrentPlayer(player, lease)
        if (activity === player) activity = null
        routes.release(lease)
        if (owner) onPlayerClosed(positionMillis)
    }

    private fun closePlayer(reason: String) {
        val player = activity ?: return
        Log.i(TAG, "closing the video player: $reason")
        player.finish()
    }

    private fun stop() {
        routes.cancelLaunch()
        itemGeneration.incrementAndGet()
        cancelPendingUrls()
        url = null
        itemUuid = null
        playing = false
        pendingSeekMillis = null
        val previous = activity
        activity = null
        previous?.finish()
    }
}
