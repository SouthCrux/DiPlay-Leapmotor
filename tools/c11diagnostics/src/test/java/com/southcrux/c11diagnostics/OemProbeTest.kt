package com.southcrux.c11diagnostics

import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class OemProbeTest {
    private fun soc(value: Double) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()
    private fun integer(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    @Test fun collectionIsBoundedToKnownEndpointEventsAndDoesNotInventOriginFreshness() {
        var now = 10L
        val samples = OemProbeSamples { now++ }
        samples.receive(39168, 39169, 6000, soc(65.2))
        samples.receive(39168, 39169, 6000, soc(65.2))
        samples.receive(39168, 39169, 6000, soc(65.1))
        samples.receive(39168, 99999, 6000, soc(1.0))
        samples.receive(39168, 39169, 1234, integer(2))
        samples.receive(4161, 20545, 301, integer(227))
        val result = samples.finish("WINDOW_COMPLETE")
        assertEquals("UNVERIFIED", result.getString("originFreshness"))
        val fields = result.getJSONArray("signals")
        assertEquals(5, fields.length())
        val battery = fields.getJSONObject(0)
        assertEquals(3, battery.getInt("deliveries"))
        assertEquals(65.2, battery.getJSONObject("first").getDouble("candidateValue"), 0.0)
        assertEquals(65.1, battery.getJSONObject("last").getDouble("candidateValue"), 0.0)
        assertEquals(10L, battery.getJSONObject("first").getLong("receivedAtElapsedNanos"))
        assertTrue(battery.getJSONObject("last").isNull("originTimestamp"))
        assertEquals(227, fields.getJSONObject(4).getJSONObject("last").getInt("candidateValue"))
        samples.receive(39168, 39169, 6000, soc(12.0))
        assertEquals(battery.toString(), samples.finish("DONE").getJSONArray("signals").getJSONObject(0).toString())
    }

    @Test fun malformedPayloadsAndInvalidSocAreReportedWithoutFabricatedValues() {
        for (payload in listOf(null, byteArrayOf(1), ByteArray(9), soc(Double.NaN), soc(Double.POSITIVE_INFINITY), soc(-1.0), soc(100.1))) {
            val samples = OemProbeSamples { 1L }
            samples.receive(39168, 39169, 6000, payload)
            val last = samples.finish("DONE").getJSONArray("signals").getJSONObject(0).getJSONObject("last")
            assertFalse(last.getBoolean("payloadValid"))
            assertTrue(last.isNull("candidateValue"))
            assertTrue(last.isNull("originTimestamp"))
        }
        val empty = OemProbeSamples().finish("DONE").getJSONArray("signals")
        for (i in 0 until empty.length()) {
            assertEquals(0, empty.getJSONObject(i).getInt("deliveries"))
            assertTrue(empty.getJSONObject(i).isNull("last"))
        }
    }

    @Test fun runnerStopsExactlyOnceAndIgnoresCallbacksDuringCleanup() {
        lateinit var activeReceiver: OemEventReceiver
        var stops = 0
        var saved = false
        val client = object : OemEventClient {
            override fun start(receiver: OemEventReceiver) {
                activeReceiver = receiver
                receiver.receive(39168, 39169, 5702, integer(0))
            }
            override fun stop() {
                assertTrue("Evidence must be saved before native cleanup can exit/crash", saved)
                stops++
                activeReceiver.receive(39168, 39169, 5702, integer(1))
            }
        }
        val result = OemProbeRunner({ client }).run(beforeCleanup = { saved = true }) { }
        assertEquals("WINDOW_COMPLETE", result.getString("probeStatus"))
        assertEquals(1, stops)
        val charge = result.getJSONArray("signals").getJSONObject(1)
        assertEquals(1, charge.getInt("deliveries"))
        assertEquals(0, charge.getJSONObject("last").getInt("candidateValue"))
    }

    @Test fun initializationAndWindowFailuresStillReleaseClientAndMissingLibraryIsTerminal() {
        for (failStart in listOf(true, false)) {
            var stops = 0
            val client = object : OemEventClient {
                override fun start(receiver: OemEventReceiver) { if (failStart) throw SecurityException("denied") }
                override fun stop() { stops++ }
            }
            val result = OemProbeRunner({ client }).run { throw InterruptedException("cancel") }
            assertEquals(if (failStart) "ACCESS_DENIED" else "NATIVE_PROBE_FAILED", result.getString("probeStatus"))
            assertEquals(1, stops)
        }
        val missing = OemProbeRunner({ throw UnsatisfiedLinkError("missing") }).run { fail("Must not wait") }
        assertEquals("NATIVE_LIBRARY_NOT_AVAILABLE", missing.getString("probeStatus"))
    }

    @Test fun openingOemScreenDoesNotLoadNativeLibraryListenOrWriteReport() {
        val controller = Robolectric.buildActivity(OemProbeActivity::class.java).setup()
        val activity = controller.get()
        assertFalse(activity.getFileStreamPath("oem-probe-last.json").exists())
        val content = (activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            .getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
        assertEquals(2, (0 until content.childCount).count { content.getChildAt(it) is Button })
        controller.pause().stop().destroy()
    }

    @Test fun roundTripChangeIsPreservedEvenWhenFinalValueMatchesInitialValue() {
        var now = 1L
        val samples = OemProbeSamples { now++ }
        for (value in listOf(0, 0, 1, 1, 0)) samples.receive(39168, 39169, 5702, integer(value))
        val result = samples.finish("WINDOW_COMPLETE")
        assertTrue(result.getBoolean("initialDeliveryMayBeCached"))
        assertEquals("UNVERIFIED", result.getString("originFreshness"))
        val field = result.getJSONArray("signals").getJSONObject(1)
        assertEquals(5, field.getInt("deliveries"))
        assertEquals(2, field.getInt("valueChanges"))
        val changes = field.getJSONArray("recentTransitions")
        assertEquals(2, changes.length())
        assertEquals(1, changes.getJSONObject(0).getInt("candidateValue"))
        assertEquals(0, changes.getJSONObject(1).getInt("candidateValue"))
        assertTrue(changes.getJSONObject(0).isNull("originTimestamp"))
    }

    @Test fun transitionHistoryHasFixedMemoryBoundAndRejectsInvalidLastSoc() {
        val samples = OemProbeSamples { 1L }
        repeat(40) { samples.receive(39168, 39169, 6000, soc((it % 2).toDouble())) }
        samples.receive(39168, 39169, 6000, soc(Double.NaN))
        val field = samples.finish("DONE").getJSONArray("signals").getJSONObject(0)
        assertEquals(40, field.getInt("valueChanges"))
        assertEquals(16, field.getJSONArray("recentTransitions").length())
        assertFalse(field.getJSONObject("last").getBoolean("payloadValid"))
        assertTrue(field.getJSONObject("last").isNull("candidateValue"))
    }
}
