package com.shilapi.xcertplay.vehicle

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import org.robolectric.RuntimeEnvironment
import com.shilapi.xcertplay.homeautomation.RefossGarageClient
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class C11RefossGateRecoveryTest {
    @Test fun reconnectsAfterInitialFailureWithoutSendingAnyControlOrReplayingButton() {
        val app = RuntimeEnvironment.getApplication()
        val key = "a1".repeat(16)
        val id = "b2".repeat(16)
        var connected = false
        var session: Any? = null
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        val gate = C11RefossGate(app, C11RefossGate.Config(InetAddress.getByName("192.168.1.2"), key, id),
            session = { session }, allowed = { true }, changed = {}, result = { fail("No button command expected") }, restore = {},
            clientFactory = {
                RefossGarageClient(key, id, RefossGarageClient.Exchange { body ->
                    val request = JSONObject(String(body))
                    val header = request.getJSONObject("header")
                    if (header.getString("method") != "GET") { writes.incrementAndGet(); error("No SET expected") }
                    reads.incrementAndGet()
                    if (!connected) throw IOException("Synthetic network unavailable")
                    val payload = if (header.getString("namespace") == "Appliance.System.All") {
                        JSONObject().put("all", JSONObject().put("system", JSONObject().put("hardware",
                            JSONObject().put("type", "msg100").put("uuid", id))))
                    } else JSONObject().put("state", JSONArray().put(JSONObject().put("channel", 0).put("open", 1)))
                    val reply = JSONObject(header.toString()).put("method", "GETACK")
                    val sign = MessageDigest.getInstance("MD5").digest((reply.getString("messageId") + key + reply.getLong("timestamp")).toByteArray())
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    reply.put("sign", sign)
                    JSONObject().put("header", reply).put("payload", payload).toString().toByteArray()
                })
            })
        fun waitFor(expected: C11RefossGate.Status) {
            val deadline = System.nanoTime() + 3_000_000_000L
            while (gate.status != expected && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertEquals(expected, gate.status)
        }
        try {
            gate.setVisible(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(C11RefossGate.Status.WAITING_CARPLAY, gate.status)
            assertEquals(0, reads.get())
            session = Any()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            waitFor(C11RefossGate.Status.UNREACHABLE)
            assertFalse(gate.ready)
            gate.receive(byteArrayOf(51), SystemClock.elapsedRealtime())
            connected = true
            // A recovery requires only read polling; the previous double is never queued.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            waitFor(C11RefossGate.Status.WAITING_BUTTON)
            gate.receive(byteArrayOf(48), SystemClock.elapsedRealtime())
            assertEquals(C11RefossGate.Status.READY, gate.status)
            assertTrue(gate.ready)
            assertEquals(0, writes.get())
            gate.receive(null, -1)
            assertFalse(gate.ready)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            waitFor(C11RefossGate.Status.WAITING_BUTTON)
            assertEquals(0, writes.get())
        } finally { gate.close() }
    }
}
