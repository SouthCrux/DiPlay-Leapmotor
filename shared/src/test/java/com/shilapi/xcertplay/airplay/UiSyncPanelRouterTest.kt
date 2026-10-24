package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class UiSyncPanelRouterTest {
    private fun message(value: Long) = BplistCodec.encode(mapOf("type" to value, "psn" to 37L))
    private fun envelope(panel: String, bytes: ByteArray) = Opack.encode(Opack.dict(
        "displayUUID" to Opack.Text(panel), "data" to Opack.Data(bytes)))

    @Test fun routesUnchangedInnerMessagesToOneOwnerPerAdvertisedPanel() {
        val owners = mutableMapOf<String, Owner>()
        UiSyncPanelRouter(setOf("center", "cluster")) { panel ->
            check(panel !in owners)
            Owner().also { owners[panel] = it }
        }.use { router ->
            assertTrue(router.receive(envelope("unknown", message(0))).isEmpty())
            assertTrue(owners.isEmpty())
            repeat(2) { index ->
                val input = message(index.toLong())
                val result = router.receive(envelope("center", input)).single()
                val outer = Opack.decode(result) as Opack.Dict
                assertEquals(Opack.Text("center"), outer["displayUUID"])
                assertArrayEquals(input, (outer["data"] as Opack.Data).bytes())
            }
            router.receive(envelope("cluster", message(2)))
            assertEquals(2, owners.size)
            assertEquals(2, owners.getValue("center").received)
            assertEquals(1, owners.getValue("cluster").received)
        }
        assertTrue(owners.values.all { it.closed })
    }

    @Test fun startsTheSelectedOwnerAndDoesNotManufactureKotlinResetOrAck() {
        val output = message(99)
        val owner = object : UiSyncPanelSession {
            var polls = 0
            override fun receive(bytes: ByteArray) = emptyList<ByteArray>()
            override fun poll() = if (polls++ == 0) listOf(output) else emptyList()
            override fun close() {}
        }
        UiSyncPanelRouter(setOf("center")) { owner }.use { router ->
            val initial = Opack.decode(router.start("center").single()) as Opack.Dict
            assertArrayEquals(output, (initial["data"] as Opack.Data).bytes())
            assertTrue(router.receive(envelope("center", message(0))).isEmpty())
            assertTrue(router.poll().isEmpty())
        }
    }

    @Test fun failedOwnerRetiresAllPanelsAndCannotBeReused() {
        val owners = mutableListOf<Owner>()
        val router = UiSyncPanelRouter(setOf("a", "b")) { Owner().also(owners::add) }
        router.start("a"); router.start("b")
        owners[0].fail = true
        assertThrows(IllegalStateException::class.java) { router.receive(envelope("a", message(2))) }
        assertTrue(owners.all { it.closed })
        assertThrows(IllegalStateException::class.java) { router.poll() }
        router.close()
    }

    @Test fun diagnosticsCannotMutateOwnerBytesOrChangeProtocolBehaviorWhenTheyFail() {
        val input = message(2)
        var observations = 0
        UiSyncPanelRouter(setOf("center"), observer = { panel, _, bytes ->
            assertEquals("center", panel)
            observations++
            bytes.fill(0)
            error("Diagnostic failure must not terminate the protocol")
        }) { Owner() }.use { router ->
            val result = router.receive(envelope("center", input)).single()
            val inner = ((Opack.decode(result) as Opack.Dict)["data"] as Opack.Data).bytes()
            assertArrayEquals(input, inner)
            assertEquals(2, observations)
        }
    }

    private class Owner : UiSyncPanelSession {
        var received = 0
        var closed = false
        var fail = false
        override fun receive(bytes: ByteArray): List<ByteArray> {
            check(!closed && !fail)
            received++
            return listOf(bytes)
        }
        override fun close() { closed = true }
    }
}
