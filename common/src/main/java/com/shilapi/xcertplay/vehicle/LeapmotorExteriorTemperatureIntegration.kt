package com.shilapi.xcertplay.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorExteriorTemperatureReader
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorExteriorTemperatureReading
import com.shilapi.xcertplay.vehicle.leapmotor.PropertyReadState
import com.shilapi.xcertplay.vehicle.leapmotor.ReflectiveExteriorTemperatureSubscription
import com.shilapi.xcertplay.vehicle.leapmotor.ReflectiveLeapmotorExteriorAccess
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit C11 exterior-temperature source leases. No acquisition occurs at boot, Bluetooth connection or class loading. */
object LeapmotorExteriorTemperatureIntegration {
    const val PERMISSION = "android.car.permission.CAR_EXTERIOR_ENVIRONMENT"
    private val ownerLock = Any()
    private val owners = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    private var lease: Lease? = null
    fun acquire(context: Context, owner: Any) = acquire(context, owner) {
        Class.forName("android.car.Car", true, it.classLoader)
    }
    internal fun acquire(context: Context, owner: Any, platformClass: (Context) -> Class<*>) = synchronized(ownerLock) {
        if (!VehicleIntegrationPolicy.isLeapmotorC11(context)) return@synchronized
        if (!owners.add(owner) || lease != null) return@synchronized
        lease = Lease(context.applicationContext,
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED, platformClass)
    }
    fun release(owner: Any) = synchronized(ownerLock) {
        owners.remove(owner)
        if (owners.isEmpty()) { lease?.close(); lease = null }
    }
    /** Cache only: no permission request, connection or binder read. */
    fun diagnosticReport(context: Context): String = synchronized(ownerLock) {
        if (!VehicleIntegrationPolicy.isLeapmotorC11(context)) return@synchronized "C11 EXTERIOR: disabled"
        lease?.diagnosticReport() ?: "C11 EXTERIOR: source=STOPPED status=UNAVAILABLE celsius=UNKNOWN"
    }

