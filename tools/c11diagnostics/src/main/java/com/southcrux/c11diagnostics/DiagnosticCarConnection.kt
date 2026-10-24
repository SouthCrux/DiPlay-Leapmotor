package com.southcrux.c11diagnostics

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal interface DiagnosticCarSession {
    fun propertyManager(): Any
    fun disconnect()
}

internal fun interface DiagnosticCarFactory {
    fun create(context: Context, handler: Handler, lifecycle: (DiagnosticCarSession, Boolean) -> Unit): DiagnosticCarSession?
}

/** One user-requested public Car connection. No app-managed retries or vehicle subscriptions. */
internal class DiagnosticCarConnection(
    private val context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val factory: DiagnosticCarFactory = PublicCarApi,
    private val cleanup: Executor = cleanupExecutor,
) {
    private var generation = 0
    private var session: DiagnosticCarSession? = null
    private var pending = false
    private var timeout: Runnable? = null

    fun open(result: (DiagnosticCarSession?, String) -> Unit) {
        close()
        val token = generation
        pending = true
        try {
            val created = factory.create(context, handler) { connected, ready ->
                // createCar can call back synchronously before returning its Car object.
                // Defer delivery until ownership and the timeout have been established.
                handler.post {
                    if (token != generation || session !== connected) return@post
                    if (!ready) {
                        close()
                        result(null, "SERVICE_DISCONNECTED")
                    } else if (pending) {
                        pending = false
                        timeout?.let(handler::removeCallbacks)
                        timeout = null
                        result(connected, "CONNECTED_PUBLIC_API")
                    }
                }
            }
            if (created == null) {
                close()
                result(null, "CAR_API_NOT_AVAILABLE")
                return
            }
            session = created
            val deadline = Runnable {
                if (token == generation && pending) {
                    close()
                    result(null, "CONNECT_TIMEOUT")
                }
            }
            timeout = deadline
            handler.postDelayed(deadline, 5_000)
        } catch (_: SecurityException) {
            close()
            result(null, "CONNECT_ACCESS_DENIED")
        } catch (_: LinkageError) {
            close()
            result(null, "CAR_LIBRARY_NOT_AVAILABLE")
        } catch (_: RuntimeException) {
            close()
            result(null, "CONNECT_FAILED")
        }
    }

    fun close() {
        generation++
        pending = false
        timeout?.let(handler::removeCallbacks)
        timeout = null
        val previous = session
        session = null
        if (previous != null) {
            // Car.disconnect shares a lock with getCarManager. A blocked Binder read must not
            // freeze the Activity's timeout or onStop while waiting to release that lock.
            cleanup.execute {
                try { previous.disconnect() } catch (_: Exception) { } catch (_: LinkageError) { }
            }
        }
    }

    private companion object {
        // Process lifetime, one cleanup worker; never a polling/reconnection executor.
        val cleanupExecutor: Executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "c11-car-cleanup").apply { isDaemon = true }
        }
    }
}
