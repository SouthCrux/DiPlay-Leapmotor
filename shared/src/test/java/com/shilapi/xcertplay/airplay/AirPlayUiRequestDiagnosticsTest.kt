package com.shilapi.xcertplay.airplay

import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AirPlayUiRequestDiagnosticsTest {
    @Test fun realCommandRoutingIsPreservedWithoutExportingUnknownUrls() {
        val logs = mutableListOf<String>()
        var home = 0
        var video = 0
        val session = AirPlaySession(Socket(),
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
                main = AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {
                override fun onHostUiRequested(session: AirPlaySession) { home++ }
                override fun onVideoPlaybackUiRequested(session: AirPlaySession) { video++ }
                override fun onDebugLog(message: String) { logs += message }
            }, object : AirPlayMediaHandler {})
        val handler = AirPlaySession::class.java.getDeclaredMethod("handleCommand", RtspMessage.Request::class.java)
            .apply { isAccessible = true }
        val privateUrl = "unknown://private-name?token=private-token"
        try {
            for (params in listOf(emptyMap(), mapOf("url" to ""), mapOf("url" to VideoInCar.UI_URL),
                mapOf("url" to CarPlayClusterDisplay.MAP_URL), mapOf("url" to privateUrl), mapOf("url" to 7L))) {
                val response = handler.invoke(session, RtspMessage.Request("POST", "/command", "RTSP/1.0",
                    emptyMap(), BplistCodec.encode(mapOf("type" to "requestUI", "params" to params)))) as RtspMessage.Response
                assertEquals(200, response.status)
            }
            assertEquals("MAP_CLUSTER remains on the existing host UI route", 5, home)
            assertEquals(1, video)
            assertEquals(listOf("DEFAULT", "DEFAULT", "VIDEO_PLAYBACK", "MAP_CLUSTER", "OTHER", "INVALID"),
                logs.filter { it.startsWith("airplay UI request target=") }.map { it.substringAfter('=') })
            assertTrue(logs.none { it.contains("private") })
        } finally { session.close() }
    }
    @Test fun onlyTheExactKnownClusterMapUrlReceivesTheMapClassification() {
        val exact = CarPlayClusterDisplay.MAP_URL
        assertEquals("airplay UI request target=MAP_CLUSTER", AirPlayUiRequestDiagnostics.summary(mapOf("url" to exact)))
        for (unrecognized in listOf("$exact?token=private-token", "$exact#private-place", "$exact/",
            " $exact", "$exact ", "MAPS:/car/instrumentcluster/map", "maps:/car/instrumentcluster/map-private",
            CarPlayClusterDisplay.Content.TURN_CARD.url, CarPlayClusterDisplay.Content.INSTRUMENTS.url)) {
            val summary = AirPlayUiRequestDiagnostics.summary(mapOf("url" to unrecognized))
            assertEquals("airplay UI request target=OTHER", summary)
            assertFalse(summary.contains(unrecognized))
            assertFalse(summary.contains("private"))
        }
        assertEquals("airplay UI request target=INVALID", AirPlayUiRequestDiagnostics.summary(mapOf("url" to null)))
    }

}
