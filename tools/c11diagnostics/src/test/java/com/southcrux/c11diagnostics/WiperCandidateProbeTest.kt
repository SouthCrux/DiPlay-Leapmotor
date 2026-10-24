package com.southcrux.c11diagnostics

import android.content.Intent
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class WiperCandidateProbeTest {
    private fun field(report: JSONObject, event: Int): JSONObject {
        val fields = report.getJSONArray("signals")
        return (0 until fields.length()).map { fields.getJSONObject(it) }.single { it.getInt("eventId") == event }
    }

    @Test fun candidateBytesArePreservedWithoutInferringWireTypeUnitsOrPhysicalState() {
        val samples = WiperCandidateSamples { 123L }
        for (candidate in WiperCandidate.entries) samples.receive(39168, 39169, candidate.event, byteArrayOf(0, -1, 1, 2))
        val report = samples.finish("WINDOW_COMPLETE")
        assertEquals(12, report.getJSONArray("signals").length())
        assertFalse(report.getBoolean("routeVerified")); assertFalse(report.getBoolean("wireTypeVerified"))
        assertFalse(report.getBoolean("semanticsVerified")); assertTrue(report.getBoolean("initialDeliveryMayBeCached"))
        for (candidate in WiperCandidate.entries) {
            val row = field(report, candidate.event)
            assertEquals(1, row.getInt("deliveries"))
            assertEquals("00ff0102", row.getJSONObject("last").getString("payloadHex"))
            assertFalse(row.getJSONObject("last").has("candidateValue"))
            assertTrue(row.getJSONObject("last").isNull("originTimestamp"))
        }
    }

    @Test fun unrelatedEndpointsAndEventsCannotEnterTheCandidateReport() {
        val samples = WiperCandidateSamples { 1L }
        samples.receive(1, 39169, 3843, byteArrayOf(1))
        samples.receive(39168, 1, 3843, byteArrayOf(1))
        samples.receive(39168, 39169, 6000, "private-value".toByteArray())
        val rows = samples.finish("DONE").getJSONArray("signals")
        assertTrue((0 until rows.length()).all { rows.getJSONObject(it).getInt("deliveries") == 0 })
    }

    @Test fun oversizedNullAndEmptyPayloadsRecordOnlyShapeWithoutRetainingContent() {
        for (payload in listOf(null, byteArrayOf(), "private-value".toByteArray())) {
            val samples = WiperCandidateSamples { 1L }
            samples.receive(39168, 39169, 3843, payload)
            val last = field(samples.finish("DONE"), 3843).getJSONObject("last")
            assertFalse(last.getBoolean("payloadRetained"))
            assertTrue(last.isNull("payloadHex"))
            assertFalse(last.toString().contains("private-value"))
        }
    }

    @Test fun finishedWindowCannotBeRevivedByQueuedCallbacksAndMissingSignalsStayMissing() {
        val samples = WiperCandidateSamples { 1L }
        samples.receive(39168, 39169, 3843, byteArrayOf(1))
        val before = samples.finish("DONE").toString()
        samples.receive(39168, 39169, 3843, byteArrayOf(2))
        samples.receive(39168, 39169, 3101, byteArrayOf(7))
        assertEquals(before, samples.finish("DONE").toString())
        assertTrue(field(samples.finish("DONE"), 3101).isNull("last"))
    }

    @Test fun onlyBoundedTransitionsAreKeptAndRepeatedPayloadsAreNotChanges() {
        val samples = WiperCandidateSamples { 1L }
        repeat(100) { samples.receive(39168, 39169, 3843, byteArrayOf(it.toByte())) }
        samples.receive(39168, 39169, 3843, byteArrayOf(99))
        val report = samples.finish("DONE")
        val row = field(report, 3843)
        assertEquals(101, row.getInt("deliveries")); assertEquals(99, row.getInt("payloadChanges"))
        assertEquals(8, row.getJSONArray("recentTransitions").length())
        assertEquals("00", row.getJSONObject("first").getString("payloadHex"))
        assertEquals("63", row.getJSONObject("last").getString("payloadHex"))
        assertTrue(report.toString().length < 48_000)
    }

    @Test fun runnerSavesFrozenCandidateEvidenceBeforeReleasingItsOwnClient() {
        var receiver: OemEventReceiver? = null
        var saved: JSONObject? = null
        val client = object : OemEventClient {
            override fun start(value: OemEventReceiver) { receiver = value; value.receive(39168, 39169, 3101, byteArrayOf(7)) }
            override fun stop() {
                assertNotNull(saved)
                receiver!!.receive(39168, 39169, 3101, byteArrayOf(8))
            }
        }
        val result = OemProbeRunner({ client }, WiperCandidateSamples { 1L }).run(beforeCleanup = { saved = it }) { }
        assertEquals("07", field(result, 3101).getJSONObject("last").getString("payloadHex"))
        assertEquals(saved.toString(), result.toString())
    }

    @Test fun candidateScreenStartsWithInstructionsAndDoesNotStartNativeCollectionOnOpen() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, OemProbeActivity::class.java).putExtra("probe_kind", "WIPERS")
        val controller = Robolectric.buildActivity(OemProbeActivity::class.java, intent).setup()
        fun texts(view: View): List<String> = when (view) {
            is TextView -> listOf(view.text.toString())
            is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else -> emptyList()
        }
        assertTrue(texts(controller.get().findViewById(android.R.id.content)).contains(context.getString(R.string.wiper_explanation)))
        controller.pause().stop().destroy()
    }
}
