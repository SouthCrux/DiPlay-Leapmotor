package com.shilapi.xcertplay.vehicle.leapmotor

import android.os.IBinder
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class LeapmotorClimateShellWriteFacadeTest {
    class Property(val id: Int, val area: Int, val value: Any)
    interface Listener { fun asBinder(): IBinder; fun onEvent(events: List<Any>) }
    class PropertyBinder {
        var ids: IntArray? = null
        val writes = mutableListOf<Property>()
        var callback: (Listener) -> Unit = {}
        fun getPropertyConfigList(requested: IntArray): List<Any> { ids = requested; return emptyList() }
        fun setProperty(value: Property, listener: Listener) {
            assertNotNull(listener.asBinder())
            listener.onEvent(emptyList()) // An error callback must not become confirmation.
            callback(listener)
            writes += value
        }
    }
    @Test fun usesExactTwoArgumentBinderSignatureAndRejectsOtherPropertiesOrTargets() {
        val binder = PropertyBinder()
        val facade = LeapmotorClimateShellWriteMain.WriteOnlyPropertyFacade(
            PropertyBinder::class.java, binder, Property::class.java, Listener::class.java)
        facade.getPropertyList()
        assertArrayEquals(intArrayOf(358614275), binder.ids)
        facade.setFloatProperty(358614275, 49, 21f)
        val value = binder.writes.single()
        assertEquals(358614275, value.id); assertEquals(49, value.area); assertEquals(21f, value.value)
        assertThrows(IllegalArgumentException::class.java) { facade.setFloatProperty(1, 49, 21f) }
        assertThrows(IllegalArgumentException::class.java) { facade.setFloatProperty(358614275, 0, 21f) }
        assertThrows(IllegalArgumentException::class.java) { facade.setFloatProperty(358614275, 49, 21.5f) }
        assertThrows(IllegalArgumentException::class.java) { facade.setFloatProperty(358614275, 49, Float.NaN) }
        assertEquals(1, binder.writes.size)
        facade.close()
        assertThrows(IllegalStateException::class.java) { facade.setFloatProperty(358614275, 49, 21f) }
    }

    @Test fun theRealCallbackBinderReadsOnlyTheCapturedSingletonErrorPrefix() {
        val binder = PropertyBinder().apply { callback = { sendEvent(it) } }
        val facade = LeapmotorClimateShellWriteMain.WriteOnlyPropertyFacade(
            PropertyBinder::class.java, binder, Property::class.java, Listener::class.java)
        facade.setFloatProperty(358614275, 49, 21f)
        val error = facade.observeSetError(0)!!
        assertEquals(LeapmotorClimateSetErrorObservation.State.ERROR_OBSERVED, error.state)
        assertEquals(LeapmotorClimateSetErrorObservation.Error.ACCESS_DENIED, error.error)
        assertEquals(1, binder.writes.size)
        facade.close()
    }

    @Test fun stateChangesWrongClassesOversizedAndTruncatedCallbacksAreNotEvidence() {
        val callbacks: List<(Listener) -> Unit> = listOf(
            { sendEvent(it, eventType = 0) },
            { sendEvent(it, propertyClass = "untrusted.arbitrary.Class") },
            { sendEvent(it, padding = "x".repeat(1_024)) },
            { sendEvent(it, truncated = true) },
        )
        for (callback in callbacks) {
            val binder = PropertyBinder().apply { this.callback = callback }
            val facade = LeapmotorClimateShellWriteMain.WriteOnlyPropertyFacade(
                PropertyBinder::class.java, binder, Property::class.java, Listener::class.java)
            assertNull(facade.observeSetError(0))
            facade.setFloatProperty(358614275, 49, 21f)
            assertEquals(LeapmotorClimateSetErrorObservation.State.NO_MATCH_SEEN, facade.observeSetError(0)?.state)
            assertEquals(1, binder.writes.size)
            facade.close()
        }
    }

    /** Parcel shape copied from the captured onEvent Stub/CarPropertyEvent.writeToParcel sources. */
    private fun sendEvent(listener: Listener, eventType: Int = 1,
                          propertyClass: String = "android.car.hardware.CarPropertyValue",
                          padding: String = "", truncated: Boolean = false) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken("android.car.hardware.property.ICarPropertyEventListener")
            data.writeInt(1) // Typed-list length.
            data.writeInt(1) // Non-null CarPropertyEvent.
            data.writeInt(eventType)
            data.writeInt(4) // Captured ACCESS_DENIED error code.
            data.writeString(propertyClass)
            if (!truncated) {
                data.writeInt(358614275)
                data.writeInt(49)
                data.writeInt(2)
                data.writeLong(0)
                data.writeString("java.lang.Integer")
                data.writeValue(-1)
            }
            if (padding.isNotEmpty()) data.writeString(padding)
            data.setDataPosition(0)
            assertTrue(listener.asBinder().transact(1, data, null, IBinder.FLAG_ONEWAY))
        } finally { data.recycle() }
    }
}
