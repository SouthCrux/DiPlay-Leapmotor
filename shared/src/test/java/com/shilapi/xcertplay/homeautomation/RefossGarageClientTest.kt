package com.shilapi.xcertplay.homeautomation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RefossGarageClientTest {
    private val key = "a1".repeat(16)
    private val id = "b2".repeat(16)
    private inner class Device(val contact: Int = 1, val wrongIdentity: Boolean = false, val loseAck: Boolean = false,
                               val wrongSignature: Boolean = false) : RefossGarageClient.Exchange {
        val requests = mutableListOf<JSONObject>()
        override fun post(body: ByteArray): ByteArray {
            val request = JSONObject(String(body))
            requests += request
            val header = request.getJSONObject("header")
            val method = header.getString("method")
            val payload = when {
                header.getString("namespace") == "Appliance.System.All" -> JSONObject().put("all", JSONObject().put("system",
                    JSONObject().put("hardware", JSONObject().put("type", "msg100").put("uuid", if (wrongIdentity) "c3".repeat(16) else id))))
                method == "GET" -> JSONObject().put("state", JSONArray().put(JSONObject().put("channel", 0).put("open", contact)))
                else -> {
                    if (loseAck) throw IOException("Synthetic lost ACK")
                    JSONObject().put("state", JSONObject().put("channel", 0).put("open", contact).put("execute", 1))
                }
            }
            val reply = JSONObject(header.toString()).put("method", "${method}ACK")
            val sign = MessageDigest.getInstance("MD5").digest((reply.getString("messageId") + key + reply.getLong("timestamp")).toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            reply.put("sign", if (wrongSignature) "0".repeat(32) else sign)
            return JSONObject().put("header", reply).put("payload", payload).toString().toByteArray()
        }
        fun writes() = requests.filter { it.getJSONObject("header").getString("method") == "SET" }
    }

    @Test fun oneActivationUsesReportedContactOnlyToRequestPulseAndNeverReportsPosition() {
        for (contact in 0..1) {
            val device = Device(contact)
            val client = RefossGarageClient(key, id, device)
            assertTrue(client.activateOnce { true }.executionReported)
            assertEquals(1, device.writes().size)
            assertEquals(1 - contact, device.writes().single().getJSONObject("payload").getJSONObject("state").getInt("open"))
        }
    }

    @Test fun wrongIdentitySignatureUnknownContactAndExpiredAuthorizationCannotWrite() {
        for (device in listOf(Device(wrongIdentity = true), Device(wrongSignature = true), Device(contact = 2), Device())) {
            val client = RefossGarageClient(key, id, device)
            try { client.activateOnce { false }; fail("Must fail before SET") } catch (_: IOException) { }
            assertFalse(client.controlRequestAttempted)
            assertTrue(device.writes().isEmpty())
        }
    }

    @Test fun lostAcknowledgementDoesNotRepeatTheCommand() {
        val device = Device(loseAck = true)
        val client = RefossGarageClient(key, id, device)
        try { client.activateOnce { true }; fail("Missing ACK") } catch (_: IOException) { }
        assertTrue(client.controlRequestAttempted)
        assertEquals(1, device.writes().size)
    }
}
