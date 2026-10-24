package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import org.junit.Assert.*
import org.junit.Test

/** Pinned LIVI ident.rs/bringup.rs map the same catalog ID to transport field 6 and 0x4301. */
class Iap2ThemeAssetTest {
    private val theme = Iap2ThemeAsset("44C9FA31EC", 985, "19.0")
    private fun wireless(theme: Iap2ThemeAsset? = null) = Iap2WirelessCarPlayEndpoint(
        "test", "secret123", 36, Iap2WirelessSecurity.WPA_WPA2, listOf("192.0.2.1"),
        7000, "device", "public-key", "1.0", themeAsset = theme,
    )
    private fun wired(theme: Iap2ThemeAsset? = null) = Iap2WiredCarPlayEndpoint(
        listOf("fe80::1"), 7000, "public-key", "1.0", themeAsset = theme,
    )

    @Test fun descriptorIsAbsentFromOrdinaryWiredAndWirelessStartSession() {
        for (frame in listOf(Iap2WirelessControlClient.carPlayStartSession(wireless()),
            Iap2WiredControlClient.carPlayStartSession(wired()))) {
            val body = Iap2BodyReader.of(frame)
            assertFalse(body.has(6)); assertFalse(body.has(7)); assertFalse(body.has(8))
        }
    }

    @Test fun bothTransportsCarryRealDescriptorWithoutClaimingMutualAuthentication() {
        for (frame in listOf(Iap2WirelessControlClient.carPlayStartSession(wireless(theme)),
            Iap2WiredControlClient.carPlayStartSession(wired(theme)))) {
            val body = Iap2BodyReader.of(frame)
            assertEquals("19.0", body.string(6))
            assertEquals("44C9FA31EC", body.group(7).string(0))
            assertEquals(985L, body.group(7).u32(1))
            assertFalse(body.has(8))
        }
    }

    @Test fun identificationNamesAssetOnCarPlayComponentAndPreservesWirelessLinkRoles() {
        val plain = Iap2IdentificationConfig("test", "test", "test", "test", "1.0", "1.0", 3)
        assertFalse(Iap2BodyReader.of(Iap2IdentificationClient.identificationInformation(plain)).group(16).has(6))
        val themed = plain.copy(themeAssetIdentifier = theme.identifier)
        val usb = Iap2BodyReader.of(Iap2IdentificationClient.identificationInformation(themed))
        assertEquals(theme.identifier, usb.group(16).string(6))
        val wirelessIdentity = Iap2WirelessIdentification("02:00:00:00:00:01", "test")
        for (role in Iap2WirelessLinkRole.entries) {
            val config = themed.forWirelessLink(role, wirelessIdentity)
            val body = Iap2BodyReader.of(Iap2IdentificationClient.identificationInformation(config))
            assertEquals(theme.identifier, body.group(24).string(6))
            assertFalse(body.group(17).has(6)) // Not a BluetoothTransport field.
            assertFalse(body.has(16))
        }
    }

    @Test fun invalidDescriptorsCannotEnterNulTerminatedOrFixedWidthFields() {
        for (id in listOf("", "bad\u0000id", "a".repeat(129), "bad/id")) {
            assertThrows(IllegalArgumentException::class.java) { Iap2ThemeAsset(id, 985, "19.0") }
        }
        for (version in listOf(-1L, 0x1_0000_0000L)) {
            assertThrows(IllegalArgumentException::class.java) { Iap2ThemeAsset("valid", version, "19.0") }
        }
        for (sdk in listOf("", "bad", "19.0\u0000", "1".repeat(33))) {
            assertThrows(IllegalArgumentException::class.java) { Iap2ThemeAsset("valid", 985, sdk) }
        }
    }
}
