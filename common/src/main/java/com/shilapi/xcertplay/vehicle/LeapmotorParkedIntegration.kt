package com.shilapi.xcertplay.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorGearFreshness
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorParkedReader
import com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorParkedReading
import com.shilapi.xcertplay.vehicle.leapmotor.PropertyReadState
import com.shilapi.xcertplay.vehicle.leapmotor.ReflectiveLeapmotorGearAccess
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit C11 session lease. No acquisition occurs at boot, Bluetooth connection or class loading. */
object LeapmotorParkedIntegration {
    const val PERMISSION = "android.car.permission.CAR_POWERTRAIN"
    fun acquire(context: Context): Lease = Lease(context.applicationContext,
        VehicleIntegrationPolicy.isLeapmotorC11(context) &&
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED)

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
        @Volatile private var cached: LeapmotorParkedReading? = null
        private val io = object : ScheduledThreadPoolExecutor(1, { task ->
            Thread(task, "diplay-c11-parked").apply { isDaemon = true }
        }) {
            override fun terminated() { ioRetired.set(true); releasePermitIfRetired() }
        }.apply { removeOnCancelPolicy = true }
        private val deadline = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "diplay-c11-parked-deadline").apply { isDaemon = true }
        }
        /** Memory only. Freshness follows successful HAL delivery, never a rewritten OEM timestamp. */
        fun readParked(): Boolean? {
            if (closed.get() || !ready.get()) return null
            val reading = cached ?: return null
            val timestamp = reading.halReadCompletedAtNanos ?: return null
            val now = SystemClock.elapsedRealtimeNanos()
            return reading.parked.takeIf { reading.state == PropertyReadState.AVAILABLE && timestamp > 0 &&
                timestamp <= now && now - timestamp <= MAX_AGE }
        }
        data class DiagnosticSnapshot(
            val sourceStatus: String,
            val permissionGranted: Boolean,
            val readingState: String,
            val oemEventAgeMillis: Long?,
            val halDeliveryAgeMillis: Long?,
            val parked: Boolean?,
            val freshnessPolicy: String = "CURRENT_HAL_SNAPSHOT",
        )
        /** Metadata only; no VIN, location, personal data or new connection. */
        fun diagnosticSnapshot(): DiagnosticSnapshot {
            val now = SystemClock.elapsedRealtimeNanos()
            val reading = cached
            fun age(timestamp: Long?): Long? = timestamp?.takeIf { it > 0 && it <= now }?.let { (now - it) / 1_000_000 }
            return DiagnosticSnapshot(
                if (closed.get()) "CLOSED_OR_DISABLED" else if (ready.get()) "CONNECTED" else "CONNECTING",
                context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED,
                reading?.state?.name ?: "UNKNOWN",
                age(reading?.timestampNanos), age(reading?.halReadCompletedAtNanos), readParked(),
            )
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
                val reader = LeapmotorParkedReader(ReflectiveLeapmotorGearAccess(manager),
                    freshness = LeapmotorGearFreshness.CURRENT_HAL_SNAPSHOT)
                if (closed.get()) return
                ready.set(true)
                io.scheduleWithFixedDelay({
                    if (!closed.get()) {
                        val next = reader.read()
                        synchronized(lock) { if (!closed.get()) cached = next }
                    }
                }, 0, 500, TimeUnit.MILLISECONDS)
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
                    runCatching { previous.javaClass.getMethod("disconnect").invoke(previous) }
                } finally { carOutstanding.set(false); releasePermitIfRetired() }
            }
        }
        private fun releasePermitIfRetired() {
            if (permitted && closed.get() && ioRetired.get() && bootstrapDone.count == 0L && !carOutstanding.get() &&
                permitReleased.compareAndSet(false, true)) ownership.release()
        }
        private companion object {
            const val MAX_AGE = 5_000_000_000L
            val ownership = Semaphore(4)
            val cleanup = Executors.newSingleThreadExecutor { task ->
                Thread(task, "diplay-c11-parked-cleanup").apply { isDaemon = true }
            }
        }
    }
}
