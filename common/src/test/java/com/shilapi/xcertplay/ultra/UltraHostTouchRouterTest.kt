package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.AirPlayContact
import org.junit.Assert.*
import org.junit.Test

class UltraHostTouchRouterTest {
    private val down=listOf(AirPlayContact(0,0.5,0.5,true))
    private val up=listOf(AirPlayContact(0,0.5,0.5,false))

    @Test fun localReleaseIsConsumedEvenAfterItsConnectionHasRetired() {
        val router=UltraHostTouchRouter()
        assertTrue(router.dispatch(down,true,false) { _,_ -> true })
        assertTrue(router.dispatch(up,false,false) { _,_ -> false })
        assertFalse(router.dispatch(down,true,false) { _,_ -> false })
        assertFalse(router.dispatch(up,false,false) { _,_ -> true })
    }

    @Test fun replacementConnectionCannotStealAPhoneGestureInProgress() {
        val router=UltraHostTouchRouter()
        assertFalse(router.dispatch(down,true,false) { _,_ -> false })
        val calls=mutableListOf<Boolean>()
        assertFalse(router.dispatch(down,false,false) { _,cancel -> calls+=cancel; true })
        assertFalse(router.dispatch(up,false,false) { _,cancel -> calls+=cancel; true })
        assertTrue(calls.isEmpty())
        assertTrue(router.dispatch(down,true,false) { _,_ -> true })
    }

    @Test fun cancelAndTheNextDownClearAnOrphanWithoutDeliveringAnUpToThePhone() {
        val router=UltraHostTouchRouter()
        assertTrue(router.dispatch(down,true,false) { _,_ -> true })
        val cancels=mutableListOf<Boolean>()
        assertTrue(router.dispatch(up,false,true) { _,canceled -> cancels+=canceled; false })
        assertEquals(listOf(true),cancels)
        assertFalse(router.dispatch(down,true,false) { _,_ -> false })
        assertTrue(router.dispatch(down,true,false) { _,_ -> true })
        val reset=mutableListOf<Pair<Int,Boolean>>()
        assertFalse(router.dispatch(down,true,false) { points,canceled -> reset+=points.size to canceled; false })
        assertEquals(listOf(0 to true,1 to false),reset)
    }
}