    class Lease internal constructor(private val context: Context, allowed: Boolean,
        platformClass: (Context) -> Class<*> = { Class.forName("android.car.Car", true, it.classLoader) },
    ) : Closeable {
        private val permitted = allowed && ownership.tryAcquire()
        private val closed = AtomicBoolean(!permitted)
        private val bootstrapDone = CountDownLatch(1)
        private val carOutstanding = AtomicBoolean()
        private val permitReleased = AtomicBoolean()
        private val ioRetired = AtomicBoolean()
        private val ready = AtomicBoolean()
        private val lock = Any()
        @Volatile private var car: Any? = null
        @Volatile private var subscription: ReflectiveExteriorTemperatureSubscription? = null
        @Volatile private var cached: LeapmotorExteriorTemperatureReading? = null
        private val io = object : ScheduledThreadPoolExecutor(1, { task ->
            Thread(task, "diplay-c11-exterior").apply { isDaemon = true }
        }) {
            override fun terminated() { ioRetired.set(true); releasePermitIfRetired() }
        }.apply { removeOnCancelPolicy = true }
        private val deadline = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "diplay-c11-exterior-deadline").apply { isDaemon = true }
        }
        /** Memory only; both original event time and completed delivery must remain fresh. */
        fun readCelsius(): Float? {
            if (closed.get() || !ready.get()) return null
            val reading = cached ?: return null
            val now = SystemClock.elapsedRealtimeNanos()
            fun fresh(at: Long?) = at != null && at > 0 && at <= now && now - at <= MAX_AGE
            return reading.celsius.takeIf { reading.state == PropertyReadState.AVAILABLE &&
                fresh(reading.timestampNanos) && fresh(reading.halReadCompletedAtNanos) }
        }
        fun diagnosticReport(): String {
            val reading = cached
            val now = SystemClock.elapsedRealtimeNanos()
            fun age(at: Long?) = at?.takeIf { it > 0 && it <= now }?.let { (now - it) / 1_000_000 }
            val celsius = readCelsius()
            val status = if (closed.get()) "UNAVAILABLE" else if (reading?.state == PropertyReadState.AVAILABLE &&
                celsius == null) "STALE" else reading?.state?.name ?: "UNAVAILABLE"
            return "C11 EXTERIOR: source=${if (closed.get()) "CLOSED_OR_DISABLED" else if (ready.get()) "CONNECTED" else "CONNECTING"}" +
                " status=$status celsius=${celsius?.toString() ?: "UNKNOWN"}" +
                " oemTimestampNanos=${reading?.timestampNanos?.toString() ?: "UNKNOWN"}" +
                " oemEventAgeMs=${age(reading?.timestampNanos)?.toString() ?: "UNKNOWN"}" +
                " halDeliveryAgeMs=${age(reading?.halReadCompletedAtNanos)?.toString() ?: "UNKNOWN"}" +
                " freshnessPolicy=OEM_EVENT_AND_HAL_DELIVERY maxAgeMs=60000"
        }
        init {
            if (!permitted) { bootstrapDone.countDown(); io.shutdownNow(); deadline.shutdownNow() }
            else {
                deadline.schedule({ if (!ready.get()) close() }, 5, TimeUnit.SECONDS)
                io.execute {
                    try {
                        if (closed.get()) return@execute
                        val type = platformClass(context)
                        val connection = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                                if (closed.get()) return
                                runCatching { io.execute { connected() } }.onFailure { close() }
                            }
                            override fun onServiceDisconnected(name: ComponentName) { close() }
                            override fun onBindingDied(name: ComponentName) { close() }
                            override fun onNullBinding(name: ComponentName) { close() }
                        }
                        val created = type.getMethod("createCar", Context::class.java, ServiceConnection::class.java,
                            Handler::class.java).invoke(null, context, connection, Handler(Looper.getMainLooper()))
                            ?: return@execute close()
                        carOutstanding.set(true)
                        synchronized(lock) {
                            if (closed.get()) { disconnect(created); return@execute }
                            car = created
                        }
                        type.getMethod("connect").invoke(created)
                    } catch (_: Exception) { close() } catch (_: LinkageError) { close() }
                    finally { bootstrapDone.countDown(); releasePermitIfRetired() }
                }
            }
        }
        private fun connected() {
            if (closed.get() || ready.get()) return
            try {
                val current = car ?: return close()
                val manager = current.javaClass.getMethod("getCarManager", String::class.java)
                    .invoke(current, "property") ?: return close()
                val reader = LeapmotorExteriorTemperatureReader(ReflectiveLeapmotorExteriorAccess(manager))
                val observer = ReflectiveExteriorTemperatureSubscription(manager, onFirstChange = {
                    if (!closed.get()) android.util.Log.i("DiPlay-EXTERIOR", "C11 EXTERIOR callback=FIRST_CHANGE")
                }) { close() }
                synchronized(lock) {
                    if (closed.get()) return
                    subscription = observer
                }
                try {
                    observer.start()
                    if (!closed.get()) android.util.Log.i("DiPlay-EXTERIOR", "C11 EXTERIOR subscription=REGISTERED")
                } catch (error: Exception) {
                    android.util.Log.i("DiPlay-EXTERIOR", "C11 EXTERIOR subscription=REJECTED")
                    throw error
                }
                if (closed.get()) return
                ready.set(true)
                io.scheduleWithFixedDelay({
                    if (!closed.get()) {
                        val initiallyPermitted = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
                        val read = if (initiallyPermitted) reader.read()
                            else LeapmotorExteriorTemperatureReading(PropertyReadState.ACCESS_DENIED)
                        // A blocked GET must not publish after permission was revoked during that call.
                        val permission = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
                        val next = if (permission) read else LeapmotorExteriorTemperatureReading(PropertyReadState.ACCESS_DENIED)
                        synchronized(lock) {
                            if (!closed.get()) {
                                cached = next
                                android.util.Log.i("DiPlay-EXTERIOR", diagnosticReport() + " permissionGranted=$permission")
                            }
                        }
                        if (!permission || next.state == PropertyReadState.ACCESS_DENIED) close()
                    }
                }, 0, 1_000, TimeUnit.MILLISECONDS)
            } catch (_: Exception) { close() } catch (_: LinkageError) { close() }
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            val previous = synchronized(lock) { cached = null; car.also { car = null } }
            ready.set(false)
            // Allow queued bootstrap to retire its ownership even if close races its first run.
            io.shutdown()
            deadline.shutdownNow()
            previous?.let(::disconnect)
            releasePermitIfRetired()
        }
        private fun disconnect(previous: Any) {
            // Platform disconnect shares a lock with Binder reads; never wait on the UI thread.
            cleanup.execute {
                try {
                    // Never disconnect before an in-flight create/connect has finished.
                    bootstrapDone.await()
                    // Registration may still be in flight. Subscription.close serializes with start,
                    // on this cleanup worker, before disconnecting the platform connection.
                    val observer = synchronized(lock) { subscription.also { subscription = null } }
                    runCatching { observer?.close() }
                    if (observer != null) android.util.Log.i("DiPlay-EXTERIOR", "C11 EXTERIOR subscription=STOPPED")
                    runCatching { previous.javaClass.getMethod("disconnect").invoke(previous) }
                } finally { carOutstanding.set(false); releasePermitIfRetired() }
            }
        }
        private fun releasePermitIfRetired() {
            if (permitted && closed.get() && ioRetired.get() && bootstrapDone.count == 0L && !carOutstanding.get() &&
                permitReleased.compareAndSet(false, true)) ownership.release()
        }
        private companion object {
            const val MAX_AGE = LeapmotorExteriorTemperatureReader.MAX_AGE_NANOS
            val ownership = Semaphore(2)
            val cleanup = Executors.newSingleThreadExecutor { task ->
                Thread(task, "diplay-c11-exterior-cleanup").apply { isDaemon = true }
            }
        }
    }
}
