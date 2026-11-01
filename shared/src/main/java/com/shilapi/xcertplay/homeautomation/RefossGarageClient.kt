package com.shilapi.xcertplay.homeautomation

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Local MSG100 protocol. A relay request never establishes physical gate position. */
class RefossGarageClient(
    private val key: String,
    private val deviceId: String,
    private val exchange: Exchange,
    private val seconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun interface Exchange { fun post(body: ByteArray): ByteArray }
    data class Receipt(val executionReported: Boolean)
    @Volatile var controlRequestAttempted = false
        private set

    init {
        require(HEX_ID.matches(key) && HEX_ID.matches(deviceId))
    }

    private fun signature(id: String, stamp: Long): String = MessageDigest.getInstance("MD5")
        .digest((id + key + stamp).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun request(namespace: String, method: String, payload: JSONObject): Pair<JSONObject, JSONObject> {
        val id = UUID.randomUUID().toString().replace("-", "")
        val stamp = seconds()
        val header = JSONObject().put("messageId", id).put("namespace", namespace).put("method", method)
            .put("payloadVersion", 1).put("timestamp", stamp).put("timestampMs", 0)
            .put("from", "/app/subscribe").put("sign", signature(id, stamp))
        try {
            val raw = exchange.post(JSONObject().put("header", header).put("payload", payload).toString().toByteArray())
            if (raw.size > 65_536) throw IOException("Refoss response bound")
            val body = JSONObject(String(raw, Charsets.UTF_8))
            val reply = body.getJSONObject("header")
            if (reply.opt("messageId") != id || reply.opt("namespace") != namespace ||
                reply.opt("method") != "${method}ACK") throw IOException("Refoss reply mismatch")
            val time = reply.opt("timestamp")
            if (time !is Int && time !is Long) throw IOException("Refoss reply time")
            val actual = reply.opt("sign") as? String ?: throw IOException("Refoss reply signature")
            if (!HEX_ID.matches(actual) || !MessageDigest.isEqual(actual.toByteArray(),
                    signature(id, (time as Number).toLong()).toByteArray())) throw IOException("Refoss authentication")
            return reply to body.getJSONObject("payload")
        } catch (_: Exception) {
            // Never retain payloads, keys, response bodies or identifiers in an exception.
            throw IOException("No verified Refoss reply")
        }
    }

    private fun verifyIdentity() {
        val (header, payload) = request("Appliance.System.All", "GET", JSONObject())
        val hardware = payload.getJSONObject("all").getJSONObject("system").getJSONObject("hardware")
        val id = (hardware.opt("uuid") as? String) ?: DEVICE_TOPIC.matchEntire(header.optString("from"))?.groupValues?.get(1)
        if (!hardware.optString("type").equals("msg100", true) || id != deviceId)
            throw IOException("Refoss target mismatch")
    }

    private fun channel(payload: JSONObject): JSONObject {
        val value = payload.opt("state")
        val values = when (value) {
            is JSONObject -> listOf(value)
            is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) as? JSONObject }
            else -> emptyList()
        }.filter { it.opt("channel") is Int && it.getInt("channel") == 0 }
        if (values.size != 1) throw IOException("Refoss channel mismatch")
        return values.single()
    }

    private fun readContact(): Int {
        val state = channel(request("Appliance.GarageDoor.State", "GET", JSONObject().put("state",
            JSONArray().put(JSONObject().put("channel", 0)))).second)
        val contact = state.opt("open")
        if (contact !is Int || contact !in 0..1) throw IOException("Refoss unknown contact")
        return contact
    }

    fun verifyAvailable() { verifyIdentity(); readContact() }

    /** One SET attempt only. A lost ACK must never cause an automatic retry. */
    fun activateOnce(stillAuthorized: () -> Boolean): Receipt {
        controlRequestAttempted = false
        verifyIdentity()
        val contact = readContact()
        if (!stillAuthorized()) throw IOException("Refoss action expired")
        controlRequestAttempted = true
        val state = channel(request("Appliance.GarageDoor.State", "SET", JSONObject().put("state",
            JSONObject().put("channel", 0).put("open", 1 - contact))).second)
        val execute = state.opt("execute")
        if (execute !is Int || execute !in 0..1) throw IOException("Refoss execution unknown")
        return Receipt(execute == 1)
    }

    companion object {
        private val HEX_ID = Regex("[a-fA-F0-9]{32}")
        private val DEVICE_TOPIC = Regex("/appliance/([a-fA-F0-9]{32})/publish")
    }
}
